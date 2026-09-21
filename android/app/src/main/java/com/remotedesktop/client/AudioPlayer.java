package com.remotedesktop.client;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayDeque;

/**
 * Plays the server's raw PCM stream (48 kHz stereo, 16-bit, 5 ms frames)
 * straight through an AudioTrack.
 *
 * The server used to send Opus, but this device's out-of-process Codec2 Opus
 * decoder hangs inside the media.codec service (MediaCodec.native_setup never
 * returns), which freezes every later codec allocation - including video - and
 * triggers an ANR. Audio now bypasses MediaCodec entirely and is handled on its
 * own thread so it can never block the UI or the UDP receiver.
 *
 * Packets arrive from the UDP receiver thread and are queued; a playback thread
 * primes a small jitter buffer, then writes each frame with a blocking write so
 * AudioTrack paces playback and never starves on normal network jitter. If the
 * queue drains, playback re-primes instead of chasing a permanent underrun.
 */
public final class AudioPlayer {
    private static final String TAG = "AudioPlayer";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAME_SAMPLES = 240;   // 5 ms per channel
    private static final int FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2;
    private static final int FRAME_US = 5000;       // 5 ms PCM frame
    private static final int PRIME_FRAMES = 16;     // ~80 ms of jitter buffer
    private static final int MAX_QUEUE = 200;       // ~1 s hard cap

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long lastSeq = -1;
    private boolean primed = false;

    private long rxFrames = 0;
    private long playedFrames = 0;
    private long underruns = 0;
    private long droppedFrames = 0;
    private long lastStatsMs = 0;

    private volatile AudioTrack track;
    private Thread thread;
    private volatile boolean running = false;

    public synchronized void start() {
        if (running) return;
        try {
            UiLog.i(TAG, "start: getMinBufferSize");
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(minBuf, FRAME_BYTES * PRIME_FRAMES);
            UiLog.i(TAG, "start: build AudioTrack");
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            UiLog.i(TAG, "start: track.play");
            t.play();
            track = t;
            UiLog.i(TAG, "start: track playing");

            synchronized (queue) {
                queue.clear();
            }
            lastSeq = -1;
            primed = false;
            running = true;
            thread = new Thread(this::loop, "Audio-Playback");
            thread.setDaemon(true);
            thread.start();
            UiLog.i(TAG, "audio started (pcm " + SAMPLE_RATE + "Hz x" + CHANNELS
                    + ", buf=" + bufBytes + "B)");
        } catch (Exception e) {
            UiLog.e(TAG, "audio start failed", e);
            releaseTrack();
        }
    }

    /** Called from the UDP receiver thread; copies the payload into the queue. */
    public void feed(int seq, byte[] data, int offset, int length) {
        if (!running || length <= 0) return;
        if (lastSeq != -1 && seq <= lastSeq) return; // stale/duplicate
        lastSeq = seq;

        byte[] pkt = new byte[length];
        System.arraycopy(data, offset, pkt, 0, length);
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
                droppedFrames++;
            }
            queue.addLast(pkt);
            queue.notifyAll();
        }
        rxFrames++;
        if (rxFrames == 1) {
            UiLog.i(TAG, "feed: first audio frame seq=" + seq + " len=" + length);
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        synchronized (queue) {
            queue.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        releaseTrack();
        UiLog.i(TAG, "audio stopped");
    }

    private void loop() {
        while (running) {
            byte[] pkt;
            synchronized (queue) {
                if (queue.isEmpty() || (!primed && queue.size() < PRIME_FRAMES)) {
                    if (primed && queue.isEmpty()) {
                        primed = false;
                        underruns++;
                    }
                    try {
                        queue.wait(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                primed = true;
                pkt = queue.pollFirst();
            }

            AudioTrack t = track;
            if (t == null) break;
            try {
                t.write(pkt, 0, pkt.length, AudioTrack.WRITE_BLOCKING);
                playedFrames++;
            } catch (IllegalStateException e) {
                UiLog.e(TAG, "AudioTrack write failed", e);
                break;
            }

            long now = System.currentTimeMillis();
            if (lastStatsMs == 0) lastStatsMs = now;
            if (now - lastStatsMs >= 2000) {
                lastStatsMs = now;
                int qsize;
                synchronized (queue) {
                    qsize = queue.size();
                }
                UiLog.i(TAG, "audio stats rx=" + rxFrames + " played=" + playedFrames
                        + " underruns=" + underruns + " dropped=" + droppedFrames
                        + " queue=" + qsize);
            }
        }
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
        }
        synchronized (queue) {
            queue.clear();
        }
    }
}
