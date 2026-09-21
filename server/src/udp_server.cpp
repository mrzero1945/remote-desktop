#include "udp_server.h"
#include "crypto.h"
#include "fec.h"

#include <cstdio>
#include <cstring>
#include <chrono>
#include <algorithm>

using boost::asio::ip::udp;
using namespace std::chrono;

namespace {
// Stable socket identity (address:port) used to key m_clients.
uint64_t client_key(const udp::endpoint& ep) {
    return (((uint64_t)ep.address().to_v4().to_uint()) << 16) |
           (uint16_t)ep.port();
}
} // namespace

UdpServer::UdpServer() {}

UdpServer::~UdpServer() {
    stop();
}

bool UdpServer::start(int port) {
    try {
        m_socket = std::make_unique<udp::socket>(m_io);
        m_socket->open(udp::v4());

        boost::asio::socket_base::reuse_address reuse(true);
        m_socket->set_option(reuse);

        boost::asio::socket_base::receive_buffer_size rcvbuf(4 * 1024 * 1024);
        m_socket->set_option(rcvbuf);

        boost::asio::socket_base::send_buffer_size sndbuf(4 * 1024 * 1024);
        m_socket->set_option(sndbuf);

        m_socket->bind(udp::endpoint(udp::v4(), (unsigned short)port));
    } catch (const std::exception& e) {
        fprintf(stderr, "[UdpServer] Cannot bind port %d: %s\n", port, e.what());
        return false;
    }

    m_running = true;
    start_receive();
    start_ping_timer();
    m_io_thread = std::thread([this] { m_io.run(); });

    printf("[UdpServer] Listening UDP on port %d\n", port);
    return true;
}

void UdpServer::stop() {
    if (!m_running.exchange(false)) return;

    if (m_socket) {
        boost::system::error_code ec;
        m_socket->close(ec);
    }

    {
        boost::system::error_code ec;
        m_ping_timer.cancel(ec);
    }

    m_io.stop();
    if (m_io_thread.joinable()) m_io_thread.join();
    m_socket.reset();
    m_client_count = 0;

    {
        std::lock_guard<std::mutex> lock(m_client_mutex);
        m_clients.clear();
    }
    {
        std::lock_guard<std::mutex> lock(m_history_mutex);
        m_packet_history.clear();
    }
    printf("[UdpServer] stopped\n");
}

void UdpServer::start_receive() {
    m_socket->async_receive_from(
        boost::asio::buffer(m_recv_buf), m_recv_sender,
        [this](const boost::system::error_code& ec, std::size_t bytes) {
            if (ec) {
                if (m_running) {
                    fprintf(stderr, "[UdpServer] receive_from: %s\n",
                            ec.message().c_str());
                    start_receive();
                }
                return;
            }
            if (bytes >= sizeof(Protocol::PacketHeader)) {
                handle_packet(m_recv_buf.data(), bytes, m_recv_sender);
            }
            start_receive();
        });
}

