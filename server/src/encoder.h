#pragma once

#include <cstdint>
#include <vector>
#include <functional>

struct EncodedFrame {
    std::vector<uint8_t> data;
    uint32_t frame_id;
};

class Encoder {
public:
    Encoder();
    ~Encoder();

    bool init(int width, int height, int fps = 60, int bitrate = 8000000);
    void shutdown();

    void request_keyframe() { m_force_keyframe = true; }

    using FrameCallback = std::function<void(EncodedFrame&&)>;
    void set_frame_callback(FrameCallback cb) { m_frame_callback = std::move(cb); }

    // Cached SPS+PPS NALs (captured from the first encoded packet). With
    // intra-refresh there are no IDR frames, so these are needed to give a
    // newly connected client a decoder sync point.
    const std::vector<uint8_t>& sps_pps() const { return m_sps_pps; }
    bool has_sps_pps() const { return !m_sps_pps.empty(); }

    bool encode(const uint8_t* bgra_data, int width, int height,
                int stride);

private:
    void* m_codec_ctx = nullptr;
    void* m_sws_ctx = nullptr;
    void* m_packet = nullptr;
    void* m_frame_rgb = nullptr;
    int m_width = 0;
    int m_height = 0;
    uint32_t m_frame_counter = 0;
    bool m_force_keyframe = false;
    std::vector<uint8_t> m_sps_pps;
    FrameCallback m_frame_callback;
};
