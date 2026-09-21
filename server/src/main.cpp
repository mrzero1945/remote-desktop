#include "screen_capture.h"
#include "encoder.h"
#include "udp_server.h"
#include "input_handler.h"
#include "audio_capture.h"
#include "protocol.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <signal.h>
#include <thread>
#include <atomic>

static std::atomic<bool> g_running{true};

static void signal_handler(int sig) {
    (void)sig;
    g_running = false;
}

int main(int argc, char* argv[]) {
    int port = Protocol::DEFAULT_PORT;
    int fps = 60;
    int bitrate = 3000000;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-p") == 0 && i + 1 < argc) port = atoi(argv[++i]);
        else if (strcmp(argv[i], "-f") == 0 && i + 1 < argc) fps = atoi(argv[++i]);
        else if (strcmp(argv[i], "-b") == 0 && i + 1 < argc) bitrate = atoi(argv[++i]) * 1000;
        else if (strcmp(argv[i], "-h") == 0) {
            printf("Usage: %s [-p port] [-f fps] [-b bitrate_kbps]\n", argv[0]);
            return 0;
        }
    }

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    printf("=== Remote Desktop Server ===\n");
    printf("Port: %d, FPS: %d, Bitrate: %d kbps\n\n", port, fps, bitrate / 1000);

    ScreenCapture capture;
    if (!capture.init()) {
        fprintf(stderr, "Failed to initialize screen capture\n");
        return 1;
    }

    Encoder encoder;
    if (!encoder.init(capture.width(), capture.height(), fps, bitrate)) {
        fprintf(stderr, "Failed to initialize encoder\n");
        return 1;
    }

    UdpServer server;
    if (!server.start(port)) {
        fprintf(stderr, "Failed to start UDP server\n");
        return 1;
    }

    encoder.set_frame_callback([&server](EncodedFrame&& ef) {
        server.broadcast_frame(ef.data.data(), ef.data.size(),
                              ef.frame_id);
    });

    server.set_keyframe_callback([&server, &encoder]() {
        encoder.request_keyframe();
        server.request_sync();
    });

    // Audio runs for the whole server lifetime; broadcast_audio() itself drops
    // the frame when no client negotiated audio, so idle cost is one Opus
    // encode per 20 ms.
    start_audio_capture([&server](const uint8_t* data, size_t len) {
        server.broadcast_audio(data, len);
    });

    InputHandler input;
    if (!input.init()) {
        fprintf(stderr, "Failed to initialize input handler\n");
        return 1;
    }
    input.set_screen_dimensions(capture.width(), capture.height());

    server.set_input_callback([&input](const ClientInfo& client,
        Protocol::PacketType type, const uint8_t* payload, size_t len) {
        (void)client;

        switch (type) {
        case Protocol::PacketType::MOUSE_MOVE: {
            if (len >= sizeof(Protocol::MouseMovePayload)) {
                auto* p = (const Protocol::MouseMovePayload*)payload;
                input.handle_mouse_move(p->x, p->y);
            }
            break;
        }
        case Protocol::PacketType::MOUSE_BUTTON: {
            if (len >= sizeof(Protocol::MouseButtonPayload)) {
                auto* p = (const Protocol::MouseButtonPayload*)payload;
                input.handle_mouse_button(p->x, p->y, p->button, p->pressed);
            }
            break;
        }
        case Protocol::PacketType::MOUSE_SCROLL: {
            if (len >= sizeof(Protocol::MouseScrollPayload)) {
                auto* p = (const Protocol::MouseScrollPayload*)payload;
                input.handle_mouse_scroll(p->x, p->y, p->delta_x, p->delta_y);
            }
            break;
        }
        case Protocol::PacketType::KEYBOARD_EVENT: {
            if (len >= sizeof(Protocol::KeyboardEventPayload)) {
                auto* p = (const Protocol::KeyboardEventPayload*)payload;
                fprintf(stderr, "[Input] key event keysym=%u pressed=%d\n",
                        p->keysym, p->pressed ? 1 : 0);
                input.handle_keyboard(p->keysym, p->pressed);
            }
            break;
        }
        default:
            break;
        }
    });

    auto frame_interval = std::chrono::microseconds(1000000 / fps);
    int keyframe_counter = 0;
    auto last_stats = std::chrono::steady_clock::now();
    uint64_t stat_frames = 0;
    uint64_t stat_capture_us = 0;
    uint64_t stat_encode_us = 0;
    uint64_t stat_max_capture_us = 0;
    uint64_t stat_max_encode_us = 0;

    printf("[Main] Streaming... Press Ctrl+C to stop\n\n");

    while (g_running) {
        auto frame_start = std::chrono::high_resolution_clock::now();

        if (server.client_count() == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        auto frame = capture.capture();
        if (!frame.data) {
            fprintf(stderr, "[Main] Capture failed\n");
            continue;
        }
        auto cap_done = std::chrono::high_resolution_clock::now();

        bool force_keyframe = (keyframe_counter == 0);
        if (force_keyframe) {
            encoder.request_keyframe();
        }

        encoder.encode(frame.data, frame.width, frame.height,
                      frame.stride);
        auto enc_done = std::chrono::high_resolution_clock::now();

        // Hand the cached SPS/PPS to the server once available so new
        // clients can get a sync marker without an IDR keyframe.
        if (encoder.has_sps_pps() && !server.has_sync_prefix()) {
            server.set_sync_prefix(encoder.sps_pps());
        }

        keyframe_counter = (keyframe_counter + 1) % Protocol::KEYFRAME_INTERVAL;

        // ---- per-frame timing stats --------------------------------------
        uint64_t cap_us = (uint64_t)std::chrono::duration_cast<
            std::chrono::microseconds>(cap_done - frame_start).count();
        uint64_t enc_us = (uint64_t)std::chrono::duration_cast<
            std::chrono::microseconds>(enc_done - cap_done).count();
        stat_frames++;
        stat_capture_us += cap_us;
        stat_encode_us += enc_us;
        stat_max_capture_us = std::max(stat_max_capture_us, cap_us);
        stat_max_encode_us = std::max(stat_max_encode_us, enc_us);

        // Flag individual stages that eat into the frame budget (>10ms each,
        // or >20ms for encode/network) so the cause of stalls is visible.
        if (cap_us > 10000) {
            fprintf(stderr,
                    "[Main] SLOW capture %llu us (frame interval %llu us)\n",
                    (unsigned long long)cap_us,
                    (unsigned long long)frame_interval.count());
        }
        if (enc_us > 20000) {
            fprintf(stderr,
                    "[Main] SLOW encode %llu us (keyframe=%d frames=%llu)\n",
                    (unsigned long long)enc_us, force_keyframe ? 1 : 0,
                    (unsigned long long)stat_frames);
        }

        auto now_stats = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(
                now_stats - last_stats).count() >= 1000) {
            fprintf(stderr,
                    "[Main] cap_avg=%.1fus cap_max=%llu enc_avg=%.1fus "
                    "enc_max=%llu frames=%llu (budget=%llu)\n",
                    stat_frames ? (double)stat_capture_us / stat_frames : 0.0,
                    (unsigned long long)stat_max_capture_us,
                    stat_frames ? (double)stat_encode_us / stat_frames : 0.0,
                    (unsigned long long)stat_max_encode_us,
                    (unsigned long long)stat_frames,
                    (unsigned long long)frame_interval.count());
            last_stats = now_stats;
            stat_frames = 0;
            stat_capture_us = 0;
            stat_encode_us = 0;
            stat_max_capture_us = 0;
            stat_max_encode_us = 0;
        }

        auto elapsed = std::chrono::high_resolution_clock::now() - frame_start;
        auto remaining = frame_interval - elapsed;

        if (remaining.count() > 0) {
            std::this_thread::sleep_for(remaining);
        }
    }

    printf("\n[Main] Shutting down...\n");

    stop_audio_capture();
    server.stop();
    encoder.shutdown();
    input.shutdown();
    capture.shutdown();

    printf("[Main] Done.\n");
    return 0;
}
