import java.net.*;
import java.nio.ByteBuffer;

public class TCPPacket {
    public static final int HEADER_SIZE = 24; // 4+4+8+4+2+2 (zeros+checksum)
    private int seq; // 4 bytes
    private int ack; // 4 bytes
    private long ackTimestamp; // 8 bytes
    private int length; // 1 byte for length, 3 bits for flags
    private boolean synFlag; // 1 bit per flag
    private boolean ackFlag;
    private boolean finFlag;
    private byte[] data;
    private byte[] rawBytes; // deserialized

    public TCPPacket(int seq, int ack, long ackTimestamp, boolean synFlag, boolean ackFlag, boolean finFlag, byte[] data) {
        this.seq = seq;
        this.ack = ack;
        this.ackTimestamp = ackTimestamp;
        this.synFlag = synFlag;
        this.ackFlag = ackFlag;
        this.finFlag = finFlag;
        if(data != null && data.length > 0) {
            this.data = data;
        } else {
            this.data = new byte[0];
        }
    }

    // from page 8 diagram
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length); 
        buffer.putInt(seq);
        buffer.putInt(ack);
        buffer.putLong(ackTimestamp);

        // bit manipulation to set length field with flags in last 3 bits
        // https://docs.oracle.com/javase/tutorial/java/nutsandbolts/op3.html
        int lengthFlag = data.length << 3; // shift left 3 bits for flags
        if (synFlag) lengthFlag |= 4; // set the 3rd flag bit for SYN ('OR 0100')
        if (finFlag) lengthFlag |= 2; // set the 2nd flag bit for FIN
        if (ackFlag) lengthFlag |= 1; // set the 1st flag bit for ACK

        buffer.putInt(lengthFlag);
        buffer.put(new byte[4]); // 4 bytes of zeros; All Zeros + Checksum ph
        buffer.put(data);
        
        byte[] raw = buffer.array();
        byte[] checksum = computeChecksum(raw); // 2 bytes at end of 24 byte header
        raw[22] = checksum[0];
        raw[23] = checksum[1];
        return raw;    
    }

    public static TCPPacket deserialize(byte[] rawData) {
        ByteBuffer buffer = ByteBuffer.wrap(rawData);
        
        int seq = buffer.getInt();
        int ack = buffer.getInt();
        long timestamp = buffer.getLong();
        int lengthFlag = buffer.getInt();
        buffer.get(new byte[4]); // skip All Zeros + checksum (matches serialize)

        // get flags from last 3 bits of length field
        boolean synFlag  = (lengthFlag & 4) != 0; // check if bit 3 is 1 (0100) for SYN
        boolean finFlag  = (lengthFlag & 2) != 0;
        boolean ackFlag = (lengthFlag & 1) != 0;

        // drop the flag bits to get length
        lengthFlag = lengthFlag >>> 3;

        byte[] data = new byte[lengthFlag];
        if (lengthFlag > 0) buffer.get(data);

        TCPPacket packet = new TCPPacket(seq, ack, timestamp, synFlag, ackFlag, finFlag, data);
        packet.rawBytes = rawData; // ready access for checksum
        return packet;
    }

    public byte[] computeChecksum(byte[] data) {
        return new byte[]{(byte) 0xFF, (byte) 0xFF}; 
    }

    public boolean isSynFlag() {
        return synFlag;
    }

    public boolean isAckFlag() {
        return ackFlag;
    }

    public boolean isFinFlag() {
        return finFlag;
    }

    public int getSeq() {
        return seq;
    }

    public int getAck() {
        return ack;
    }

    public long getTimestamp() {
        return ackTimestamp;
    }

    public byte[] getData() {
        return data;
    }  

    public void printSummary(String type) {
        String flags = (synFlag ? "S " : "- ")
                    + (ackFlag ? "A " : "- ")
                    + (finFlag ? "F " : "- ")
                    + (data.length > 0  ? "D"  : "-");
        System.out.printf("%s %.3f %s %d %d %d\n",
            type, System.nanoTime() / 1e9, flags, seq, data.length, ack);
    }
    

}