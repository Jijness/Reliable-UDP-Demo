package channel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.ThreadLocalRandom;

public class LossyChannel {
    private final DatagramSocket socket;
    private final double lossRate;      // 0..1
    private final int delayMs;          // >=0
    private final double corruptRate;   // 0..1

    public LossyChannel(DatagramSocket socket, double lossRate, int delayMs, double corruptRate) {
        this.socket      = socket;
        this.lossRate    = clamp01(lossRate);
        this.delayMs     = Math.max(0, delayMs);
        this.corruptRate = clamp01(corruptRate);
    }

    public void send(DatagramPacket pkt, String tag) throws Exception {
        // Drop?
        if (ThreadLocalRandom.current().nextDouble() < lossRate) {
            System.out.printf("xx DROP %s -> %s:%d len=%d%n",
                    tag, pkt.getAddress(), pkt.getPort(), pkt.getLength());
            return;
        }
        // Delay?
        if (delayMs > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(delayMs + 1));

        // Copy payload để có thể sửa (tránh đụng buffer gốc)
        byte[] src = pkt.getData();
        int off = pkt.getOffset(), len = pkt.getLength();
        byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);

        // Corrupt 1 byte?
        if (len > 0 && ThreadLocalRandom.current().nextDouble() < corruptRate) {
            int idx = ThreadLocalRandom.current().nextInt(len);
            copy[idx] ^= (byte)0xFF;
            System.out.printf("!! CORRUPT %s at idx=%d%n", tag, idx);
        }

        // Gửi thật
        DatagramPacket p2 = new DatagramPacket(copy, copy.length, pkt.getAddress(), pkt.getPort());
        socket.send(p2);
        System.out.printf(">> SEND %s -> %s:%d len=%d%n",
                tag, p2.getAddress(), p2.getPort(), p2.getLength());
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}