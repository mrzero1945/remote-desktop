#include "input_handler.h"
#include <X11/keysym.h>
#include <X11/extensions/XTest.h>
#include <cstdio>

InputHandler::InputHandler() {}

InputHandler::~InputHandler() {
    shutdown();
}

bool InputHandler::init() {
    m_display = XOpenDisplay(nullptr);
    if (!m_display) {
        fprintf(stderr, "[InputHandler] Cannot open X display\n");
        return false;
    }

    int screen = DefaultScreen(m_display);
    m_root_window = RootWindow(m_display, screen);
    m_screen_width = DisplayWidth(m_display, screen);
    m_screen_height = DisplayHeight(m_display, screen);

    int event_base = 0, error_base = 0, major = 0, minor = 0;
    Bool supported = XTestQueryExtension(m_display, &event_base, &error_base,
                                         &major, &minor);
    if (!supported) {
        fprintf(stderr, "[InputHandler] XTest extension not supported\n");
        XCloseDisplay(m_display);
        m_display = nullptr;
        return false;
    }

    printf("[InputHandler] Initialized\n");
    return true;
}

void InputHandler::shutdown() {
    if (m_display) {
        XCloseDisplay(m_display);
        m_display = nullptr;
    }
}

void InputHandler::handle_mouse_move(int32_t x, int32_t y) {
    if (!m_display) return;

    x = (x < 0) ? 0 : (x >= m_screen_width ? m_screen_width - 1 : x);
    y = (y < 0) ? 0 : (y >= m_screen_height ? m_screen_height - 1 : y);

    XTestFakeMotionEvent(m_display, -1, x, y, CurrentTime);
    XFlush(m_display);
}

void InputHandler::handle_mouse_button(int32_t x, int32_t y,
                                       Protocol::InputButtonType button, bool pressed) {
    if (!m_display) return;

    handle_mouse_move(x, y);

    unsigned int btn = translate_button(button);
    XTestFakeButtonEvent(m_display, btn, pressed ? True : False, CurrentTime);
    XFlush(m_display);
}

void InputHandler::handle_mouse_scroll(int32_t x, int32_t y,
                                       int32_t delta_x, int32_t delta_y) {
    if (!m_display) return;

    handle_mouse_move(x, y);

    if (delta_y > 0) {
        for (int32_t i = 0; i < delta_y; i++) {
            XTestFakeButtonEvent(m_display, 4, True, CurrentTime);
            XTestFakeButtonEvent(m_display, 4, False, CurrentTime);
        }
    } else if (delta_y < 0) {
        for (int32_t i = 0; i < -delta_y; i++) {
            XTestFakeButtonEvent(m_display, 5, True, CurrentTime);
            XTestFakeButtonEvent(m_display, 5, False, CurrentTime);
        }
    }

    if (delta_x > 0) {
        for (int32_t i = 0; i < delta_x; i++) {
            XTestFakeButtonEvent(m_display, 6, True, CurrentTime);
            XTestFakeButtonEvent(m_display, 6, False, CurrentTime);
        }
    } else if (delta_x < 0) {
        for (int32_t i = 0; i < -delta_x; i++) {
            XTestFakeButtonEvent(m_display, 7, True, CurrentTime);
            XTestFakeButtonEvent(m_display, 7, False, CurrentTime);
        }
    }

    XFlush(m_display);
}

void InputHandler::handle_keyboard(uint32_t keysym, bool pressed) {
    if (!m_display) return;

    int keycode = translate_keycode(keysym);
    if (keycode < 0) return;

    XTestFakeKeyEvent(m_display, keycode, pressed ? True : False, CurrentTime);
    XFlush(m_display);
}

unsigned int InputHandler::translate_button(Protocol::InputButtonType btn) {
    switch (btn) {
    case Protocol::InputButtonType::LEFT:   return 1;
    case Protocol::InputButtonType::MIDDLE: return 2;
    case Protocol::InputButtonType::RIGHT:  return 3;
    default: return 1;
    }
}

int InputHandler::translate_keycode(uint32_t keysym) {
    KeySym sym = (KeySym)keysym;
    return XKeysymToKeycode(m_display, sym);
}