void UdpServer::handle_packet(const uint8_t* data, size_t length,
                              const udp::endpoint& from) {
    const auto* hdr = (const Protocol::PacketHeader*)data;

    if (hdr->magic != Protocol::MAGIC) return;

    uint32_t checksum = Protocol::compute_checksum(
        data + sizeof(Protocol::PacketHeader),
        length - sizeof(Protocol::PacketHeader));
    if (checksum != hdr->checksum) return;

    const uint8_t* payload = data + sizeof(Protocol::PacketHeader);
    size_t payload_len = length - sizeof(Protocol::PacketHeader);

    uint64_t key = client_key(from);

    switch (hdr->type) {
    case Protocol::PacketType::HANDSHAKE: {
        // Accept legacy 49-byte handshakes (pre audio/encryption negotiation).
        size_t min_hs = sizeof(Protocol::HandshakePayload) - 2;
        if (payload_len < min_hs) {
            fprintf(stderr, "[UdpServer] HANDSHAKE too short (%zu)\n", payload_len);
            return;
        }

        auto now = high_resolution_clock::now();
        uint64_t now_us = duration_cast<microseconds>(
            now.time_since_epoch()).count();

        ClientInfo client;
        client.endpoint = from;
        client.last_sequence = hdr->sequence;
        client.last_heartbeat_us = now_us;
        client.connected = true;
        memcpy(client.session_key,
               ((const Protocol::HandshakePayload*)payload)->session_key, 32);
        if (payload_len >= sizeof(Protocol::HandshakePayload)) {
            const auto* hs = (const Protocol::HandshakePayload*)payload;
            client.fec_level = hs->fec_level;
            if (client.fec_level > Protocol::FEC_MAX_PARITY) {
                client.fec_level = Protocol::FEC_MAX_PARITY;
            }
            client.audio_enabled = hs->audio_enabled != 0;
            client.encryption_enabled = hs->encryption_enabled != 0;
        }

        {
            std::lock_guard<std::mutex> lock(m_client_mutex);
            auto it = m_clients.find(key);
            if (it == m_clients.end()) m_client_count++;
            m_clients[key] = client;
        }

        printf("[UdpServer] Client connected from %s (total: %d)\n",
               from.address().to_string().c_str(), m_client_count.load());
        printf("[UdpServer] Client flags fec=%d audio=%d ssl=%d\n",
               client.fec_level, client.audio_enabled ? 1 : 0,
               client.encryption_enabled ? 1 : 0);

        Protocol::PacketHeader ack = {};
        ack.magic = Protocol::MAGIC;
        ack.type = Protocol::PacketType::HANDSHAKE_ACK;
        ack.sequence = m_sequence++;
        ack.fragment_index = 0;
        ack.total_fragments = 1;
        ack.payload_length = 0;
        ack.checksum = 0;

        std::vector<uint8_t> msg(sizeof(ack));
        memcpy(msg.data(), &ack, sizeof(ack));
        send_datagram(from, std::move(msg));
        break;
    }

    case Protocol::PacketType::HEARTBEAT: {
        // Legacy ping (desktop-viewer): always echo the payload verbatim so
        // that client can measure RTT off its own timestamp.
        auto now = high_resolution_clock::now();
        uint64_t now_us = duration_cast<microseconds>(
            now.time_since_epoch()).count();

        {
            std::lock_guard<std::mutex> lock(m_client_mutex);
            auto it = m_clients.find(key);
            if (it != m_clients.end()) {
                it->second.last_heartbeat_us = now_us;
                it->second.last_sequence = hdr->sequence;
            }
        }

        if (payload_len >= sizeof(Protocol::HeartbeatPayload)) {
            Protocol::PacketHeader hb = {};
            hb.magic = Protocol::MAGIC;
            hb.type = Protocol::PacketType::HEARTBEAT;
            hb.sequence = m_sequence++;
            hb.fragment_index = 0;
            hb.total_fragments = 1;
            hb.payload_length = (uint16_t)sizeof(Protocol::HeartbeatPayload);
            hb.checksum = Protocol::compute_checksum(
                payload, sizeof(Protocol::HeartbeatPayload));

            std::vector<uint8_t> msg(sizeof(hb) +
                                     sizeof(Protocol::HeartbeatPayload));
            memcpy(msg.data(), &hb, sizeof(hb));
            memcpy(msg.data() + sizeof(hb), payload,
                   sizeof(Protocol::HeartbeatPayload));
            send_datagram(from, std::move(msg));
        }
        break;
    }

    case Protocol::PacketType::HEARTBEAT_PING: {
        // New bidirectional ping (Android client): echo the client's own
        // timestamp back as a HEARTBEAT_PONG so it can measure RTT.
        auto now = high_resolution_clock::now();
        uint64_t now_us = duration_cast<microseconds>(
            now.time_since_epoch()).count();

        {
            std::lock_guard<std::mutex> lock(m_client_mutex);
            auto it = m_clients.find(key);
            if (it != m_clients.end()) {
                it->second.last_heartbeat_us = now_us;
                it->second.last_sequence = hdr->sequence;
            }
        }

        if (payload_len >= sizeof(Protocol::HeartbeatPayload)) {
            Protocol::PacketHeader pong = {};
            pong.magic = Protocol::MAGIC;
            pong.type = Protocol::PacketType::HEARTBEAT_PONG;
            pong.sequence = m_sequence++;
            pong.fragment_index = 0;
            pong.total_fragments = 1;
            pong.payload_length = (uint16_t)sizeof(Protocol::HeartbeatPayload);
            pong.checksum = Protocol::compute_checksum(
                payload, sizeof(Protocol::HeartbeatPayload));

            std::vector<uint8_t> msg(sizeof(pong) +
                                     sizeof(Protocol::HeartbeatPayload));
            memcpy(msg.data(), &pong, sizeof(pong));
            memcpy(msg.data() + sizeof(pong), payload,
                   sizeof(Protocol::HeartbeatPayload));
            send_datagram(from, std::move(msg));
        }
        break;
    }

    case Protocol::PacketType::HEARTBEAT_PONG: {
        // A PONG carrying one of our own ping timestamps completes the round
        // trip: measure the RTT and log the delay analysis.
        if (payload_len >= sizeof(Protocol::HeartbeatPayload)) {
            Protocol::HeartbeatPayload hp;
            memcpy(&hp, payload, sizeof(hp));
            auto now = high_resolution_clock::now();
            uint64_t now_us = duration_cast<microseconds>(
                now.time_since_epoch()).count();

            std::lock_guard<std::mutex> lock(m_client_mutex);
            auto it = m_clients.find(key);
            if (it == m_clients.end()) break;
            it->second.last_heartbeat_us = now_us;
            it->second.last_sequence = hdr->sequence;
            on_pong(it->second, hp.timestamp_us, now_us);
        }
        break;
    }

    case Protocol::PacketType::KEYBOARD_EVENT:
    case Protocol::PacketType::MOUSE_MOVE:
    case Protocol::PacketType::MOUSE_BUTTON:
    case Protocol::PacketType::MOUSE_SCROLL: {
        // Accept input only from endpoints that actually completed the
        // HANDSHAKE. Without this any host on the network could inject
        // keystrokes/clicks into our session.
        {
            std::lock_guard<std::mutex> lock(m_client_mutex);
            if (m_clients.find(key) == m_clients.end()) break;
        }
        ClientInfo client;
        client.endpoint = from;
        client.last_sequence = hdr->sequence;
        if (m_input_callback) {
            m_input_callback(client, hdr->type, payload, payload_len);
        }
        break;
    }

    case Protocol::PacketType::KEYFRAME_REQ: {
        auto now = high_resolution_clock::now();
        uint64_t now_us = duration_cast<microseconds>(
            now.time_since_epoch()).count();
        if (now_us - m_last_keyframe_req_us > 1000000) {
            m_last_keyframe_req_us = now_us;
            if (m_keyframe_callback) m_keyframe_callback();
            request_sync();
        }
        break;
    }

    case Protocol::PacketType::FRAGMENT_RETRANSMIT_REQ: {
        handle_retransmit(payload, payload_len, from);
        break;
    }

    case Protocol::PacketType::DISCONNECT: {
        std::lock_guard<std::mutex> lock(m_client_mutex);
        auto it = m_clients.find(key);
        if (it != m_clients.end()) {
            m_clients.erase(it);
            if (m_client_count > 0) m_client_count--;
            printf("[UdpServer] Client disconnected from %s (total: %d)\n",
                   from.address().to_string().c_str(), m_client_count.load());
        }
        break;
    }

    default:
        break;
    }
}

