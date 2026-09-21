# Remote Desktop — Android Client

Android client for the remote-desktop server in `../server`. It connects
over UDP, decodes the H.264 stream with `MediaCodec`, and acts as a
touchpad / keyboard for the remote X11 desktop.

## Build

```
cd android
./gradlew --offline assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

Requirements: Android Gradle Plugin 8.13, Gradle 8.13 wrapper, SDK
`compileSdk 36`, `minSdk 24`, `targetSdk 36`. Only dependency is
`androidx.appcompat:appcompat:1.6.1`.

Install on an attached device/emulator:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Start the server on the desktop machine (see `../server/README.md`).
2. Open the app, enter the server host and UDP port (default `9876`),
   and tap **CONNECT**.
3. Rotate to landscape and tap the fullscreen button (⛶) for the best
   experience, then use the floating keyboard button to toggle the
   virtual keyboard.

## Features / gestures

- **Single-finger drag** — trackpad-style relative mouse movement (scroll
  factor `POINTER_SPEED = 3.0`). A cursor arrow is drawn on the video at
  the remote position.
- **Double-tap (one finger)** — left click.
- **Two-finger tap** — right click.
- **Two-finger drag** — scroll (vertical/horizontal wheel steps).
- **Virtual keyboard** — translucent, laptop-style overlay with digits,
  qwerty/AWSD layout, arrow keys and `Ctrl`/`Shift`/`Alt` modifiers.
  Keys support multi-touch and key-hold. Toggle via the floating keyboard
  button; **BACK** hides it.
- **Hardware keyboard** — physical keys are forwarded as raw
  press/release events with automatic Shift injection for uppercase.
- **Fullscreen / immersive mode** — one-tap toggle.

## Source layout

| File | Purpose |
|------|---------|
| `MainActivity.java` | Entry point: host/port UI, `UdpClient` + `VideoDecoder` lifecycle, keyboard handling and fullscreen toggles |
| `RemoteView.java` | `SurfaceView` that draws decoded frames (fit or stretch) with a cursor overlay and translates gestures into input packets |
| `UdpClient.java` | UDP transport: handshake, sender/receiver threads, fragment reassembly with checksum validation |
| `VideoDecoder.java` | MediaCodec (`video/avc`) decoder in buffer mode, YUV→ARGB conversion, SPS/PPS handling, stall watchdog |
| `VirtualKeyboardView.java` | Translucent on-screen laptop-style keyboard overlay |
| `Keys.java` | Android `KeyEvent` → X11 keysym mapping incl. Shift logic |
| `Proto.java` | Java mirror of the wire protocol (headers, packet types, checksum, builders) |

See `Protocol` class in `Proto.java` / the server's `protocol.h` for the
wire format (UDP, port `9876`, magic `0x5244`).