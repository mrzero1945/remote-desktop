# Remote Desktop — Server

Streams the local X11 desktop (H.264) to Android clients over UDP and
injects mouse/keyboard input received from the clients via XTest.

## Requirements

- Linux with an X11 display
- CMake ≥ 3.16 and a C++17 compiler
- pkg-config
- Boost (only `boost/asio` is used)
- X11 development libraries: `x11`, `xext`, `xfixes`, `xtst`
- FFmpeg development libraries: `libavformat`, `libavcodec`, `libavutil`, `libswscale`

On Debian/Ubuntu this roughly maps to:

```
sudo apt install cmake g++ pkg-config libboost-dev \
     libx11-dev libxext-dev libxfixes-dev libxtst-dev \
     libavformat-dev libavcodec-dev libavutil-dev libswscale-dev
```

## Build

```
cmake -S . -B build
cmake --build build          # or: make -C build
```

Output binary: `build/remote_desktop_server`.

To wipe any stale build cache before rebuilding:

```
rm -rf build/CMakeCache.txt build/CMakeFiles build/Makefile build/cmake_install.cmake
```

## Usage

```
./build/remote_desktop_server [-p port] [-f fps] [-b bitrate_kbps]
```

| Option | Default | Description                       |
|--------|---------|-----------------------------------|
| `-p`   | `9876`  | UDP port to listen on             |
| `-f`   | `60`    | Capture/encode frame rate         |
| `-b`   | `3000`  | H.264 bitrate in kbit/s           |

Example:

```
DISPLAY=:1 ./build/remote_desktop_server -p 9876
```

## How it works

- **Screen capture** — `screen_capture` grabs the X11 root window via
  MIT-SHM (`XShmGetImage`) and exposes raw BGRA frames.
- **Encoding** — `encoder` converts BGRA → YUV420P (swscale) and encodes
  with FFmpeg `libx264` (ultrafast, zerolatency, baseline). SPS/PPS headers
  are repeated on *every* frame and a real IDR keyframe is emitted every 15
  frames, so a client joining mid-stream syncs in well under a second.
- **Transport** — `udp_server` (Boost.Asio, UDP only) handles the
  handshake, heartbeats, keyframe/retransmit requests, slices each H.264
  access unit into ≤1400-byte fragments and broadcasts to all clients.
- **Input** — `input_handler` applies client mouse/keyboard packets to the
  X11 session via the XTest extension
  (`XTestFakeMotionEvent` / `ButtonEvent` / `KeyEvent`).

See `src/protocol.h` for the wire format (21-byte packet header, magic
`0x5244`, per-fragment checksum, packet types).

## Notes

- Video is delivered **UDP only**; there is no TCP fallback. Lost
  fragments are retransmitted on request, and every frame carries repeated
  SPS/PPS headers plus a periodic IDR keyframe so any fully-received access
  unit is a usable decoder sync point.
- Clients are tracked per endpoint; a client that stops sending heartbeats
  for 8 seconds is evicted so a crashed/disconnected phone does not keep the
  stream alive forever.
- Input packets (mouse/keyboard) are only honored from endpoints that have
  completed the handshake.
- The server streams only while at least one client is connected.
- Press `Ctrl+C` (SIGINT/SIGTERM) to stop cleanly.