package java.reliableudp;

import channel.LossyChannel;

import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReliableClient {

    private int base;            // seq nhỏ nhất chưa ACK
    private int nextSeq;
    private boolean[] acked;
    private long[] lastSendNs;
    private int totalPackets;

    public static void main(String[] args) throws Exception {
//        if (args.length != 3) {
//            System.out.println("Usage: java reliableudp.ReliableClient <host> <port> <\"msg\"|file:/path.txt>");
//            return;
//        }
        String host = "127.0.0.1";
        int port = 5000;
        String fileArg = "file:input.txt";
        new ReliableClient().run(host, port, fileArg);
    }

    private void run(String host, int port, String fileArg) throws Exception {
        // Đọc file .txt và chia chunk
        String path = fileArg.substring("file:".length());
        byte[] data = readAllBytes(path);
        List<byte[]> chunks = splitChunks(data, ReliablePacket.CHUNK_SIZE);
        totalPackets = chunks.size();


        InetAddress addr = InetAddress.getByName(host);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(ReliablePacket.SOCKET_TIMEOUT_MS);
        System.out.printf("Sender -> %s:%d | packets=%d | window=%d | timeout=%dms ", host, port, totalPackets, ReliablePacket.WINDOW_SIZE, ReliablePacket.TIMEOUT_MS);

        // Kênh mô phỏng: drop 5%, delay ≤ 30ms
        LossyChannel ch = new LossyChannel(socket, /*lossRate*/ 0.3, /*delayMs*/ 0, 0.0);

        // Trạng thái SR
        base = 0; // seq nhỏ nhất chưa ACK
        nextSeq = 0; // seq kế tiếp để gửi
        acked = new boolean[totalPackets];
        lastSendNs = new long[totalPackets];


        ExecutorService exec = Executors.newCachedThreadPool();
        long startNs = System.nanoTime();



        // Thread nhận ACK (verify aux=checksum16)
        exec.submit(() -> {
            byte[] buf = new byte[32]; // ACK rất nhỏ
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    socket.receive(dp);
                    ReliablePacket ack = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                    if (ack.type == ReliablePacket.TYPE_ACK) {
                        // verify aux là checksum16 cho ACK
                        short expect = ReliablePacket.checksum16(ack.type, ack.seq);
                        if (ack.aux != expect) {
                            System.out.printf("!! BAD ACK CHKSUM seq=%d (got=0x%04X, exp=0x%04X) -> ignore%n",
                                    ack.seq, Short.toUnsignedInt(ack.aux), Short.toUnsignedInt(expect));
                            continue;
                        }
                        int s = ack.seq;
                        if (s >= 0 && s < totalPackets && !acked[s]) {
                            acked[s] = true;
                            synchronized (acked) {
                                while (base < totalPackets && acked[base]) base++;
                            }
                            System.out.printf("<< RECV ACK   seq=%d, newBase=%d (aux=0x%04X)%n",
                                    s, base, Short.toUnsignedInt(ack.aux));
                        }
                    }
                } catch (SocketTimeoutException ignore) {
                } catch (Exception e) {
                    // Khi đóng socket lúc kết thúc có thể ném SocketException - bỏ qua
                    break;
                }
            }
        });


        // Vòng lặp gửi + retransmission
        while (base < totalPackets) {
        // Gửi mới trong cửa sổ
            while (nextSeq < totalPackets && nextSeq < base + ReliablePacket.WINDOW_SIZE) {
                if (lastSendNs[nextSeq] == 0L) {
                    sendData(ch, addr, port, chunks.get(nextSeq), nextSeq, nextSeq == totalPackets - 1);
                    lastSendNs[nextSeq] = System.nanoTime();
                }
                nextSeq++;
            }
            // Retransmission nếu timeout
            long now = System.nanoTime();
            for (int s = base; s < Math.min(base + ReliablePacket.WINDOW_SIZE, totalPackets); s++) {
                if (!acked[s] && lastSendNs[s] != 0L) {
                    long elapsedMs = (now - lastSendNs[s]) / 1_000_000L;
                    if (elapsedMs >= ReliablePacket.TIMEOUT_MS) {
                        sendData(ch, addr, port, chunks.get(s), s, s == totalPackets - 1);
                        lastSendNs[s] = System.nanoTime();
                    }
                }
            }
            Thread.sleep(5);
        }


        // Kết thúc
        long endNs = System.nanoTime();

        exec.shutdownNow();
        socket.close();


        long durMs = (endNs - startNs) / 1_000_000L;
        long totalBytes = data.length;
        double throughputMbps = (totalBytes * 8.0) / (durMs / 1000.0) / 1_000_000.0;
        System.out.printf("SENDER DONE. Time=%d ms, Bytes=%d, Throughput=%.2f Mbps ", durMs, totalBytes, throughputMbps);
    }

    private void sendData(LossyChannel ch, InetAddress addr, int port, byte[] payload, int seq, boolean last) throws Exception {
        ReliablePacket p = ReliablePacket.makeData(seq, last, payload); // checksum tính trong makeData
        byte[] bytes = p.toBytes();
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, addr, port);
        // Gửi qua kênh mô phỏng
        ch.send(dp, String.format("DATA seq=%d last=%b", seq, last));
    }

    private static byte[] readAllBytes(String path) throws Exception {
        try (FileInputStream fis = new FileInputStream(path)) { return fis.readAllBytes(); }
    }


    private static List<byte[]> splitChunks(byte[] data, int chunkSize) {
        List<byte[]> out = new java.util.ArrayList<>();
        for (int off = 0; off < data.length; off += chunkSize) {
            int len = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            out.add(chunk);
        }
        if (out.isEmpty()) out.add(new byte[0]);
        return out;
    }
}
