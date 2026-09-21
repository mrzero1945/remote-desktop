/**
 * desktop-viewer: a minimal UDP desktop-viewer client used to exercise the
 * Boost.Asio UDP server in ../server.
 *
 *  - connects over UDP, sends the protocol HANDSHAKE
 *  - reassembles fragmented VIDEO_FRAME packets (PacketHeader)
 *  - decodes H.264 with libavcodec and renders via SDL2
 *  - forwards mouse/keyboard input back to the server
 */

#include "protocol.h"
#include "fec.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <algorithm>
#include <string>
#include <vector>
#include <array>
#include <optional>
#include <deque>
#include <chrono>
#include <thread>
#include <future>
#include <functional>
#include <unordered_map>

#include <boost/asio.hpp>
#include <SDL.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

using boost::asio::ip::udp;
using namespace std::chrono;

// ---------------------------------------------------------------------------
// H.264 decoder (libavcodec)
// ---------------------------------------------------------------------------
class H264Decoder {
public:
    H264Decoder() = default;
    ~H264Decoder() { shutdown(); }

    bool init() {
        const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (!codec) {
            fprintf(stderr, "[viewer] H.264 decoder not found\n");
            return false;
        }
        m_ctx = avcodec_alloc_context3(codec);
        if (!m_ctx) return false;
        m_ctx->thread_count = 1;
        m_ctx->flags2 |= AV_CODEC_FLAG2_FAST;
        if (avcodec_open2(m_ctx, codec, nullptr) < 0) {
            fprintf(stderr, "[viewer] Cannot open H.264 decoder\n");
            return false;
        }
        m_pkt = av_packet_alloc();
        m_frame = av_frame_alloc();
        return true;
    }

    void shutdown() {
        if (m_ctx) avcodec_free_context(&m_ctx);
        if (m_pkt) av_packet_free(&m_pkt);
        if (m_frame) av_frame_free(&m_frame);
        if (m_sws) sws_freeContext(m_sws);
        if (m_rgb) av_free(m_rgb);
        m_sws = nullptr;
        m_rgb = nullptr;
        m_rgb_w = m_rgb_h = 0;
    }

    /** Feed one complete access unit. Returns true when a decoded frame is
     *  produced; RGB24 output is copied to out_buf/out_stride. */
    bool decode(const uint8_t* data, size_t len,
                uint8_t*& out_buf, int& out_w, int& out_h, int& out_stride) {
        if (!m_ctx || len == 0) return false;

        av_packet_unref(m_pkt);
        m_pkt->data = (uint8_t*)data;
        m_pkt->size = (int)len;

        int ret = avcodec_send_packet(m_ctx, m_pkt);
        if (ret < 0) return false;

        ret = avcodec_receive_frame(m_ctx, m_frame);
        if (ret < 0) return false;

        if (m_frame->width <= 0 || m_frame->height <= 0) return false;

        int w = m_frame->width;
        int h = m_frame->height;
        if (w != m_rgb_w || h != m_rgb_h) {
            if (m_sws) sws_freeContext(m_sws);
            if (m_rgb) av_free(m_rgb);
            m_rgb = nullptr;
            m_sws = sws_getContext(w, h, AV_PIX_FMT_YUV420P,
                                   w, h, AV_PIX_FMT_RGB24,
                                   SWS_BILINEAR, nullptr, nullptr, nullptr);
            if (!m_sws) return false;
            m_rgb_stride = av_image_get_linesize(AV_PIX_FMT_RGB24, w, 0);
            m_rgb = (uint8_t*)av_malloc((size_t)m_rgb_stride * h);
            if (!m_rgb) return false;
            m_rgb_w = w;
            m_rgb_h = h;

            // The server encodes with BT.709 colour metadata; tell swscale.
            const int* src_coeffs = sws_getCoefficients(
                m_frame->colorspace == AVCOL_SPC_BT709
                    ? SWS_CS_ITU709 : SWS_CS_ITU601);
            const int* dst_coeffs = sws_getCoefficients(SWS_CS_DEFAULT);
            int src_range = m_frame->color_range == AVCOL_RANGE_JPEG
                                ? 1 : 0;
            sws_setColorspaceDetails(m_sws, src_coeffs, src_range,
                                     dst_coeffs, 0, 0, 1 << 16, 1 << 16);
        }

        uint8_t* dst[4] = { m_rgb, nullptr, nullptr, nullptr };
        int dst_stride[4] = { m_rgb_stride, 0, 0, 0 };
        sws_scale(m_sws, m_frame->data, m_frame->linesize, 0, h,
                  dst, dst_stride);

        out_buf = m_rgb;
        out_w = w;
        out_h = h;
        out_stride = m_rgb_stride;
        return true;
    }

private:
    AVCodecContext* m_ctx = nullptr;
    AVPacket* m_pkt = nullptr;
    AVFrame* m_frame = nullptr;
    SwsContext* m_sws = nullptr;
    uint8_t* m_rgb = nullptr;
    int m_rgb_w = 0;
    int m_rgb_h = 0;
    int m_rgb_stride = 0;
};

// ---------------------------------------------------------------------------
// UDP client
// ---------------------------------------------------------------------------
class UdpClient {
public:
    bool open(const std::string& host, int port) {
        boost::system::error_code resolve_ec;
        auto results = m_resolver.resolve(
            host, std::to_string(port), udp::resolver::numeric_service,
            resolve_ec);
        if (resolve_ec) {
            fprintf(stderr, "[viewer] cannot resolve %s: %s\n",
                    host.c_str(), resolve_ec.message().c_str());
            return false;
        }
        m_endpoint = *results;

        boost::system::error_code ec;
        m_socket = std::make_unique<udp::socket>(m_io);
        m_socket->open(m_endpoint.protocol(), ec);
        if (ec) return false;
        boost::asio::socket_base::receive_buffer_size rcvbuf(4 * 1024 * 1024);
        m_socket->set_option(rcvbuf);
        // asio-level non_blocking(true) makes synchronous receive_from return
        // would_block immediately when the socket is drained, so the main
        // loop can run its stall timers and auto-exit. native_non_blocking
        // alone only sets O_NONBLOCK and boost still waits in its reactor.
        boost::system::error_code nb_ec;
        m_socket->non_blocking(true, nb_ec);
        boost::system::error_code nn_ec;
        m_socket->native_non_blocking(true, nn_ec);
        return true;
    }

