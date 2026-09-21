#pragma once

#include "protocol.h"

#include <array>
#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include <boost/asio.hpp>
#include <boost/asio/steady_timer.hpp>

struct ClientInfo {
    boost::asio::ip::udp::endpoint endpoint;
    uint32_t last_sequence = 0;
    uint64_t last_heartbeat_us = 0;
    bool connected = false;
    char session_key[32] = {};
    int fec_level = 0; // parity fragments per FEC block requested by this client
    bool audio_enabled = false;
    bool encryption_enabled = false;

    // RTT analysis: timestamps of server-originated HEARTBEAT_PING packets that
    // still await their HEARTBEAT_PONG echo, and the ring of measured RTT
    // samples (microseconds) used for min/avg/max reporting.
    std::deque<uint64_t> outstanding_ping_us;
    std::deque<uint64_t> rtt_samples_us;
};

/**
 * Connectionless, best-effort transport on top of Boost.Asio UDP.
 *
 * Video access units exceed the 1400-byte UDP datagram limit, so each frame
 * is sliced into MAX_PAYLOAD_SIZE fragments that the client reassembles from
 * the PacketHeader (frame_id/fragment_index/total_fragments/checksum).
 * Because UDP drops and reorders packets:
 *   - the client requests lost fragments via FRAGMENT_RETRANSMIT_REQ, served
 *     from a bounded send-history ring,
 *   - the cached SPS/PPS is prepended to every frame so any completed frame
 *     is a decoder sync point (intra-refresh mode has no IDR keyframes).
 * All socket I/O happens on a single io_context thread; FEC/queueing stays on
 * the caller's thread.
 */
class UdpServer {
public:
    UdpServer();
    ~UdpServer();

    bool start(int port = Protocol::DEFAULT_PORT);
    void stop();

    void broadcast_frame(const uint8_t* data, size_t length,
                         uint32_t frame_id);

    // Send one Opus packet to every client that negotiated audio. No
    // fragmentation: Opus frames are far below MAX_PAYLOAD_SIZE. Payloads are
    // AES-256-GCM sealed for clients that negotiated encryption.
    void broadcast_audio(const uint8_t* data, size_t length);

    using InputCallback = std::function<void(const ClientInfo&, Protocol::PacketType,
        const uint8_t* payload, size_t len)>;
    void set_input_callback(InputCallback cb) { m_input_callback = std::move(cb); }

    using KeyframeCallback = std::function<void()>;
    void set_keyframe_callback(KeyframeCallback cb) { m_keyframe_callback = std::move(cb); }

    void set_sync_prefix(const std::vector<uint8_t>& prefix);
    bool has_sync_prefix() const;
    void request_sync() {
        std::lock_guard<std::mutex> lock(m_sync_mutex);
        m_sync_pending = true;
    }

    int client_count() const { return m_client_count.load(); }

private:
    void start_receive();
    void handle_packet(const uint8_t* data, size_t length,
                       const boost::asio::ip::udp::endpoint& from);
    void handle_retransmit(const uint8_t* payload, size_t payload_len,
                           const boost::asio::ip::udp::endpoint& from);
    void send_datagram(const boost::asio::ip::udp::endpoint& dest,
                       std::vector<uint8_t> data);

    void start_ping_timer();
    void send_pings();
    void on_pong(ClientInfo& client, uint64_t pong_timestamp_us, uint64_t now_us);

    boost::asio::io_context m_io;
    boost::asio::steady_timer m_ping_timer{m_io};
    std::unique_ptr<boost::asio::ip::udp::socket> m_socket;
    boost::asio::ip::udp::endpoint m_recv_sender;
    std::array<uint8_t, Protocol::MAX_PACKET_SIZE> m_recv_buf{};
    std::thread m_io_thread;
    std::atomic<bool> m_running{false};
    std::atomic<int> m_client_count{0};

    std::mutex m_client_mutex;
    std::unordered_map<uint64_t, ClientInfo> m_clients;

    // Serializes every send_to: video fragments are written from the main
    // encoder thread while control packets (heartbeats, retransmits, pings)
    // are written from the io thread. boost::asio sockets are not safe for
    // concurrent use, and interleaving here stalled the stream.
    std::mutex m_send_mutex;

    struct HistoryPacket {
        boost::asio::ip::udp::endpoint endpoint;
        uint32_t frame_id;
        uint16_t fragment_index;
        std::vector<uint8_t> data;
        uint64_t ts_us;
    };
    std::mutex m_history_mutex;
    std::deque<HistoryPacket> m_packet_history;
    static constexpr size_t HISTORY_CAP = 4000;

    std::atomic<uint32_t> m_sequence{0};
    uint64_t m_last_keyframe_req_us = 0;
    uint64_t m_last_retransmit_us = 0;

    mutable std::mutex m_sync_mutex;
    std::vector<uint8_t> m_sync_prefix;
    bool m_sync_pending = false;

    InputCallback m_input_callback;
    KeyframeCallback m_keyframe_callback;
};