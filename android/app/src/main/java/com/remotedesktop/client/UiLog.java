package com.remotedesktop.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Logs to both logcat and an on-screen list so problems can be read on the
 * device without adb. Drop-in replacement for android.util.Log: the i/w/e/d/v
 * signatures mirror Log's.
 */
public final class UiLog {
    private static final int MAX_LINES = 500;
    private static final long REFRESH_MS = 200;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static ArrayAdapter<String> adapter;
    private static boolean dirty = false;

    private static final Runnable REFRESH = () -> {
        dirty = false;
        ArrayAdapter<String> a = adapter;
        if (a == null) return;
        ArrayList<String> snapshot;
        synchronized (LINES) {
            snapshot = new ArrayList<>(LINES);
        }
        a.clear();
        a.addAll(snapshot);
        a.notifyDataSetChanged();
    };

    private UiLog() {
    }

    public static void attach(ListView view) {
        ArrayAdapter<String> a = new ArrayAdapter<>(view.getContext(),
                R.layout.log_line, R.id.logLineText, new ArrayList<>());
        view.setAdapter(a);
        adapter = a;
        schedule();
    }

    public static void i(String tag, String msg) { Log.i(tag, msg); add("I/" + tag, msg); }

    public static void i(String tag, String msg, Throwable t) {
        Log.i(tag, msg, t);
        add("I/" + tag, msg + " " + t);
    }

    public static void w(String tag, String msg) { Log.w(tag, msg); add("W/" + tag, msg); }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        add("W/" + tag, msg + " " + t);
    }

    public static void e(String tag, String msg) { Log.e(tag, msg); add("E/" + tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        add("E/" + tag, msg + " " + t);
    }

    public static void d(String tag, String msg) { Log.d(tag, msg); add("D/" + tag, msg); }

    public static void v(String tag, String msg) { Log.v(tag, msg); add("V/" + tag, msg); }

    private static void add(String tag, String msg) {
        String line = FMT.format(new Date()) + " " + tag + ": " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
        schedule();
    }

    private static void schedule() {
        if (dirty) return;
        dirty = true;
        MAIN.postDelayed(REFRESH, REFRESH_MS);
    }
}
