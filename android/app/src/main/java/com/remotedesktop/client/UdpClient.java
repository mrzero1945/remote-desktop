package com.remotedesktop.client;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP transport matching the Boost.Asio UDP server (see ../server/src/udp_server.cpp).
 *
 * Unlike TCP, UDP is connectionless, best-effort and datagram sized, so this
 * client does exactly what the desktop-viewer C++ reference client does:
 *   - sends the HANDSHAKE, then dispatches input / keyframe-request packets
 *     as single datagrams (21-byte PacketHeader + payload),
 *   - receives H.264 access units split into MAX_PAYLOAD_SIZE fragments and
 *     reassembles them from (frame_id, fragment_index, total_fragments),
 *   - validates the per-fragment checksum,
 *   - FEC-first recovery: the server sends Reed-Solomon parity packets
 *     (VIDEO_FEC_PARITY, 29-byte FecPacketHeader). FEC is only *used* when a
 *     block loses data fragments: if it still has >= block_data symbols
 *     (data + parity), the block is reconstructed with Fec.recoverBlock.
 *     A retransmit (FRAGMENT_RETRANSMIT_REQ) is requested only for losses
 *     that FEC can never repair (missing > parity, all parity arrived but
 *     still undecodable, or parity never arrived), so a frame the FEC
 *     repaired never triggers ARQ and pays no retransmit round-trip.
 */
