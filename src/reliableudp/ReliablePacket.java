package reliableudp;

import java.nio.ByteBuffer;


/**
 * ReliablePacket (RUDP header tự định nghĩa)
 * Layout: [type:1][flags:1][seq:4][aux:2][sendTs:8] + [payload... nếu DATA]
 *
 * - type:  DATA(0), ACK(1), FIN(2), FIN-ACK(3)
 * - flags: FLAG_LAST (đánh dấu gói DATA cuối) - tùy chọn dùng
 * - seq:   DATA = Sequence Number; ACK/FIN/FIN-ACK = Ack/Echo Number
 * - aux:   DATA = length16; (ACK|FIN|FIN-ACK) = checksum16(type, seq)
 */
public class ReliablePacket {
    // ===== Cấu hình demo (chỉnh cho báo cáo/thử nghiệm) =====
    public static final int WINDOW_SIZE = 8;          // kích thước cửa sổ SR
    public static final int CHUNK_SIZE  = 700;        // payload/packet (< MTU; phải <= 65535)
    public static final int TIMEOUT_MS  = 300;        // timeout retransmission
    public static final int SOCKET_TIMEOUT_MS = 100;  // receive polling

    // ===== Kiểu gói tin =====
    public static final byte TYPE_DATA   = 0;
    public static final byte TYPE_ACK    = 1;


    // ===== Flags =====
    public static final byte FLAG_LAST = 0x1; // đánh dấu gói DATA cuối

    // ===== Trường header =====
    public byte type;           // DATA/ACK
    public byte flags;          // LAST cho DATA
    public int seq;             // sequence number
    public short aux;           // DATA: checksum
    public byte[] payload;      // null với ACK


    public static final int HEADER_BYTES = 1 + 1 + 4 + 2;
    public static final int MAX_LEN16 = 0xFFFF;


    /** Tạo DATA packet. AUX = length16. */
    public static ReliablePacket makeData(int seq, boolean last, byte[] payload) {
        ReliablePacket p = new ReliablePacket();
        p.type = TYPE_DATA;
        p.flags = (byte) (last ? FLAG_LAST : 0);
        p.seq = seq;

        // an toàn null
        p.payload = (payload == null) ? new byte[0] : payload;
        int len = p.payload.length;
        if (len > MAX_LEN16) {
            throw new IllegalArgumentException("payload too large for 16-bit length: " + len);
        }
        p.aux = checksum16Data(p.payload);       // AUX = LENGTH(16b)
        return p;
    }


    /** Tạo ACK. AUX = checksum16(type, seq). Không có payload. */
    public static ReliablePacket makeAck(int ackSeq) {
        ReliablePacket p = new ReliablePacket();
        p.type   = TYPE_ACK;
        p.flags  = 0;
        p.seq    = ackSeq;
        p.aux    = 0;
        p.payload = null;
        return p;
    }

    /** DATA hợp lệ khi aux == checksum16Data(payload). */
    public boolean validateDataChecksum() {
        if (type != TYPE_DATA) return true;
        return aux == checksum16Data(payload);
    }

    /** Checksum16 cho DATA: cộng 8-bit mở rộng (sum16) hoặc XOR; ở đây dùng sum16 đơn giản. */
    public static short checksum16Data(byte[] data) {
        int sum = 0;
        if (data != null) {
            for (byte b : data) {
                sum += (b & 0xFF);
                sum &= 0xFFFF; // giữ 16-bit
            }
        }
        return (short) sum;
    }


    public boolean isLast()    { return (flags & FLAG_LAST) != 0; }



    public byte[] toBytes() {
        int payloadLen = (type == TYPE_DATA && payload != null) ? payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payloadLen);
        buf.put(type);
        buf.put(flags);
        buf.putInt(seq);
        buf.putShort(aux);
        if (payloadLen > 0) buf.put(payload);
        return buf.array();
    }


    public static ReliablePacket fromBytes(byte[] data, int length) {
        if (length < HEADER_BYTES) throw new IllegalArgumentException("packet too short");
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        ReliablePacket p = new ReliablePacket();
        p.type = buf.get();
        p.flags = buf.get();
        p.seq = buf.getInt();
        p.aux = buf.getShort();

        if (p.type == TYPE_DATA) {
            int len = Short.toUnsignedInt(p.aux); // AUX=LENGTH
            int remain = length - HEADER_BYTES;
            if (len > remain) {
                // gói không đủ dữ liệu; cắt theo thực nhận
                len = remain;
            }
            p.payload = new byte[len];
            if (len > 0) buf.get(p.payload);
        } else {
            p.payload = null; // ACK/FIN/FIN-ACK không có payload
        }
        return p;
    }
}
