package com.remotedesktop.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Proto {
    public static final int MAGIC = 0x5244; // "RD"
    // Same layout as MAGIC but the payload is AES-256-GCM sealed:
    // [nonce(12)|ciphertext|tag(16)]. Header fields describe the seal;
    // checksum is over the plaintext payload. Matches server protocol.h.
    public static final int MAGIC_ENCRYPTED = 0x5245; // "RE"
    public static final int NONCE_SIZE = 12;
    public static final int TAG_SIZE = 16;
    public static final int HEADER_SIZE = 21;
    public static final int FEC_HEADER_SIZE = 29;
    public static final int MAX_HEADER_SIZE = 29;
    public static final int MAX_PAYLOAD_SIZE = 1400;
    public static final int MAX_PACKET_SIZE = MAX_PAYLOAD_SIZE + MAX_HEADER_SIZE;
    // Must match Protocol::MAX_TOTAL_FRAGMENTS in server/src/protocol.h.
    public static final int MAX_TOTAL_FRAGMENTS = 1024;

    public static final int DEFAULT_PORT = 9876;

    // Packet types
    public static final byte TYPE_HANDSHAKE     = 0x01;
    public static final byte TYPE_HANDSHAKE_ACK = 0x02;
    public static final byte TYPE_VIDEO_FRAME   = 0x10;
    public static final byte TYPE_KEYFRAME_REQ  = 0x11;
    public static final byte TYPE_RETRANSMIT_REQ = 0x12;
    public static final byte TYPE_VIDEO_FEC_PARITY = 0x13;
    public static final byte TYPE_INPUT_EVENT   = 0x20;
    public static final byte TYPE_MOUSE_MOVE    = 0x21;
    public static final byte TYPE_MOUSE_BUTTON  = 0x22;
    public static final byte TYPE_MOUSE_SCROLL  = 0x23;
    public static final byte TYPE_KEYBOARD_EVENT = 0x24;
    public static final byte TYPE_AUDIO_FRAME   = 0x31;
    public static final byte TYPE_HEARTBEAT     = 0x40;
    public static final byte TYPE_HEARTBEAT_PING = 0x41;
    public static final byte TYPE_HEARTBEAT_PONG = 0x42;
    public static final byte TYPE_DISCONNECT    = (byte) 0xFF;

    // FEC (Reed-Solomon over GF(2^8)) parameters
    public static final int FEC_BLOCK_DATA = 16;
    public static final int FEC_MAX_PARITY = 4;
    public static final int FEC_DEFAULT_LEVEL = 2;

    // Buttons
    public static final byte BTN_LEFT   = 0;
    public static final byte BTN_MIDDLE = 1;
    public static final byte BTN_RIGHT  = 2;

    // 4 x uint32 + 32-byte session key + uint8 fec_level
    // + uint8 audio_enabled + uint8 encryption_enabled
    public static final int HANDSHAKE_PAYLOAD_SIZE = 51;

    public static long computeChecksum(byte[] data, int offset, int length) {
        long sum = 0x12345678L;
        for (int i = 0; i < length; i++) {
            sum ^= (long) (data[offset + i] & 0xFF) << ((i % 4) * 8);
            if (i % 4 == 3) {
                sum = ((sum << 1) | (sum >>> 31)) & 0xFFFFFFFFL;
            }
        }
        return sum & 0xFFFFFFFFL;
    }

    public static ByteBuffer buildPacket(byte type) {
        return buildPacket(type, 0);
    }

    public static ByteBuffer buildPacket(byte type, int payloadSize) {
        if (payloadSize < 0) payloadSize = 0;
        int total = HEADER_SIZE + payloadSize;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) MAGIC);
        buf.put(type);
        buf.putInt(0);            // sequence
        buf.putInt(0);            // frame_id
        buf.putShort((short) 0);  // fragment_index
        buf.putShort((short) 1);  // total_fragments
        buf.putShort((short) payloadSize);
        buf.putInt(0);            // checksum (filled later)
        buf.position(HEADER_SIZE + payloadSize);
        buf.position(HEADER_SIZE);
        return buf;
    }

    /** Compute checksum over the payload region and write it in the header. */
    public static void fillChecksum(ByteBuffer buf) {
        int payloadSize = buf.getShort(15) & 0xFFFF;
        long sum = computeChecksum(buf.array(), HEADER_SIZE, payloadSize);
        buf.putInt(17, (int) sum);
    }

    /**
     * Build a VIDEO_FEC_PARITY packet (29-byte header + parity payload).
     * Layout matches Protocol::FecPacketHeader in server/src/protocol.h.
     */
    public static ByteBuffer buildFecPacket(int sequence, int frameId,
                                            int blockIndex, int blockData,
                                            int parityIndex, int parityCount,
                                            int payloadLength,
                                            int frameDataLength) {
        int total = FEC_HEADER_SIZE + payloadLength;
        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) MAGIC);
        buf.put(TYPE_VIDEO_FEC_PARITY);
        buf.putInt(sequence);
        buf.putInt(frameId);
        buf.putShort((short) blockIndex);
        buf.putShort((short) blockData);
        buf.putShort((short) parityIndex);
        buf.putShort((short) parityCount);
        buf.putShort((short) payloadLength);
        buf.putInt(0); // checksum (filled at offset 20)
        buf.putInt(frameDataLength);
        buf.position(FEC_HEADER_SIZE + payloadLength);
        buf.position(FEC_HEADER_SIZE);
        return buf;
    }

    /** Fill the checksum at offset 21 (FecPacketHeader) over the payload. */
    public static void fillFecChecksum(ByteBuffer buf) {
        int payloadSize = buf.getShort(19) & 0xFFFF;
        long sum = computeChecksum(buf.array(), FEC_HEADER_SIZE, payloadSize);
        buf.putInt(21, (int) sum);
    }
}