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
import java.util.Map;
import java.util.TreeMap;

public class ReliableClient {
    private final int listenPort;
    private final String outFile;
    private final InetAddress serverAddr;
    private final int serverPort;
    private final DatagramSocket socket;

    private long expectedSeq = 1;
    private final Map<Long, byte[]> buffer = new TreeMap<>(); // store out-of-order

    public ReliableClient(int listenPort, String outFile, String serverHost, int serverPort) throws Exception {
        this.listenPort = listenPort;
        this.outFile = outFile;
        this.serverAddr = InetAddress.getByName(serverHost);
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(listenPort);
    }
    public void startReceiving() throws IOException {
        Utils.log("Client: listening on port " + listenPort);
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (true) {
            socket.receive(dp);
            ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
            if (rp.getType() == ReliablePacket.TYPE_DATA) {
                long seq = rp.seqOrAck;
                Utils.log("Client: received DATA seq=" + seq + " len=" + rp.payload.length);
                // if seq < expected => duplicate; ack expectedSeq-1
                if (seq < expectedSeq) {
                    sendAck(expectedSeq - 1);
                    continue;
                }
                // store
                buffer.put(seq, rp.payload);
                // flush contiguous
                while (buffer.containsKey(expectedSeq)) {
                    byte[] chunk = buffer.remove(expectedSeq);
                    Utils.appendToFile(outFile, chunk);
                    expectedSeq++;
                }
                // send cumulative ack
                sendAck(expectedSeq - 1);
                // optionally exit when we detect last packet? For demo, server will stop when everything acked.
            } else {
                // ignore other types
            }
        }
    }

    private void sendAck(long ackNum) {
        ReliablePacket ack = ReliablePacket.createAck(ackNum);
        byte[] data = ack.toBytes();
        try {
            DatagramPacket dp = new DatagramPacket(data, data.length, serverAddr, serverPort);
            socket.send(dp);
            Utils.log("Client: sent ACK " + ackNum);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java com.example.rudp.ReliableClient <listenPort> <outputFile> <serverHost> <serverPort>");
            return;
        }
        int listenPort = Integer.parseInt(args[0]);
        String outFile = args[1];
        String serverHost = args[2];
        int serverPort = Integer.parseInt(args[3]);
        ReliableClient client = new ReliableClient(listenPort, outFile, serverHost, serverPort);
        client.startReceiving();
    }
}
