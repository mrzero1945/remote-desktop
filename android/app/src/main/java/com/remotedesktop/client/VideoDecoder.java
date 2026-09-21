package com.remotedesktop.client;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-latency H.264 decoder backed by MediaCodec in BUFFER mode.
 *
 * The MTK hardware surface path renders this stream without chroma (all-grey,
 * or green with the software decoder), so we never target a Surface. The
 * codec outputs raw YUV and we convert YUV -> ARGB explicitly in pure Java,
 * giving full, correct colors.
 */
public final class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final String MIME = "video/avc";
    private static final int INPUT_TIMEOUT_US = 10_000;
    private static final int OUTPUT_TIMEOUT_US = 10_000;
    private static final int MAX_QUEUED_FRAMES = 2;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<byte[]> frameQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RenderTask> renderQueue = new ConcurrentLinkedQueue<>();
    private static final int MAX_PENDING = 2;
    private final AtomicBoolean receivedKeyframe = new AtomicBoolean(false);

    private volatile MediaCodec codec;
    private Thread decodeThread;
    private Thread outputThread;
    private Thread renderThread;
    private boolean csdConfigured = false;
    private long ptsCounterUs = 0;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private final AtomicInteger queuedIn = new AtomicInteger(0);
    private final AtomicInteger renderedOut = new AtomicInteger(0);
    private byte[] csdSps;
    private byte[] csdPps;

    // A fresh MTK decoder with the intra-refresh stream still outputs nothing
    // until a real IDR arrives. Ask the server for one immediately (no
    // "repairing" throttle) whenever the pipeline is (re)built.
    private volatile Runnable keyframeRequester;

    // Watchdog against a silently-stuck decoder: if frames are flowing in but
    // nothing renders for a while, rebuild the pipeline on a fresh codec.
    private volatile long lastInputMs = SystemClock.elapsedRealtime();
    private volatile long lastOutputMs = SystemClock.elapsedRealtime();
    private volatile long lastRestartMs = 0;

    public interface SizeListener {
        void onSizeChanged(int width, int height);
    }

    public interface FrameRenderer {
        void onFrame(Bitmap frame);
    }

    /** GPU path: receives raw YUV frames for on-GPU conversion instead of the
     *  Java conversion + Bitmap renderer. Runs on the render thread. */
    public interface YuvRenderer {
        void renderYuv(Image image, boolean semiplanar);
        void detach();
    }

    private SizeListener sizeListener;
    private volatile FrameRenderer frameRenderer;
    private volatile YuvRenderer yuvRenderer;

    private int colorFormat = ColorFormatUtil.FORMAT_SEMIPLANAR;

    public void setSizeListener(SizeListener l) {
        sizeListener = l;
    }

    public void setFrameRenderer(FrameRenderer r) {
        frameRenderer = r;
    }

    public void setYuvRenderer(YuvRenderer r) {
        yuvRenderer = r;
    }

    public void setKeyframeRequester(Runnable r) {
        keyframeRequester = r;
    }

    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }

    public boolean start() {
        if (running.get()) return true;

        receivedKeyframe.set(false);

        // MTK C2 codecs (c2.mtk.avc.decoder) deadlock configure/start when the
        // media codec is driven from a thread without a Looper. Create the
        // codec on a dedicated Looper thread and hand all blocking setup to it.
        final HandlerThread setupThread = new HandlerThread("Codec-Setup");
        setupThread.start();
        final Handler handler = new Handler(setupThread.getLooper());
        final Object[] result = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);

        handler.post(() -> {
            try {
                MediaCodec c = createDecoder();
                MediaFormat fmt = MediaFormat.createVideoFormat(MIME, 1280, 720);
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
                c.configure(fmt, null, null, 0);
                c.start();
                c.flush();
                result[0] = c;
            } catch (Exception e) {
                result[0] = e;
            } finally {
                done.countDown();
            }
        });

        MediaCodec c = null;
        try {
            if (!done.await(8000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                UiLog.e(TAG, "codec setup timed out (8s)");
                result[0] = new java.io.IOException("codec setup timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result[0] = e;
        }
        setupThread.quitSafely();

        if (result[0] instanceof Exception) {
            UiLog.e(TAG, "Failed to start codec", (Exception) result[0]);
            release();
            return false;
        }
        if (result[0] == null || !(result[0] instanceof MediaCodec)) {
            UiLog.e(TAG, "Failed to start codec: null");
            release();
            return false;
        }
        c = (MediaCodec) result[0];
        codec = c;

        running.set(true);
        UiLog.i(TAG, "started codec=" + c.getName());
        requestKeyframe();
        decodeThread = new Thread(this::decodeLoop, "H264-Decode");
        decodeThread.start();
        outputThread = new Thread(this::outputLoop, "H264-Output");
        outputThread.start();
        renderThread = new Thread(this::renderLoop, "H264-Render");
        renderThread.start();
        return true;
    }

    public void restart() {
        requestKeyframe();
        try {
            release();
            MediaCodec c = createDecoder();
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME,
                    videoWidth > 0 ? videoWidth : 1280,
                    videoHeight > 0 ? videoHeight : 720);
            if (csdSps != null && csdPps != null) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csdSps));
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csdPps));
            }
            c.configure(fmt, null, null, 0);
            c.start();
            c.flush();
            codec = c;
            csdConfigured = true;
            lastRestartMs = SystemClock.elapsedRealtime();
            UiLog.i(TAG, "pipeline (re)started on fresh codec");
        } catch (Exception e) {
            UiLog.e(TAG, "fresh codec start failed", e);
            running.set(false);
        }
    }

    private void requestKeyframe() {
        Runnable r = keyframeRequester;
        if (r != null) r.run();
    }

    /** Feed one complete H.264 access unit. */
    public void queueFrame(byte[] data, boolean isKeyframe) {
        if (!running.get()) return;

        boolean isSync = isKeyframe || containsSps(data);
        if (isSync) {
            receivedKeyframe.set(true);
        } else if (!receivedKeyframe.get()) {
            return; // drop non-keyframes until we have sync
        }

        if (frameQueue.size() > MAX_QUEUED_FRAMES) {
            frameQueue.poll(); // decoder behind; drop oldest for low latency
        }
        frameQueue.add(data);
    }

    public void stop() {
        running.set(false);
        if (decodeThread != null) {
            try {
                decodeThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            decodeThread = null;
        }
        if (outputThread != null) {
            try {
                outputThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            outputThread = null;
        }
        if (renderThread != null) {
            try {
                renderThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            renderThread = null;
        }
        RenderTask t;
        while ((t = renderQueue.poll()) != null) dropRenderTask(t);
        frameQueue.clear();
        release();
    }

    private void release() {
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    private MediaCodec createDecoder() throws java.io.IOException {
        // Prefer a SOFTWARE H.264 decoder (c2.android.avc.decoder / OMX.google
        // h264) exactly like the reference desktop-viewer's libavcodec path.
        // The MTK hardware codec (c2.mtk.avc.decoder) hangs in configure/start
        // on this device, so a software decoder is our reliable fallback.
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String swName = null;
        String fallbackName = null;
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (ci.isEncoder()) continue;
            boolean supportsAvc = false;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(MIME)) {
                    supportsAvc = true;
                    break;
                }
            }
            if (!supportsAvc) continue;
            String n = ci.getName();
            if (ci.isSoftwareOnly()) {
                if (n.contains("c2.android") && n.contains("avc"))
                    return MediaCodec.createByCodecName(n);
                if (swName == null) swName = n;
            } else if (fallbackName == null) {
                fallbackName = n;
            }
        }
        if (swName != null)
            return MediaCodec.createByCodecName(swName);
        if (fallbackName != null)
            return MediaCodec.createByCodecName(fallbackName);
        return MediaCodec.createDecoderByType(MIME);
    }

    private void decodeLoop() {
        while (running.get()) {
            if (codec == null) break;

            byte[] frame = frameQueue.poll();
            if (frame == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                checkStall();
                continue;
            }

            if (!csdConfigured) {
                byte[][] csd = extractCsd(frame);
                if (csd == null) {
                    continue; // wait for a frame that carries SPS/PPS
                }
                csdSps = csd[0];
                csdPps = csd[1];
                frameQueue.clear();
                restart();
            }

            // Backpressure: feeding the hardware decoder faster than the
            // render thread can keep up overflows the MTK codec and it
            // silently stalls (no output). When the render queue is full,
            // pause feeding so the pipeline back-pressures naturally.
            if (renderQueue.size() >= MAX_PENDING) {
                try { Thread.sleep(2); } catch (InterruptedException e) { break; }
            }

            lastInputMs = SystemClock.elapsedRealtime();

            try {
                int inIdx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(frame);
                        codec.queueInputBuffer(inIdx, 0, frame.length,
                                ptsCounterUs, 0);
                        ptsCounterUs += 33333L;
                        int c = queuedIn.incrementAndGet();
                        if (c % 60 == 1) UiLog.i(TAG, "queuedIn=" + c + " size=" + frame.length);
                    }
                }
            } catch (IllegalStateException e) {
                UiLog.e(TAG, "decodeLoop IllegalState", e);
                break;
            }

            checkStall();
        }
    }

    private void checkStall() {
        long now = SystemClock.elapsedRealtime();
        long sinceRestart = now - lastRestartMs;
        if (sinceRestart < 6000) return;
        boolean gotFrames = now - lastInputMs < 600;
        boolean noOutput = now - lastOutputMs > 4000;
        if (gotFrames && noOutput) {
            UiLog.w(TAG, "decoder stalled (frames flowing, no output), rebuilding");
            restart();
        }
    }

    private void outputLoop() {
        long tryAgain = 0;
        while (running.get()) {
            MediaCodec c = codec;
            if (c == null) {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                continue;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                int outIdx = c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        Image img = null;
                        try {
                            img = c.getOutputImage(outIdx);
                        } catch (Exception e) {
                            UiLog.e(TAG, "getOutputImage error", e);
                            img = null;
                        }
                        if (img != null) {
                            // Hand the Image to the dedicated render thread; it
                            // converts YUV->ARGB, draws, then closes/releases.
                            if (renderQueue.size() < MAX_PENDING) {
                                renderQueue.add(new RenderTask(img, outIdx, c));
                            } else {
                                // render thread behind: drop oldest so the codec
                                // never blocks on us.
                                RenderTask old = renderQueue.poll();
                                if (old != null) dropRenderTask(old);
                                renderQueue.add(new RenderTask(img, outIdx, c));
                            }
                        } else {
                            c.releaseOutputBuffer(outIdx, false);
                        }
                    } else {
                        c.releaseOutputBuffer(outIdx, false);
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = c.getOutputFormat();
                    updateSize(fmt);
                    colorFormat = ColorFormatUtil.planarLayout(fmt);
                    UiLog.i(TAG, "output format w=" + videoWidth + " h=" + videoHeight
                            + " yuv=" + ColorFormatUtil.name(colorFormat)
                            + " codec=" + c.getName());
                    lastOutputMs = SystemClock.elapsedRealtime();
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    tryAgain++;
                    if (tryAgain % 600 == 1) UiLog.i(TAG, "tryAgain=" + tryAgain);
                    try { Thread.sleep(2); } catch (InterruptedException e) { break; }
                }
            } catch (IllegalStateException e) {
                try { Thread.sleep(2); } catch (InterruptedException ie) { break; }
            } catch (Exception e) {
                UiLog.e(TAG, "outputLoop error", e);
                try { Thread.sleep(2); } catch (InterruptedException ie) { break; }
            }
        }
        // drain anything left behind on shutdown
        RenderTask t;
        while ((t = renderQueue.poll()) != null) dropRenderTask(t);
    }

    private void dropRenderTask(RenderTask t) {
        try {
            if (t.image != null) t.image.close();
        } catch (Exception ignored) {
        }
        try {
            if (t.codec != null) t.codec.releaseOutputBuffer(t.bufferIndex, false);
        } catch (Exception ignored) {
        }
    }

    private void renderLoop() {
        Bitmap bitmap = null;
        long lastHealthLogMs = SystemClock.elapsedRealtime();
        int lastRendered = 0;
        while (running.get()) {
            RenderTask t = renderQueue.poll();
            if (t == null) {
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                continue;
            }
            try {
                Image img = t.image;
                YuvRenderer yr = yuvRenderer;
                if (yr != null) {
                    // GPU path: convert on the graphics chip, no Bitmap.
                    boolean semi = (colorFormat & ColorFormatUtil.FLAG_SEMIPLANAR) != 0
                            && img.getPlanes().length >= 2
                            && img.getPlanes()[1].getPixelStride() == 2;
                    yr.renderYuv(img, semi);
                } else {
                    int w = img.getWidth();
                    int h = img.getHeight();
                    if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    }
                    convertImage(img, bitmap);
                    bitmap.setHasAlpha(false);
                    FrameRenderer r = frameRenderer;
                    if (r != null) r.onFrame(bitmap);
                }
                lastOutputMs = SystemClock.elapsedRealtime();
                int n = renderedOut.incrementAndGet();
                if (n % 30 == 1) UiLog.i(TAG, "renderedOut=" + n);
                long now = SystemClock.elapsedRealtime();
                if (now - lastHealthLogMs >= 1000) {
                    int rendered = n - lastRendered;
                    lastRendered = n;
                    UiLog.i(TAG, "health fps=" + rendered
                            + " renderQ=" + renderQueue.size()
                            + " frameQ=" + frameQueue.size()
                            + " queuedIn=" + queuedIn.get());
                    lastHealthLogMs = now;
                }
            } catch (Exception e) {
                UiLog.e(TAG, "frame render error", e);
            } finally {
                dropRenderTask(t);
            }
        }
        YuvRenderer yr = yuvRenderer;
        if (yr != null) yr.detach();
    }

    private static final class RenderTask {
        final Image image;
        final int bufferIndex;
        final MediaCodec codec;

        RenderTask(Image image, int bufferIndex, MediaCodec codec) {
            this.image = image;
            this.bufferIndex = bufferIndex;
            this.codec = codec;
        }
    }

    private int[] argbPixels;
    private byte[] yRow;
    private byte[] uRow;
    private byte[] vRow;
    private byte[] uvRow;

    /**
     * Convert the decoder's single YUV420 frame straight into an ARGB
     * Bitmap in pure Java (no OpenCV). YV12/NV12 semantics are handled by
     * two tight loops; the row copies are the only per-row overhead.
     */
    private void convertImage(Image img, Bitmap bitmap) {
        int w = img.getWidth();
        int h = img.getHeight();
        Image.Plane[] p = img.getPlanes();

        if (argbPixels == null || argbPixels.length != w * h) {
            argbPixels = new int[w * h];
            yRow = new byte[w];
            uRow = new byte[w / 2];
            vRow = new byte[w / 2];
            uvRow = new byte[w];
        }

        if ((colorFormat & ColorFormatUtil.FLAG_SEMIPLANAR) != 0
                && p[1].getPixelStride() == 2) {
            // interleaved U,V chroma (NV12): flexible formats can still present
            // semiplanar with pixelStride 2.
            convertSemiplanar(p[0], p[1], w, h);
        } else {
            // planar: pixelStride 1 on each chroma plane (I420 or YV12).
            // Image planes are ordered Y,Cb,Cr regardless of the raw layout
            // (YV12 swaps the *plane buffers*, but getPlanes() normalizes the
            // order), so Cb/U is always planes[1], Cr/V always planes[2].
            convertPlanar(p[0], p[1], p[2], w, h);
        }

        bitmap.setPixels(argbPixels, 0, w, 0, 0, w, h);
    }

    private void convertSemiplanar(Image.Plane yp, Image.Plane uvp,
                                   int w, int h) {
        ByteBuffer yb = yp.getBuffer().duplicate();
        ByteBuffer uvb = uvp.getBuffer().duplicate();
        int yStride = yp.getRowStride();
        int uvStride = uvp.getRowStride();
        int y0 = yp.getBuffer().position();
        int uv0 = uvp.getBuffer().position();
        for (int j = 0; j < h; j++) {
            yb.position(y0 + j * yStride);
            yb.get(yRow, 0, w);
            uvb.position(uv0 + (j >> 1) * uvStride);
            uvb.get(uvRow, 0, w);
            yuvRowToArgb(j * w, w);
        }
    }

    private void convertPlanar(Image.Plane yp, Image.Plane up,
                               Image.Plane vp, int w, int h) {
        ByteBuffer yb = yp.getBuffer().duplicate();
        ByteBuffer ub = up.getBuffer().duplicate();
        ByteBuffer vb = vp.getBuffer().duplicate();
        int yStride = yp.getRowStride();
        int uStride = up.getRowStride();
        int vStride = vp.getRowStride();
        int y0 = yp.getBuffer().position();
        int u0 = up.getBuffer().position();
        int v0 = vp.getBuffer().position();
        int wp2 = w / 2;
        for (int j = 0; j < h; j++) {
            yb.position(y0 + j * yStride);
            yb.get(yRow, 0, w);
            int cr = j >> 1;
            ub.position(u0 + cr * uStride);
            ub.get(uRow, 0, wp2);
            vb.position(v0 + cr * vStride);
            vb.get(vRow, 0, wp2);
            int base = j * w;
            for (int i = 0; i < w; i++) {
                int y = (yRow[i] & 0xFF) - 16;
                if (y < 0) y = 0;
                int c = i >> 1;
                int u = (uRow[c] & 0xFF) - 128;
                int v = (vRow[c] & 0xFF) - 128;
                argbPixels[base + i] = yuvToArgb(y, u, v);
            }
        }
    }

    private void yuvRowToArgb(int base, int w) {
        for (int i = 0; i < w; i++) {
            int y = (yRow[i] & 0xFF) - 16;
            if (y < 0) y = 0;
            int c = i & ~1;
            // NV12: chroma plane is U,V interleaved (Cb,Cr).
            int u = (uvRow[c]     & 0xFF) - 128;
            int v = (uvRow[c + 1] & 0xFF) - 128;
            argbPixels[base + i] = yuvToArgb(y, u, v);
        }
    }

    /** BT.709 limited-range YUV -> ARGB (the encoder signals BT.709, matching
     *  swscale's SWS_CS_ITU709 path used by the reference desktop-viewer). */
    private static int yuvToArgb(int y, int u, int v) {
        return 0xFF000000
                | (clampByte((1192 * y + 1836 * v + 512) >> 10) << 16)
                | (clampByte((1192 * y - 218 * u - 546 * v + 512) >> 10) << 8)
                | clampByte((1192 * y + 2163 * u + 512) >> 10);
    }

    private static int clampByte(int v) {
        return (v < 0) ? 0 : (v > 255 ? 255 : v);
    }

    private static final class ColorFormatUtil {
        static final int FORMAT_SEMIPLANAR = 0x02;
        static final int FORMAT_YV12 = 0x03;
        static final int FLAG_SEMIPLANAR = 0x01;
        static final int FLAG_YV12 = 0x02;

        static int planarLayout(MediaFormat fmt) {
            int cf = fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            boolean flex = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            boolean semi = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    || cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
            boolean yv12 = cf == 0x32315659;
            int out = (semi || flex) ? FORMAT_SEMIPLANAR : (yv12 ? FORMAT_YV12 : FORMAT_SEMIPLANAR);
            if (semi) out |= FLAG_SEMIPLANAR;
            if (yv12) out |= FLAG_YV12;
            return out;
        }

        static String name(int format) {
            if ((format & FLAG_SEMIPLANAR) != 0) return "semiplanar";
            if ((format & FLAG_YV12) != 0) return "yv12";
            return "planar-i420";
        }
    }

    private void updateSize(MediaFormat fmt) {
        if (fmt == null) return;
        try {
            int w = fmt.getInteger(MediaFormat.KEY_WIDTH);
            int h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
            if (w > 0 && h > 0) {
                videoWidth = w;
                videoHeight = h;
                if (sizeListener != null) sizeListener.onSizeChanged(w, h);
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[][] extractCsd(byte[] data) {
        if (data.length < 16) return null;
        int[] codes = new int[3];
        int n = 0;
        int i = 0;
        while (i < data.length - 3 && n < 3) {
            if ((data[i] & 0xFF) == 0 && (data[i + 1] & 0xFF) == 0 &&
                    (data[i + 2] & 0xFF) == 1) {
                int codeStart = (i > 0 && (data[i - 1] & 0xFF) == 0) ? i - 1 : i;
                if (n == 0 || codeStart != codes[n - 1]) {
                    codes[n++] = codeStart;
                }
                i += 2;
            }
            i++;
        }
        if (n < 2) return null;

        int sps0 = codes[0];
        int spsEnd = codes[1];
        int pps0 = codes[1];
        int ppsEnd = (n > 2) ? codes[2] : data.length;
        if (spsEnd <= sps0 || ppsEnd <= pps0) return null;
        if (typeAt(data, sps0) != 7 || typeAt(data, pps0) != 8) return null;

        byte[] sps = new byte[spsEnd - sps0];
        byte[] pps = new byte[ppsEnd - pps0];
        System.arraycopy(data, sps0, sps, 0, sps.length);
        System.arraycopy(data, pps0, pps, 0, pps.length);
        return new byte[][]{sps, pps};
    }

    private static int typeAt(byte[] data, int codeStart) {
        int h = codeStart + 3;
        if ((data[codeStart] & 0xFF) == 0 && (data[codeStart + 1] & 0xFF) == 0 &&
                (data[codeStart + 2] & 0xFF) == 0 && (data[codeStart + 3] & 0xFF) == 1) {
            h = codeStart + 4;
        }
        return data[h] & 0x1F;
    }

    private static boolean containsSps(byte[] data) {
        for (int i = 1; i < data.length - 3; i++) {
            if ((data[i] & 0xFF) == 0 && (data[i + 1] & 0xFF) == 0 &&
                    (data[i + 2] & 0xFF) == 1) {
                int nalType = data[i + 3] & 0x1F;
                if (nalType == 7) return true; // SPS
            }
        }
        return false;
    }
}