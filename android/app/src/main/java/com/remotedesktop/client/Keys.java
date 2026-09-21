package com.remotedesktop.client;

import android.view.KeyEvent;

/**
 * Maps Android key events to X11 keysyms and handles the
 * shift-modifier dance for producing correct on-screen output.
 */
public final class Keys {

    // X11 keysyms
    static final int XK_Return     = 0xFF0D;
    static final int XK_Escape     = 0xFF1B;
    static final int XK_BackSpace  = 0xFF08;
    static final int XK_Tab        = 0xFF09;
    static final int XK_Home       = 0xFF50;
    static final int XK_Left       = 0xFF51;
    static final int XK_Up         = 0xFF52;
    static final int XK_Right      = 0xFF53;
    static final int XK_Down       = 0xFF54;
    static final int XK_PGUp       = 0xFF55;
    static final int XK_PGDn       = 0xFF56;
    static final int XK_End        = 0xFF57;
    static final int XK_Insert     = 0xFF63;
    static final int XK_Delete     = 0xFFFF;
    static final int XK_Shift_L    = 0xFFE1;
    static final int XK_Control_L  = 0xFFE3;
    static final int XK_Alt_L      = 0xFFE9;
    static final int XK_Super_L    = 0xFFEB;

    private static final int XK_0 = 0x30;
    private static final int XK_1 = 0x31;
    private static final int XK_9 = 0x39;

    // Characters that require a held Shift on a US keyboard layout.
    private static final String SHIFTED_PUNCT = "!@#$%^&*()_+{}|:\"<>?";

    /** Map a single character to the (unshifted) X11 keysym needed to
     *  produce it with the Shift modifier. Returns -1 if unsupported. */
    public static int keysymForChar(char c) {
        switch (c) {
            case '\n': return XK_Return;
            case ' ': return 0x20;
            case '\t': return XK_Tab;
            case '\b': return XK_BackSpace;
            case 0x7F: return XK_Delete;
            case '!': return '1';
            case '@': return '2';
            case '#': return '3';
            case '$': return '4';
            case '%': return '5';
            case '^': return '6';
            case '&': return '7';
            case '*': return '8';
            case '(': return '9';
            case ')': return '0';
            case '_': return '-';
            case '+': return '=';
            case '{': return '[';
            case '}': return ']';
            case '|': return '\\';
            case ':': return ';';
            case '"': return '\'';
            case '<': return ',';
            case '>': return '.';
            case '?': return '/';
            default: break;
        }
        if (c >= 'A' && c <= 'Z') return (int) 'a' + (c - 'A');
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return c;
        if (c >= 0x20 && c <= 0x7E) return c;
        return -1;
    }

    /** Whether producing this character requires a held Shift on the
     *  remote (uppercase letters and shifted punctuation). */
    public static boolean needsShift(char c) {
        if (c >= 'A' && c <= 'Z') return true;
        return SHIFTED_PUNCT.indexOf(c) >= 0;
    }

    public static int mapKeycode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:       return XK_Return;
            case KeyEvent.KEYCODE_SPACE:       return 0x20;
            case KeyEvent.KEYCODE_BACK:        return XK_Escape;
            case KeyEvent.KEYCODE_DEL:         return XK_BackSpace;
            case KeyEvent.KEYCODE_TAB:         return XK_Tab;
            case KeyEvent.KEYCODE_MOVE_HOME:   return XK_Home;
            case KeyEvent.KEYCODE_DPAD_LEFT:   return XK_Left;
            case KeyEvent.KEYCODE_DPAD_UP:     return XK_Up;
            case KeyEvent.KEYCODE_DPAD_RIGHT:  return XK_Right;
            case KeyEvent.KEYCODE_DPAD_DOWN:   return XK_Down;
            case KeyEvent.KEYCODE_PAGE_UP:     return XK_PGUp;
            case KeyEvent.KEYCODE_PAGE_DOWN:   return XK_PGDn;
            case KeyEvent.KEYCODE_MOVE_END:    return XK_End;
            case KeyEvent.KEYCODE_INSERT:      return XK_Insert;
            case KeyEvent.KEYCODE_FORWARD_DEL: return XK_Delete;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return XK_Shift_L;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:  return XK_Control_L;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:   return XK_Alt_L;
            case KeyEvent.KEYCODE_HOME:        return XK_Super_L;
            default: return -1;
        }
    }

    /**
     * Given a KeyEvent, compute the primary keysym to press (returns -1
     * if the event has no usable character / mapping).
     */
    public static int eventKeysym(KeyEvent ev) {
        int keyCode = ev.getKeyCode();
        int mapped = mapKeycode(keyCode);
        if (mapped >= 0) return mapped;

        int unicode = ev.getUnicodeChar(ev.getMetaState() & ~KeyEvent.META_SHIFT_ON);
        if (unicode == 0 || unicode > 0x7F) return -1;

        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return XK_0 + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return (int) 'a' + (keyCode - KeyEvent.KEYCODE_A);
        }

        return unicode;
    }

    /**
     * Whether the desired char requires a held shift on the server
     * (uppercase letters, and symbols that live on shifted keys).
     */
    public static boolean needsShift(int unicode) {
        return unicode >= 'A' && unicode <= 'Z';
    }

    /**
     * Whether producing this KeyEvent's character requires a held Shift on
     * the remote. Decided from the event's own meta state (the character it
     * produced vs the character the same key produces without Shift), so it
     * also covers shifted symbols like '!' or '_' and works when Android
     * reports the shifted character without a separate KEYCODE_SHIFT event.
     */
    public static boolean needsShift(KeyEvent ev) {
        int meta = ev.getMetaState();
        int typed = ev.getUnicodeChar(meta);
        if (typed <= 0 || typed > 0x7F) return false;
        int unshifted = ev.getUnicodeChar(meta & ~KeyEvent.META_SHIFT_ON);
        if (unshifted <= 0 || unshifted > 0x7F) return false;
        return typed != unshifted;
    }
}