    void close() {
        if (m_socket) {
            boost::system::error_code ec;
            m_socket->close(ec);
        }
    }

    void send(const uint8_t* data, size_t len) {
        if (!m_socket) return;
        boost::system::error_code ec;
        m_socket->send_to(boost::asio::buffer(data, len), m_endpoint, 0, ec);
    }

    /** Receive one datagram. Returns false on EWOULDBLOCK/error. */
    bool recv(std::vector<uint8_t>& out, size_t max_len) {
        if (!m_socket) return false;
        out.resize(max_len);
        boost::system::error_code ec;
        udp::endpoint sender;
        size_t n = m_socket->receive_from(boost::asio::buffer(out), sender, 0, ec);
        if (ec) {
            if (ec == boost::asio::error::would_block) return false;
            if (m_last_err != ec) {
                fprintf(stderr, "[viewer] receive_from: %s\n",
                        ec.message().c_str());
                m_last_err = ec;
            }
            return false;
        }
        out.resize(n);
        return true;
    }

    const udp::endpoint& endpoint() const { return m_endpoint; }

private:
    boost::asio::io_context m_io;
    udp::resolver m_resolver{m_io};
    std::unique_ptr<udp::socket> m_socket;
    udp::endpoint m_endpoint;
    boost::system::error_code m_last_err;
};

// ---------------------------------------------------------------------------
// Packet builders (mirror ../server/src/protocol.h on the wire)
// ---------------------------------------------------------------------------
static uint32_t s_seq = 0;

static std::vector<uint8_t> build_packet(Protocol::PacketType type,
                                         const void* payload,
                                         size_t payload_len) {
    Protocol::PacketHeader hdr = {};
    hdr.magic = Protocol::MAGIC;
    hdr.type = type;
    hdr.sequence = s_seq++;
    hdr.frame_id = 0;
    hdr.fragment_index = 0;
    hdr.total_fragments = 1;
    hdr.payload_length = (uint16_t)payload_len;
    hdr.checksum = Protocol::compute_checksum(
        (const uint8_t*)payload, payload_len);

    std::vector<uint8_t> pkt(sizeof(hdr) + payload_len);
    memcpy(pkt.data(), &hdr, sizeof(hdr));
    if (payload_len) memcpy(pkt.data() + sizeof(hdr), payload, payload_len);
    return pkt;
}

static void send_input_packet(UdpClient& cl, Protocol::PacketType type,
                              const void* payload, size_t len) {
    auto pkt = build_packet(type, payload, len);
    cl.send(pkt.data(), pkt.size());
}

static void send_handshake(UdpClient& cl) {
    Protocol::HandshakePayload hs = {};
    hs.screen_width = 0;   // unknown on the client
    hs.screen_height = 0;
    hs.fps = 0;
    hs.encoder_id = 0;
    hs.fec_level = Protocol::FEC_DEFAULT_LEVEL;
    // leave session_key zeroed
    auto pkt = build_packet(Protocol::PacketType::HANDSHAKE, &hs, sizeof(hs));
    cl.send(pkt.data(), pkt.size());
    printf("[viewer] handshake sent to %s (fec_level=%d)\n",
           cl.endpoint().address().to_string().c_str(), hs.fec_level);
}

// ---------------------------------------------------------------------------
// Input mapping (SDL -> X11 keysyms / buttons)
// ---------------------------------------------------------------------------
static uint32_t sdl_to_x11(unsigned int sdl_key) {
    // SDL printable codes match ASCII for letters/digits/punct (0x20..0x7e),
    // which for Latin-1 is the same value as the X11 keysym.
    switch (sdl_key) {
    case SDLK_BACKSPACE: return 0xFF08; // XK_BackSpace
    case SDLK_TAB:       return 0xFF09; // XK_Tab
    case SDLK_RETURN:    return 0xFF0D; // XK_Return
    case SDLK_KP_ENTER:  return 0xFF8D; // XK_KP_Enter
    case SDLK_ESCAPE:    return 0xFF1B; // XK_Escape
    case SDLK_DELETE:    return 0xFFFF; // XK_Delete
    case SDLK_HOME:      return 0xFF50; // XK_Home
    case SDLK_LEFT:      return 0xFF51; // XK_Left
    case SDLK_UP:        return 0xFF52; // XK_Up
    case SDLK_RIGHT:     return 0xFF53; // XK_Right
    case SDLK_DOWN:      return 0xFF54; // XK_Down
    case SDLK_PAGEUP:    return 0xFF55; // XK_Page_Up
    case SDLK_PAGEDOWN:  return 0xFF56; // XK_Page_Down
    case SDLK_END:       return 0xFF57; // XK_End
    default:
        if (sdl_key >= 0x20 && sdl_key <= 0x7e) return sdl_key;
        return 0; // unmapped
    }
}

// ---------------------------------------------------------------------------
// Frame reassembly with FEC-first, ARQ-fallback recovery
// ---------------------------------------------------------------------------
struct FecBlock {
    int K = 0;                            // block_data (data fragments in block)
    int P = 0;                            // parity_count (server generator rows)
    std::vector<std::vector<uint8_t>> rows; // rows[j] = parity for parity_index j
};

struct FrameState {
    uint32_t id = 0;
    int total_fragments = 0;
    uint32_t frame_data_length = 0;       // total frame bytes (from parity header)
    bool frame_data_length_known = false;
    std::vector<std::vector<uint8_t>> fragments;
    std::vector<uint8_t> have;            // 0/1 per fragment
    int received = 0;
    std::unordered_map<int, FecBlock> blocks; // block_index -> FEC blocks
    // ARQ bookkeeping
    uint64_t last_activity_us = 0;
    uint64_t arq_req_us = 0;
    int arq_rounds = 0;
};