void UdpServer::handle_retransmit(const uint8_t* payload, size_t payload_len,
                                  const udp::endpoint& from) {
    auto now = high_resolution_clock::now();
    uint64_t now_us = duration_cast<microseconds>(
        now.time_since_epoch()).count();
    if (now_us - m_last_retransmit_us < 20000) return;
    m_last_retransmit_us = now_us;

    if (payload_len == 0 || payload_len % 6 != 0) return;
    size_t n = std::min(payload_len / 6, (size_t)200);

    bool enc = false;
    {
        std::lock_guard<std::mutex> lock(m_client_mutex);
        auto it = m_clients.find(client_key(from));
        if (it != m_clients.end()) enc = it->second.encryption_enabled;
    }

    std::lock_guard<std::mutex> lock(m_history_mutex);
    size_t requeued = 0;
    for (size_t r = 0; r < n && requeued < 40; r++) {
        uint32_t fid;
        uint16_t fidx;
        memcpy(&fid, payload + r * 6, 4);
        memcpy(&fidx, payload + r * 6 + 4, 2);
        for (auto& h : m_packet_history) {
            if (h.frame_id == fid && h.fragment_index == fidx &&
                now_us - h.ts_us < 6000000) {
                if (h.data.size() >= sizeof(Protocol::PacketHeader)) {
                    std::vector<uint8_t> pkt = h.data;
                    if (enc) {
                        const Protocol::PacketHeader* hdr =
                            reinterpret_cast<const Protocol::PacketHeader*>(
                                h.data.data());
                        std::vector<uint8_t> sealed = crypto::seal(
                            h.data.data() + sizeof(Protocol::PacketHeader),
                            hdr->payload_length);
                        if (sealed.empty()) continue; // never leak plaintext
                        Protocol::PacketHeader nh = *hdr;
                        nh.magic = Protocol::MAGIC_ENCRYPTED;
                        nh.payload_length = (uint16_t)sealed.size();
                        pkt.assign(sizeof(nh) + sealed.size(), 0);
                        memcpy(pkt.data(), &nh, sizeof(nh));
                        memcpy(pkt.data() + sizeof(nh), sealed.data(),
                               sealed.size());
                    }
                    send_datagram(from, std::move(pkt));
                } else {
                    send_datagram(from, h.data);
                }
                requeued++;
                break;
            }
        }
    }
    if (requeued) {
        fprintf(stderr, "[UdpServer] retransmitted %zu fragments\n", requeued);
    }
}

