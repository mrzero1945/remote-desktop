#include "audio_capture.h"

#include <pulse/simple.h>
#include <pulse/error.h>
#include <pulse/context.h>
#include <pulse/introspect.h>
#include <pulse/mainloop.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

constexpr int SAMPLE_RATE = 48000;
constexpr int CHANNELS = 2;
// 5 ms per channel: 960 B/frame keeps the whole datagram under the 1400 B
// protocol payload cap so it fits one MTU and the client's receive buffer.
constexpr int FRAME_SAMPLES = 240;               // 5 ms per channel
constexpr int FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2; // S16LE

std::atomic<bool> g_running{false};
std::thread g_thread;
std::function<void(const uint8_t*, size_t)> g_sink;

void server_info_cb(pa_context*, const pa_server_info* info, void* userdata) {
    auto* out = static_cast<std::string*>(userdata);
    if (info && info->default_sink_name) {
        // Recording from a sink's monitor captures what the desktop is
        // *playing* (internal audio), unlike the default source (microphone).
        *out = std::string(info->default_sink_name) + ".monitor";
    }
}

// Resolve the default sink's monitor source, e.g.
// "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor". Empty on failure.
std::string default_monitor_source() {
    if (const char* env = std::getenv("REMOTE_DESKTOP_AUDIO_SOURCE")) {
        return env;
    }
    pa_mainloop* ml = pa_mainloop_new();
    if (!ml) return {};
    pa_context* ctx = pa_context_new(pa_mainloop_get_api(ml), "remote_desktop_probe");
    if (!ctx) {
        pa_mainloop_free(ml);
        return {};
    }
    if (pa_context_connect(ctx, nullptr, PA_CONTEXT_NOFLAGS, nullptr) < 0) {
        pa_context_unref(ctx);
        pa_mainloop_free(ml);
        return {};
    }

    int guard = 0;
    while (pa_context_get_state(ctx) != PA_CONTEXT_READY && guard++ < 500) {
        if (!PA_CONTEXT_IS_GOOD(pa_context_get_state(ctx))) break;
        pa_mainloop_iterate(ml, 0, nullptr);
        usleep(2000);
    }

    std::string monitor;
    if (pa_context_get_state(ctx) == PA_CONTEXT_READY) {
        pa_operation* op = pa_context_get_server_info(ctx, server_info_cb, &monitor);
        while (op && pa_operation_get_state(op) == PA_OPERATION_RUNNING)
            pa_mainloop_iterate(ml, 1, nullptr);
        if (op) pa_operation_unref(op);
    }

    pa_context_disconnect(ctx);
    pa_context_unref(ctx);
    pa_mainloop_free(ml);
    return monitor;
}

void capture_loop() {
    pa_sample_spec ss;
    ss.format = PA_SAMPLE_S16LE;
    ss.rate = SAMPLE_RATE;
    ss.channels = CHANNELS;

    std::vector<uint8_t> pcm(FRAME_BYTES);

    std::string source = default_monitor_source();
    if (source.empty()) {
        fprintf(stderr,
                "[Audio] no default sink monitor found; falling back to "
                "default source (microphone)\n");
    }

    while (g_running.load()) {
        pa_simple* pa = nullptr;
        int perr = 0;
        pa = pa_simple_new(nullptr, "remote_desktop", PA_STREAM_RECORD,
                           source.empty() ? nullptr : source.c_str(),
                           "desktop audio", &ss, nullptr, nullptr, &perr);
        if (!pa) {
            fprintf(stderr, "[Audio] pa_simple_new failed: %s\n",
                    pa_strerror(perr));
            for (int i = 0; i < 10 && g_running.load(); i++)
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        fprintf(stderr,
                "[Audio] capture started on '%s' (%d Hz, %d ch, PCM S16LE)\n",
                source.empty() ? "<default source>" : source.c_str(),
                SAMPLE_RATE, CHANNELS);

        while (g_running.load()) {
            if (pa_simple_read(pa, pcm.data(), FRAME_BYTES, &perr) < 0) {
                fprintf(stderr, "[Audio] pa_simple_read failed: %s\n",
                        pa_strerror(perr));
                break; // reopen the stream
            }
            // Send raw PCM: Android's out-of-process Opus decoder is
            // unreliable on some devices (hangs the media.codec service), so
            // the client plays PCM directly through AudioTrack.
            if (g_sink) g_sink(pcm.data(), (size_t)FRAME_BYTES);
        }
        pa_simple_free(pa);
    }
}

} // namespace

void start_audio_capture(std::function<void(const uint8_t*, size_t)> sink) {
    if (g_running.exchange(true)) return;
    g_sink = std::move(sink);
    g_thread = std::thread(capture_loop);
}

void stop_audio_capture() {
    if (!g_running.exchange(false)) return;
    if (g_thread.joinable()) g_thread.join();
    g_sink = nullptr;
}