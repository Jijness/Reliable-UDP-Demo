package rudp;

import Channel.LossyChannel;
import Channel.Utils;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class ReliableServer {
    // cấu hình
    private static final int SEGMENT_SIZE = 14000; // payload size per packet
    private static final int WINDOW_SIZE = 16;
    private static final int RTO_MS = 300; // retransmission timeout
    // Ngưỡng ACK trùng lặp cho Fast Retransmit (SR-ARQ Hint)
    private static final int DUP_ACK_THRESHOLD = 3;

    private InetAddress clientAddr;
    private int clientPort;
    private final int serverPort;
    private final DatagramSocket socket;
    private LossyChannel channel;

    private final Map<Integer, ReliablePacket> segments = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Set<Integer> acked = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, Integer> dupAckCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timerExec = Executors.newScheduledThreadPool(8);

    // hỗ trợ tránh spam retransmit / backoff
    private final Set<Integer> inFlightRetrans = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> fastRetxed = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, Long> lastRetransmitTs = new ConcurrentHashMap<>();
    private volatile int highestAckSeen = 0;
    private static final long RETX_MIN_INTERVAL_MS = 150; // tối thiểu giữa 2 lần retransmit cho cùng seq

    private int baseSeq = 1;
    private int nextSeq = 1;
    private int maxSeq = 0;

    // statistics
    private int sentCount = 0;
    private int retransCount = 0;

    public ReliableServer(int serverPort) throws SocketException {
        this.serverPort = serverPort;
        this.socket = new DatagramSocket(serverPort);
    }

    // Server chờ yêu cầu (Waiting State)
    private boolean waitForRequest(double lossRate) throws IOException {
        Utils.log("Server: Listening on port " + serverPort + " for REQUEST (RUDP)...");
        byte[] buf = new byte[1500];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        while (true) {
            socket.receive(dp);
            try {
                ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                if ((rp.flags & ReliablePacket.FLAG_REQ) != 0) {
                    this.clientAddr = dp.getAddress();
                    this.clientPort = dp.getPort(); // client bound socket port
                    // init channel (disable header corruption)
                    this.channel = new LossyChannel(socket, lossRate, 0.0, 50);
                    Utils.log("Server: Received REQUEST from " + clientAddr + ":" + clientPort + ". Sending DATA.");
                    return true;
                } else {
                    Utils.log("Server: Received non-REQ packet while waiting. Ignoring.");
                }
            } catch (IllegalArgumentException ignored) {
                // ignore short/invalid packets
            }
        }
    }

    public void sendFile(String filePath, double lossRate) throws IOException, InterruptedException {
        if (!waitForRequest(lossRate)) return;

        byte[] data = Utils.readFile(filePath);
        int total = data.length;
        Utils.log("Server: file size=" + total);

        // slice into segments
        int idx = 0;
        int seq = 1;
        while (idx < total) {
            int len = Math.min(SEGMENT_SIZE, total - idx);
            byte[] seg = Arrays.copyOfRange(data, idx, idx + len);
            ReliablePacket p = ReliablePacket.createData(seq, seg, 0);
            segments.put(seq, p);
            idx += len;
            seq++;
        }
        maxSeq = seq - 1;
        Utils.log("Server: total segments=" + maxSeq);
        // start ACK listener
        Thread ackThread = new Thread(this::receiveAcks, "ACK-Listener");
        ackThread.setDaemon(true);
        ackThread.start();

        long start = System.currentTimeMillis();
        // main sending loop
        while (baseSeq <= maxSeq) {
            synchronized (this) {
                while (nextSeq <= maxSeq && nextSeq < baseSeq + WINDOW_SIZE) {
                    sendSegment(nextSeq, false);
                    nextSeq++;
                }
            }
            // give CPU to other thread
            Thread.sleep(5);
        }
        long end = System.currentTimeMillis();
        Utils.log("Server: all segments acked. sent=" + sentCount + " retrans=" + retransCount);
        Utils.log(String.format("Time taken: %.2f s", (end - start) / 1000.0));
        // send FIN packet (ACK with FIN flag)
        sendFin();
        // cleanup
        timerExec.shutdownNow();
        channel.shutdown();
        socket.close();
    }

    private void sendFin() {
        ReliablePacket fin = ReliablePacket.createAck(maxSeq);
        fin.flags = (byte) (fin.flags | ReliablePacket.FLAG_FIN);
        byte[] buf = fin.toBytes();
        DatagramPacket dp = new DatagramPacket(buf, buf.length, clientAddr, clientPort);
        for (int i = 0; i < 3; i++) {
            channel.send(dp);
            try { Thread.sleep(RTO_MS); } catch (InterruptedException ignored) {}
        }
        Utils.log("Server: Sent FIN segments.");
    }
    private void sendSegment(int seq, boolean isRetransmit) {
        ReliablePacket p = segments.get(seq);
        if (p == null) return;
        // avoid retransmitting too fast for same seq
        long now = System.currentTimeMillis();
        Long lastTs = lastRetransmitTs.get(seq);
        if (isRetransmit && lastTs != null && now - lastTs < RETX_MIN_INTERVAL_MS) {
            // skip; the RTO task will (maybe) reschedule later
            return;
        }
        byte[] buf = p.toBytes();
        DatagramPacket dp = new DatagramPacket(buf, buf.length, clientAddr, clientPort);
        // mark in-flight before sending to avoid duplicate sends from other threads
        inFlightRetrans.add(seq);
        try {
            channel.send(dp);
        } finally {
            // note timestamp & stats
            lastRetransmitTs.put(seq, System.currentTimeMillis());
            sentCount++;
            if (isRetransmit) {
                retransCount++;
                Utils.log("Server: retransmit seq=" + seq);
            } else {
                Utils.log("Server: send seq=" + seq);
            }
        }
        // cancel old timer and schedule a fresh RTO for this seq
        ScheduledFuture<?> old = timers.get(seq);
        if (old != null) old.cancel(false);
        ScheduledFuture<?> fut = timerExec.schedule(() -> {
            synchronized (ReliableServer.this) {
                // if already acked, skip
                if (acked.contains(seq) || !segments.containsKey(seq)) {
                    inFlightRetrans.remove(seq);
                    timers.remove(seq);
                    return;
                }
                Utils.log("RTO: timeout -> retransmit seq=" + seq);
                // schedule retransmit (this call will update lastRetransmitTs and inFlight)
                sendSegment(seq, true);
                // clear inFlight (sendSegment will re-add)
            }
        }, RTO_MS, TimeUnit.MILLISECONDS);
        timers.put(seq, fut);
        // done: remove inFlight flag after scheduling timer so further callers can check
        inFlightRetrans.remove(seq);
    }

    private void receiveAcks() {
        byte[] buf = new byte[15000];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        final Map<Integer,Integer> dupCounts = new HashMap<>();
        while (!socket.isClosed()) {
            try {
                socket.setSoTimeout(500);
                socket.receive(dp);
                ReliablePacket rp = ReliablePacket.fromBytes(dp.getData(), dp.getLength());
                if (rp.getType() != ReliablePacket.TYPE_ACK) continue;
                int acknum = rp.seqOrAck;
                // verify ACK checksum
                ByteBuffer bb = ByteBuffer.allocate(5);
                bb.put((byte) ReliablePacket.TYPE_ACK);
                bb.putInt(acknum);
                int expect = Utils.udpChecksum16(bb.array(), 0, bb.position());
                if (((rp.checksum & 0xFFFF) != expect)) {
                    Utils.log("Server: Bad ACK checksum for seq=" + acknum + " -> ignore");
                    continue;
                }
                Utils.log("Server: received ACK " + acknum);
                synchronized (this) {
                    // mark selective
                    if (acknum >= 1 && acknum <= maxSeq) {
                        if (!acked.contains(acknum)) {
                            acked.add(acknum);
                            ScheduledFuture<?> f = timers.remove(acknum);
                            if (f != null) f.cancel(false);
                            lastRetransmitTs.remove(acknum);
                        }
                    }
                    // slide base
                    while (acked.contains(baseSeq)) {
                        acked.remove(baseSeq);
                        segments.remove(baseSeq);
                        timers.remove(baseSeq);
                        fastRetxed.remove(baseSeq);
                        baseSeq++;
                    }
                    // update highestAckSeen (used to limit selective retransmit)
                    if (acknum > highestAckSeen) highestAckSeen = acknum;
                    // duplicate ack count per acknum
                    int c = dupCounts.getOrDefault(acknum, 0) + 1;
                    dupCounts.put(acknum, c);
                    if (c >= DUP_ACK_THRESHOLD) {
                        int missing = acknum + 1;
                        if (missing <= maxSeq && !acked.contains(missing) && !fastRetxed.contains(missing)) {
                            // fast retransmit ONCE for this missing seq
                            Utils.log("FAST RETRANSMIT: Retransmitting seq=" + missing + " due to dup-acks for ack=" + acknum);
                            sendSegment(missing, true);
                            fastRetxed.add(missing);
                        }
                        dupCounts.put(acknum, 0); // reset for this acknum
                    }
                    // selective retransmit ONLY when acknum advances the highestAckSeen (i.e. progress)
                    // retransmit missing between baseSeq..highestAckSeen if any (but limit to window)
                    int windowEnd = Math.min(baseSeq + WINDOW_SIZE - 1, maxSeq);
                    // Only do selective when highestAckSeen advanced beyond previous base (less repetitive)
                    for (int s = baseSeq; s <= windowEnd; s++) {
                        if (!acked.contains(s) && s <= highestAckSeen) {
                            // avoid repeated immediate retransmits of same seq
                            Long last = lastRetransmitTs.get(s);
                            long now = System.currentTimeMillis();
                            if (last == null || now - last >= RETX_MIN_INTERVAL_MS) {
                                Utils.log("SELECTIVE-RETX: retransmit seq=" + s);
                                sendSegment(s, true);
                            }
                        }
                    }
                    // clear dupCounts for seqs < base (housekeeping)
                    dupCounts.keySet().removeIf(k -> k < baseSeq);
                }
            } catch (SocketTimeoutException ignored) {
                // continue
            } catch (Exception e) {
                if (!socket.isClosed()) e.printStackTrace();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        // Hardcoded: server listens on 5000, file "data_source.txt", default loss 10%
        int serverPort = 5000;
        String filePath = "src/data_source.txt";
        double loss = 0.10;

        System.out.println("--- RUDP Server ---");
        System.out.println(String.format("File: %s, Loss: %.2f, ServerPort: %d", filePath, loss, serverPort));

        ReliableServer server = new ReliableServer(serverPort);
        server.sendFile(filePath, loss);
    }
}