void UdpServer::set_sync_prefix(const std::vector<uint8_t>& prefix) {
    std::lock_guard<std::mutex> lock(m_sync_mutex);
    m_sync_prefix = prefix;
}

bool UdpServer::has_sync_prefix() const {
    std::lock_guard<std::mutex> lock(m_sync_mutex);
    return !m_sync_prefix.empty();
}

void UdpServer::broadcast_frame(const uint8_t* data, size_t length,
                                uint32_t frame_id) {
    // Prepend the cached SPS/PPS prefix so the completed frame carries
    // in-band decoder headers; the stream also has real IDR keyframes every
    // gop_size frames so a joining client syncs in under 0.5s.
    std::vector<uint8_t> merged;
    {
        std::lock_guard<std::mutex> lock(m_sync_mutex);
        if (!m_sync_prefix.empty()) {
            merged.reserve(m_sync_prefix.size() + length);
            merged.insert(merged.end(), m_sync_prefix.begin(),
                          m_sync_prefix.end());
            merged.insert(merged.end(), data, data + length);
            if (m_sync_pending) {
                m_sync_pending = false;
                fprintf(stderr, "[UdpServer] SPS/PPS prefix armed\n");
            }
        }
    }

    const uint8_t* src = merged.empty() ? data : merged.data();
    size_t total = merged.empty() ? length : merged.size();

    std::vector<ClientInfo> targets;
    {
        std::lock_guard<std::mutex> lock(m_client_mutex);
        for (auto& [k, c] : m_clients) {
            if (c.connected) targets.push_back(c);
        }
    }
    if (targets.empty()) return;

    uint64_t now_us = duration_cast<microseconds>(
        high_resolution_clock::now().time_since_epoch()).count();
    size_t n_frags = (total + Protocol::MAX_PAYLOAD_SIZE - 1)
                     / Protocol::MAX_PAYLOAD_SIZE;
    if (n_frags > Protocol::MAX_TOTAL_FRAGMENTS) {
        // No client can reassemble a frame this large: sending it would
        // waste bandwidth and the client would drop it wholesale anyway.
        fprintf(stderr,
                "[UdpServer] frame %u too large: %zu fragments (%zu bytes), "
                "skipping (max %d)\n",
                frame_id, n_frags, total, Protocol::MAX_TOTAL_FRAGMENTS);
        return;
    }
    uint16_t total_frags = (uint16_t)n_frags;

    for (auto& client : targets) {
        size_t offset = 0;
        uint16_t frag_index = 0;
        while (offset < total) {
            size_t chunk = std::min((size_t)Protocol::MAX_PAYLOAD_SIZE,
                                    total - offset);

            Protocol::PacketHeader hdr = {};
            hdr.magic = Protocol::MAGIC;
            hdr.type = Protocol::PacketType::VIDEO_FRAME;
            hdr.sequence = m_sequence++;
            hdr.frame_id = frame_id;
            hdr.fragment_index = frag_index;
            hdr.total_fragments = total_frags;
            hdr.payload_length = (uint16_t)chunk;
            hdr.checksum = Protocol::compute_checksum(src + offset, chunk);

            // History stores the *cleartext* packet so retransmits can be
            // re-sealed per client; only the copy on the wire is encrypted.
            std::vector<uint8_t> packet(sizeof(hdr) + chunk);
            memcpy(packet.data(), &hdr, sizeof(hdr));
            memcpy(packet.data() + sizeof(hdr), src + offset, chunk);

            {
                std::lock_guard<std::mutex> lock(m_history_mutex);
                if (m_packet_history.size() >= HISTORY_CAP) {
                    m_packet_history.pop_front();
                }
                m_packet_history.push_back(
                    {client.endpoint, frame_id, frag_index, packet, now_us});
            }

            if (client.encryption_enabled) {
                std::vector<uint8_t> sealed =
                    crypto::seal(src + offset, chunk);
                if (sealed.empty()) {
                    fprintf(stderr,
                            "[UdpServer] seal failed frame=%u frag=%u\n",
                            frame_id, frag_index);
                    offset += chunk;
                    frag_index++;
                    continue;
                }
                hdr.magic = Protocol::MAGIC_ENCRYPTED;
                hdr.payload_length = (uint16_t)sealed.size();
                packet.assign(sizeof(hdr) + sealed.size(), 0);
                memcpy(packet.data(), &hdr, sizeof(hdr));
                memcpy(packet.data() + sizeof(hdr), sealed.data(),
                       sealed.size());
            }

            send_datagram(client.endpoint, std::move(packet));

            offset += chunk;
            frag_index++;
        }
    }

    // FEC: generate parity once for all clients at the highest requested
    // fec_level and send it only to clients that asked for it. Parity is
    // generated from the exact fragments sent above (same L for every block:
    // MAX_PAYLOAD_SIZE, runts zero-filled by encode_block).
    int max_fec = 0;
    for (auto& client : targets) max_fec = std::max(max_fec, client.fec_level);
    if (max_fec > 0) {
        int nb = (total_frags + Protocol::FEC_BLOCK_DATA - 1)
                 / Protocol::FEC_BLOCK_DATA;
        // Build a zero-padded fragment layout (each block a 16x1400 matrix,
        // runts zero-filled). Parity must be computed over exactly this grid;
        // a contiguous copy read at MAX_PAYLOAD_SIZE would read past the end
        // of the frame buffer for the runt fragment and desync from what
        // clients reconstruct.
        std::vector<uint8_t> padded(
            (size_t)nb * Protocol::FEC_BLOCK_DATA * Protocol::MAX_PAYLOAD_SIZE,
            0);
        for (size_t off = 0, c = 0; off < total; off += Protocol::MAX_PAYLOAD_SIZE,
                                                             c++) {
            size_t chunk = std::min((size_t)Protocol::MAX_PAYLOAD_SIZE,
                                    total - off);
            memcpy(padded.data() +
                       c * (size_t)Protocol::MAX_PAYLOAD_SIZE,
                   src + off, chunk);
        }
        for (int b = 0; b < nb; b++) {
            int kb = std::min(Protocol::FEC_BLOCK_DATA,
                              (int)total_frags - b * Protocol::FEC_BLOCK_DATA);
            std::vector<const uint8_t*> ptrs((size_t)kb);
            for (int c = 0; c < kb; c++) {
                ptrs[(size_t)c] =
                    padded.data() +
                    ((size_t)(b * Protocol::FEC_BLOCK_DATA + c)
                     * Protocol::MAX_PAYLOAD_SIZE);
            }
            std::vector<std::vector<uint8_t>> parity;
            Fec::encode_block(kb, max_fec, ptrs.data(), nullptr,
                              Protocol::MAX_PAYLOAD_SIZE, parity);

            for (int r = 0; r < max_fec; r++) {
                Protocol::FecPacketHeader fh = {};
                fh.magic = Protocol::MAGIC;
                fh.type = Protocol::PacketType::VIDEO_FEC_PARITY;
                fh.sequence = m_sequence++;
                fh.frame_id = frame_id;
                fh.block_index = (uint16_t)b;
                fh.block_data = (uint16_t)kb;
                fh.parity_index = (uint16_t)r;
                fh.parity_count = (uint16_t)max_fec;
                fh.payload_length = (uint16_t)parity[(size_t)r].size();
                fh.frame_data_length = (uint32_t)total;
                fh.checksum = Protocol::compute_checksum(
                    parity[(size_t)r].data(), parity[(size_t)r].size());

                std::vector<uint8_t> pkt(sizeof(fh) + parity[(size_t)r].size());
                memcpy(pkt.data(), &fh, sizeof(fh));
                memcpy(pkt.data() + sizeof(fh), parity[(size_t)r].data(),
                       parity[(size_t)r].size());

                for (auto& client : targets) {
                    if (client.fec_level > 0) {
                        if (client.encryption_enabled) {
                            std::vector<uint8_t> sealed = crypto::seal(
                                parity[(size_t)r].data(),
                                parity[(size_t)r].size());
                            if (sealed.empty()) {
                                fprintf(stderr,
                                        "[UdpServer] seal failed "
                                        "frame=%u parity=%d\n",
                                        frame_id, r);
                                continue;
                            }
                            Protocol::FecPacketHeader eh = fh;
                            eh.magic = Protocol::MAGIC_ENCRYPTED;
                            eh.payload_length = (uint16_t)sealed.size();
                            std::vector<uint8_t> epkt(sizeof(eh) + sealed.size());
                            memcpy(epkt.data(), &eh, sizeof(eh));
                            memcpy(epkt.data() + sizeof(eh), sealed.data(),
                                   sealed.size());
                            send_datagram(client.endpoint, std::move(epkt));
                        } else {
                            send_datagram(client.endpoint, pkt);
                        }
                    }
                }
            }
        }
    }
}

