package java.reliableudp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/** Server = Sender: SR-ARQ + SACK. Gửi DATA theo cửa sổ, nhận ACK/SACK, RETX theo timeout (RTO thích ứng) */
public class ReliableServer {


    public static void main(String[] args) throws Exception {
        int port = (args.length == 1) ? Integer.parseInt(args[0]) : 5000;
        new ReliableServer().run(port);
    }

    private void run(int port) throws Exception {
        DatagramSocket socket = new DatagramSocket(port);
        socket.setSoTimeout(ReliablePacket.SOCKET_TIMEOUT_MS);

        System.out.printf("Receiver listening on %d | window=%d%n", port, ReliablePacket.WINDOW_SIZE);

        int base = 0;
        Map<Integer, byte[]> buffer = new HashMap<>();
        Integer lastSeq = null;
        ByteArrayOutputStream deliver = new ByteArrayOutputStream();
        File outFile = new File("received_output.txt");

        long startNs = 0L;

        // HEADER(12) + CHUNK_SIZE(<=700) ~ an toàn trong 1500
        byte[] rx = new byte[1536];
        DatagramPacket dp = new DatagramPacket(rx, rx.length);
        InetAddress clientAddr = null;
        int clientPort = -1;

        while (true) {
            try {
                socket.receive(dp);
                if (startNs == 0L) startNs = System.nanoTime();



                ReliablePacket p = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                if (p.type != ReliablePacket.TYPE_DATA) continue;

                clientAddr = dp.getAddress();
                clientPort = dp.getPort();

                // Log nhận DATA hợp lệ (ở đây không dùng checksum payload nữa)
                int len = Short.toUnsignedInt(p.aux); // AUX = LENGTH
                System.out.printf("<< RECV DATA  seq=%d, bytes=%d, last=%b%n", p.seq, len, p.isLast());

                // Gửi ACK (AUX = checksum16 cho ACK)
                sendAck(socket, p.seq, clientAddr, clientPort);

                // Chỉ nhận vào nếu trong cửa sổ
                if (p.seq >= base && p.seq < base + ReliablePacket.WINDOW_SIZE) {
                    buffer.putIfAbsent(p.seq, p.payload);
                    if (p.isLast()) lastSeq = p.seq;

                    // Deliver liên tục từ base
                    while (buffer.containsKey(base)) {
                        byte[] payload = buffer.remove(base);
                        if (payload != null && payload.length > 0) deliver.write(payload);
                        base++;
                    }
                }

                // Kết thúc khi đã deliver qua LAST
                if (lastSeq != null && base > lastSeq) {
                    long endNs = System.nanoTime();
                    long durMs = (endNs - startNs) / 1_000_000L;
                    byte[] all = deliver.toByteArray();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) { fos.write(all); }
                    double throughputMbps = (all.length * 8.0) / (durMs / 1000.0) / 1_000_000.0;
                    System.out.printf(
                            "RECEIVER DONE. Bytes=%d, Time=%d ms, Throughput=%.2f Mbps -> %s%n",
                            all.length, durMs, throughputMbps, outFile.getAbsolutePath()
                    );
                    socket.close();
                    return;
                }
            } catch (SocketTimeoutException ignore) {
            }
        }
    }

    private void sendAck(DatagramSocket socket, int seq, InetAddress addr, int port) throws Exception {
        ReliablePacket ack = ReliablePacket.makeAck(seq); // AUX = checksum16(type, seq)
        byte[] b = ack.toBytes();
        DatagramPacket adp = new DatagramPacket(b, b.length, addr, port);
        socket.send(adp); // gửi trực tiếp
        System.out.printf("<< SEND ACK   seq=%d (aux=0x%04X)%n", seq, Short.toUnsignedInt(ack.aux));
    }
}