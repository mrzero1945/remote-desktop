package com.remotedesktop.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.media.Image;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SurfaceView that renders the remote desktop and translates
 * multi-touch gestures into mouse / scroll events.
 */
public class RemoteView extends SurfaceView implements SurfaceHolder.Callback, VideoDecoder.YuvRenderer {

    public interface InputSender {
        void sendMouseMove(int x, int y);
        void sendMouseButton(int x, int y, int button, boolean pressed);
        void sendMouseScroll(int x, int y, int dx, int dy);
        void sendKey(int keysym, boolean pressed);
    }

    private final SurfaceHolder holder;
    private InputSender sender;
    private volatile int remoteWidth = 1280;
    private volatile int remoteHeight = 720;
    private boolean stretchToFill = false;

    private final Object frameLock = new Object();
    private Bitmap lastFrame;

    // Latency overlay state (written from the network thread, read on draw)
    private volatile boolean latencyActive = false;
    private volatile double latencyMs = -1;
    private final float density;

    private static class PointerData {
        boolean active;
        float lastX, lastY;
    }

    private final Map<Integer, PointerData> pointers = new ConcurrentHashMap<>();
    private final float touchSlop;
    private final float scrollSlop = 10;
    private static final float POINTER_SPEED = 3.0f;

    private float cursorX = -1;
    private float cursorY = -1;
    private float cursorStartX;
    private float cursorStartY;
    private boolean gestureMoved = false;
    private boolean hadTwoPointers = false;

    // Fullscreen margins (px) on all four sides; the remote image is drawn
    // inside the view bounds shrunk by these amounts.
    private int marginLeft = 0;
    private int marginTop = 0;
    private int marginRight = 0;
    private int marginBottom = 0;

    // ------------------------------------------------------------------
    // OpenGL ES renderer: each decoded YUV frame is uploaded as textures and
    // converted to RGB on the GPU (runs on the decoder's render thread).
    // ------------------------------------------------------------------
    private static final String GL_TAG = "GpuRender";
    private static final String VS_CODE =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  vTex = aTex;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";
    private static final String FS_SEMI =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uY;\n" +
            "uniform sampler2D uUV;\n" +
            "void main() {\n" +
            "  float y = (texture2D(uY, vTex).r - 0.0625) * 1.16439;\n" +
            "  float u = texture2D(uUV, vTex).r - 0.5;\n" +
            "  float v = texture2D(uUV, vTex).a - 0.5;\n" +
            "  float r = clamp(y + 1.5748 * v, 0.0, 1.0);\n" +
            "  float g = clamp(y - 0.1873 * u - 0.4681 * v, 0.0, 1.0);\n" +
            "  float b = clamp(y + 1.8556 * u, 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";
    private static final String FS_PLANAR =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uY;\n" +
            "uniform sampler2D uU;\n" +
            "uniform sampler2D uV;\n" +
            "void main() {\n" +
            "  float y = (texture2D(uY, vTex).r - 0.0625) * 1.16439;\n" +
            "  float u = texture2D(uU, vTex).r - 0.5;\n" +
            "  float v = texture2D(uV, vTex).r - 0.5;\n" +
            "  float r = clamp(y + 1.5748 * v, 0.0, 1.0);\n" +
            "  float g = clamp(y - 0.1873 * u - 0.4681 * v, 0.0, 1.0);\n" +
            "  float b = clamp(y + 1.8556 * u, 0.0, 1.0);\n" +
            "  gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";

    private volatile boolean gpuRender = false;
    private boolean eglInited = false;
    private boolean eglInitFailed = false;
    private volatile boolean eglSurfaceDirty = true;
    private EGLDisplay eglDisplay;
    private EGLSurface eglSurface;
    private EGLContext eglContext;
    private EGLConfig eglConfig;
    private android.view.Surface boundSurface;
    private int progSemi, progPlanar;
    private int aPosLoc, aTexLoc;
    private int uYLoc, uUPloc, uUVLoc;
    private int[] texIds = new int[3];
    private FloatBuffer vPos, tPos;
    private byte[] glRow;
    private ByteBuffer yPack, uvPack, uPack, vPack;
    private boolean eglWorldUp = false; // tex coord orientation (see fillQuad)

    // Copy of the composing text currently mirrored on the server side; the
    // IME updates it per keystroke, so its change is forwarded immediately.
    private String composing = "";