class FrameReassembler {
public:
    /** Callback to emit a FRAGMENT_RETRANSMIT_REQ for the given frame. */
    using ArqCb = std::function<void(uint32_t frame_id,
                                     const std::vector<uint16_t>& missing)>;
    void set_arq_cb(ArqCb cb) { m_arq = std::move(cb); }
    uint64_t fec_recovered() const { return m_fec_recovered; }

    void add(const Protocol::PacketHeader& hdr, const uint8_t* payload,
             size_t payload_len, uint64_t now_us,
             std::vector<std::vector<uint8_t>>& out) {
        if (payload_len != hdr.payload_length) return;

        uint32_t fid = hdr.frame_id;
        uint16_t fi = hdr.fragment_index;
        uint16_t total = hdr.total_fragments;
        if (total == 0 || fi >= total ||
            total > Protocol::MAX_TOTAL_FRAGMENTS) return;

        // Route: current frame is "m_cur"; anything with a strictly newer id
        // becomes the new current frame and finalizes m_cur. Old retransmits
        // and parity feed the pending slots (no re-creation of old ids when
        // m_cur is empty, tracked by m_watermark).
        if (m_cur.total_fragments > 0 && fid == m_cur.id) {
            add_fragment(m_cur, fi, total, payload, payload_len, now_us);
            if (frame_complete(m_cur)) {
                emit_frame(m_cur, out);
            } else {
                try_recover(m_cur, out);
            }
        } else if (fid > m_watermark || !m_started) {
            finalize(m_cur, now_us, out);
            FrameState next;
            next.id = fid;
            add_fragment(next, fi, total, payload, payload_len, now_us);
            if (frame_complete(next)) emit_frame(next, out);
            m_cur = std::move(next);
            m_watermark = fid;
            m_started = true;
        } else if (!m_pending.empty()) {
            // Feed ARQ retransmits to any pending frame; the deque is small
            // and bounded, so a full scan is cheap.
            for (auto it = m_pending.begin(); it != m_pending.end(); ++it) {
                FrameState& p = *it;
                if (p.id == fid && p.total_fragments > 0) {
                    add_fragment(p, fi, total, payload, payload_len, now_us);
                    bool done = false;
                    if (frame_complete(p)) {
                        emit_frame(p, out);
                        done = true;
                    } else {
                        done = try_recover(p, out);
                    }
                    if (done || frame_complete(p)) {
                        m_pending.erase(it);
                    }
                    break;
                }
            }
        }
        // else: stale or unknown older frame, ignore
    }

    void add_parity(const Protocol::FecPacketHeader& fh, const uint8_t* payload,
                    size_t payload_len, uint64_t now_us,
                    std::vector<std::vector<uint8_t>>& out) {
        if (fh.frame_id != m_cur.id) {
            // Could be parity for a pending frame awaiting retransmits.
            for (auto it = m_pending.begin(); it != m_pending.end(); ++it) {
                FrameState& p = *it;
                if (p.id == fh.frame_id && p.total_fragments > 0) {
                    apply_parity(p, fh, payload, payload_len, now_us, out);
                    if (frame_complete(p)) {
                        emit_frame(p, out);
                        m_pending.erase(it);
                    }
                    break;
                }
            }
            return;
        }
        if (m_cur.total_fragments == 0) return;

        // re-check header against payload
        if (payload_len != fh.payload_length ||
            (size_t)fh.payload_length > Protocol::MAX_PAYLOAD_SIZE) return;

        apply_parity(m_cur, fh, payload, payload_len, now_us, out);
    }

    void apply_parity(FrameState& f, const Protocol::FecPacketHeader& fh,
                      const uint8_t* payload, size_t payload_len, uint64_t now_us,
                      std::vector<std::vector<uint8_t>>& out) {
        int b = fh.block_index;
        int K = fh.block_data;
        int P = fh.parity_count;
        int pi = fh.parity_index;
        if (K <= 0 || K > Protocol::FEC_BLOCK_DATA || P <= 0 ||
            P > Protocol::FEC_MAX_PARITY || pi >= P) return;

        FecBlock& blk = f.blocks[b];
        blk.K = K;
        blk.P = P;
        if ((int)blk.rows.size() < pi + 1) blk.rows.resize(pi + 1);
        blk.rows[pi] = std::vector<uint8_t>(payload, payload + payload_len);
        f.frame_data_length = fh.frame_data_length;
        f.frame_data_length_known = true;
        f.last_activity_us = now_us;

        try_recover(f, out);
    }

    /** Periodic maintenance: time out the tail frame into ARQ, re-request
     *  missing fragments, give up on fallback after several rounds. */
    void poll(uint64_t now_us, std::vector<std::vector<uint8_t>>& out) {
        // If the current frame stalls (no newer frame arriving), move it to
        // the fallback slot and ask for retransmits rather than waiting on a
        // next frame to finalize it.
        if (m_cur.total_fragments > 0 && !frame_complete(m_cur) &&
            now_us - m_cur.last_activity_us > 150000) {
            finalize(m_cur, now_us, out);
            m_cur = FrameState{};
        }

        // Re-request + give up for pending frames.
        for (auto it = m_pending.begin(); it != m_pending.end();) {
            FrameState& p = *it;
            if (frame_complete(p)) {
                emit_frame(p, out);
                it = m_pending.erase(it);
                continue;
            }
            if (now_us - p.arq_req_us > 150000 && p.arq_rounds < kMaxArqRounds) {
                send_arq(p, now_us);
            }
            if (now_us - p.arq_req_us > 2000000) {
                fprintf(stderr,
                        "[viewer] giving up on frame %u (%d missing of %d)\n",
                        p.id, p.total_fragments - p.received,
                        p.total_fragments);
                it = m_pending.erase(it);
                continue;
            }
            ++it;
        }
    }

