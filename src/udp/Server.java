/*
  UDP Server (thuần) - gửi file bằng UDP, mỗi segment kèm 4 bytes sequence number (big-endian).
  Usage:
    java udp.Server <clientHost> <clientPort> <filePath> [lossRate]
  Ví dụ: java udp.Server 127.0.0.1 5001 bigfile.bin 0.1
*/

package udp;

import Channel.LossyChannel;
import Channel.Utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Server {
    // THAM SỐ MẶC ĐỊNH
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private static final int SEGMENT_SIZE = 1000; // payload per UDP packet (without seq)
    private final int serverPort;
    private InetAddress clientAddr;
    private int clientPort;
    private final DatagramSocket socket;
    private LossyChannel channel;

    public Server(int serverPort) throws SocketException {
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(serverPort);
    }

    // Chờ Client gửi gói YÊU CẦU
    private boolean waitForRequest(double lossRate) throws IOException {
        Utils.log("UDP Server: Listening on port " + serverPort + " for REQUEST (UDP)...");
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        // Chờ gói Request (nhận 1 gói tin bất kỳ)
        socket.setSoTimeout(0); // Chờ vô thời hạn
        socket.receive(dp);

        this.clientAddr = dp.getAddress();
        this.clientPort = dp.getPort();

        // KHỞI TẠO LOSSY CHANNEL
        this.channel = new LossyChannel(socket, lossRate, 0.0, 50);

        Utils.log("UDP Server: Received REQUEST from " + clientAddr + ":" + clientPort + ". Starting file transfer.");
        return true;
    }

    public void sendFile(String filePath, double lossRate) throws Exception {
        if (!waitForRequest(lossRate)) return; // Chờ yêu cầu từ Client

        byte[] data = Files.readAllBytes(Paths.get(filePath));
        int total = data.length;
        Utils.log("UDP Server: file size=" + total);

        long startTime = System.currentTimeMillis();
        int idx = 0;
        int seq = 1;
        int sent = 0;

        while (idx < total) {
            int len = Math.min(SEGMENT_SIZE, total - idx);
            byte[] seg = new byte[4 + len]; // 4 bytes seq + payload
            ByteBuffer.wrap(seg, 0, 4).putInt(seq);
            System.arraycopy(data, idx, seg, 4, len);

            DatagramPacket dp = new DatagramPacket(seg, seg.length, clientAddr, clientPort);
            channel.send(dp);
            sent++;
            if (sent % 100 == 0) Utils.log("UDP Server: sent seq=" + seq + " total_sent=" + sent);
            seq++;
            idx += len;

            Thread.sleep(1);
        }

        long endTime = System.currentTimeMillis();

        Utils.log("UDP Server: finished sending. total_sent=" + sent);
        Utils.log(String.format("Time taken: %.2f s", (endTime - startTime) / 1000.0));

        // Cần delay để các gói tin cuối (có delay) kịp đến Client
        Thread.sleep(2000);
        channel.shutdown();
        socket.close();
    }

    public static void main(String[] args) throws Exception {
        // Usage: java udp.Server <filePath> [lossRate] [serverPort]
        String file = "data_source.txt"; // File đầu vào
        double loss = 0.1; // Tỉ lệ mất gói mặc định 10%
        int serverPort = 5002; // Port mặc định

        System.out.println("--- UDP Server ---");
        System.out.println(String.format("File: %s, Loss: %.1f, Port: %d", file, loss, serverPort));

        Server s = new Server(serverPort);
        s.sendFile(file, loss);
    }
}