public final class UdpClient {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onVideoFrame(byte[] accessUnit, boolean isKeyframe);
        void onHeartbeat(long remoteTimeUs);
    }

    private static final String TAG = "UdpClient";
    private static final int MAX_TOTAL_FRAGMENTS = Proto.MAX_TOTAL_FRAGMENTS;
    private static final long ARQ_STALL_US = 150_000;   // frame silent this long -> finalize to ARQ
    private static final long ARQ_SETTLE_US = 40_000;   // grace for in-flight data+parity before ARQ
    private static final long ARQ_MIN_SEND_US = 25_000; // min gap between ARQ sends (server throttle is 20ms)
    private static final int ARQ_MAX_ROUNDS = 3;
    private static final long HEARTBEAT_INTERVAL_US = 500_000;
    private static final int HEARTBEAT_PAYLOAD_SIZE = 8;

    // Recovery diagnostics (receiver thread only).
    private int statParityReceived = 0;
    private int statFecBlocksRecovered = 0;
    private int statFecFragsRecovered = 0;
    private int statArqRequests = 0;
    private long lastStatsLogUs = 0;

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DatagramSocket socket;
    private InetSocketAddress serverAddr;
    private Thread receiverThread;
    private Thread senderThread;
    private final ConcurrentLinkedQueue<byte[]> outQueue = new ConcurrentLinkedQueue<>();
    private long lastHeartbeatUs = 0;

    // Timestamps of HEARTBEAT_PING packets we sent whose HEARTBEAT_PONG echo
    // has not arrived yet. Only the receiver thread touches this. Matched PONGs
    // yield the RTT sample; unmatched timestamps (lost/duplicate pings) are
    // trimmed by age.
    private final java.util.ArrayDeque<Long> pendingHeartbeatTs = new java.util.ArrayDeque<>();

    // Frame reassembly state (single receiver thread accesses it).
    private FrameState cur;
    private FrameState fallback;

    // Recovery toggles (set before connect(), read live on the receiver
    // thread). FEC is negotiated with the server at handshake time via the
    // fec_level field, so it applies on (re)connect; ARQ is pure client logic.
    private volatile boolean fecEnabled = true;
    private volatile boolean arqEnabled = true;

    // Feature negotiation flags carried in the handshake (see Proto.java):
    // audio requests a server audio stream, encryption requests an
    // encrypted transport. Actual codec/cipher plumbing lands here later;
    // today the server just records them per client.
    private volatile boolean audioEnabled = true;
    private volatile boolean encryptionEnabled = false;

    // Opus playback; created once the handshake is acknowledged when audio
    // was negotiated, torn down on disconnect.
    private volatile AudioPlayer audioPlayer;

    public void setFecEnabled(boolean on) {
        fecEnabled = on;
    }

    public void setArqEnabled(boolean on) {
        arqEnabled = on;
    }

    public void setAudioEnabled(boolean on) {
        audioEnabled = on;
    }

    public void setEncryptionEnabled(boolean on) {
        encryptionEnabled = on;
    }

    public UdpClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return running.get();
    }

    /** Bind the socket and send the handshake. Never blocks on the network. */
    public synchronized void connect(String host, int port) throws IOException {
        disconnect();

        UiLog.i(TAG, "connect(): resolving " + host);
        InetAddress addr = InetAddress.getByName(host);
        UiLog.i(TAG, "connect(): resolved " + addr.getHostAddress());
        serverAddr = new InetSocketAddress(addr, port);

        socket = new DatagramSocket();
        // The phone must absorb short bursts of video + FEC parity + control
        // (pings/retransmits); a too-tiny receive buffer overflows and drops
        // heartbeat PINGs, which corrupts the RTT measurement and triggers ARQ
        // storms. 512KB was too small at high bitrate, so use 2MB.
        socket.setReceiveBufferSize(2 * 1024 * 1024);
        socket.setSendBufferSize(512 * 1024);
        socket.setSoTimeout(1000);

        running.set(true);
        outQueue.clear();
        pendingHeartbeatTs.clear();
        receiverThread = new Thread(this::receiverLoop, "UDP-Receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
        senderThread = new Thread(this::senderLoop, "UDP-Sender");
        senderThread.setDaemon(true);
        senderThread.start();

        UiLog.i(TAG, "connect(): sending handshake");
        sendHandshake();
        UiLog.i(TAG, "connect(): handshake sent, waiting...");
        UiLog.i(TAG, "connected to " + host + ":" + port);
    }

    public synchronized void disconnect() {
        running.set(false);
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (receiverThread != null) {
            try {
                receiverThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            receiverThread = null;
        }
        if (senderThread != null) {
            senderThread.interrupt();
            try {
                senderThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            senderThread = null;
        }
        outQueue.clear();
        pendingHeartbeatTs.clear();
        cur = null;
        fallback = null;
        stopAudio();
    }

    private void startAudio() {
        if (!audioEnabled) return;
        AudioPlayer ap = audioPlayer;
        if (ap == null) {
            ap = new AudioPlayer();
            audioPlayer = ap;
        }
        final AudioPlayer target = ap;
        // Run audio setup (AudioTrack) and playback on a dedicated thread so it
        // can never stall the UDP receiver thread or the UI.
        new Thread(target::start, "Audio-Start").start();
    }

    private void stopAudio() {
        AudioPlayer ap = audioPlayer;
        audioPlayer = null;
        if (ap != null) ap.stop();
    }

    // ------------------------------------------------------------------
    // Outbound
    // ------------------------------------------------------------------

    /** Build a single UDP datagram: PacketHeader + payload, checksum filled. */
    private byte[] buildPacket(byte type, ByteBuffer payload) {
        int ps = (payload != null && payload.position() > 0)
                ? payload.position() : 0;
        ByteBuffer buf = Proto.buildPacket(type, ps);
        if (ps > 0) {
            buf.put(payload.array(), 0, ps);
        }
        Proto.fillChecksum(buf);
        int total = Proto.HEADER_SIZE + ps;
        byte[] out = new byte[total];
        System.arraycopy(buf.array(), 0, out, 0, total);
        return out;
    }

    private boolean send(byte[] data) {
        DatagramSocket s = socket;
        if (s == null || !running.get()) return false;
        return outQueue.add(data);
    }

    /**
     * Dedicated sender thread: DatagramSocket.send is a syscall that Android
     * forbids on the main thread (NetworkOnMainThreadException), and RemoteView
     * dispatches touch/input calls on the UI thread. Everything outbound is
     * pushed on the FIFO queue here and sent off the UI thread.
     */
    private void senderLoop() {
        while (running.get()) {
            byte[] data = outQueue.poll();
            if (data == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                continue;
            }
            DatagramSocket s = socket;
            if (s == null || serverAddr == null) continue;
            try {
                DatagramPacket p = new DatagramPacket(
                        data, data.length, serverAddr.getAddress(), serverAddr.getPort());
                synchronized (this) {
                    s.send(p);
                }
            } catch (IOException e) {
                releaseConnection("Send failed: " + e.getMessage());
                break;
            }
        }
    }

    public void sendHandshake() {
        ByteBuffer payload = ByteBuffer.allocate(Proto.HANDSHAKE_PAYLOAD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(0); // screen width  (unknown yet)
        payload.putInt(0); // screen height
        payload.putInt(0); // fps
        payload.putInt(0); // encoder id
        payload.put(new byte[32]); // session key (server ignores)
        payload.put((byte) (fecEnabled ? Proto.FEC_DEFAULT_LEVEL : 0));
        payload.put((byte) (audioEnabled ? 1 : 0));
        payload.put((byte) (encryptionEnabled ? 1 : 0));
        send(buildPacket(Proto.TYPE_HANDSHAKE, payload));
    }

    public void sendKeyframeRequest() {
        send(buildPacket(Proto.TYPE_KEYFRAME_REQ, null));
    }

    /**
     * Send a HEARTBEAT_PING carrying our current timestamp. The server echoes
     * it back as HEARTBEAT_PONG (see udp_server.cpp); matching the echo
     * against {@link #pendingHeartbeatTs} yields the round-trip latency.
     */
    public void sendHeartbeat() {
        long ts = nowUs();
        pendingHeartbeatTs.addLast(ts);
        while (pendingHeartbeatTs.size() > 16) pendingHeartbeatTs.pollFirst();
        ByteBuffer p = ByteBuffer.allocate(HEARTBEAT_PAYLOAD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        p.putLong(ts);
        send(buildPacket(Proto.TYPE_HEARTBEAT_PING, p));
    }

    public void sendDisconnect() {
        send(buildPacket(Proto.TYPE_DISCONNECT, null));
    }

    public void sendMouseMove(int x, int y) {
        ByteBuffer p = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(x).putInt(y);
        send(buildPacket(Proto.TYPE_MOUSE_MOVE, p));
    }

    public void sendMouseButton(int x, int y, int button, boolean pressed) {
        ByteBuffer p = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(x).putInt(y);
        p.put((byte) button);
        p.put((byte) (pressed ? 1 : 0));
        send(buildPacket(Proto.TYPE_MOUSE_BUTTON, p));
    }

    public void sendMouseScroll(int x, int y, int dx, int dy) {
        ByteBuffer p = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(x).putInt(y);
        p.putInt(dx).putInt(dy);
        send(buildPacket(Proto.TYPE_MOUSE_SCROLL, p));
    }

    public void sendKeyEvent(int keysym, boolean pressed) {
        ByteBuffer p = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(0); // keycode unused
        p.put((byte) (pressed ? 1 : 0));
        p.putInt(keysym);
        send(buildPacket(Proto.TYPE_KEYBOARD_EVENT, p));
    }

    // ------------------------------------------------------------------
    // Receiver
    // ------------------------------------------------------------------

    private void receiverLoop() {
        byte[] buf = new byte[Proto.MAX_PACKET_SIZE];
        while (running.get()) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(pkt);
                maintenance();
            } catch (SocketTimeoutException e) {
                maintenance();
                continue; // poll the running flag
            } catch (IOException e) {
                if (running.get()) {
                    releaseConnection("Network error: " + e.getMessage());
                }
                return;
            }
            if (!running.get()) return;
            handleDatagram(pkt.getData(), pkt.getLength());
        }
    }

    private void handleDatagram(byte[] data, int len) {
        if (len < Proto.HEADER_SIZE) return;

        ByteBuffer hb = ByteBuffer.wrap(data, 0, Proto.MAX_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        int magic = hb.getShort() & 0xFFFF;
        byte type = hb.get();
        if (magic != Proto.MAGIC && magic != Proto.MAGIC_ENCRYPTED) return;
        boolean encrypted = magic == Proto.MAGIC_ENCRYPTED;

        if (type == Proto.TYPE_VIDEO_FEC_PARITY) {
            if (encrypted) {
                byte[] nb = Crypto.decryptPacket(data, len, Proto.FEC_HEADER_SIZE);
                if (nb == null) return;
                // Header payload_length still describes the seal, but the
                // buffer now holds plaintext-only: fix the field so the
                // checksum/size checks in handleFecParity pass.
                ByteBuffer wb = ByteBuffer.wrap(nb).order(ByteOrder.LITTLE_ENDIAN);
                wb.putShort(20, (short) (nb.length - Proto.FEC_HEADER_SIZE));
                handleFecParity(nb, nb.length);
            } else {
                handleFecParity(data, len);
            }
            return;
        }

        hb.position(3);
        int sequence = hb.getInt();
        int frameId = hb.getInt();
        int fragmentIndex = hb.getShort() & 0xFFFF;
        int totalFragments = hb.getShort() & 0xFFFF;
        int payloadLength = hb.getShort() & 0xFFFF;
        int checksum = hb.getInt();

        if (payloadLength != len - Proto.HEADER_SIZE) return;

        switch (type) {
            case Proto.TYPE_HANDSHAKE_ACK:
                startAudio();
                listener.onConnected();
                break;

            case Proto.TYPE_AUDIO_FRAME: {
                AudioPlayer ap = audioPlayer;
                if (ap == null || payloadLength <= 0) break;
                if (encrypted) {
                    byte[] nb = Crypto.decryptPacket(data, len, Proto.HEADER_SIZE);
                    if (nb == null) break;
                    int pl = nb.length - Proto.HEADER_SIZE;
                    if ((checksum & 0xFFFFFFFFL) != Proto.computeChecksum(nb,
                            Proto.HEADER_SIZE, pl)) break;
                    ap.feed(sequence, nb, Proto.HEADER_SIZE, pl);
                } else {
                    if ((checksum & 0xFFFFFFFFL) != Proto.computeChecksum(data,
                            Proto.HEADER_SIZE, payloadLength)) break;
                    ap.feed(sequence, data, Proto.HEADER_SIZE, payloadLength);
                }
                break;
            }

            case Proto.TYPE_VIDEO_FRAME:
                if (encrypted) {
                    byte[] nb = Crypto.decryptPacket(data, len, Proto.HEADER_SIZE);
                    if (nb == null) break;
                    handleVideoFrame(nb, frameId, fragmentIndex, totalFragments,
                            nb.length - Proto.HEADER_SIZE, checksum);
                } else {
                    handleVideoFrame(data, frameId, fragmentIndex, totalFragments,
                            payloadLength, checksum);
                }
                break;

            case Proto.TYPE_DISCONNECT:
                releaseConnection("Server disconnected");
                break;

            case Proto.TYPE_HEARTBEAT_PING:
                // Server RTT probe: echo the payload back as a PONG.
                if (payloadLength == HEARTBEAT_PAYLOAD_SIZE
                        && len - Proto.HEADER_SIZE >= HEARTBEAT_PAYLOAD_SIZE) {
                    ByteBuffer echo = Proto.buildPacket(
                            Proto.TYPE_HEARTBEAT_PONG, HEARTBEAT_PAYLOAD_SIZE);
                    echo.put(data, Proto.HEADER_SIZE, HEARTBEAT_PAYLOAD_SIZE);
                    Proto.fillChecksum(echo);
                    byte[] out = new byte[Proto.HEADER_SIZE + HEARTBEAT_PAYLOAD_SIZE];
                    System.arraycopy(echo.array(), 0, out, 0, out.length);
                    send(out);
                }
                break;

            case Proto.TYPE_HEARTBEAT_PONG:
                if (payloadLength == HEARTBEAT_PAYLOAD_SIZE
                        && len - Proto.HEADER_SIZE >= HEARTBEAT_PAYLOAD_SIZE) {
                    long tsUs = ByteBuffer.wrap(data, Proto.HEADER_SIZE,
                            HEARTBEAT_PAYLOAD_SIZE)
                            .order(ByteOrder.LITTLE_ENDIAN).getLong();
                    if (consumePendingHeartbeat(tsUs)) {
                        listener.onHeartbeat(tsUs);
                    }
                }
                break;

            case Proto.TYPE_HEARTBEAT:
                // Legacy server echo of our old-style heartbeat; keep measuring
                // RTT the same way for compatibility with older servers.
                if (payloadLength == HEARTBEAT_PAYLOAD_SIZE
                        && len - Proto.HEADER_SIZE >= HEARTBEAT_PAYLOAD_SIZE) {
                    long tsUs = ByteBuffer.wrap(data, Proto.HEADER_SIZE,
                            HEARTBEAT_PAYLOAD_SIZE)
                            .order(ByteOrder.LITTLE_ENDIAN).getLong();
                    listener.onHeartbeat(tsUs);
                }
                break;

            default:
                break; // keyframe-ack-less control, unknown
        }
    }

    /** Drop stale pings, then match a PONG timestamp against an outstanding
     *  ping of ours. Returns false for unmatched (duplicate/lost) echoes. */
    private boolean consumePendingHeartbeat(long tsUs) {
        long now = nowUs();
        while (!pendingHeartbeatTs.isEmpty()
                && now - pendingHeartbeatTs.peekFirst() > 3_000_000) {
            pendingHeartbeatTs.pollFirst();
        }
        return pendingHeartbeatTs.remove(tsUs);
    }

    /** Parse the 29-byte FEC parity header and feed the parity symbol to the
     *  reassembler (checksum lives at offset 21 in FecPacketHeader). */
    private void handleFecParity(byte[] data, int len) {
        if (!fecEnabled) return; // no FEC negotiated (handshake fec_level=0)
        if (len < Proto.FEC_HEADER_SIZE) return;
        ByteBuffer fb = ByteBuffer.wrap(data, 0, Proto.FEC_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        fb.getShort();                    // magic (checked above)
        fb.get();                         // type
        fb.getInt();                      // sequence
        int frameId = fb.getInt();
        int blockIndex = fb.getShort() & 0xFFFF;
        int blockData = fb.getShort() & 0xFFFF;
        int parityIndex = fb.getShort() & 0xFFFF;
        int parityCount = fb.getShort() & 0xFFFF;
        int payloadLength = fb.getShort() & 0xFFFF;
        int checksum = fb.getInt();
        int frameDataLength = fb.getInt();

        if (payloadLength != len - Proto.FEC_HEADER_SIZE) return;
        if (payloadLength > 0) {
            long sum = Proto.computeChecksum(data, Proto.FEC_HEADER_SIZE,
                    payloadLength);
            if (sum != (checksum & 0xFFFFFFFFL)) {
                UiLog.w(TAG, "FEC checksum failed frame=" + frameId);
                return;
            }
        }

        byte[] sym = new byte[payloadLength];
        System.arraycopy(data, Proto.FEC_HEADER_SIZE, sym, 0, payloadLength);
        addFecParity(frameId, blockIndex, blockData, parityIndex,
                parityCount, frameDataLength, sym);
    }

    private void handleVideoFrame(byte[] data, int frameId, int fragmentIndex,
                                  int totalFragments, int payloadLength, int checksum) {
        // Empty-payload control frames (handshake ack) have checksum 0; video
        // fragments always carry a real payload checksum.
        if (payloadLength > 0) {
            long sum = Proto.computeChecksum(data, Proto.HEADER_SIZE, payloadLength);
            if (sum != (checksum & 0xFFFFFFFFL)) {
                UiLog.w(TAG, "checksum failed frame=" + frameId
                        + " frag=" + fragmentIndex);
                return;
            }
        }

        if (totalFragments <= 0 || fragmentIndex >= totalFragments ||
                totalFragments > MAX_TOTAL_FRAGMENTS) return;

        byte[] frag = new byte[payloadLength];
        System.arraycopy(data, Proto.HEADER_SIZE, frag, 0, payloadLength);
        addVideoFragment(frameId, fragmentIndex, totalFragments, frag);
    }

    // ------------------------------------------------------------------
    // FEC-first reassembly
    // ------------------------------------------------------------------

    private void addVideoFragment(int frameId, int fragIndex, int total, byte[] frag) {
        FrameState t = null;
        boolean newer = cur == null || cur.id < frameId;
        if (newer) {
            // Newer frame (or first): finalize whatever was being assembled.
            finalizeFrame(cur);
            FrameState ns = new FrameState(frameId);
            cur = ns;
            t = ns;
        } else if (cur.id == frameId) {
            t = cur;
        } else if (fallback != null && fallback.id == frameId) {
            t = fallback;
        }
        if (t == null) return; // stale fragment

        if (t.total != total) {
            t.total = total;
            t.frags = new byte[total][];
            t.have = new boolean[total];
            t.received = 0;
        }
        if (t.have[fragIndex]) return; // duplicate
        t.frags[fragIndex] = frag;
        t.have[fragIndex] = true;
        t.received++;
        t.lastActivityUs = nowUs();

        // Data may complete a block that already has parity symbols: FEC
        // first, whether assembling the newest frame or repairing the
        // fallback one with late-arriving data.
        if (t != null) tryFecRecover(t);
        if (complete(t)) {
            deliverFrame(t);
            if (t == fallback) fallback = null;
        }
    }

    private void addFecParity(int frameId, int blockIndex, int blockData,
                              int parityIndex, int parityCount,
                              int frameDataLength, byte[] sym) {
        // Parity can repair the current frame or the fallback frame: a superseded
        // frame's parity is sent right after its data, so discarding it (as the
        // old code did) turned FEC-recoverable losses into forced ARQ round trips.
        FrameState f = (cur != null && cur.id == frameId) ? cur
                : (fallback != null && fallback.id == frameId) ? fallback : null;
        if (f == null) return; // parity for an already-completed/dropped frame
        if (blockData <= 0 || blockData > Proto.FEC_BLOCK_DATA ||
                parityCount <= 0 || parityCount > Proto.FEC_MAX_PARITY ||
                parityIndex >= parityCount) return;

        FecBlock blk = f.blocks.get(blockIndex);
        if (blk == null) {
            blk = new FecBlock();
            f.blocks.put(blockIndex, blk);
        }
        blk.K = blockData;
        blk.P = parityCount;
        blk.ensure(parityIndex);
        blk.rows.set(parityIndex, sym);
        f.frameDataLength = frameDataLength;
        f.frameDataLengthKnown = true;
        f.lastActivityUs = nowUs();
        statParityReceived++;

        // FEC first: repair the block with the parity that just landed. ARQ is
        // deliberately NOT attempted here - the frame may still be assembling
        // (missing fragments can simply be in flight), so sending a retransmit
        // on every parity arrival is what caused the ARQ storms. Decisions are
        // made once the frame is settled (finalizeFrame / maintenance).
        tryFecRecover(f);
        if (complete(f)) {
            deliverFrame(f);
            if (f == fallback) fallback = null;
        }
    }

    /** Recover every FEC block that has parity symbols and at least K symbols
     *  (data + parity) present. Blocks already complete are untouched. */
    private void tryFecRecover(FrameState f) {
        if (f == null || complete(f)) return;
        for (Map.Entry<Integer, FecBlock> e : f.blocks.entrySet()) {
            FecBlock blk = e.getValue();
            int b = e.getKey();
            int base = b * Proto.FEC_BLOCK_DATA;
            if (base + blk.K > f.total) continue; // block beyond frame

            int present = 0;
            int missing = 0;
            for (int c = 0; c < blk.K; c++) {
                if (f.have[base + c]) present++;
                else missing++;
            }
            if (missing == 0) continue;

            // Parity symbols may arrive out of order (UDP reordering), so the
            // rows list can contain null holes (ensure() pads every slot up to
            // the highest index seen). Only count/use the symbols actually
            // received; otherwise get(0) nulls crash on .length and null rows
            // would be fed into the Reed-Solomon recoverer.
            int haveParity = 0;
            int paddedLen = -1;
            for (byte[] row : blk.rows) {
                if (row == null) continue;
                haveParity++;
                if (paddedLen < 0) paddedLen = row.length;
            }
            if (haveParity == 0 || missing > haveParity) continue;
            if (present + haveParity < blk.K) continue;
            if (paddedLen <= 0) continue;

            List<byte[]> recv = new ArrayList<>(blk.K);
            List<Integer> rows = new ArrayList<>(blk.K);
            for (int c = 0; c < blk.K; c++) {
                if (f.have[base + c]) {
                    byte[] sym = new byte[paddedLen];
                    byte[] frag = f.frags[base + c];
                    if (frag != null) {
                        int n = Math.min(frag.length, paddedLen);
                        System.arraycopy(frag, 0, sym, 0, n);
                    }
                    recv.add(sym);
                    rows.add(c);
                }
            }
            for (int j = 0; j < blk.rows.size(); j++) {
                if (blk.rows.get(j) == null) continue; // never-received parity
                recv.add(blk.rows.get(j));
                rows.add(blk.K + j);
            }

            int[] rArr = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) rArr[i] = rows.get(i);
            List<byte[]> recovered = Fec.recoverBlock(blk.K, blk.P, rArr, recv,
                    paddedLen);
            if (recovered == null) {
                UiLog.w(TAG, "FEC decode FAILED frame=" + f.id + " block=" + b
                        + " K=" + blk.K + " P=" + blk.P
                        + " symbols=" + rArr.length);
                continue;
            }

            int repaired = 0;
            for (int c = 0; c < blk.K; c++) {
                int idx = base + c;
                if (!f.have[idx]) {
                    f.frags[idx] = recovered.get(c);
                    f.have[idx] = true;
                    f.received++;
                    f.lastActivityUs = nowUs();
                    repaired++;
                }
            }
            if (repaired > 0) {
                statFecBlocksRecovered++;
                statFecFragsRecovered += repaired;
                UiLog.i(TAG, "FEC recovered frame=" + f.id + " block=" + b
                        + " frags=" + repaired + " (blocks=" + statFecBlocksRecovered
                        + " frags=" + statFecFragsRecovered + ")");
            }
            if (complete(f)) return;
        }
    }

    /** Ship the current frame to ARQ (single fallback slot), or drop it if
     *  already complete. Called when a newer frame arrives or the stream
     *  stalls. The frame keeps accepting data and parity so FEC can still
     *  complete it; only irrecoverable fragments are retransmit-requested. */
    private void finalizeFrame(FrameState f) {
        if (f == null || f.total == 0) return;
        if (complete(f)) return;
        tryFecRecover(f);
        if (complete(f)) return;
        if (!arqEnabled) return; // ARQ off: drop frames FEC cannot repair
        fallback = f;
        if (f.arqSettleUs == 0) f.arqSettleUs = nowUs(); // start settle/grace timer
        maybeArq(f);
    }

    /**
     * Request a retransmit only for fragments that FEC can never repair.
     *
     * The server transmits every FEC block's P parity symbols immediately
     * after the frame's data fragments, so a loss is FEC-coverable whenever
     * the block lost at most P fragments. Requesting a retransmit for those
     * would add a full RTT (and congestion-driven delay) for nothing, because
     * the parity that lets the client rebuild the loss is already in flight.
     * A retransmit is issued only when FEC is exhausted for a block:
     *   - more fragments lost than parity can cover (missing > P),
     *   - all P parity symbols arrived and the block still did not decode,
     *   - the frame has been silent long enough that its parity won't arrive.
     */
    private void maybeArq(FrameState f) {
        if (f == null || complete(f)) return;
        if (!arqEnabled) return; // ARQ toggled off: FEC-only recovery
        long now = nowUs();

        // The server throttles retransmit requests to one per ~20ms; spamming
        // faster just saturates the pipe. Never send more often than the
        // server can serve (and total sends are bounded by ARQ_MAX_ROUNDS).
        if (f.arqReqUs != 0 && now - f.arqReqUs < ARQ_MIN_SEND_US) return;

        // A frame only becomes provably "settled" once it was superseded /
        // stalled (arqSettleUs set). Before that, missing fragments may simply
        // still be in flight and must not be assumed lost.
        boolean parityGaveUp = f.arqSettleUs != 0
                && now - f.arqSettleUs > ARQ_SETTLE_US;

        List<int[]> missing = new ArrayList<>();
        int nb = (f.total + Proto.FEC_BLOCK_DATA - 1) / Proto.FEC_BLOCK_DATA;
        for (int b = 0; b < nb; b++) {
            int base = b * Proto.FEC_BLOCK_DATA;
            int k = Math.min(Proto.FEC_BLOCK_DATA, f.total - base);
            if (k <= 0) continue;

            List<Integer> miss = new ArrayList<>();
            for (int c = 0; c < k; c++) {
                if (!f.have[base + c]) miss.add(base + c);
            }
            if (miss.isEmpty()) continue;

            FecBlock blk = f.blocks.get(b);
            int p = (blk != null && blk.P > 0) ? blk.P : Proto.FEC_DEFAULT_LEVEL;

            if (miss.size() > p) {
                // Even with all p parity symbols the block cannot reach k
                // symbols: FEC can never repair these - ask for them.
                for (int idx : miss) missing.add(new int[] { f.id, idx });
            } else if (blk != null && blk.completeParity()) {
                // All p parity arrived; tryFecRecover already attempted the
                // decode and it failed - FEC is exhausted, ask for them.
                for (int idx : miss) missing.add(new int[] { f.id, idx });
            } else if (parityGaveUp) {
                // The frame has been settled (data+parity fully in flight) long
                // enough that the missing parity won't arrive either; waiting
                // only stalls the frame - ask for them.
                for (int idx : miss) missing.add(new int[] { f.id, idx });
            }
            // Otherwise missing <= p and parity is still coming: FEC will
            // reconstruct the block once the parity symbols land. No ARQ.
        }

        if (missing.isEmpty()) return;
        sendArq(f, missing);
    }

    private void sendArq(FrameState f, List<int[]> missing) {
        if (missing.isEmpty()) return;
        ByteBuffer payload = ByteBuffer.allocate(missing.size() * 6)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int[] m : missing) {
            payload.putInt(m[0]);   // frame_id
            payload.putShort((short) m[1]); // fragment_index
        }
        f.arqReqUs = nowUs();
        f.arqRounds++;
        statArqRequests++;
        send(buildPacket(Proto.TYPE_RETRANSMIT_REQ, payload));
        UiLog.w(TAG, "ARQ req frame=" + f.id + " missing=" + missing.size()
                + " round=" + f.arqRounds);
    }

    /** Timeout maintenance: finalize a stalled current frame, keep repairing
     *  the fallback frame with FEC, re-request only what FEC cannot cover
     *  (bounded rounds), give up on too-old fallbacks. */
    private void maintenance() {
        long now = nowUs();

        // Latency ping: heartbeats are echoed by the server (see udp_server.cpp).
        if (now - lastHeartbeatUs >= HEARTBEAT_INTERVAL_US) {
            sendHeartbeat();
            lastHeartbeatUs = now;
        }

        if (cur != null && cur.total > 0 && !complete(cur) &&
                now - cur.lastActivityUs > ARQ_STALL_US) {
            finalizeFrame(cur);
            cur = null;
        }
        if (fallback != null && !complete(fallback)) {
            long sinceSettle = fallback.arqSettleUs != 0
                    ? now - fallback.arqSettleUs : 0;
            if (sinceSettle > 2_000_000) {
                UiLog.w(TAG, "giving up on frame " + fallback.id
                        + " (" + (fallback.total - fallback.received) + " missing)");
                fallback = null;
                return;
            }
            // Late parity can still repair the frame; re-run FEC before
            // considering retransmits.
            tryFecRecover(fallback);
            if (complete(fallback)) {
                deliverFrame(fallback);
                fallback = null;
            } else if (fallback.arqRounds < ARQ_MAX_ROUNDS) {
                maybeArq(fallback);
            }
        }

        // Periodic recovery summary so FEC total works are visible in logcat.
        if (now - lastStatsLogUs >= 1_000_000) {
            lastStatsLogUs = now;
            UiLog.i(TAG, "stats parity=" + statParityReceived
                    + " fecBlocks=" + statFecBlocksRecovered
                    + " fecFrags=" + statFecFragsRecovered
                    + " arq=" + statArqRequests);
        }
    }

    private static boolean complete(FrameState f) {
        return f.total > 0 && f.received == f.total;
    }

    /** Deliver a completed frame; the last recovered fragment is trimmed to
     *  its true length (frame bytes - (T-1)*MAX_PAYLOAD) when known. */
    private void deliverFrame(FrameState f) {
        if (f == null || !complete(f)) return;

        int last = f.total - 1;
        int lastTrue = 0;
        if (f.frameDataLengthKnown) {
            int prefix = last * Proto.MAX_PAYLOAD_SIZE;
            lastTrue = f.frameDataLength > prefix ? f.frameDataLength - prefix : 0;
        }

        int[] lens = new int[f.total];
        int size = 0;
        for (int i = 0; i < f.total; i++) {
            int n = f.frags[i] == null ? 0 : f.frags[i].length;
            if (f.frameDataLengthKnown && i == last && lastTrue > 0 && lastTrue < n) {
                n = lastTrue;
            }
            lens[i] = n;
            size += n;
        }

        byte[] au = new byte[size];
        int off = 0;
        for (int i = 0; i < f.total; i++) {
            System.arraycopy(f.frags[i], 0, au, off, lens[i]);
            off += lens[i];
        }

        boolean hasSps = containsSps(au);
        f.reset();
        listener.onVideoFrame(au, hasSps);
    }

    private void releaseConnection(String reason) {
        synchronized (this) {
            if (!running.get()) return;
            running.set(false);
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
        stopAudio();
        UiLog.i(TAG, "disconnected: " + reason);
        listener.onDisconnected(reason);
    }

    /** Monotonic clock (ms since boot, includes deep sleep) so RTT samples are
     *  never distorted by wall-clock jumps (NTP, manual change). The same clock
     *  must be used to measure RTT on the receiving side (MainActivity). */
    private static long nowUs() {
        return android.os.SystemClock.elapsedRealtime() * 1000L;
    }

    private static boolean containsSps(byte[] data) {
        for (int i = 2; i < data.length - 3; i++) {
            if ((data[i] & 0xFF) == 0 && (data[i + 1] & 0xFF) == 0 &&
                    (data[i + 2] & 0xFF) == 1) {
                int nalType = data[i + 3] & 0x1F;
                if (nalType == 7) return true; // SPS
            }
        }
        return false;
    }

    private static final class FecBlock {
        int K;
        int P;
        final List<byte[]> rows = new ArrayList<>();

        void ensure(int index) {
            while (rows.size() <= index) rows.add(null);
        }

        int received() {
            int n = 0;
            for (byte[] r : rows) if (r != null) n++;
            return n;
        }

        /** True when every one of the P parity symbols for this block landed,
         *  i.e. the block's FEC capability is fully provisioned. */
        boolean completeParity() {
            return P > 0 && received() >= P;
        }
    }

    private static final class FrameState {
        final int id;
        int total = 0;
        int received = 0;
        byte[][] frags;
        boolean[] have;
        long lastActivityUs;
        int frameDataLength = 0;
        boolean frameDataLengthKnown = false;
        long arqReqUs = 0;     // when ARQ was last sent for this frame (0 = never)
        long arqSettleUs = 0;  // when the frame was superseded/stalled (0 = still current)
        int arqRounds = 0;
        final Map<Integer, FecBlock> blocks = new HashMap<>();

        FrameState(int id) {
            this.id = id;
        }

        void reset() {
            total = 0;
            received = 0;
            frags = null;
            have = null;
            frameDataLengthKnown = false;
            blocks.clear();
        }
    }
}