    bool has_pending() const {
        return (m_cur.total_fragments > 0 && !frame_complete(m_cur)) ||
               !m_pending.empty();
    }

    void reset() {
        m_cur = FrameState{};
        m_pending.clear();
    }

private:
    static constexpr int kMaxPending = 8;
    static constexpr int kMaxArqRounds = 6;
    FrameState m_cur;
    std::deque<FrameState> m_pending;
    ArqCb m_arq;
    uint64_t m_fec_recovered = 0;
    uint32_t m_watermark = 0;
    bool m_started = false;

    static bool frame_complete(const FrameState& f) {
        return f.total_fragments > 0 && f.received == f.total_fragments;
    }

    void add_fragment(FrameState& f, uint16_t fi, uint16_t total,
                      const uint8_t* payload, size_t payload_len,
                      uint64_t now_us) {
        if (f.total_fragments != (int)total) {
            f.total_fragments = total;
            f.fragments.assign(total, {});
            f.have.assign(total, 0);
            f.received = 0;
        }
        if (f.have[fi]) return; // duplicate
        f.fragments[fi] = std::vector<uint8_t>(payload, payload + payload_len);
        f.have[fi] = 1;
        f.received++;
        f.last_activity_us = now_us;
    }

    /** FEC recovery of every block that has at least one parity row. A block
     *  is recoverable when (present data + present parity) >= K. Returns true
     *  when the frame became complete (and was emitted into out). */
    bool try_recover(FrameState& f, std::vector<std::vector<uint8_t>>& out) {
        if (frame_complete(f)) return false;
        for (auto& [b, blk] : f.blocks) {
            if (blk.K <= 0 || blk.rows.empty()) continue;
            int base = b * Protocol::FEC_BLOCK_DATA;
            if (base + blk.K > f.total_fragments) continue;

            // extended statuses
            int present = 0;
            for (int c = 0; c < blk.K; c++) {
                if (f.have[base + c]) present++;
            }
            if (present == blk.K || (present + (int)blk.rows.size()) < blk.K)
                continue;

            std::vector<int> rows;
            std::vector<std::vector<uint8_t>> recv;
            int L = (int)blk.rows[0].size();
            for (int c = 0; c < blk.K; c++) {
                if (!f.have[base + c]) continue;
                std::vector<uint8_t> sym(L, 0);
                auto& frag = f.fragments[base + c];
                int n = std::min((int)frag.size(), L);
                memcpy(sym.data(), frag.data(), n);
                rows.push_back(c);
                recv.push_back(std::move(sym));
            }
            for (int j = 0; j < (int)blk.rows.size(); j++) {
                rows.push_back(blk.K + j);
                recv.push_back(blk.rows[j]);
            }

            std::vector<std::vector<uint8_t>> recovered;
            if (!Fec::recover_block(blk.K, blk.P, rows, recv, (size_t)L,
                                    recovered)) {
                continue;
            }
            for (int c = 0; c < blk.K; c++) {
                if (f.have[base + c]) continue;
                f.fragments[base + c] = recovered[(size_t)c];
                f.have[base + c] = 1;
                f.received++;
                f.last_activity_us = now_us_now();
            }
            m_fec_recovered++; // block recovered via parity
        }
        if (frame_complete(f)) {
            emit_frame(f, out);
            return true;
        }
        return false;
    }

    void finalize(FrameState& f, uint64_t now_us,
                  std::vector<std::vector<uint8_t>>& out) {
        if (f.total_fragments == 0) return;
        if (frame_complete(f)) return;
        // Try FEC one last time, then ship an ARQ request.
        if (f.blocks.empty() == false) {
            if (try_recover(f, out)) return;
        }
        if (frame_complete(f)) return;
        send_arq(f, now_us);
        // Preserve the frame for multi-slot ARQ fallback.
        if (m_pending.size() < kMaxPending) m_pending.push_back(f);
    }

    void send_arq(FrameState& f, uint64_t now_us) {
        std::vector<uint16_t> missing;
        missing.reserve(f.total_fragments - f.received);
        for (int i = 0; i < f.total_fragments; i++) {
            if (!f.have[(size_t)i]) missing.push_back((uint16_t)i);
        }
        f.arq_req_us = now_us;
        f.arq_rounds++;
        if (m_arq && !missing.empty()) m_arq(f.id, missing);
    }

    void emit_frame(FrameState& f, std::vector<std::vector<uint8_t>>& out) {
        if (!frame_complete(f)) return;
        std::vector<uint8_t> unit;
        unit.reserve((size_t)f.total_fragments * Protocol::MAX_PAYLOAD_SIZE);

        int frag_max = f.total_fragments - 1;
        uint32_t last_true = 0;
        if (f.frame_data_length_known) {
            uint32_t prefix = (uint32_t)frag_max * Protocol::MAX_PAYLOAD_SIZE;
            last_true = f.frame_data_length > prefix ? f.frame_data_length - prefix : 0;
        }

        for (int i = 0; i < f.total_fragments; i++) {
            auto& frag = f.fragments[(size_t)i];
            size_t n = frag.size();
            if (f.frame_data_length_known && i == frag_max &&
                last_true < n) n = last_true;
            unit.insert(unit.end(), frag.begin(), frag.begin() + n);
        }
        out.push_back(std::move(unit));
        f = FrameState{};
    }

    static uint64_t now_us_now() {
        return (uint64_t)duration_cast<microseconds>(
            high_resolution_clock::now().time_since_epoch()).count();
    }
};

// ---------------------------------------------------------------------------
// Round-trip latency (RTT) measured off timestamped HEARTBEAT echoes
// ---------------------------------------------------------------------------
class RttTracker {
public:
    /** Remember a ping we just sent, so its echo can be matched later. */
    void send(uint64_t ts_us) {
        m_sent.push_back(ts_us);
        if (m_sent.size() > 16) m_sent.pop_front();
    }

