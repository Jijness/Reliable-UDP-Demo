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
    private final int listenPort;
    private final String outFile;
    private final InetAddress serverAddr;
    private final int serverPort;
    private final DatagramSocket socket;

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

    public void startReceiving() throws IOException {
        sendRequest();
        Utils.log("UDP Client: listening on port " + listenPort);
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        long start = System.currentTimeMillis();
        long totalBytes = 0;

        while (true) {
            socket.receive(dp);
            ByteBuffer bb = ByteBuffer.wrap(dp.getData(), 0, dp.getLength());
            int seq = bb.getInt();
            if (seq == -1) { // FIN
                Utils.log("UDP Client: Received FIN. Stopping.");
                break;
            }
            byte[] payload = new byte[dp.getLength() - 4];
            bb.get(payload);
            if (!buffer.containsKey(seq)) {
                buffer.put(seq, payload);
                uniqueReceived++;
                totalBytes += payload.length;
                if (seq > highestSeq) highestSeq = seq;
            }
        }

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            for (int i = 1; i <= highestSeq; i++) {
                byte[] chunk = buffer.get(i);
                if (chunk != null) fos.write(chunk);
            }
        }

        long end = System.currentTimeMillis();
        int loss = (highestSeq - uniqueReceived);
        Utils.log("--- TRANSFER SUMMARY (UDP) ---");
        Utils.log(String.format("File: %s", outFile));
        Utils.log(String.format("Total data: %.2f KB", totalBytes / 1024.0));
        Utils.log("Highest Seq: " + highestSeq);
        Utils.log("Unique Packets: " + uniqueReceived);
        Utils.log("Approx Loss: " + loss);
        Utils.log(String.format("Time: %.2f s", (end - start) / 1000.0));
        Utils.log("-----------------------------");

        socket.close();
    }

    public static void main(String[] args) throws Exception {
        String out = "src/received_udp.txt"; // File đầu ra
        int listenPort = 5003; // Client lắng nghe trên port 5001
        String host = "127.0.0.1";
        int port = 5002; // Server lắng nghe trên port 5000
        System.out.println("--- UDP Client (Tự động) ---");
        System.out.println(String.format("Out File: %s, Listen Port: %d, Server: %s:%d", out, listenPort, host, port));

        Client c = new Client(listenPort, out, host, port);
        c.startReceiving();
    }
}
