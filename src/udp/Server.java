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

    private static final int SEGMENT_SIZE = 1000; // payload per UDP packet (without seq)
    private final int serverPort;
    private final DatagramSocket socket;
    private LossyChannel channel;
    private InetAddress clientAddr;
    private int clientPort;

    public Server(int serverPort) throws SocketException {
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(serverPort);
    }
    // Chờ Client gửi gói YÊU CẦU
    private boolean waitForRequest(double lossRate) throws IOException {
        Utils.log("UDP Server: Listening on port " + serverPort + " for REQUEST...");
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        this.clientAddr = dp.getAddress();
        this.clientPort = dp.getPort();
        this.channel = new LossyChannel(socket, lossRate, 0.0, 50);
        Utils.log("UDP Server: Got REQUEST from " + clientAddr + ":" + clientPort);
        return true;
    }

    public void sendFile(String filePath, double lossRate) throws Exception {
        if (!waitForRequest(lossRate)) return;

        byte[] data = Files.readAllBytes(Paths.get(filePath));
        int total = data.length;
        Utils.log("UDP Server: file size=" + total);

        int idx = 0, seq = 1;
        long start = System.currentTimeMillis();

        while (idx < total) {
            int len = Math.min(SEGMENT_SIZE, total - idx);
            byte[] seg = new byte[4 + len];
            ByteBuffer.wrap(seg).putInt(seq);
            System.arraycopy(data, idx, seg, 4, len);
            DatagramPacket dp = new DatagramPacket(seg, seg.length, clientAddr, clientPort);
            channel.send(dp);

            if (seq % 100 == 0) Utils.log("UDP Server: sent seq=" + seq);
            seq++;
            idx += len;
            Thread.sleep(1);
        }
        // gửi FIN vài lần để client biết kết thúc
        byte[] fin = ByteBuffer.allocate(4).putInt(-1).array(); // seq=-1 => FIN
        DatagramPacket finPkt = new DatagramPacket(fin, fin.length, clientAddr, clientPort);
        for (int i = 0; i < 3; i++) channel.send(finPkt);

        long end = System.currentTimeMillis();
        Utils.log(String.format("UDP Server: done. Sent=%d, Time=%.2fs", seq - 1, (end - start) / 1000.0));
        channel.shutdown();
    }

    public static void main(String[] args) throws Exception {
        String file = "src/data_source.txt";
        double loss = 0.1; // Tỉ lệ mất gói mặc định 10%
        int serverPort = 5002;
        System.out.println("--- UDP Server ---");
        System.out.println(String.format("File: %s, Loss: %.1f, Port: %d", file, loss, serverPort));
        Server s = new Server(serverPort);
        s.sendFile(file, loss);
    }
}