void UdpServer::broadcast_audio(const uint8_t* data, size_t length) {
    if (length == 0) return;

    std::vector<ClientInfo> targets;
    {
        std::lock_guard<std::mutex> lock(m_client_mutex);
        for (auto& [k, c] : m_clients) {
            if (c.connected && c.audio_enabled) targets.push_back(c);
        }
    }
    if (targets.empty()) return;

    for (auto& client : targets) {
        Protocol::PacketHeader hdr = {};
        hdr.magic = Protocol::MAGIC;
        hdr.type = Protocol::PacketType::AUDIO_FRAME;
        hdr.sequence = m_sequence++;
        hdr.payload_length = (uint16_t)length;
        hdr.checksum = Protocol::compute_checksum(data, length);

        std::vector<uint8_t> packet(sizeof(hdr) + length);
        memcpy(packet.data(), &hdr, sizeof(hdr));
        memcpy(packet.data() + sizeof(hdr), data, length);

        if (client.encryption_enabled) {
            std::vector<uint8_t> sealed = crypto::seal(data, length);
            if (sealed.empty()) {
                fprintf(stderr, "[UdpServer] seal failed (audio)\n");
                continue;
            }
            hdr.magic = Protocol::MAGIC_ENCRYPTED;
            hdr.payload_length = (uint16_t)sealed.size();
            packet.assign(sizeof(hdr) + sealed.size(), 0);
            memcpy(packet.data(), &hdr, sizeof(hdr));
            memcpy(packet.data() + sizeof(hdr), sealed.data(), sealed.size());
        }
        send_datagram(client.endpoint, std::move(packet));
    }
}

