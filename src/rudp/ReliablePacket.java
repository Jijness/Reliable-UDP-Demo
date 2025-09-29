package rudp;/*
ReliablePacket - biểu diễn RUDP packet (header + payload)
Header (8 bytes): gồm
byte0: Flags
byte1: Control (Type(4)|WinIdx(2)|SACKCount(2))
bytes2-3: Aux (Len hoặc Checksum)
bytes4-7: Seq/Ack (32-bit)
*/


import Channel.Utils;

import java.nio.ByteBuffer;

public class ReliablePacket {
    // bit map cho vị trí cờ
    public static final int FLAG_FIN = 1 << 6;
    public static final int FLAG_SYN = 1 << 5;
    public static final int FLAG_ACK = 1 << 4;
    public static final int FLAG_DATA = 1 << 3;
    public static final int FLAG_SACK = 1 << 2;
    public static final int FLAG_REQ = 1 << 1; // Cờ yêu cầu (Request)
    // Types
    public static final int TYPE_DATA = 1;
    public static final int TYPE_ACK = 2;
    public static final int TYPE_PURE_ACK = 3;

    public static final int WINDOW_SIZE = 16;
    public static final int SOCKET_TIMEOUT_MS = 500;
    public static final int RTO_MS = 700;

    public byte flags, control;
    public short checksum;
    public int seqOrAck;
    public byte[] payload;

    public ReliablePacket() {}

    // Tạo packet DATA
    public static ReliablePacket createData(int seq, byte[] payload, int winIdx) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_DATA;
        int type = TYPE_DATA;
        int sackCount = 0;
        p.control = (byte) ((type << 4) | ((winIdx & 0x3) << 2) | (sackCount & 0x3));
        p.checksum = (short) Utils.udpChecksum16(payload, 0, payload.length);
        p.seqOrAck = seq;
        p.payload = payload == null ? new byte[0] : payload;
        return p;
    }
    // Tạo packet ACK (cumulative)
    public static ReliablePacket createAck(int ackNum) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_ACK;
        int type = TYPE_ACK;
        int winIdx = 0;
        int sackCount = 0;
        p.control = (byte) ((type << 4) | ((winIdx & 0x3) << 2) | (sackCount & 0x3));
        p.payload = new byte[0];
        // Tính checksum trên type + seqNum
        ByteBuffer bb = ByteBuffer.allocate(5);
        bb.put((byte) type);
        bb.putInt(ackNum);
        byte[] arr = bb.array();
        p.checksum = (short) Utils.udpChecksum16(arr, 0, arr.length);
        p.seqOrAck = ackNum;
        return p;
    }
    // Tạo packet YÊU CẦU (Request)
    public static ReliablePacket createRequest(int listenPort) {
        ReliablePacket p = new ReliablePacket();
        p.flags = (byte) FLAG_REQ;
        p.control = 0;
        p.checksum = (short) (listenPort & 0xFFFF);
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
        buffer.putShort((short) (checksum & 0xFFFF));
        buffer.putInt(seqOrAck);
        if(payload != null && payload.length > 0) buffer.put(payload);
        return buffer.array();
    }
    // Parse từ bytes
    public static ReliablePacket fromBytes(byte[] buf, int len) throws IllegalArgumentException{
        if(len < 8) throw new IllegalArgumentException("Packet too short");
        ByteBuffer buffer = ByteBuffer.wrap(buf, 0, len);
        ReliablePacket p = new ReliablePacket();
        p.flags = buffer.get();
        p.control = buffer.get();
        p.checksum = (short) (buffer.getShort() & 0xFFFF);
        p.seqOrAck = buffer.getInt();
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
    public boolean isLast() {
        return (flags & FLAG_FIN) != 0;
    }
    @Override
    public String toString() {
        return String.format("RPacket[type=%d flags=0x%02X aux=%d seq=%d payload=%d]",
                getType(), flags, checksum, seqOrAck, (payload==null?0:payload.length));
    }
}