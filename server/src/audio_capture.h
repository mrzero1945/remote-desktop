#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

// Captures the default PulseAudio source and encodes it as Opus, handing each
// finished packet to the sink. Fixed format, chosen to match what Android's
// MediaCodec "audio/opus" decoder handles natively:
//   48000 Hz, stereo, 20 ms frames (960 samples/channel).
// The sink is invoked from the capture thread; it must be fast and
// thread-safe (the UDP broadcast path is).
void start_audio_capture(std::function<void(const uint8_t*, size_t)> sink);
void stop_audio_capture();