    public RemoteView(Context context, AttributeSet attrs) {
        super(context, attrs);
        holder = getHolder();
        holder.addCallback(this);
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = context.getResources().getDisplayMetrics().density;
        // SurfaceView defaults to willNotDraw(true), which would silently
        // skip onDraw(). We need it for the RTT/cursor overlays that sit on
        // the window layer above the GL video surface (GPU mode).
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setInputSender(InputSender s) {
        sender = s;
    }

    /** Enable/disable GPU (OpenGL ES) rendering of YUV frames. When enabled,
     *  the decoder's render thread hands YUV Images to renderYuv() and the
     *  CPU color conversion / canvas drawing is disabled. */
    public void setGpuRender(boolean enabled) {
        gpuRender = enabled;
        synchronized (frameLock) {
            if (enabled) lastFrame = null;
        }
        invalidate();
    }

    /** Show/hide the latency overlay (e.g. tied to connection state). */
    public void setLatencyActive(boolean active) {
        latencyActive = active;
        redraw();
    }

    /** Set the averaged RTT in milliseconds; the overlay redraws. */
    public void setLatencyMs(double ms) {
        latencyMs = ms;
        redraw();
    }

    public void setRemoteSize(int w, int h) {
        if (w > 0 && h > 0) {
            remoteWidth = w;
            remoteHeight = h;
            cursorX = w / 2;
            cursorY = h / 2;
            requestLayout();
        }
    }

    public void setStretchToFill(boolean stretch) {
        stretchToFill = stretch;
        if (gpuRender) { invalidate(); return; }
        synchronized (frameLock) { drawFrameLocked(); }
    }

    /** Set the on-screen margins (px) applied on all four sides while the
     *  image is stretched to fill (fullscreen mode). */
    public void setFullscreenMargins(int left, int top, int right, int bottom) {
        marginLeft = left;
        marginTop = top;
        marginRight = right;
        marginBottom = bottom;
        if (gpuRender) { invalidate(); return; }
        synchronized (frameLock) { drawFrameLocked(); }
    }

    /** Rect (view coordinates) actually occupied by the rendered image. */
    private Rect contentRect() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return null;
        if (!stretchToFill) {
            return fitRect(vw, vh, remoteWidth, remoteHeight);
        }
        int left = Math.min(marginLeft, vw - 1);
        int right = Math.max(left + 1, vw - Math.min(marginRight, vw - 1));
        int top = Math.min(marginTop, vh - 1);
        int bottom = Math.max(top + 1, vh - Math.min(marginBottom, vh - 1));
        return new Rect(left, top, right, bottom);
    }

    // ------------------------------------------------------------------
    // Frame rendering: buffer mode produces Bitmaps (drawn with canvas) and
    // GPU mode renders YUV straight from the decoder via OpenGL ES. Both are
    // guarded for use from the render thread and the UI thread.
    // ------------------------------------------------------------------

    public void render(Bitmap frame) {
        if (gpuRender) return; // GPU path owns the surface
        synchronized (frameLock) {
            lastFrame = frame;
            drawFrameLocked();
        }
    }

    private void drawFrameLocked() {
        if (gpuRender) return; // video is drawn by GL into the surface
        if (lastFrame == null) return;
        if (!holder.getSurface().isValid()) return;
        Canvas c = holder.lockCanvas();
        if (c == null) return;
        try {
            int vw = getWidth();
            int vh = getHeight();
            c.drawColor(Color.BLACK);
            Rect dst = contentRect();
            if (dst != null) {
                c.drawBitmap(lastFrame, null, dst, null);
                drawCursor(c);
            }
            drawLatency(c);
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    /** Redraw the last frame plus cursor overlay (e.g. after a cursor move). */
    public void redraw() {
        if (gpuRender) { invalidate(); return; }
        synchronized (frameLock) {
            drawFrameLocked();
        }
    }

    /** GPU mode: the video surface is the view's own Surface layer, so the
     *  letterbox mattes and cursor/RTT overlays are drawn on the window canvas
     *  (composited above the video) instead of lockCanvas. */
    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!gpuRender) return;
        Rect dst = contentRect();
        if (dst == null) return;
        int vw = getWidth(), vh = getHeight();
        android.graphics.Paint blk = new android.graphics.Paint();
        blk.setColor(Color.BLACK);
        c.drawRect(0, 0, vw, dst.top, blk);
        c.drawRect(0, dst.bottom, vw, vh, blk);
        c.drawRect(0, dst.top, dst.left, dst.bottom, blk);
        c.drawRect(dst.right, dst.top, vw, dst.bottom, blk);
        drawCursor(c);
        drawLatency(c);
    }

