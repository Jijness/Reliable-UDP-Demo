/*
  UDP Client (thuần) - nhận packet có 4-byte seq + payload.
  Usage:
    java udp.Client <listenPort> <outputFile> <waitMs>
  waitMs: thời gian (ms) sau khi không nhận packet mới sẽ coi là kết thúc (default 5000)
  Ví dụ: java udp.Client 5001 received_udp.bin 5000
*/

package udp;

import Channel.Utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

public class Client {
    // THAM SỐ MẶC ĐỊNH
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final int listenPort;
    private final String outFile;
    private final DatagramSocket socket;
    private final InetAddress serverAddr;
    private final int serverPort;

    private final Map<Integer, byte[]> buffer = new TreeMap<>(); // giữ out-of-order
    private int highestSeq = 0;
    private int uniqueReceived = 0;

    public Client(int listenPort, String outFile, String serverHost, int serverPort) throws Exception {
        this.listenPort = listenPort;
        this.outFile = outFile;
        this.serverAddr = InetAddress.getByName(serverHost);
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(listenPort);
    }
    // Gửi gói yêu cầu đến Server để Server bắt đầu gửi file
    private void sendRequest() throws IOException {
        byte[] data = "UDP_START".getBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, serverAddr, serverPort);

        // Gửi 3 lần để đảm bảo Request đến nơi
        for(int i = 0; i < 3; i++) {
            socket.send(dp);
            Utils.log("UDP Client: Sent REQUEST to Server.");
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    public void receiveAndWrite(int waitMs) throws IOException {
        // Bắt tay khởi tạo
        sendRequest();

        Utils.log("UDP Client: listening on " + listenPort);
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        long startTime = System.currentTimeMillis();
        long totalBytes = 0;

        while (true) {
            socket.setSoTimeout(waitMs);
            try {
                socket.receive(dp);
                int len = dp.getLength();
                if (len < 4) continue;
                ByteBuffer bb = ByteBuffer.wrap(dp.getData(), 0, len);
                int seq = bb.getInt();
                byte[] payload = new byte[len - 4];
                bb.get(payload);

                if (!buffer.containsKey(seq)) {
                    buffer.put(seq, payload);
                    uniqueReceived++;
                    totalBytes += payload.length;
                    if (seq > highestSeq) highestSeq = seq;
                }

            } catch (java.net.SocketTimeoutException st) {
                Utils.log("UDP Client: timeout waiting. stopping receive.");
                break;
            }
        }
        // GHI TOÀN BỘ DỮ LIỆU ĐÃ NHẬN (KỂ CẢ CÁC GÓI BỊ MẤT GIỮA CHỪNG)
        // Sẽ tạo ra 1 file KHÔNG LIÊN TỤC (có thể thiếu/lỗi), nhưng phản ánh đúng số byte nhận được
        try (FileOutputStream fos = new FileOutputStream(outFile)) { // Không dùng append (false)
            int maxSeq = highestSeq;
            Utils.log("UDP Client: Writing all received data to file...");
            for (int seq = 1; seq <= maxSeq; seq++) {
                byte[] chunk = buffer.get(seq);
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
        }
        socket.close();

        long endTime = System.currentTimeMillis();

        // print summary
        int lossCount = (highestSeq == 0) ? 0 : (highestSeq - uniqueReceived);
        Utils.log("--- TRANSFER SUMMARY (UDP) ---");
        Utils.log(String.format("File size received: %.2f KB", totalBytes / 1024.0));
        Utils.log("Highest Seq Received: " + highestSeq);
        Utils.log("Unique Packets Received: " + uniqueReceived);
        Utils.log("Approx. Loss Count: " + lossCount);
        Utils.log(String.format("Time taken: %.2f s", (endTime - startTime) / 1000.0));
        Utils.log("----------------------------");
    }

    public static void main(String[] args) throws Exception {
        String out = "received_udp.txt"; // File đầu ra
        int listenPort = 5003; // Client lắng nghe trên port 5001
        String host = "127.0.0.1";
        int port = 5002; // Server lắng nghe trên port 5000
        int waitMs = 10000; // Thời gian chờ (10s)

        System.out.println("--- UDP Client (Tự động) ---");
        System.out.println(String.format("Out File: %s, Listen Port: %d, Server: %s:%d, Timeout: %dms", out, listenPort, host, port, waitMs));

        Client c = new Client(listenPort, out, host, port);
        c.receiveAndWrite(waitMs);
    }
}
