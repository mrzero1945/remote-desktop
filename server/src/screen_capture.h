#pragma once

#include <cstdint>
#include <memory>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XShm.h>
#include <sys/ipc.h>
#include <sys/shm.h>

struct CapturedFrame {
    const uint8_t* data;
    int width;
    int height;
    int stride;
};

class ScreenCapture {
public:
    ScreenCapture();
    ~ScreenCapture();

    bool init(int screen_index = 0);
    void shutdown();

    CapturedFrame capture();
    int width() const { return m_width; }
    int height() const { return m_height; }

private:
    Display* m_display = nullptr;
    Window m_root_window = 0;
    XShmSegmentInfo m_shm_info = {};
    XImage* m_image = nullptr;
    uint8_t* m_buffer = nullptr;
    int m_shm_id = -1;
    int m_width = 0;
    int m_height = 0;
    bool m_initialized = false;
};
