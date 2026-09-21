package com.remotedesktop.client;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements UdpClient.Listener, RemoteView.InputSender {

    private static final String TAG = "MainActivity";
    private static final String PREF_HOST = "saved_host";
    private static final String PREF_PORT = "saved_port";
    private static final String PREF_FEC = "saved_fec";
    private static final String PREF_ARQ = "saved_arq";
    private static final String PREF_GPU = "saved_gpu";
    private static final String PREF_AUDIO = "saved_audio";
    private static final String PREF_SSL = "saved_ssl";
    private static final int FULLSCREEN_MARGIN_DP = 20;

    private EditText ipInput;
    private EditText portInput;
    private Button connectBtn;
    private Button scanBtn;
    private ToggleButton fecToggle;
    private ToggleButton arqToggle;
    private ToggleButton gpuToggle;
    private ToggleButton audioToggle;
    private ToggleButton sslToggle;
    private TextView statusText;
    private RemoteView remoteView;
    private LinearLayout topBar;
    private ListView logList;
    private TextView logHeader;
    private Button fullscreenBtn;
    private TextView exitFullscreenBtn;
    private TextView kbFloatBtn;
    private boolean fullscreen = false;
    private boolean keyboardVisible = false;

    private UdpClient udpClient;
    private VideoDecoder decoder;
    private boolean videoStatusSet = false;

    private boolean shiftHeld = false;
    private final java.util.HashSet<Integer> injectedShiftKeys = new java.util.HashSet<>();

    // RTT latency sampling (populated from the UDP receiver thread).
    private static final int MAX_RTT_SAMPLES = 8;
    private final java.util.ArrayDeque<Long> rttSamples = new java.util.ArrayDeque<>();
    private volatile double lastRttMs = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipInput = findViewById(R.id.ipInput);
        portInput = findViewById(R.id.portInput);
        connectBtn = findViewById(R.id.connectBtn);
        scanBtn = findViewById(R.id.scanBtn);
        statusText = findViewById(R.id.statusText);
        remoteView = findViewById(R.id.remoteView);
        topBar = findViewById(R.id.topBar);
        fullscreenBtn = findViewById(R.id.fullscreenBtn);
        exitFullscreenBtn = findViewById(R.id.exitFullscreenBtn);
        kbFloatBtn = findViewById(R.id.kbFloatBtn);
        fecToggle = findViewById(R.id.fecToggle);
        arqToggle = findViewById(R.id.arqToggle);
        gpuToggle = findViewById(R.id.gpuToggle);
        audioToggle = findViewById(R.id.audioToggle);
        sslToggle = findViewById(R.id.sslToggle);

        logList = findViewById(R.id.logList);
        logHeader = findViewById(R.id.logHeader);
        UiLog.attach(logList);
        Watchdog.start();
        logHeader.setOnClickListener(v ->
                logList.setVisibility(logList.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));

        final SharedPreferences prefs = getSharedPreferences("rd", MODE_PRIVATE);
        fecToggle.setChecked(prefs.getBoolean(PREF_FEC, true));
        arqToggle.setChecked(prefs.getBoolean(PREF_ARQ, true));
        gpuToggle.setChecked(prefs.getBoolean(PREF_GPU, true));
        audioToggle.setChecked(prefs.getBoolean(PREF_AUDIO, true));
        sslToggle.setChecked(prefs.getBoolean(PREF_SSL, false));
        // GPU (GLES) YUV->RGB vs the old CPU (Java) conversion; both modes use
        // the same decoder, this only switches where conversion happens.
        gpuToggle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_GPU, checked).apply();
            applyGpuMode(checked);
            UiLog.i(TAG, "GPU mode " + (checked ? "ON" : "OFF"));
        });
        // Audio & SSL are negotiated in the handshake, so they take effect on
        // (re)connect like FEC. (Audio stream + transport cipher pending.)
        audioToggle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_AUDIO, checked).apply();
            if (udpClient != null && udpClient.isConnected()) {
                Toast.makeText(this, "Audio " + (checked ? "ON" : "OFF")
                        + " saat reconnect", Toast.LENGTH_SHORT).show();
            }
        });
        sslToggle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_SSL, checked).apply();
            if (udpClient != null && udpClient.isConnected()) {
                Toast.makeText(this, "SSL " + (checked ? "ON" : "OFF")
                        + " saat reconnect", Toast.LENGTH_SHORT).show();
            }
        });
        // FEC level is negotiated at handshake time, so it only takes effect on
        // (re)connect; ARQ is a live client-side flag.
        fecToggle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_FEC, checked).apply();
            if (udpClient != null && udpClient.isConnected()) {
                Toast.makeText(this, "FEC diterapkan saat reconnect",
                        Toast.LENGTH_SHORT).show();
            }
        });
        arqToggle.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(PREF_ARQ, checked).apply();
            if (udpClient != null && udpClient.isConnected()) {
                Toast.makeText(this, "ARQ " + (checked ? "ON" : "OFF"),
                        Toast.LENGTH_SHORT).show();
            }
        });

        fullscreenBtn.setOnClickListener(v -> enterFullscreen());
        exitFullscreenBtn.setOnClickListener(v -> exitFullscreen());
        kbFloatBtn.setOnClickListener(v -> toggleKeyboard());

        syncKeyboardState();

        int lastPort = getSharedPreferences("rd", MODE_PRIVATE)
                .getInt(PREF_PORT, 9876);
        portInput.setText(String.valueOf(lastPort));

        String lastHost = getSharedPreferences("rd", MODE_PRIVATE)
                .getString(PREF_HOST, "10.58.137.64");
        ipInput.setText(lastHost);

        remoteView.setInputSender(this);

        connectBtn.setOnClickListener(v -> {
            if (udpClient != null && udpClient.isConnected()) {
                disconnect();
            } else {
                connect();
            }
        });

        scanBtn.setOnClickListener(v -> runServerScan());
    }

    /** Scan the local /24 networks for matching Remote Desktop servers and
     *  show the results in a table dialog; tapping a row fills the address
     *  fields and connects. Runs off the UI thread to avoid
     *  NetworkOnMainThreadException. */
    private void runServerScan() {
        final List<String> ips = ServerScanner.enumerateLocalIpCandidates();
        final int userPort = parseIntOrDefault(
                portInput.getText().toString().trim(), Proto.DEFAULT_PORT);
        final java.util.LinkedHashSet<Integer> ports = new java.util.LinkedHashSet<>();
        ports.add(userPort);
        ports.add(Proto.DEFAULT_PORT);

        statusText.setText("Scanning " + ips.size() * ports.size()
                + " probe(s)...");
        scanBtn.setEnabled(false);

        new Thread(() -> {
            try {
                ServerScanner.scan(ips, new ArrayList<>(ports),
                        new ServerScanner.Callback() {
                            @Override
                            public void onScanStarted(int probeCount) {
                            }

                            @Override
                            public void onResult(List<ServerScanner.Match> matches) {
                                runOnUiThread(() -> showScanDialog(matches));
                            }
                        });
            } catch (final Exception e) {
                UiLog.e(TAG, "scan failed", e);
                runOnUiThread(() -> {
                    statusText.setText("Scan failed: " + e.getMessage());
                    scanBtn.setEnabled(true);
                });
            }
        }, "ServerScan").start();
    }

    private void showScanDialog(List<ServerScanner.Match> matches) {
        scanBtn.setEnabled(true);
        statusText.setText("Scan done: " + matches.size() + " server(s) found");

        List<String> rows = new ArrayList<>();
        for (ServerScanner.Match m : matches) {
            rows.add(m.display());
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_scan, null);
        TextView scanEmpty = view.findViewById(R.id.scanEmpty);
        android.widget.ListView list = view.findViewById(R.id.scanList);
        scanEmpty.setVisibility(matches.isEmpty()
                ? View.VISIBLE : View.GONE);
        list.setVisibility(matches.isEmpty() ? View.GONE : View.VISIBLE);

        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(
                        this, android.R.layout.simple_list_item_1, rows) {
                    @Override
                    public View getView(int position, View convert,
                                        android.view.ViewGroup parent) {
                        String s = getItem(position);
                        int colon = s.lastIndexOf(':');
                        String ipCol = s.substring(0, colon);
                        String portCol = s.substring(colon + 1);
                        android.widget.LinearLayout row =
                                new android.widget.LinearLayout(getContext());
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setPadding(dp(8), dp(10), dp(8), dp(10));
                        TextView ip = new TextView(getContext());
                        ip.setText(ipCol);
                        ip.setTextColor(0xFFFFFFFF);
                        ip.setTextSize(13f);
                        android.widget.LinearLayout.LayoutParams lip =
                                new android.widget.LinearLayout.LayoutParams(
                                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
                        row.addView(ip, lip);
                        TextView port = new TextView(getContext());
                        port.setText(portCol);
                        port.setTextColor(0xFFFFFFFF);
                        port.setTextSize(13f);
                        android.widget.LinearLayout.LayoutParams lport =
                                new android.widget.LinearLayout.LayoutParams(
                                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        row.addView(port, lport);
                        TextView st = new TextView(getContext());
                        st.setText("OK");
                        st.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        st.setTextColor(0xFF9CCC65);
                        st.setTextSize(13f);
                        android.widget.LinearLayout.LayoutParams lst =
                                new android.widget.LinearLayout.LayoutParams(
                                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        row.addView(st, lst);
                        return row;
                    }
                };
        list.setAdapter(adapter);

        android.app.AlertDialog.Builder b =
                new android.app.AlertDialog.Builder(this);
        android.app.AlertDialog dlg = b.setView(view).setCancelable(true).create();
        dlg.show();

        list.setOnItemClickListener((p, v, pos, id) -> {
            String s = rows.get(pos);
            int colon = s.lastIndexOf(':');
            ipInput.setText(s.substring(0, colon));
            portInput.setText(s.substring(colon + 1));
            dlg.dismiss();
            connect();
        });
    }

    private int dp(int px) {
        return Math.round(
                px * getResources().getDisplayMetrics().density);
    }

    /** Keep the keyboardVisible flag and the floating-button look in sync
     *  with the actual IME state. When the keyboard is dismissed by the BACK
     *  key, the event is consumed by the IME/system and never reaches
     *  dispatchKeyEvent, so we track the IME window insets instead. */
    private void syncKeyboardState() {
        final android.view.View decor = getWindow().getDecorView();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                decor, (v, insets) -> {
                    int imeBottom = insets.getInsets(
                            android.view.WindowInsets.Type.ime()).bottom;
                    boolean shown = imeBottom > 0;
                    if (shown != keyboardVisible) {
                        keyboardVisible = shown;
                        kbFloatBtn.setAlpha(shown ? 0.4f : 1.0f);
                    }
                    return insets;
                });
    }

    /** Hide the Android soft (native) keyboard. */
    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(remoteView.getWindowToken(), 0);
        }
        keyboardVisible = false;
        kbFloatBtn.setAlpha(1.0f);
    }

    /** Show/hide the Android soft (native) keyboard. */
    private void toggleKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (keyboardVisible) {
            hideKeyboard();
        } else {
            remoteView.requestFocus();
            imm.showSoftInput(remoteView, InputMethodManager.SHOW_IMPLICIT);
            keyboardVisible = true;
            kbFloatBtn.setAlpha(0.4f);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyboardVisible) {
                hideKeyboard();
                return true;
            }
        }
        // Hardware / physical keyboard keys are forwarded here as raw
        // press/release so text lands on the remote untouched.
        if (udpClient == null || !udpClient.isConnected()) {
            return super.dispatchKeyEvent(event);
        }
        int mapped = Keys.mapKeycode(event.getKeyCode());
        if (mapped == Keys.XK_Shift_L) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                udpClient.sendKeyEvent(mapped, true);
                shiftHeld = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                udpClient.sendKeyEvent(mapped, false);
                shiftHeld = false;
            }
            return true;
        }
        if (mapped < 0) {
            int keysym = Keys.eventKeysym(event);
            if (keysym < 0) return super.dispatchKeyEvent(event);
            // Shift is decided from the character the event actually produces
            // (meta state), i.e. uppercase letters and symbols on the shifted
            // level of their physical key. This works even when Android
            // reports the shifted char without a separate KEYCODE_SHIFT event
            // (soft keyboard / IME). The release is keyed by the keysym so we
            // never lift Shift while the user is still physically holding it.
            boolean shiftFor = Keys.needsShift(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (shiftFor && !shiftHeld) {
                    udpClient.sendKeyEvent(Keys.XK_Shift_L, true);
                    shiftHeld = true;
                    injectedShiftKeys.add(keysym);
                }
                udpClient.sendKeyEvent(keysym, true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                udpClient.sendKeyEvent(keysym, false);
                if (injectedShiftKeys.remove(keysym)) {
                    udpClient.sendKeyEvent(Keys.XK_Shift_L, false);
                    shiftHeld = false;
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP) {
            udpClient.sendKeyEvent(mapped, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Point the decoder at the GPU renderer (GLES YUV->RGB on the render
     *  thread) or back at the CPU Bitmap path. Safe to call anytime, including
     *  mid-stream: the decoder checks the renderer per frame. */
    private void applyGpuMode(boolean gpu) {
        remoteView.setGpuRender(gpu);
        VideoDecoder d = decoder;
        if (d == null) return;
        if (gpu) {
            d.setYuvRenderer(remoteView);
        } else {
            d.setYuvRenderer(null);
        }
    }

    private void connect() {
        String host = ipInput.getText().toString().trim();
        final int port = parseIntOrDefault(
                portInput.getText().toString().trim(), 9876);
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter server IP", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences("rd", MODE_PRIVATE).edit()
                .putString(PREF_HOST, host)
                .putInt(PREF_PORT, port)
                .apply();

        statusText.setText("Connecting to " + host + ":" + port + "...");
        videoStatusSet = false;
        connectBtn.setEnabled(false);

        rttSamples.clear();
        lastRttMs = -1;
        remoteView.setLatencyActive(true);

        udpClient = new UdpClient(this);
        udpClient.setFecEnabled(fecToggle.isChecked());
        udpClient.setArqEnabled(arqToggle.isChecked());
        udpClient.setAudioEnabled(audioToggle.isChecked());
        udpClient.setEncryptionEnabled(sslToggle.isChecked());
        final String connectHost = host;
        final int connectPort = port;
        new Thread(() -> {
            try {
                UiLog.i(TAG, "connect(): udp connect " + connectHost + ":" + connectPort);
                udpClient.connect(connectHost, connectPort);
                UiLog.i(TAG, "connect(): udp connected");
            } catch (final Exception e) {
                UiLog.e(TAG, "connect failed", e);
                runOnUiThread(() -> {
                    statusText.setText("Connection failed: " + getStackTrace(e));
                    connectBtn.setEnabled(true);
                });
                udpClient = null;
                return;
            }
        }, "UDP-Connect").start();

        decoder = new VideoDecoder();
        applyGpuMode(gpuToggle.isChecked());
        decoder.setSizeListener(this::onVideoSizeChanged);
        decoder.setFrameRenderer(frame -> remoteView.render(frame));
        decoder.setKeyframeRequester(() -> {
            UdpClient c = udpClient;
            if (c != null) c.sendKeyframeRequest();
        });

        // Start the decoder off the UI thread; codec creation plus configure
        // can block for seconds (MTK), which would otherwise trigger an ANR.
        // It no longer gates the UDP handshake above.
        new Thread(() -> {
            try {
                UiLog.i(TAG, "connect(): decoding start...");
                boolean ok = decoder.start();
                UiLog.i(TAG, "connect(): decoding started ok=" + ok);
            } catch (final Exception e) {
                UiLog.e(TAG, "decoder start failed", e);
                decoder.stop();
                decoder = null;
            }
        }, "Decoder-Start").start();
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String getStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    private void disconnect() {
        if (udpClient != null) {
            udpClient.sendDisconnect();
            udpClient.disconnect();
            udpClient = null;
        }
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
        remoteView.setRemoteSize(1280, 720);
        resetHoldState();
        rttSamples.clear();
        lastRttMs = -1;
        remoteView.setLatencyActive(false);
        statusText.setText(R.string.not_connected);
        connectBtn.setText(R.string.connect);
        connectBtn.setEnabled(true);
    }

    private void resetHoldState() {
        shiftHeld = false;
        injectedShiftKeys.clear();
    }

    private void enterFullscreen() {
        fullscreen = true;
        topBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        logHeader.setVisibility(View.GONE);
        logList.setVisibility(View.GONE);
        exitFullscreenBtn.setVisibility(View.VISIBLE);
        kbFloatBtn.setVisibility(View.VISIBLE);
        remoteView.setStretchToFill(true);
        int m = dp(FULLSCREEN_MARGIN_DP);
        remoteView.setFullscreenMargins(m, m, m, m);
        enterImmersive();
    }

    private void exitFullscreen() {
        fullscreen = false;
        topBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        logHeader.setVisibility(View.VISIBLE);
        logList.setVisibility(View.VISIBLE);
        exitFullscreenBtn.setVisibility(View.GONE);
        kbFloatBtn.setVisibility(View.GONE);
        remoteView.setStretchToFill(false);
        remoteView.setFullscreenMargins(0, 0, 0, 0);
        exitImmersive();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressWarnings("deprecation")
    private void exitImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(true);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // UdpClient.Listener (called from network threads)
    // ------------------------------------------------------------------

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            connectBtn.setEnabled(true);
            connectBtn.setText(R.string.disconnect);
            statusText.setText("Connected. Waiting for video...");
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            if (udpClient != null) {
                udpClient = null;
            }
            if (decoder != null) {
                decoder.stop();
                decoder = null;
            }
            resetHoldState();
            rttSamples.clear();
            lastRttMs = -1;
            remoteView.setLatencyActive(false);
            statusText.setText("Disconnected: " + reason);
            connectBtn.setText(R.string.connect);
            connectBtn.setEnabled(true);
        });
    }

    @Override
    public void onVideoFrame(byte[] accessUnit, boolean isKeyframe) {
        if (decoder != null) {
            if (!videoStatusSet) {
                videoStatusSet = true;
                int w = decoder.getVideoWidth();
                int h = decoder.getVideoHeight();
                final String msg = (w > 0 && h > 0)
                        ? "Connected, video " + w + "x" + h
                        : "Connected, video streaming";
                runOnUiThread(() -> statusText.setText(msg));
            }
            decoder.queueFrame(accessUnit, isKeyframe);
        } else {
            UiLog.i(TAG, "onVideoFrame dropped: decoder null size=" + accessUnit.length);
        }
    }

    @Override
    public void onHeartbeat(long remoteTimeUs) {
        // Same monotonic clock source as UdpClient.nowUs().
        long nowUs = android.os.SystemClock.elapsedRealtime() * 1000L;
        long rttUs = nowUs - remoteTimeUs;
        if (rttUs <= 0 || rttUs > 3_000_000) return; // stale / bogus echo

        double avg;
        double minMs = Double.POSITIVE_INFINITY;
        double maxMs = 0.0;
        int sampleCount;
        synchronized (rttSamples) {
            rttSamples.addLast(rttUs);
            while (rttSamples.size() > MAX_RTT_SAMPLES) rttSamples.pollFirst();
            long sum = 0;
            for (long v : rttSamples) {
                sum += v;
                minMs = Math.min(minMs, v / 1000.0);
                maxMs = Math.max(maxMs, v / 1000.0);
            }
            avg = sum / (double) rttSamples.size() / 1000.0; // ms
            sampleCount = rttSamples.size();
        }
        lastRttMs = avg;
        remoteView.setLatencyMs(avg);
        UiLog.i(TAG, String.format("RTT analysis: sample=%.2f ms avg=%.2f ms " +
                        "min=%.2f ms max=%.2f ms samples=%d",
                rttUs / 1000.0, avg, minMs, maxMs, sampleCount));
    }

    /** Called by VideoDecoder.SizeListener when output dims are known. */
    public void onVideoSizeChanged(final int w, final int h) {
        runOnUiThread(() -> {
            remoteView.setRemoteSize(w, h);
            statusText.setText("Connected, video " + w + "x" + h);
        });
    }

    // ------------------------------------------------------------------
    // RemoteView.InputSender
    // ------------------------------------------------------------------

    @Override
    public void sendMouseMove(int x, int y) {
        if (udpClient != null && udpClient.isConnected()) {
            udpClient.sendMouseMove(x, y);
        }
    }

    @Override
    public void sendMouseButton(int x, int y, int button, boolean pressed) {
        if (udpClient != null && udpClient.isConnected()) {
            udpClient.sendMouseButton(x, y, button, pressed);
        }
    }

    @Override
    public void sendMouseScroll(int x, int y, int dx, int dy) {
        if (udpClient != null && udpClient.isConnected()) {
            udpClient.sendMouseScroll(x, y, dx, dy);
        }
    }

    @Override
    public void sendKey(int keysym, boolean pressed) {
        if (udpClient != null && udpClient.isConnected()) {
            udpClient.sendKeyEvent(keysym, pressed);
        }
    }
}