#pragma once

#include "protocol.h"
#include <X11/Xlib.h>

class InputHandler {
public:
    InputHandler();
    ~InputHandler();

    bool init();
    void shutdown();

    void handle_mouse_move(int32_t x, int32_t y);
    void handle_mouse_button(int32_t x, int32_t y,
                            Protocol::InputButtonType button, bool pressed);
    void handle_mouse_scroll(int32_t x, int32_t y,
                            int32_t delta_x, int32_t delta_y);
    void handle_keyboard(uint32_t keysym, bool pressed);

    void set_screen_dimensions(int width, int height) {
        m_screen_width = width;
        m_screen_height = height;
    }

private:
    Display* m_display = nullptr;
    Window m_root_window = 0;
    int m_screen_width = 0;
    int m_screen_height = 0;

    unsigned int translate_button(Protocol::InputButtonType btn);
    int translate_keycode(uint32_t keysym);
};
