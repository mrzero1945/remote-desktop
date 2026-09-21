#include "encoder.h"
#include <cstring>
#include <cstdio>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

Encoder::Encoder() {}

Encoder::~Encoder() {
    shutdown();
}

bool Encoder::init(int width, int height, int fps, int bitrate) {
    m_width = width;
    m_height = height;

    const AVCodec* codec = avcodec_find_encoder_by_name("libx264");
    if (!codec) {
        codec = avcodec_find_encoder(AV_CODEC_ID_H264);
    }
    if (!codec) {
        fprintf(stderr, "[Encoder] H.264 encoder not found\n");
        return false;
    }

    AVCodecContext* ctx = avcodec_alloc_context3(codec);
    if (!ctx) {
        fprintf(stderr, "[Encoder] Cannot allocate codec context\n");
        return false;
    }

    ctx->codec_id = AV_CODEC_ID_H264;
    ctx->codec_type = AVMEDIA_TYPE_VIDEO;
    ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    ctx->width = width;
    ctx->height = height;
    ctx->bit_rate = bitrate;
    ctx->time_base = {1, fps};
    ctx->framerate = {fps, 1};
    ctx->gop_size = 15;
    ctx->max_b_frames = 0;
    // Multi-thread the frame encode. A single thread cannot sustain 60fps at
    // 1920x1200 on complex content (~100-400ms/frame), which crippled the
    // stream to ~9fps. 4 frame threads add at most ~3 frames of encode delay
    // but keep the encode inside the frame budget.
    ctx->thread_count = 4;
    ctx->thread_type = FF_THREAD_FRAME;

    // Make the SPS carry explicit BT.709 color metadata. MediaTek decoders
    // otherwise render this stream without chroma (all grey) because nothing
    // tells them which matrix to use on the Surface path.
    ctx->color_primaries = AVCOL_PRI_BT709;
    ctx->color_trc = AVCOL_TRC_BT709;
    ctx->colorspace = AVCOL_SPC_BT709;
    ctx->color_range = AVCOL_RANGE_MPEG;

    av_opt_set(ctx->priv_data, "preset", "ultrafast", 0);
    av_opt_set(ctx->priv_data, "tune", "zerolatency", 0);
    av_opt_set(ctx->priv_data, "profile", "baseline", 0);
    // Repeat SPS/PPS on every frame so a joining client can configure its
    // decoder immediately; real IDR keyframes (with gop_size above) give it a
    // genuine sync point every 0.5s. intra-refresh would make x264 ignore the
    // forced keyframe and no client could ever sync mid-stream.
    av_opt_set(ctx->priv_data, "x264opts", "repeat-headers=1", 0);

    if (avcodec_open2(ctx, codec, nullptr) < 0) {
        fprintf(stderr, "[Encoder] Cannot open codec\n");
        avcodec_free_context(&ctx);
        return false;
    }

    m_codec_ctx = ctx;

    AVPacket* pkt = av_packet_alloc();
    m_packet = pkt;

    AVFrame* frame = av_frame_alloc();
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = width;
    frame->height = height;
    av_frame_get_buffer(frame, 32);
    m_frame_rgb = frame;

    SwsContext* sws = sws_getContext(
        width, height, AV_PIX_FMT_BGRA,
        width, height, AV_PIX_FMT_YUV420P,
        SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!sws) {
        fprintf(stderr, "[Encoder] sws_getContext failed\n");
        return false;
    }
    m_sws_ctx = sws;

    printf("[Encoder] Initialized: %dx%d @ %d fps, %d kbps\n",
           width, height, fps, bitrate / 1000);
    return true;
}

void Encoder::shutdown() {
    if (m_codec_ctx) {
        AVCodecContext* ctx = (AVCodecContext*)m_codec_ctx;
        avcodec_free_context(&ctx);
        m_codec_ctx = nullptr;
    }
    if (m_packet) {
        av_packet_free((AVPacket**)&m_packet);
        m_packet = nullptr;
    }
    if (m_frame_rgb) {
        av_frame_free((AVFrame**)&m_frame_rgb);
        m_frame_rgb = nullptr;
    }
    if (m_sws_ctx) {
        sws_freeContext((SwsContext*)m_sws_ctx);
        m_sws_ctx = nullptr;
    }
}

bool Encoder::encode(const uint8_t* bgra_data, int width, int height,
                     int stride) {
    if (!m_codec_ctx || !m_sws_ctx) return false;

    AVCodecContext* ctx = (AVCodecContext*)m_codec_ctx;
    SwsContext* sws = (SwsContext*)m_sws_ctx;
    AVFrame* frame = (AVFrame*)m_frame_rgb;
    AVPacket* pkt = (AVPacket*)m_packet;

    const uint8_t* src_data[1] = { bgra_data };
    int src_linesize[1] = { stride };

    sws_scale(sws, src_data, src_linesize, 0, height,
              frame->data, frame->linesize);

    bool is_keyframe = m_force_keyframe;
    m_force_keyframe = false;
    frame->pict_type = is_keyframe ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE;
    frame->pts = m_frame_counter++;

    int ret = avcodec_send_frame(ctx, frame);
    if (ret < 0) {
        fprintf(stderr, "[Encoder] Error sending frame\n");
        return false;
    }

    while (ret >= 0) {
        ret = avcodec_receive_packet(ctx, pkt);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) {
            fprintf(stderr, "[Encoder] Error receiving packet\n");
            return false;
        }

        if (m_sps_pps.empty()) {
            m_sps_pps.assign(pkt->data, pkt->data + pkt->size);
            // Keep only the leading SPS/PPS NALs (types 7 and 8). With
            // repeat-headers they appear at the head of the first packet.
            size_t start = 0;
            for (;;) {
                size_t i = start;
                while (i + 4 < (size_t)pkt->size &&
                       !(pkt->data[i] == 0 && pkt->data[i + 1] == 0 &&
                         pkt->data[i + 2] == 1)) {
                    i++;
                }
                if (i + 4 >= (size_t)pkt->size) break; // no more NALs
                int nal_type = pkt->data[i + 3] & 0x1F;
                if (nal_type != 7 && nal_type != 8) break;
                size_t next = i + 4;
                while (next + 4 < (size_t)pkt->size &&
                       !(pkt->data[next] == 0 && pkt->data[next + 1] == 0 &&
                         pkt->data[next + 2] == 1)) {
                    next++;
                }
                start = next;
                if (next >= (size_t)pkt->size) break;
            }
            m_sps_pps.resize(start);
        }

        EncodedFrame ef;
        ef.data.assign(pkt->data, pkt->data + pkt->size);
        ef.frame_id = m_frame_counter;

        if (m_frame_callback) {
            m_frame_callback(std::move(ef));
        }

        av_packet_unref(pkt);
    }

    return true;
}
