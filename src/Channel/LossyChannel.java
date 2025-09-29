/*
LossyChannel - mô phỏng kênh mạng không tin cậy
Tham số:
  lossRate: 0.0 - 1.0 (xác suất drop)
  corruptRate: 0.0 - 1.0 (xác suất corrupt)
  maxDelayMs: tối đa delay (random 0..maxDelayMs)
  Gửi bất đồng bộ: nếu bị drop -> bỏ; nếu delay-> sleep task rồi gửi.
*/
package Channel;

import rudp.ReliablePacket;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LossyChannel {
    private final DatagramSocket socket;
    private final double lossRate, corruptRate;
    private final int maxDelayMs;
    private final Random rnd = new Random();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(8);

    public LossyChannel(DatagramSocket socket, double lossRate, double corruptRate, int maxDelayMs) {
        this.socket = socket;
        this.lossRate = Math.max(0.0, Math.min(0.5, lossRate));
        this.corruptRate = Math.max(0.0, Math.min(0.5, corruptRate));
        this.maxDelayMs = Math.max(0, maxDelayMs);
    }
    public void send(DatagramPacket packet) {
        double d = rnd.nextDouble();
        if (d < lossRate) {
            try {
                ReliablePacket rp = ReliablePacket.fromBytes(packet.getData(), packet.getLength());
                Utils.log(String.format("LossyChannel: DROPPED to %s:%d type=%d seqOrAck=%d",
                        packet.getAddress(), packet.getPort(), rp.getType(), rp.seqOrAck));
            } catch (Exception e) {
                Utils.log("LossyChannel: DROPPED (unparsable packet)");
            }
            return;
//            Utils.log("LossyChannel: DROPPED packet to " + packet.getAddress() + ":" + packet.getPort());
//            return;
        }
        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int len = packet.getLength();
        byte[] copy = new byte[len];
        System.arraycopy(data, offset, copy, 0, len);

        // Không corrupt header RUDP (8 bytes). Nếu gói ngắn hơn 8 thì vẫn corrupt payload.
        int protectedHeader = Math.min(8, len);
        if (rnd.nextDouble() < corruptRate && len > protectedHeader) {
            int idx = protectedHeader + rnd.nextInt(len - protectedHeader);
            copy[idx] ^= (byte) (rnd.nextInt(256) & 0xFF);
            Utils.log("LossyChannel: CORRUPTED packet (index=" + idx + ")");
        }

        int delay = maxDelayMs > 0 ? rnd.nextInt(maxDelayMs + 1) : 0;
        executor.schedule(() -> {
            try {
                DatagramPacket p2 = new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort());
                socket.send(p2);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