void UdpServer::send_datagram(const udp::endpoint& dest,
                              std::vector<uint8_t> data) {
    boost::system::error_code ec;
    {
        std::lock_guard<std::mutex> lock(m_send_mutex);
        m_socket->send_to(boost::asio::buffer(data.data(), data.size()), dest,
                          0, ec);
    }
    if (ec && m_running) {
        fprintf(stderr, "[UdpServer] send_to failed: %s\n", ec.message().c_str());
    }
}

// ---------------------------------------------------------------------------
// RTT analysis: the server pings every connected client every 500 ms with a
// HEARTBEAT_PING carrying its own timestamp; the client echoes it back as a
// HEARTBEAT_PONG and the round-trip time is logged as a delay analysis line.
// ---------------------------------------------------------------------------

void UdpServer::start_ping_timer() {
    m_ping_timer.expires_after(milliseconds(500));
    m_ping_timer.async_wait([this](const boost::system::error_code& ec) {
        if (ec) return; // timer cancelled / io stopped
        send_pings();
        start_ping_timer();
    });
}

void UdpServer::send_pings() {
    if (!m_running) return;

    uint64_t now_us = duration_cast<microseconds>(
        high_resolution_clock::now().time_since_epoch()).count();

    std::vector<ClientInfo> targets;
    {
        // Sweep stale clients at the same 500ms cadence as the pings. A
        // client that stops sending HEARTBEAT/PING/PONG for kClientTimeoutUs
        // is gone (network drop, app killed) and would otherwise accumulate
        // forever and keep the stream alive to dead endpoints.
        constexpr uint64_t kClientTimeoutUs = 8ULL * 1000 * 1000;
        std::lock_guard<std::mutex> lock(m_client_mutex);
        for (auto it = m_clients.begin(); it != m_clients.end();) {
            if (now_us - it->second.last_heartbeat_us > kClientTimeoutUs) {
                printf("[UdpServer] Client %s timed out (total: %zu)\n",
                       it->second.endpoint.address().to_string().c_str(),
                       m_clients.size() - 1);
                if (m_client_count > 0) m_client_count--;
                it = m_clients.erase(it);
            } else {
                if (it->second.connected) targets.push_back(it->second);
                ++it;
            }
        }
    }
    if (targets.empty()) return;

    for (auto& client : targets) {
        Protocol::HeartbeatPayload hp = {};
        hp.timestamp_us = now_us;

        Protocol::PacketHeader hdr = {};
        hdr.magic = Protocol::MAGIC;
        hdr.type = Protocol::PacketType::HEARTBEAT_PING;
        hdr.sequence = m_sequence++;
        hdr.fragment_index = 0;
        hdr.total_fragments = 1;
        hdr.payload_length = (uint16_t)sizeof(Protocol::HeartbeatPayload);
        hdr.checksum = Protocol::compute_checksum(
            (const uint8_t*)&hp, sizeof(hp));

        std::vector<uint8_t> msg(sizeof(hdr) + sizeof(hp));
        memcpy(msg.data(), &hdr, sizeof(hdr));
        memcpy(msg.data() + sizeof(hdr), &hp, sizeof(hp));
        send_datagram(client.endpoint, std::move(msg));

        std::lock_guard<std::mutex> lock(m_client_mutex);
        auto it = m_clients.find(client_key(client.endpoint));
        if (it == m_clients.end()) continue;
        auto& out = it->second.outstanding_ping_us;
        out.push_back(now_us);
        while (out.size() > 1 && now_us - out.front() > 3 * 1000 * 1000) {
            out.pop_front();
        }
        if (out.size() > 16) out.pop_front();
    }
}

