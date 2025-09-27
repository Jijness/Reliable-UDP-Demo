/*
ReliableClient - receiver:
Usage:
java com.example.rudp.ReliableClient <listenPort> <outputFile> <serverHost> <serverPort>
client listens on listenPort for DATA, write to outputFile incrementally,
sends ACKs to serverHost:serverPort (serverListenPort).
*/

package rudp;

import Channel.Utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.util.Map;
import java.util.TreeMap;

public class ReliableClient {
    // THAM SỐ MẶC ĐỊNH
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private final int listenPort;
    private final String outFile;
    private final InetAddress serverAddr;
    private final int serverPort;
    private final DatagramSocket socket;

    private long expectedSeq = 1;
    private final Map<Long, byte[]> buffer = new TreeMap<>(); // store out-of-order
    private boolean receivedFin = false;

    public ReliableClient(int listenPort, String outFile, String serverHost, int serverPort) throws Exception {
        this.listenPort = listenPort;
        this.outFile = outFile;
        this.serverAddr = InetAddress.getByName(serverHost);
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(listenPort);
    }
    // Gửi gói yêu cầu đến Server
    private void sendRequest() throws IOException {
        ReliablePacket req = ReliablePacket.createRequest(listenPort);
        byte[] data = req.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, serverAddr, serverPort);

        // Gửi 3 lần để đảm bảo Request đến nơi
        for(int i = 0; i < 3; i++) {
            socket.send(dp);
            Utils.log("Client: Sent REQUEST to Server (RUDP).");
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    public void startReceiving() throws IOException {
        // Bắt tay khởi tạo: Gửi Request trước khi nghe
        sendRequest();

        Utils.log("Client: Listening on port " + listenPort + " for DATA...");

        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        long startTime = System.currentTimeMillis();
        long totalBytes = 0;

        while (!receivedFin) {
            try {
                socket.receive(dp);
            } catch (PortUnreachableException pue) {
                Utils.log("Client: Server closed connection unexpectedly.");
                break;
            }

            ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());

            // Xử lý FIN Segment
            if ((rp.flags & ReliablePacket.FLAG_FIN) != 0) {
                long finalAck = expectedSeq - 1;
                Utils.log("Client: Received FIN. Sending final ACK " + finalAck + " and terminating.");
                sendAck(finalAck);
                receivedFin = true;
                break;
            }

            if (rp.getType() == ReliablePacket.TYPE_DATA) {
                long seq = rp.seqOrAck;

                if (seq < expectedSeq) {
                    // Gói tin trùng lặp: Gửi lại ACK lũy tiến cũ (Hint cho Fast Retransmit)
                    sendAck(expectedSeq - 1);
                    continue;
                }

                // Lưu vào buffer và xử lý gói tin ngoài thứ tự
                if (!buffer.containsKey(seq)) {
                    buffer.put(seq, rp.payload);
                    totalBytes += rp.payload.length;
                }

                // Nếu là gói ngoài thứ tự, gửi ACK lũy tiến cũ để báo hiệu gói bị mất
                if (seq > expectedSeq) {
                    sendAck(expectedSeq - 1); // Gửi ACK N-1 để kích hoạt Fast Retransmit ở Server
                }

                // flush contiguous
                int flushedCount = 0;
                while (buffer.containsKey(expectedSeq)) {
                    byte[] chunk = buffer.remove(expectedSeq);
                    Utils.appendToFile(outFile, chunk);
                    expectedSeq++;
                    flushedCount++;
                }

                // Gửi ACK lũy tiến MỚI nếu có gói tin được flush
                if (flushedCount > 0) {
                    sendAck(expectedSeq - 1);
                }
            }
        }

        socket.close();

        long endTime = System.currentTimeMillis();
        Utils.log("--- TRANSFER SUMMARY (RUDP) ---");
        Utils.log(String.format("File received: %s", outFile));
        Utils.log(String.format("Total data received: %.2f KB", totalBytes / 1024.0));
        Utils.log(String.format("Time taken: %.2f s", (endTime - startTime) / 1000.0));
        Utils.log("-------------------------------");
    }

    private void sendAck(long ackNum) {
        ReliablePacket ack = ReliablePacket.createAck(ackNum);
        byte[] data = ack.toBytes();
        try {
            DatagramPacket dp = new DatagramPacket(data, data.length, serverAddr, serverPort);
            socket.send(dp);
        } catch (IOException ignored) {}
    }


    public static void main(String[] args) throws Exception {
        String outFile = "received_rudp.txt"; // File đầu ra
        int listenPort = 5001; // Client lắng nghe trên port 5001
        String serverHost = "127.0.0.1";
        int serverPort = 5000; // Server lắng nghe trên port 5000

        System.out.println("--- RUDP Client (Tự động) ---");
        System.out.println(String.format("Out File: %s, Listen Port: %d, Server: %s:%d", outFile, listenPort, serverHost, serverPort));

        ReliableClient client = new ReliableClient(listenPort, outFile, serverHost, serverPort);
        client.startReceiving();
    }
}
