package rudp;/*
ReliablePacket - biểu diễn RUDP packet (header + payload)
Header (8 bytes): gồm
byte0: Flags
byte1: Control (Type(4)|WinIdx(2)|SACKCount(2))
bytes2-3: Aux (Len hoặc Checksum)
bytes4-7: Seq/Ack (32-bit)
*/


import java.nio.ByteBuffer;

public class ReliablePacket {
    // bit map cho vị trí cờ
    public static final int FLAG_FIN = 1 << 6;
    public static final int FLAG_SYN = 1 << 5;
    public static final int FLAG_ACK = 1 << 4;
    public static final int FLAG_DATA = 1 << 3;
    public static final int FlAG_SACK_PRESENT = 1 << 2;
    public static final int FLAG_REQ = 1 << 1; // Cờ yêu cầu (Request)
    // Types
    public static final int TYPE_DATA = 1;
    public static final int TYPE_ACK = 2;
    public static final int TYPE_PURE_ACK = 3;

    public byte flags, control;
    public int aux16;
    public long seqOrAck;
    public byte[] payload;

    public ReliablePacket() {}

    // Tạo packet DATA
    public static ReliablePacket createData(long seq, byte[] payload, int winIdx) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_DATA;
        int type = TYPE_DATA;
        int sackCount = 0;
        p.control = (byte) ((type << 4) | ((winIdx & 0x3) << 2) | (sackCount & 0x3));
        p.aux16 = payload.length > 0xFFFF ? 0xFFFF : payload.length;
        p.seqOrAck = seq;
        p.payload = payload;
        return p;
    }
    // Tạo packet ACK (cumulative)
    public static ReliablePacket createAck(long ackNum) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_ACK;
        int type = TYPE_ACK;
        int winIdx = 0;
        int sackCount = 0;
        p.control = (byte) ((type << 4) | ((winIdx & 0x3) << 2) | (sackCount & 0x3));
        p.aux16 = 0; // checksum reserved
        p.seqOrAck = ackNum;
        p.payload = new byte[0];
        return p;
    }
    // Tạo packet YÊU CẦU (Request)
    public static ReliablePacket createRequest(int listenPort) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_REQ;
        p.aux16 = listenPort; // Client gửi port nghe của mình trong aux16
        p.seqOrAck = 0;
        p.payload = new byte[0];
        return p;
    }
    // serialize -> bytes (network order)
    public byte[] toBytes() {
        int headerLen = 8;
        int total = headerLen + (payload == null ? 0 : payload.length);
        ByteBuffer buffer = ByteBuffer.allocate(total);
        buffer.put(flags);
        buffer.put(control);
        buffer.putShort((short) (aux16 & 0xFFFF));
        buffer.putInt((int) (seqOrAck & 0xFFFFFFFFL));
        if(payload != null) buffer.put(payload);
        return buffer.array();
    }
    // Parse từ bytes
    public static ReliablePacket fromBytes(byte[] buf, int len) throws IllegalArgumentException{
        if(len < 8) throw new IllegalArgumentException("Packet too short");
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, len);
        ReliablePacket p = new ReliablePacket();
        p.flags = buffer.get();
        p.control = buffer.get();
        p.aux16 = buffer.getShort() & 0xFFFF;
        p.seqOrAck = ((long) buffer.getInt()) & 0xFFFFFFFFL;
        int payloadLen = len - 8;
        if(payloadLen > 0) {
            p.payload = new byte[payloadLen];
            buffer.get(p.payload);
        } else {
            p.payload = new byte[0];
        }
        return p;
    }
    public int getType() {
        return (control >> 4) & 0x0F;
    }
    public int getSackCount() {
        return control & 0x03;
    }
    @Override
    public String toString() {
        return String.format("RPacket[type=%d flags=0x%02X aux=%d seq=%d payload=%d]",
                getType(), flags, aux16, seqOrAck, (payload==null?0:payload.length));
    }
}