void UdpServer::on_pong(ClientInfo& client, uint64_t pong_timestamp_us,
                        uint64_t now_us) {
    constexpr uint64_t kMaxRttUs = 3 * 1000 * 1000;
    constexpr size_t kMaxSamples = 128;

    auto& out = client.outstanding_ping_us;
    while (!out.empty() && now_us - out.front() > kMaxRttUs) out.pop_front();

    // Match the exact ping this PONG echoes. The previous heuristic ("any
    // outstanding ping younger than kMaxRttUs") always matched the OLDEST
    // outstanding ping, inflating every sample by the 500ms ping spacing and
    // reporting several-thousand-ms "RTT" on healthy links.
    auto it = std::find(out.begin(), out.end(), pong_timestamp_us);
    if (it == out.end()) return; // stale / duplicated PONG, ignore
    uint64_t sent_us = *it;
    out.erase(it);
    uint64_t rtt_us = now_us - sent_us;
    if (rtt_us == 0 || rtt_us >= kMaxRttUs) return;

    auto& samples = client.rtt_samples_us;
    samples.push_back(rtt_us);
    if (samples.size() > kMaxSamples) samples.pop_front();

    uint64_t sum = 0, mn = rtt_us, mx = rtt_us;
    for (uint64_t s : samples) {
        sum += s;
        mn = std::min(mn, s);
        mx = std::max(mx, s);
    }

    printf("[UdpServer] RTT from %s = %.2f ms (avg %.2f ms, min %.2f ms, "
           "max %.2f ms, samples=%zu)\n",
           client.endpoint.address().to_string().c_str(),
           rtt_us / 1000.0, (double)sum / samples.size() / 1000.0,
           mn / 1000.0, mx / 1000.0, samples.size());
}