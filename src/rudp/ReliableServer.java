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
    // cấu hình
    private static final int SEGMENT_SIZE = 1000; // payload size per packet
    private static final int WINDOW_SIZE = 16;
    private static final int RTO_MS = 500; // retransmission timeout

    private final InetAddress clientAddr;
    private final int clientPort;
    private final int serverPort;
    private final DatagramSocket socket;
    private final LossyChannel channel;

    private final Map<Long, ReliablePacket> segments = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Set<Long> acked = Collections.synchronizedSet(new HashSet<>());
    private final ScheduledExecutorService timerExec = Executors.newScheduledThreadPool(4);

    private long baseSeq = 1;
    private long nextSeq = 1;
    private long maxSeq = 0;

    // statistics
    private int sentCount = 0;
    private int retransCount = 0;

    public ReliableServer(String clientHost, int clientPort, int serverPort, double lossRate) throws UnknownHostException, SocketException {
        this.clientAddr = InetAddress.getByName(clientHost);
        this.clientPort = clientPort;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(serverPort); // Listen ACKs here
        this.channel = new LossyChannel(socket, lossRate, 0.01, 50); // small corrupt, small delay
    }

    public void sendFile(String filePath) throws IOException {
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
            // sleep short, let ACK listener update
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        // all done
        Utils.log("Server: all segments acked. sent=" + sentCount + " retrans=" + retransCount);
        channel.shutdown();
        timerExec.shutdownNow();
        socket.close();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("Usage: java com.example.rudp.ReliableServer <clientHost> <clientPort> <serverListenPort> <filePath> [lossRate]");
            return;
        }
        String clientHost = args[0];
        int clientPort = Integer.parseInt(args[1]);
        int serverPort = Integer.parseInt(args[2]);
        String filePath = args[3];
        double loss = args.length >= 5 ? Double.parseDouble(args[4]) : 0.1;
        ReliableServer server = new ReliableServer(clientHost, clientPort, serverPort, loss);
        server.sendFile(filePath);
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
                socket.receive(dp);
                ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                int type = rp.getType();
                if (type == ReliablePacket.TYPE_ACK) {
                    long acknum = rp.seqOrAck;
                    Utils.log("Server: received ACK " + acknum);
                    // mark acked up to acknum (cumulative)
                    // seq numbers start at 1
                    synchronized (this) {
                        for (long s = baseSeq; s <= acknum; s++) {
                            if (segments.containsKey(s)) {
                                acked.add(s);
                                ScheduledFuture<?> f = timers.remove(s);
                                if (f != null) f.cancel(false);
                            }
                        }
                        // slide base
                        while (acked.contains(baseSeq)) {
                            segments.remove(baseSeq);
                            baseSeq++;
                        }
                    }
                } else {
                    // ignore DATA in server
                }
            } catch (Exception e) {
                if (!socket.isClosed()) e.printStackTrace();
            }
        }
    }
}