    // ------------------------------------------------------------------
    // GPU renderer internals (called from the decoder's render thread)
    // ------------------------------------------------------------------

    /** Render one decoded YUV frame converted to RGB on the GPU. Must be
     *  called from the thread that owns the EGL context (the render thread). */
    public void renderYuv(Image img, boolean semiplanar) {
        if (!gpuRender) return;
        if (!ensureGl()) return;
        android.view.Surface s = holder.getSurface();
        boolean needSurface = eglSurfaceDirty
                || eglSurface == null
                || s == null
                || !s.isValid()
                || eglSurfaceDimmed();
        if (needSurface) {
            if (!createEglSurface(s)) return;
        }
        try {
            drawFrameGpu(img, semiplanar);
            if (EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                eglSurfaceDirty = false;
            } else {
                UiLog.e(GL_TAG, "swap failed err=" + EGL14.eglGetError()
                        + " -> recreating surface");
                destroyEglSurface();
            }
        } catch (Exception e) {
            UiLog.e(GL_TAG, "draw/swap error: " + e.getMessage());
            destroyEglSurface();
        }
    }

    /** True when the EGL surface exists but the compositor reports it gone
     *  (size query fails or is zero) so it should be recreated. */
    private boolean eglSurfaceDimmed() {
        if (eglSurface == null) return true;
        int[] wh = new int[2];
        if (!EGL14.eglQuerySurface(eglDisplay, eglSurface,
                EGL14.EGL_WIDTH, wh, 0)
                || !EGL14.eglQuerySurface(eglDisplay, eglSurface,
                EGL14.EGL_HEIGHT, wh, 1)) {
            return true;
        }
        return wh[0] <= 0 || wh[1] <= 0;
    }

