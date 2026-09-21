#pragma once

#include <cstdint>
#include <cstddef>

namespace Protocol {

constexpr uint16_t MAGIC = 0x5244; // "RD"
// Same layout as MAGIC but transports an AES-256-GCM sealed payload:
// [nonce(12)|ciphertext|tag(16)] replaces the plain payload. Header fields
// (including payload_length & checksum) describe the seal, so reassembly
// state stays identical; the checksum is over the *plaintext* payload.
constexpr uint16_t MAGIC_ENCRYPTED = 0x5245; // "RE"
constexpr int NONCE_SIZE = 12;
constexpr int TAG_SIZE = 16;
constexpr int MAX_PAYLOAD_SIZE = 1400;
constexpr int MAX_HEADER_SIZE = 29;   // fits both PacketHeader (21) and FecPacketHeader (29)
constexpr int MAX_PACKET_SIZE = MAX_PAYLOAD_SIZE + MAX_HEADER_SIZE;
// Max fragments per access unit. Kept identical across server and both
// clients (Proto.java, FrameReassembler) so the server refuses to transmit
// a frame no client would ever reassemble.
constexpr int MAX_TOTAL_FRAGMENTS = 1024;
constexpr int DEFAULT_PORT = 9876;
constexpr int KEYFRAME_INTERVAL = 30;

// FEC (Reed-Solomon over GF(2^8)): video data fragments of a frame are
// grouped into blocks of FEC_BLOCK_DATA fragments; each block carries up to
// FEC_MAX_PARITY extra parity fragments. A block is recoverable whenever at
// least FEC_BLOCK_DATA of its (data+parity) fragments arrive, so the client
// tries FEC first and falls back to FRAGMENT_RETRANSMIT_REQ only when a
// block cannot be reconstructed.
constexpr int FEC_BLOCK_DATA = 16;   // data fragments per FEC block
constexpr int FEC_MAX_PARITY = 4;    // upper bound a client may request
constexpr int FEC_DEFAULT_LEVEL = 2; // parity fragments a client requests by default

enum class PacketType : uint8_t {
    HANDSHAKE       = 0x01,
    HANDSHAKE_ACK   = 0x02,
    VIDEO_FRAME     = 0x10,
    VIDEO_FEC_PARITY = 0x13,
    KEYFRAME_REQ    = 0x11,
    FRAGMENT_RETRANSMIT_REQ = 0x12,
    MOUSE_MOVE      = 0x21,
    MOUSE_BUTTON    = 0x22,
    MOUSE_SCROLL    = 0x23,
    KEYBOARD_EVENT  = 0x24,
    AUDIO_FRAME     = 0x31,       // server->client Opus frame (see audio_capture.h)
    HEARTBEAT       = 0x40,       // legacy ping: always echoed verbatim
    HEARTBEAT_PING  = 0x41,       // ping: receiver echoes it back as HEARTBEAT_PONG
    HEARTBEAT_PONG  = 0x42,       // pong: sender uses the echoed timestamp to measure RTT
    DISCONNECT      = 0xFF,
};

enum class InputButtonType : uint8_t {
    LEFT   = 0,
    MIDDLE = 1,
    RIGHT  = 2,
};

#pragma pack(push, 1)
struct PacketHeader {
    uint16_t magic;
    PacketType type;
    uint32_t sequence;
    uint32_t frame_id;
    uint16_t fragment_index;
    uint16_t total_fragments;
    uint16_t payload_length;
    uint32_t checksum;
};

struct HandshakePayload {
    uint32_t screen_width;
    uint32_t screen_height;
    uint32_t fps;
    uint32_t encoder_id; // 0=h264
    char session_key[32];
    uint8_t fec_level;          // 0 = no FEC, else parity fragments per FEC block
    uint8_t audio_enabled;      // 1 = client requests the audio stream
    uint8_t encryption_enabled; // 1 = client requests an encrypted transport
};

// Header for FEC parity fragments (VIDEO_FEC_PARITY packets). The wire
// payload is the Reed-Solomon parity vector for one FEC block of one frame.
struct FecPacketHeader {
    uint16_t magic;
    PacketType type;           // VIDEO_FEC_PARITY
    uint32_t sequence;
    uint32_t frame_id;
    uint16_t block_index;      // FEC block within the frame (block b covers data fragments [b*FEC_BLOCK_DATA, ...))
    uint16_t block_data;       // number of data fragments in this block (K)
    uint16_t parity_index;     // 0..parity_count-1 index into the RS parity rows
    uint16_t parity_count;     // number of parity fragments for this block (P)
    uint16_t payload_length;   // length of the parity vector in bytes (L)
    uint32_t checksum;
    uint32_t frame_data_length; // total bytes of the whole frame's data (all fragments)
};  // 29 bytes

struct MouseMovePayload {
    int32_t x;
    int32_t y;
};

struct MouseButtonPayload {
    int32_t x;
    int32_t y;
    InputButtonType button;
    uint8_t pressed; // 1=press, 0=release
};

struct MouseScrollPayload {
    int32_t x;
    int32_t y;
    int32_t delta_x;
    int32_t delta_y;
};

struct KeyboardEventPayload {
    uint32_t keycode;
    uint8_t pressed; // 1=press, 0=release
    uint32_t keysym; // X11 keysym
};

struct HeartbeatPayload {
    uint64_t timestamp_us;
};
#pragma pack(pop)

inline uint32_t compute_checksum(const uint8_t* data, size_t length) {
    uint32_t sum = 0x12345678;
    for (size_t i = 0; i < length; i++) {
        sum ^= (uint32_t)data[i] << ((i % 4) * 8);
        if (i % 4 == 3) {
            sum = (sum << 1) | (sum >> 31);
        }
    }
    return sum;
}

} // namespace Protocol
