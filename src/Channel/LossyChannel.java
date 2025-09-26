/*
LossyChannel - mô phỏng kênh mạng không tin cậy
Tham số:
  lossRate: 0.0 - 1.0 (xác suất drop)
  corruptRate: 0.0 - 1.0 (xác suất corrupt)
  maxDelayMs: tối đa delay (random 0..maxDelayMs)
  Gửi bất đồng bộ: nếu bị drop -> bỏ; nếu delay-> sleep task rồi gửi.
*/
package Channel;

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
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

    public LossyChannel(DatagramSocket socket, double lossRate, double corruptRate, int maxDelayMs) {
        this.socket = socket;
        this.lossRate = lossRate;
        this.corruptRate = corruptRate;
        this.maxDelayMs = maxDelayMs;
    }
    public void send(DatagramPacket packet) {
        // Chọn tỉ lệ drop
        double d = rnd.nextDouble();
        if(d < lossRate) {
            Utils.log("LossyChannel: DROPPED packet to " + packet.getAddress() + ":" + packet.getPort());
            return;
        }
        // Nếu corrupt
        byte[] data = packet.getData();
        int offset= packet.getOffset(), len =  packet.getLength();
        byte[] copy = new byte[len];
        System.arraycopy(data, offset, copy, 0, len);
        if(rnd.nextDouble() < corruptRate && len > 0) {
            int index = rnd.nextInt(len);
            copy[index] ^= 0xFF; // flip bits
            Utils.log("LossyChannel: CORRUPTED packet (index="+ index +")");
        }
        // Delay
        int delay = maxDelayMs > 0 ? rnd.nextInt(maxDelayMs) : 0;
        executor.schedule(() -> {
            try{
                DatagramPacket packet2 = new DatagramPacket(copy, copy.length, packet.getAddress(), packet.getPort());
                socket.send(packet2);
            }catch(Exception e){
                e.printStackTrace();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