    /** Match an echoed heartbeat timestamp and record the RTT sample.
     *  Returns true when a valid sample was added (i.e. overlay changed). */
    bool reply(uint64_t ts_us, uint64_t now_us) {
        // Drop sends that are too old to still be in flight.
        while (!m_sent.empty() &&
               now_us > m_sent.front() && now_us - m_sent.front() >= kMaxSweepUs) {
            m_sent.pop_front();
        }
        // Match the exact timestamp this echo carries. A loose "any younger
        // than kMaxSweepUs" match always picked the oldest outstanding ping
        // and inflated RTT by the 500ms ping interval.
        auto it = std::find(m_sent.begin(), m_sent.end(), ts_us);
        if (it == m_sent.end()) return false; // stale / duplicate echo
        uint64_t sent_us = *it;
        m_sent.erase(it);
        uint64_t rtt_us = now_us - sent_us;
        if (rtt_us == 0 || rtt_us >= kMaxSweepUs) return false;
        m_samples.push_back(rtt_us);
        if (m_samples.size() > kMaxSamples) m_samples.pop_front();
        return true;
    }

    bool has() const { return !m_samples.empty(); }

    /** Average latency in milliseconds (1 decimal of precision). */
    double avg_ms() const {
        if (m_samples.empty()) return 0.0;
        uint64_t sum = 0;
        for (uint64_t s : m_samples) sum += s;
        return (double)sum / (double)m_samples.size() / 1000.0;
    }

private:
    static constexpr uint64_t kMaxSweepUs = 3 * 1000 * 1000;
    static constexpr size_t kMaxSamples = 8;
    std::deque<uint64_t> m_sent;     // outstanding ping timestamps
    std::deque<uint64_t> m_samples;  // recent RTT samples in microseconds
};

// ---------------------------------------------------------------------------
// Minimal 5x7 bitmap-font overlay so the viewer works without SDL_ttf
// ---------------------------------------------------------------------------
static const uint8_t* glyph5x7(char c) {
    // Standard 5x7 font, printable ASCII 0x20..0x5A, one row = 5 bits.
    static const uint8_t font[59][7] = {
        {0,0,0,0,0,0,0},                        // ' '
        {0x04,0x04,0x04,0x04,0x04,0x00,0x04},   // '!'
        {0x0A,0x0A,0x0A,0,0,0,0},               // '"'
        {0x0A,0x0A,0x1F,0x0A,0x1F,0x0A,0x0A},   // '#'
        {0x04,0x0E,0x15,0x0E,0x14,0x0E,0x04},   // '$'
        {0x00,0x19,0x0A,0x04,0x0A,0x13,0x00},   // '%'
        {0x0C,0x12,0x14,0x08,0x15,0x12,0x0D},   // '&'
        {0x0C,0x04,0x08,0,0,0,0},               // '\''
        {0x02,0x04,0x08,0x08,0x08,0x04,0x02},   // '('
        {0x08,0x04,0x02,0x02,0x02,0x04,0x08},   // ')'
        {0x00,0x04,0x15,0x0E,0x15,0x04,0x00},   // '*'
        {0x00,0x04,0x04,0x1F,0x04,0x04,0x00},   // '+'
        {0,0,0,0,0x0C,0x04,0x08},               // ','
        {0x00,0x00,0x00,0x1F,0x00,0x00,0x00},   // '-'
        {0,0,0,0,0,0x0C,0x0C},                  // '.'
        {0x01,0x02,0x02,0x04,0x08,0x08,0x10},   // '/'
        {0x0E,0x11,0x13,0x15,0x19,0x11,0x0E},   // '0'
        {0x04,0x0C,0x04,0x04,0x04,0x04,0x0E},   // '1'
        {0x0E,0x11,0x01,0x02,0x04,0x08,0x1F},   // '2'
        {0x1F,0x02,0x04,0x02,0x01,0x11,0x0E},   // '3'
        {0x02,0x06,0x0A,0x12,0x1F,0x02,0x02},   // '4'
        {0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E},   // '5'
        {0x06,0x08,0x10,0x1E,0x11,0x11,0x0E},   // '6'
        {0x1F,0x01,0x02,0x04,0x08,0x08,0x08},   // '7'
        {0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E},   // '8'
        {0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C},   // '9'
        {0x00,0x0C,0x0C,0,0x0C,0x0C,0},         // ':'
        {0x00,0x0C,0x0C,0,0x0C,0x04,0x08},      // ';'
        {0x02,0x04,0x08,0x10,0x08,0x04,0x02},   // '<'
        {0,0,0x1F,0,0x1F,0,0},                  // '='
        {0x08,0x04,0x02,0x01,0x02,0x04,0x08},   // '>'
        {0x0E,0x11,0x01,0x02,0x04,0,0x04},      // '?'
        {0x0E,0x11,0x01,0x0D,0x15,0x15,0x0E},   // '@'
        {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11},   // 'A'
        {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E},   // 'B'
        {0x0E,0x11,0x10,0x10,0x10,0x11,0x0E},   // 'C'
        {0x1C,0x12,0x11,0x11,0x11,0x12,0x1C},   // 'D'
        {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F},   // 'E'
        {0x1F,0x10,0x10,0x1E,0x10,0x10,0x10},   // 'F'
        {0x0E,0x11,0x10,0x17,0x11,0x11,0x0F},   // 'G'
        {0x11,0x11,0x11,0x1F,0x11,0x11,0x11},   // 'H'
        {0x0E,0x04,0x04,0x04,0x04,0x04,0x0E},   // 'I'
        {0x07,0x02,0x02,0x02,0x02,0x12,0x0C},   // 'J'
        {0x11,0x12,0x14,0x18,0x14,0x12,0x11},   // 'K'
        {0x10,0x10,0x10,0x10,0x10,0x10,0x1F},   // 'L'
        {0x11,0x1B,0x15,0x15,0x11,0x11,0x11},   // 'M'
        {0x11,0x19,0x15,0x13,0x11,0x11,0x11},   // 'N'
        {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E},   // 'O'
        {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10},   // 'P'
        {0x0E,0x11,0x11,0x11,0x15,0x12,0x0D},   // 'Q'
        {0x1E,0x11,0x11,0x1E,0x14,0x12,0x11},   // 'R'
        {0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E},   // 'S'
        {0x1F,0x04,0x04,0x04,0x04,0x04,0x04},   // 'T'
        {0x11,0x11,0x11,0x11,0x11,0x11,0x0E},   // 'U'
        {0x11,0x11,0x11,0x11,0x11,0x0A,0x04},   // 'V'
        {0x11,0x11,0x11,0x15,0x15,0x1B,0x11},   // 'W'
        {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11},   // 'X'
        {0x11,0x11,0x0A,0x04,0x04,0x04,0x04},   // 'Y'
        {0x1F,0x01,0x02,0x04,0x08,0x10,0x1F},   // 'Z'
    };
    if (c < ' ' || c > 'Z') return nullptr;
    return font[c - ' '];
}