    private boolean ensureGl() {
        if (eglInited) return true;
        if (eglInitFailed) return false;
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (eglDisplay == null
                    || !EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
                throw new RuntimeException("eglInitialize failed");
            }
            int[] attrs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfg, 0, 1, num, 0)
                    || num[0] == 0) {
                throw new RuntimeException("eglChooseConfig failed");
            }
            eglConfig = cfg[0];
            int[] cattr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                    EGL14.EGL_NO_CONTEXT, cattr, 0);
            if (eglContext == null) throw new RuntimeException("eglCreateContext null");
            android.view.Surface s = holder.getSurface();
            if (!createEglSurface(s)) {
                throw new RuntimeException("eglCreateWindowSurface null");
            }
            progSemi = buildProgram(VS_CODE, FS_SEMI);
            progPlanar = buildProgram(VS_CODE, FS_PLANAR);
            aPosLoc = GLES20.glGetAttribLocation(progSemi, "aPos");
            aTexLoc = GLES20.glGetAttribLocation(progSemi, "aTex");
            uYLoc = GLES20.glGetUniformLocation(progSemi, "uY");
            uUVLoc = GLES20.glGetUniformLocation(progSemi, "uUV");
            uUPloc = GLES20.glGetUniformLocation(progPlanar, "uU");
            GLES20.glGenTextures(3, texIds, 0);
            for (int i = 0; i < 3; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            vPos = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            tPos = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            eglSurfaceDirty = false;
            eglInited = true;
            UiLog.i(GL_TAG, "GLES renderer ready");
            return true;
        } catch (RuntimeException e) {
            UiLog.e(GL_TAG, "GL init failed: " + e.getMessage());
            eglInitFailed = true;
            detach();
            return false;
        }
    }

    /** Create (or recreate) the EGL window surface for the current holder
     *  Surface. Caller must hold the GL thread. */
    private boolean createEglSurface(android.view.Surface s) {
        if (eglDisplay == null || eglContext == null) return false;
        if (s == null || !s.isValid()) {
            eglSurfaceDirty = true;
            return false;
        }
        destroyEglSurface();
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, s,
                new int[]{ EGL14.EGL_NONE }, 0);
        if (eglSurface == null) {
            UiLog.e(GL_TAG, "eglCreateWindowSurface null, err=" + EGL14.eglGetError());
            eglSurfaceDirty = true;
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            UiLog.e(GL_TAG, "eglMakeCurrent surface failed, err=" + EGL14.eglGetError());
            destroyEglSurface();
            return false;
        }
        boundSurface = s;
        eglSurfaceDirty = false;
        return true;
    }

    private void destroyEglSurface() {
        if (eglSurface == null) {
            boundSurface = null;
            return;
        }
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        } catch (Exception ignored) {
        }
        try {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
        } catch (Exception ignored) {
        }
        eglSurface = null;
        boundSurface = null;
        eglSurfaceDirty = true;
    }

    private int buildProgram(String vsSrc, String fsSrc) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            UiLog.e(GL_TAG, "link error: " + GLES20.glGetProgramInfoLog(p));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            UiLog.e(GL_TAG, "shader error: " + GLES20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private void drawFrameGpu(Image img, boolean semiplanar) {
        Image.Plane yp = img.getPlanes()[0];
        int w = img.getWidth();
        int h = img.getHeight();
        Rect dst = contentRect();
        if (dst == null) return;

        GLES20.glViewport(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        int program = semiplanar ? progSemi : progPlanar;
        GLES20.glUseProgram(program);

        // geometry: quad covering exactly contentRect()
        float vw = getWidth(), vh = getHeight();
        float l = dst.left * 2f / vw - 1f;
        float r = dst.right * 2f / vw - 1f;
        float b = 1f - dst.top * 2f / vh;
        float t = 1f - dst.bottom * 2f / vh;
        vPos.clear();
        vPos.put(new float[]{ l, b, r, b, l, t, r, t });
        vPos.flip();
        // buffer row 0 is the top of the video; glTexImage2D stores row 0 at
        // v=0, so the screen's top edge must sample v=0 (and the bottom v=1).
        tPos.clear();
        tPos.put(new float[]{ 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f });
        tPos.flip();

        uploadPlaneY(yp, w, h);

        if (semiplanar) {
            uploadSemiUv(img.getPlanes()[1], w, h);
        } else {
            uploadPlanarUv(img.getPlanes()[1], img.getPlanes()[2], w, h);
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0]);
        GLES20.glUniform1i(uYLoc, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1]);
        if (semiplanar) {
            GLES20.glUniform1i(uUVLoc, 1);
        } else {
            GLES20.glUniform1i(uUPloc, 1);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[2]);
        if (!semiplanar) {
            GLES20.glUniform1i(uVLoc(), 2);
        }

        GLES20.glEnableVertexAttribArray(aPosLoc);
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vPos);
        GLES20.glEnableVertexAttribArray(aTexLoc);
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosLoc);
        GLES20.glDisableVertexAttribArray(aTexLoc);
    }

    private int uVLoc() {
        return GLES20.glGetUniformLocation(progPlanar, "uV");
    }

    private void uploadPlaneY(Image.Plane p, int w, int h) {
        if (yPack == null || yPack.capacity() < w * h) {
            yPack = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
            glRow = new byte[w];
        }
        ByteBuffer src = p.getBuffer();
        int pos0 = p.getBuffer().position();
        int stride = p.getRowStride();
        for (int i = 0; i < h; i++) {
            src.position(pos0 + i * stride);
            src.get(glRow, 0, w);
            yPack.position(i * w);
            yPack.put(glRow, 0, w);
        }
        yPack.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yPack);
    }

    private void uploadSemiUv(Image.Plane p, int w, int h) {
        int uvW = w;
        int uvH = h / 2;
        if (glRow == null || glRow.length < uvW) glRow = new byte[uvW];
        if (uvPack == null || uvPack.capacity() < uvW * uvH) {
            uvPack = ByteBuffer.allocateDirect(uvW * uvH).order(ByteOrder.nativeOrder());
        }
        ByteBuffer src = p.getBuffer();
        int pos0 = p.getBuffer().position();
        int stride = p.getRowStride();
        for (int i = 0; i < uvH; i++) {
            src.position(pos0 + i * stride);
            src.get(glRow, 0, uvW);
            uvPack.position(i * uvW);
            uvPack.put(glRow, 0, uvW);
        }
        uvPack.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[1]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA,
                uvW, uvH, 0, GLES20.GL_LUMINANCE_ALPHA,
                GLES20.GL_UNSIGNED_BYTE, uvPack);
    }

    private void uploadPlanarUv(Image.Plane up, Image.Plane vp, int w, int h) {
        int cw = w / 2;
        int ch = h / 2;
        if (glRow == null || glRow.length < cw) glRow = new byte[cw];
        uploadPlane(up, uPack, 1, cw, ch);
        uploadPlane(vp, vPack, 2, cw, ch);
    }

    private void uploadPlane(Image.Plane p, ByteBuffer dstBuf, int texIndex,
                             int cw, int ch) {
        if (dstBuf == null || dstBuf.capacity() < cw * ch) {
            dstBuf = ByteBuffer.allocateDirect(cw * ch).order(ByteOrder.nativeOrder());
            if (texIndex == 1) uPack = dstBuf; else vPack = dstBuf;
        }
        ByteBuffer src = p.getBuffer();
        int pos0 = p.getBuffer().position();
        int stride = p.getRowStride();
        for (int i = 0; i < ch; i++) {
            src.position(pos0 + i * stride);
            src.get(glRow, 0, cw);
            dstBuf.position(i * cw);
            dstBuf.put(glRow, 0, cw);
        }
        dstBuf.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texIndex);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[texIndex]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                cw, ch, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, dstBuf);
    }

    /** Tear down the GPU renderer. Must run on the GL render thread; called
     *  by the decoder when its render loop exits. */
    @Override
    public void detach() {
        if (!eglInited && eglDisplay == null) return;
        if (eglDisplay != null) {
            if (texIds[0] != 0) {
                try { GLES20.glDeleteTextures(3, texIds, 0); } catch (Exception ignored) {}
                texIds[0] = 0;
            }
            try {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != null) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != null) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            } catch (Exception ignored) {
            }
        }
        eglDisplay = null;
        eglSurface = null;
        eglContext = null;
        boundSurface = null;
        eglInited = false;
        eglInitFailed = false;
    }

    /**
     * Draws a small pointer at the current cursor position, mapped from
     * remote coordinates into view coordinates so it stays glued to the
     * spot the server would actually place the real mouse.
     */
    private void drawCursor(Canvas c) {
        if (cursorX < 0 || cursorY < 0) return;
        Rect dst = contentRect();
        if (dst == null) return;
        float vx = dst.left + cursorX * dst.width() / (float) remoteWidth;
        float vy = dst.top + cursorY * dst.height() / (float) remoteHeight;
        drawArrow(c, vx, vy);
    }

    /** classic white arrow with dark outline, tip at (vx, vy) */
    private void drawArrow(Canvas c, float vx, float vy) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(0, 0);
        p.lineTo(20, 11);
        p.lineTo(13, 11);
        p.lineTo(17, 20);
        p.lineTo(13, 22);
        p.lineTo(8, 13);
        p.lineTo(0, 18);
        p.close();
        p.offset(vx, vy);

        android.graphics.Paint outline = new android.graphics.Paint();
        outline.setStyle(android.graphics.Paint.Style.STROKE);
        outline.setStrokeWidth(2.5f);
        outline.setColor(Color.BLACK);

        android.graphics.Paint fill = new android.graphics.Paint();
        fill.setStyle(android.graphics.Paint.Style.FILL);
        fill.setColor(Color.WHITE);

        c.drawPath(p, outline);
        c.drawPath(p, fill);
    }

    /**
     * Draws the round-trip latency (RTT) badge in the top-left corner, with
     * the same green/yellow/red traffic-light thresholds as the desktop viewer.
     */
    private void drawLatency(Canvas c) {
        if (!latencyActive) return;
        float ms = (float) latencyMs;
        int color;
        String text;
        if (ms < 0) {
            text = "RTT -- ms";
            color = 0xFFFFFFFF;
        } else {
            text = String.format("RTT %.1f ms", ms);
            color = ms < 30 ? 0xFF3CFF3C
                    : ms < 100 ? 0xFFFFE22C
                               : 0xFFFF4545;
        }

        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(14 * density);
        paint.setColor(color);

        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float tw = paint.measureText(text);
        float th = fm.descent - fm.ascent;
        float pad = 6 * density;
        float left = 4 * density;
        float top = 4 * density;
        android.graphics.RectF bg = new android.graphics.RectF(
                left, top, left + tw + pad * 2, top + th + pad * 2);

        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(0x8C000000); // translucent black
        c.drawRoundRect(bg, pad * 0.5f, pad * 0.5f, bgPaint);

        c.drawText(text, left + pad, top + pad - fm.ascent, paint);
    }

    private static Rect fitRect(int vw, int vh, int iw, int ih) {
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return null;
        float s = Math.min(vw / (float) iw, vh / (float) ih);
        int w = Math.round(iw * s);
        int h = Math.round(ih * s);
        int x = (vw - w) / 2;
        int y = (vh - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        eglSurfaceDirty = true;
        synchronized (frameLock) {
            drawFrameLocked();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        eglSurfaceDirty = true;
        synchronized (frameLock) {
            drawFrameLocked();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        eglSurfaceDirty = true;
    }

    // ------------------------------------------------------------------
    // Touch → mouse mapping
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (sender == null) return true;

        int actionMasked = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                gestureMoved = false;
                hadTwoPointers = false;
                ensureCursor();
                PointerData pd = new PointerData();
                pd.active = true;
                pd.lastX = event.getX(pointerIndex);
                pd.lastY = event.getY(pointerIndex);
                cursorStartX = pd.lastX;
                cursorStartY = pd.lastY;
                pointers.put(pointerId, pd);
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                hadTwoPointers = true;
                PointerData pd = new PointerData();
                pd.active = true;
                pd.lastX = event.getX(pointerIndex);
                pd.lastY = event.getY(pointerIndex);
                pointers.put(pointerId, pd);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    PointerData pd = pointers.get(id);
                    if (pd == null || !pd.active) continue;

                    float x = event.getX(i);
                    float y = event.getY(i);
                    float dx = x - pd.lastX;
                    float dy = y - pd.lastY;
                    pd.lastX = x;
                    pd.lastY = y;

                    int count = pointers.size();

                    if (count == 1) {
                        // single finger: relative cursor move (trackpad style)
                        float distFromStart = Math.abs(x - cursorStartX) + Math.abs(y - cursorStartY);
                        if (distFromStart > touchSlop) gestureMoved = true;
                        if (gestureMoved) {
                            Rect dst = contentRect();
                            float dispW = dst != null ? dst.width() : getWidth();
                            float dispH = dst != null ? dst.height() : getHeight();
                            float scaleX = (remoteWidth / dispW) * POINTER_SPEED;
                            float scaleY = (remoteHeight / dispH) * POINTER_SPEED;
                            cursorX += dx * scaleX;
                            cursorY += dy * scaleY;
                            cursorX = clamp(cursorX, 0, remoteWidth - 1);
                            cursorY = clamp(cursorY, 0, remoteHeight - 1);
                            sender.sendMouseMove((int) cursorX, (int) cursorY);
                            redraw();
                        }
                    } else if (count == 2) {
                        // two-finger scroll
                        gestureMoved = gestureMoved ||
                                Math.abs(dx) > scrollSlop || Math.abs(dy) > scrollSlop;
                        int[] r = { (int) cursorX, (int) cursorY };
                        if (Math.abs(dy) > scrollSlop) {
                            int steps = (int) Math.copySign(
                                    Math.max(1, Math.abs(dy) / 20.0f), dy);
                            sender.sendMouseScroll(r[0], r[1], 0, steps);
                        }
                        if (Math.abs(dx) > scrollSlop) {
                            int steps = (int) Math.copySign(
                                    Math.max(1, Math.abs(dx) / 20.0f), dx);
                            sender.sendMouseScroll(r[0], r[1], steps, 0);
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                PointerData pd = pointers.remove(pointerId);
                if (pd == null || !pd.active) break;

                if (pointers.isEmpty()) {
                    if (!gestureMoved) {
                        if (hadTwoPointers) {
                            // two-finger tap = right click
                            int[] r = { (int) cursorX, (int) cursorY };
                            sender.sendMouseButton(r[0], r[1], Proto.BTN_RIGHT, true);
                            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                            sender.sendMouseButton(r[0], r[1], Proto.BTN_RIGHT, false);
                        } else {
                            // single-finger tap = left click
                            int[] r = { (int) cursorX, (int) cursorY };
                            sender.sendMouseButton(r[0], r[1], Proto.BTN_LEFT, true);
                            sender.sendMouseButton(r[0], r[1], Proto.BTN_LEFT, false);
                        }
                    }
                    gestureMoved = false;
                    hadTwoPointers = false;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                pointers.clear();
                gestureMoved = false;
                hadTwoPointers = false;
                break;
            }
        }

        performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    // ------------------------------------------------------------------
    // Native (Android) soft-keyboard input
    // ------------------------------------------------------------------

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new RemoteInputConnection(this);
    }

    private final class RemoteInputConnection extends BaseInputConnection {

        RemoteInputConnection(RemoteView target) {
            super(target, true);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            String next = text.toString();
            sendCompositionDelta(composing, next);
            composing = next;
            return super.setComposingText(text, newCursorPosition);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String t = text.toString();
            if (composing.isEmpty()) {
                sendChars(t);
            } else if (t.equals(composing)) {
                // Already sent incrementally while composing.
            } else {
                sendCompositionDelta(composing, t);
            }
            composing = "";
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return super.finishComposingText();
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    || event.getAction() == KeyEvent.ACTION_UP) {
                sendKeyEventInner(event, event.getAction() == KeyEvent.ACTION_DOWN);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (composing.isEmpty()) {
                for (int i = 0; i < beforeLength; i++) {
                    remoteSendKey(Keys.XK_BackSpace, true);
                    remoteSendKey(Keys.XK_BackSpace, false);
                }
                for (int i = 0; i < afterLength; i++) {
                    remoteSendKey(Keys.XK_Delete, true);
                    remoteSendKey(Keys.XK_Delete, false);
                }
            } else {
                // While composing, edits are delivered via setComposingText
                // deltas; just mirror the last chars locally.
                if (beforeLength > 0 && !composing.isEmpty()) {
                    composing = composing.substring(0,
                            Math.max(0, composing.length() - beforeLength));
                }
            }
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            remoteSendKey(Keys.XK_Return, true);
            remoteSendKey(Keys.XK_Return, false);
            return true;
        }

        /** Translate a change of the composing text into the minimal set of
         *  key presses so the remote stays in sync in real time. */
        private void sendCompositionDelta(String from, String to) {
            if (to == null) to = "";
            if (from == null) from = "";
            if (to.startsWith(from)) {
                sendChars(to.substring(from.length()));
            } else if (to.length() < from.length() && from.endsWith(to)) {
                int removed = from.length() - to.length();
                for (int i = 0; i < removed; i++) {
                    remoteSendKey(Keys.XK_BackSpace, true);
                    remoteSendKey(Keys.XK_BackSpace, false);
                }
            } else {
                for (int i = 0; i < from.length(); i++) {
                    remoteSendKey(Keys.XK_BackSpace, true);
                    remoteSendKey(Keys.XK_BackSpace, false);
                }
                sendChars(to);
            }
        }

        private void sendChars(String s) {
            for (int i = 0; i < s.length(); i++) {
                sendChar(s.charAt(i));
            }
        }
    }

    private void sendChar(char c) {
        int keysym = Keys.keysymForChar(c);
        if (keysym < 0) return;
        boolean shift = Keys.needsShift(c);
        if (shift) remoteSendKey(Keys.XK_Shift_L, true);
        remoteSendKey(keysym, true);
        remoteSendKey(keysym, false);
        if (shift) remoteSendKey(Keys.XK_Shift_L, false);
    }

    private void sendKeyEventInner(KeyEvent ev, boolean pressed) {
        // While composing, BackSpace / ForwardDel are handled through the
        // setComposingText deltas; skip raw key events to avoid double input.
        int mapped = Keys.mapKeycode(ev.getKeyCode());
        if (mapped >= 0) {
            if (composing.isEmpty()
                    || (mapped != Keys.XK_BackSpace && mapped != Keys.XK_Delete)) {
                remoteSendKey(mapped, pressed);
            }
            return;
        }
        int keysym = Keys.eventKeysym(ev);
        if (keysym >= 0) {
            if (keysym == Keys.XK_BackSpace || keysym == Keys.XK_Delete) {
                return; // handled by composition delta if composing
            }
            boolean shift = pressed && Keys.needsShift(ev);
            if (shift) remoteSendKey(Keys.XK_Shift_L, true);
            remoteSendKey(keysym, pressed);
            if (shift) remoteSendKey(Keys.XK_Shift_L, false);
        }
    }

    private void remoteSendKey(int keysym, boolean pressed) {
        if (sender != null) sender.sendKey(keysym, pressed);
    }

    private void ensureCursor() {
        if (cursorX < 0 || cursorY < 0) {
            cursorX = remoteWidth / 2;
            cursorY = remoteHeight / 2;
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }
}