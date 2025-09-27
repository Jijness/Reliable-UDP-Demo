/*
ReliableServer - sender:
Usage:
 java com.example.rudp.ReliableServer <clientHost> <clientPort> <serverListenPort> <filePath> [lossRate]
 serverListenPort: nơi server cũng lắng nghe ACK (vì client sẽ gửi ACK về serverListenPort)
*/
package rudp;

import Channel.LossyChannel;
import Channel.Utils;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ReliableServer {
    // THAM SỐ MẶC ĐỊNH
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;
    // cấu hình
    private static final int SEGMENT_SIZE = 1000; // payload size per packet
    private static final int WINDOW_SIZE = 16;
    private static final int RTO_MS = 500; // retransmission timeout
    // Ngưỡng ACK trùng lặp cho Fast Retransmit (SR-ARQ Hint)
    private static final int DUP_ACK_THRESHOLD = 3;

    private InetAddress clientAddr;
    private int clientPort;
    private final int serverPort;
    private DatagramSocket socket;
    private LossyChannel channel;

    private final Map<Long, ReliablePacket> segments = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Set<Long> acked = Collections.synchronizedSet(new HashSet<>());
    private final Map<Long, Integer> dupAckCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timerExec = Executors.newScheduledThreadPool(4);

    private long baseSeq = 1;
    private long nextSeq = 1;
    private long maxSeq = 0;

    // statistics
    private int sentCount = 0;
    private int retransCount = 0;

    // Server chờ yêu cầu (Waiting State)
    private boolean waitForRequest(String filePath, double lossRate) throws IOException {
        Utils.log("Server: Listening on port " + serverPort + " for REQUEST (RUDP)...");
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        // Chờ gói Request
        while(true) {
            try {
                socket.setSoTimeout(0); // Chờ vô thời hạn
                socket.receive(dp);
                ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());

                if ((rp.flags & ReliablePacket.FLAG_REQ) != 0) {
                    this.clientAddr = dp.getAddress();
                    this.clientPort = rp.aux16; // Client gửi port nghe của nó trong aux16

                    // KHỞI TẠO LOSSY CHANNEL SAU KHI CÓ ĐỊA CHỈ CLIENT
                    // Tắt Corruption (0.0)
                    this.channel = new LossyChannel(socket, lossRate, 0.0, 50);

                    Utils.log("Server: Received REQUEST from " + clientAddr + ":" + dp.getPort() +
                            ". Sending DATA to port " + clientPort + ".");
                    return true;
                } else {
                    Utils.log("Server: Received unknown packet. Ignoring.");
                }
            } catch (SocketTimeoutException ignored) {
                // Sẽ không xảy ra vì timeout = 0
            }
        }
    }

    public ReliableServer(int serverPort) {
        this.serverPort = serverPort;
    }

    public ReliableServer(String clientHost, int clientPort, int serverPort, double lossRate) throws UnknownHostException, SocketException {
        this.clientAddr = InetAddress.getByName(clientHost);
        this.clientPort = clientPort;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(serverPort); // Listen ACKs here
        this.channel = new LossyChannel(socket, lossRate, 0.01, 50); // small corrupt, small delay
    }

    public void sendFile(String filePath, double lossRate) throws IOException, InterruptedException {
        if (!waitForRequest(filePath, lossRate)) return; // Chờ yêu cầu từ Client

        byte[] data = Utils.readFile(filePath);
        int total = data.length;
        Utils.log("Server: file size=" + total);

        // slice into segments
        int idx = 0;
        long seq = 1;
        while (idx < total) {
            int len = Math.min(SEGMENT_SIZE, total - idx);
            byte[] seg = Arrays.copyOfRange(data, idx, idx + len);
            ReliablePacket p = ReliablePacket.createData(seq, seg, 2);
            segments.put(seq, p);
            idx += len;
            seq++;
        }
        maxSeq = seq - 1;
        Utils.log("Server: total segments=" + maxSeq);

        // start thread to receive ACKs
        Thread ackThread = new Thread(this::receiveAcks, "ACK-Listener");
        ackThread.start();

        // main sending loop
        while (baseSeq <= maxSeq) {
            synchronized (this) {
                while (nextSeq < baseSeq + WINDOW_SIZE && nextSeq <= maxSeq) {
                    sendSegment(nextSeq, false);
                    nextSeq++;
                }
            }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }

        // Gửi FIN khi hoàn thành
        sendFin();

        // Đợi ACK thread kết thúc trước khi đóng
        ackThread.join(2000);

        Utils.log("Server: all segments acked. sent=" + sentCount + " retrans=" + retransCount);
        Utils.log(String.format("Retransmission Rate: %.2f%%", (double)retransCount / sentCount * 100));
        channel.shutdown();
        timerExec.shutdownNow();
        socket.close();
    }

    private void sendFin() {
        ReliablePacket fin = ReliablePacket.createAck(maxSeq);
        fin.flags = (byte) (fin.flags | ReliablePacket.FLAG_FIN);
        byte[] buf = fin.toBytes();
        DatagramPacket dp = new DatagramPacket(buf, buf.length, clientAddr, clientPort);

        // Gửi FIN segment 3 lần
        for (int i = 0; i < 3; i++) {
            channel.send(dp);
            try { Thread.sleep(RTO_MS); } catch (InterruptedException ignored) {}
        }
        Utils.log("Server: Sent FIN segments.");
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String filePath = "data_source.txt"; // File đầu vào
        double loss = 0.3; // Tỉ lệ mất gói mặc định 30%
        int serverPort = 5000; // Port mặc định

        System.out.println("--- RUDP Server (Tự động) ---");
        System.out.println(String.format("File: %s, Loss: %.1f, Port: %d", filePath, loss, serverPort));

        ReliableServer server = new ReliableServer(serverPort);
        server.sendFile(filePath, loss);
    }

    private void sendSegment(long seq, boolean isRetransmit) {
        ReliablePacket p = segments.get(seq);
        if (p == null) return;
        byte[] buf = p.toBytes();
        DatagramPacket dp = new DatagramPacket(buf, buf.length, clientAddr, clientPort);
        channel.send(dp);
        sentCount++;
        if (isRetransmit) retransCount++;

        // schedule timer (cancel old one if exists)
        ScheduledFuture<?> old = timers.get(seq);
        if (old != null) old.cancel(false);
        ScheduledFuture<?> fut = timerExec.schedule(() -> {
            // timeout -> retransmit
            Utils.log("RTO: retransmit seq=" + seq);
            sendSegment(seq, true);
        }, RTO_MS, TimeUnit.MILLISECONDS);
        timers.put(seq, fut);
    }
    private void receiveAcks() {
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (!socket.isClosed()) {
            try {
                socket.setSoTimeout(500);
                socket.receive(dp);

                ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                int type = rp.getType();
                if (type == ReliablePacket.TYPE_ACK) {
                    long acknum = rp.seqOrAck;

                    synchronized (this) {
                        // 1. Xử lý ACK trùng lặp (Fast Retransmit Hint): Chỉ đếm ACK = baseSeq - 1
                        if (acknum == baseSeq - 1 && baseSeq > 1) {
                            // Gói tin ACK lũy tiến cũ đến. Đây là dấu hiệu gói tin baseSeq bị mất.
                            dupAckCounts.put(baseSeq, dupAckCounts.getOrDefault(baseSeq, 0) + 1);
                            if (dupAckCounts.get(baseSeq) == DUP_ACK_THRESHOLD) {
                                Utils.log("FAST RETRANSMIT: Retransmitting seq=" + baseSeq);
                                sendSegment(baseSeq, true);
                                dupAckCounts.put(baseSeq, 0); // Reset bộ đếm sau khi kích hoạt retransmit
                            }
                            return;
                        }

                        // 2. Xử lý ACK lũy tiến MỚI (acknum >= baseSeq)
                        if (acknum >= baseSeq) {
                            // Xử lý các gói đã được ACK thành công
                            for (long s = baseSeq; s <= acknum; s++) {
                                if (segments.containsKey(s)) {
                                    acked.add(s);
                                    ScheduledFuture<?> f = timers.remove(s);
                                    if (f != null) f.cancel(false);
                                }
                            }

                            // Trượt cửa sổ (Slide base)
                            while (acked.contains(baseSeq)) {
                                segments.remove(baseSeq);
                                baseSeq++;
                            }

                            dupAckCounts.clear(); // Reset toàn bộ bộ đếm khi window trượt
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Timeout, tiếp tục vòng lặp để chờ ACK
            } catch (Exception e) {
                if (!socket.isClosed()) e.printStackTrace();
            }
        }
    }
}
