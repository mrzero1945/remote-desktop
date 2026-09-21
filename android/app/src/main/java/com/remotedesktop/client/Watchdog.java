package com.remotedesktop.client;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects a blocked main thread (the usual cause of "Input dispatching timed
 * out" ANRs) and dumps its stack to the on-screen log, so the stall can be
 * diagnosed without adb/ANR traces.
 */
public final class Watchdog {
    private static final String TAG = "Watchdog";
    private static final long INTERVAL_MS = 2500;
    private static boolean running = false;

    private Watchdog() {
    }

    public static synchronized void start() {
        if (running) return;
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            while (running) {
                final AtomicBoolean acked = new AtomicBoolean(false);
                main.post(() -> acked.set(true));
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!acked.get()) {
                    UiLog.e(TAG, "main thread unresponsive > " + INTERVAL_MS + "ms");
                    dumpMain();
                }
            }
        }, "MainWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void dumpMain() {
        Thread main = Looper.getMainLooper().getThread();
        StackTraceElement[] st = main.getStackTrace();
        for (int i = 0; i < st.length && i < 30; i++) {
            UiLog.e(TAG, "    at " + st[i]);
        }
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            if (th == main) continue;
            if (!th.getName().equals("UDP-Receiver")
                    && !th.getName().startsWith("Audio")
                    && !th.getName().startsWith("H264")
                    && !th.getName().equals("MainWatchdog")) continue;
            StackTraceElement[] s = th.getStackTrace();
            UiLog.e(TAG, "  thread " + th.getName() + " state=" + th.getState());
            for (int i = 0; i < s.length && i < 12; i++) {
                UiLog.e(TAG, "    at " + s[i]);
            }
        }
    }
}