/** Render a short text string to a new ARGB8888 texture with a translucent
 *  black backing; caller owns the texture. */
static SDL_Texture* make_overlay_texture(SDL_Renderer* ren, const std::string& text,
                                         const uint8_t rgb[3],
                                         int& out_w, int& out_h) {
    constexpr int glyph_w = 5, glyph_h = 7, glyph_gap = 1, pad = 3;
    int w = pad * 2 + (int)text.size() * (glyph_w + glyph_gap);
    int h = pad * 2 + glyph_h;
    out_w = w;
    out_h = h;

    SDL_Surface* surf = SDL_CreateRGBSurfaceWithFormat(
        0, w, h, 32, SDL_PIXELFORMAT_ARGB8888);
    if (!surf) return nullptr;

    SDL_Rect bg{0, 0, w, h};
    SDL_FillRect(surf, &bg, SDL_MapRGBA(surf->format, 0, 0, 0, 140));

    Uint32 px = SDL_MapRGBA(surf->format, rgb[0], rgb[1], rgb[2], 255);
    int x = pad;
    for (char c : text) {
        const uint8_t* g = glyph5x7(c);
        if (g) {
            for (int r = 0; r < glyph_h; r++) {
                for (int col = 0; col < glyph_w; col++) {
                    if (g[r] & (0x10 >> col)) {
                        SDL_Rect p{ x + col, pad + r, 1, 1 };
                        SDL_FillRect(surf, &p, px);
                    }
                }
            }
        }
        x += glyph_w + glyph_gap;
    }

    SDL_Texture* tex = SDL_CreateTextureFromSurface(ren, surf);
    SDL_FreeSurface(surf);
    if (tex) SDL_SetTextureBlendMode(tex, SDL_BLENDMODE_BLEND);
    return tex;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
int main(int argc, char* argv[]) {
    std::string host = "127.0.0.1";
    int port = Protocol::DEFAULT_PORT;
    int max_seconds = 0; // 0 = run until window closed

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 && i + 1 < argc) host = argv[++i];
        else if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) port = atoi(argv[++i]);
        else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) max_seconds = atoi(argv[++i]);
        else if (strcmp(argv[i], "--help") == 0) {
            printf("Usage: %s [-h host] [-p port] [-t seconds]\n", argv[0]);
            return 0;
        }
    }

    if (SDL_Init(SDL_INIT_VIDEO) < 0) {
        fprintf(stderr, "[viewer] SDL init failed: %s\n", SDL_GetError());
        return 1;
    }
    atexit(SDL_Quit);

    SDL_Window* win = SDL_CreateWindow(
        "desktop-viewer", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        1280, 800, SDL_WINDOW_RESIZABLE | SDL_WINDOW_SHOWN);
    if (!win) {
        fprintf(stderr, "[viewer] window create failed: %s\n", SDL_GetError());
        return 1;
    }
    SDL_Renderer* ren = SDL_CreateRenderer(
        win, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!ren) ren = SDL_CreateRenderer(win, -1, SDL_RENDERER_SOFTWARE);
    if (!ren) {
        fprintf(stderr, "[viewer] renderer create failed: %s\n", SDL_GetError());
        return 1;
    }
    SDL_Texture* tex = nullptr;
    int tex_w = 0, tex_h = 0;

    H264Decoder decoder;
    if (!decoder.init()) return 1;

    UdpClient cl;
    if (!cl.open(host, port)) {
        fprintf(stderr, "[viewer] cannot open UDP socket to %s:%d\n",
                host.c_str(), port);
        return 1;
    }
    send_handshake(cl);

    FrameReassembler reassembler;
    reassembler.set_arq_cb([&](uint32_t frame_id,
                               const std::vector<uint16_t>& missing) {
        std::vector<uint8_t> payload;
        payload.reserve(missing.size() * 6);
        for (uint16_t fi : missing) {
            uint32_t fid_le = frame_id;
            uint16_t fi_le = fi;
            uint8_t buf[6];
            memcpy(buf, &fid_le, 4);
            memcpy(buf + 4, &fi_le, 2);
            payload.insert(payload.end(), buf, buf + 6);
        }
        auto pkt = build_packet(Protocol::PacketType::FRAGMENT_RETRANSMIT_REQ,
                                payload.data(), payload.size());
        cl.send(pkt.data(), pkt.size());
        fprintf(stderr, "[viewer] ARQ req for frame %u: %zu missing\n",
                frame_id, missing.size());
    });

    std::vector<uint8_t> recv_buf;
    std::vector<std::vector<uint8_t>> pending_frames;

    auto started = steady_clock::now();
    auto last_stats = started;
    auto last_keyframe_req = started;
    auto last_heartbeat = started;
    uint64_t last_frame_us = 0;
    uint64_t frames_rendered = 0;
    uint64_t packets_seen = 0;
    uint64_t dropped_frames = 0;

    RttTracker rtt;
    SDL_Texture* overlay_tex = nullptr;
    int overlay_w = 0, overlay_h = 0;
    std::string overlay_text;
    uint8_t overlay_color[3] = { 255, 255, 255 };

    auto now_us = [] {
        return (uint64_t)duration_cast<microseconds>(
            high_resolution_clock::now().time_since_epoch()).count();
    };

    // Map viewport coordinates to remote display coordinates. The decoded
    // texture is stretched to the window, so proportional scaling is correct
    // once the first frame arrives (tex_w/tex_h are the remote dimensions).
    // Before that there is nothing to scale against, so pass through.
    int remoteCursorX = 0, remoteCursorY = 0;
    auto to_remote = [&](int x, int y, int& rx, int& ry) {
        if (tex_w > 0 && tex_h > 0) {
            int ww = 0, wh = 0;
            SDL_GetWindowSize(win, &ww, &wh);
            rx = (ww > 0) ? x * tex_w / ww : x;
            ry = (wh > 0) ? y * tex_h / wh : y;
        } else {
            rx = x;
            ry = y;
        }
    };

    bool running = true;
    bool connected = false;
    auto last_handshake_sent = steady_clock::time_point::min();

    while (running) {
        // Re-send the handshake until the server confirms it (lossy links).
        if (!connected &&
            steady_clock::now() - last_handshake_sent >= milliseconds(500)) {
            send_handshake(cl);
            last_handshake_sent = steady_clock::now();
        }
        // ---- outbound input ----------------------------------------------
        SDL_Event ev;
        while (SDL_PollEvent(&ev)) {
            if (ev.type == SDL_QUIT) {
                running = false;
            } else if (ev.type == SDL_KEYDOWN || ev.type == SDL_KEYUP) {
                uint32_t keysym = sdl_to_x11(ev.key.keysym.sym);
                if (keysym) {
                    Protocol::KeyboardEventPayload ke = {};
                    ke.keycode = 0;
                    ke.pressed = (ev.key.state == SDL_PRESSED) ? 1 : 0;
                    ke.keysym = keysym;
                    send_input_packet(cl, Protocol::PacketType::KEYBOARD_EVENT,
                                      &ke, sizeof(ke));
                }
            } else if (ev.type == SDL_MOUSEMOTION) {
                int rx, ry;
                to_remote(ev.motion.x, ev.motion.y, rx, ry);
                remoteCursorX = rx;
                remoteCursorY = ry;
                Protocol::MouseMovePayload mm = {};
                mm.x = rx;
                mm.y = ry;
                send_input_packet(cl, Protocol::PacketType::MOUSE_MOVE,
                                  &mm, sizeof(mm));
            } else if (ev.type == SDL_MOUSEBUTTONDOWN ||
                       ev.type == SDL_MOUSEBUTTONUP) {
                int rx, ry;
                to_remote(ev.button.x, ev.button.y, rx, ry);
                remoteCursorX = rx;
                remoteCursorY = ry;
                Protocol::MouseButtonPayload mb = {};
                mb.x = rx;
                mb.y = ry;
                mb.button = (ev.button.button == SDL_BUTTON_RIGHT)
                                ? Protocol::InputButtonType::RIGHT
                                : (ev.button.button == SDL_BUTTON_MIDDLE)
                                    ? Protocol::InputButtonType::MIDDLE
                                    : Protocol::InputButtonType::LEFT;
                mb.pressed = (ev.button.state == SDL_PRESSED) ? 1 : 0;
                send_input_packet(cl, Protocol::PacketType::MOUSE_BUTTON,
                                  &mb, sizeof(mb));
            } else if (ev.type == SDL_MOUSEWHEEL) {
                // Repeat the last cursor position so the server scrolls at
                // the pointing location instead of resetting it to (0,0).
                Protocol::MouseScrollPayload ms = {};
                ms.x = remoteCursorX;
                ms.y = remoteCursorY;
                ms.delta_x = ev.wheel.x;
                ms.delta_y = ev.wheel.y;
                send_input_packet(cl, Protocol::PacketType::MOUSE_SCROLL,
                                  &ms, sizeof(ms));
            }
        }

        // ---- inbound ------------------------------------------------------
        while (cl.recv(recv_buf, Protocol::MAX_PACKET_SIZE)) {
            packets_seen++;
            if (recv_buf.size() < sizeof(Protocol::PacketHeader)) continue;

            Protocol::PacketHeader hdr;
            memcpy(&hdr, recv_buf.data(), sizeof(hdr));
            if (hdr.magic != Protocol::MAGIC) {
                fprintf(stderr, "[viewer] bad magic\n");
                continue;
            }

            // FEC parity packets use the larger 29-byte header and carry
            // their payload checksum the same way; validate against it.
            if (hdr.type == Protocol::PacketType::VIDEO_FEC_PARITY) {
                if (recv_buf.size() < sizeof(Protocol::FecPacketHeader)) {
                    dropped_frames++;
                    continue;
                }
                Protocol::FecPacketHeader fh;
                memcpy(&fh, recv_buf.data(), sizeof(fh));
                uint32_t cksum = Protocol::compute_checksum(
                    recv_buf.data() + sizeof(fh), fh.payload_length);
                if (fh.payload_length > 0 && cksum != fh.checksum) {
                    fprintf(stderr, "[viewer] FEC checksum failed\n");
                    dropped_frames++;
                    continue;
                }
                reassembler.add_parity(
                    fh, recv_buf.data() + sizeof(fh), fh.payload_length,
                    now_us(), pending_frames);
                continue;
            }

            uint32_t cksum = Protocol::compute_checksum(
                recv_buf.data() + sizeof(hdr), hdr.payload_length);
            // Empty-payload control frames (handshake ack, keyframe req) are
            // sent with checksum 0 by the server; video fragments always carry
            // a real payload checksum.
            if (hdr.payload_length > 0 && cksum != hdr.checksum) {
                fprintf(stderr, "[viewer] checksum failed (frame %u frag %u)\n",
                        hdr.frame_id, hdr.fragment_index);
                dropped_frames++;
                continue;
            }

            const uint8_t* payload = recv_buf.data() + sizeof(hdr);
            size_t payload_len = recv_buf.size() - sizeof(hdr);

            switch (hdr.type) {
            case Protocol::PacketType::HANDSHAKE_ACK:
                if (!connected) {
                    connected = true;
                    printf("[viewer] server confirmed handshake\n");
                }
                break;

            case Protocol::PacketType::VIDEO_FRAME:
                reassembler.add(hdr, payload, payload_len, now_us(),
                                pending_frames);
                break;

            case Protocol::PacketType::DISCONNECT:
                printf("[viewer] server disconnected\n");
                running = false;
                break;

            case Protocol::PacketType::HEARTBEAT:
                if (payload_len >= sizeof(Protocol::HeartbeatPayload)) {
                    Protocol::HeartbeatPayload hp;
                    memcpy(&hp, payload, sizeof(hp));
                    rtt.reply(hp.timestamp_us, now_us());
                }
                break;

            default:
                break; // ignore keyframe req, etc.
            }
        }

        // Render any frame completed by the reassembler.
        reassembler.poll(now_us(), pending_frames);
        for (auto& unit : pending_frames) {
            uint8_t* rgb = nullptr;
            int w = 0, h = 0, stride = 0;
            if (decoder.decode(unit.data(), unit.size(), rgb, w, h, stride)) {
                if (!tex || w != tex_w || h != tex_h) {
                    if (tex) SDL_DestroyTexture(tex);
                    tex = SDL_CreateTexture(ren, SDL_PIXELFORMAT_RGB24,
                                            SDL_TEXTUREACCESS_STATIC, w, h);
                    tex_w = w;
                    tex_h = h;
                }
                if (tex) {
                    SDL_UpdateTexture(tex, nullptr, rgb, stride);
                    SDL_RenderClear(ren);
                    SDL_RenderCopy(ren, tex, nullptr, nullptr);

                    // Latency overlay (top-left corner), rebuilt only when the
                    // RTT text or its traffic-light colour changes.
                    char buf[64];
                    uint8_t col[3];
                    if (rtt.has()) {
                        double ms = rtt.avg_ms();
                        snprintf(buf, sizeof(buf), "RTT %.1f ms", ms);
                        if (ms < 30) {
                            col[0]=0x3C; col[1]=0xFF; col[2]=0x3C;   // green
                        } else if (ms < 100) {
                            col[0]=0xFF; col[1]=0xE2; col[2]=0x2C;   // yellow
                        } else {
                            col[0]=0xFF; col[1]=0x45; col[2]=0x45;   // red
                        }
                    } else {
                        snprintf(buf, sizeof(buf), "RTT -- ms");
                        col[0]=0xFF; col[1]=0xFF; col[2]=0xFF;       // white
                    }
                    std::string text(buf);
                    bool text_changed = (text != overlay_text);
                    bool color_changed = (col[0] != overlay_color[0] ||
                                          col[1] != overlay_color[1] ||
                                          col[2] != overlay_color[2]);
                    if (overlay_tex && (text_changed || color_changed)) {
                        SDL_DestroyTexture(overlay_tex);
                        overlay_tex = nullptr;
                    }
                    if (!overlay_tex) {
                        overlay_tex = make_overlay_texture(ren, text, col,
                                                           overlay_w, overlay_h);
                        overlay_text = text;
                        overlay_color[0] = col[0];
                        overlay_color[1] = col[1];
                        overlay_color[2] = col[2];
                    }
                    if (overlay_tex) {
                        SDL_Rect dst{ 8, 8, overlay_w, overlay_h };
                        SDL_RenderCopy(ren, overlay_tex, nullptr, &dst);
                    }

                    SDL_RenderPresent(ren);
                    frames_rendered++;
                }
                last_frame_us = now_us();
            } else {
                dropped_frames++;
            }
        }
        pending_frames.clear();

        // ---- recovery: request keyframe if the stream stalled ------------
        uint64_t now = now_us();
        uint64_t elapsed_ms = (uint64_t)duration_cast<milliseconds>(
            steady_clock::now() - last_keyframe_req).count();
        if ((connected && last_frame_us > 0 && now - last_frame_us > 2000000) ||
            (connected && last_frame_us == 0 && elapsed_ms > 500)) {
            auto pkt = build_packet(
                Protocol::PacketType::KEYFRAME_REQ, nullptr, 0);
            cl.send(pkt.data(), pkt.size());
            last_keyframe_req = steady_clock::now();
            fprintf(stderr, "[viewer] requesting keyframe\n");
        }

        // ---- stats -------------------------------------------------------
        auto now_steady = steady_clock::now();

        // ---- latency ping: heartbeats are echoed by the server ------------
        if (connected && now_steady - last_heartbeat >= milliseconds(500)) {
            Protocol::HeartbeatPayload hp = {};
            hp.timestamp_us = now_us();
            auto pkt = build_packet(Protocol::PacketType::HEARTBEAT,
                                    &hp, sizeof(hp));
            cl.send(pkt.data(), pkt.size());
            rtt.send(hp.timestamp_us);
            last_heartbeat = now_steady;
        }

        if (now_steady - last_stats >= seconds(1)) {
            printf("[viewer] packets=%llu rendered=%llu dropped=%llu "
                   "fec_recovered=%llu pending=%d connected=%d\n",
                   (unsigned long long)packets_seen,
                   (unsigned long long)frames_rendered,
                   (unsigned long long)dropped_frames,
                   (unsigned long long)reassembler.fec_recovered(),
                   reassembler.has_pending() ? 1 : 0,
                   connected ? 1 : 0);
            last_stats = now_steady;
        }

        // ---- auto-exit ----------------------------------------------------
        if (max_seconds > 0 &&
            duration_cast<seconds>(now_steady - started).count() >= max_seconds) {
            running = false;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    if (tex) SDL_DestroyTexture(tex);
    if (overlay_tex) SDL_DestroyTexture(overlay_tex);
    SDL_DestroyRenderer(ren);
    SDL_DestroyWindow(win);
    cl.close();
    return 0;
}