#include "screen_capture.h"
#include <cstring>
#include <cstdio>
#include <unistd.h>

ScreenCapture::ScreenCapture() {}

ScreenCapture::~ScreenCapture() {
    shutdown();
}

bool ScreenCapture::init(int screen_index) {
    m_display = XOpenDisplay(nullptr);
    if (!m_display) {
        fprintf(stderr, "[ScreenCapture] Cannot open X display\n");
        return false;
    }

    int screen = DefaultScreen(m_display);
    m_root_window = RootWindow(m_display, screen);
    m_width = DisplayWidth(m_display, screen);
    m_height = DisplayHeight(m_display, screen);

    if (!XShmQueryExtension(m_display)) {
        fprintf(stderr, "[ScreenCapture] XShm not supported\n");
        XCloseDisplay(m_display);
        m_display = nullptr;
        return false;
    }

    m_image = XShmCreateImage(m_display,
        DefaultVisual(m_display, screen),
        DefaultDepth(m_display, screen),
        ZPixmap, nullptr, &m_shm_info,
        m_width, m_height);

    if (!m_image) {
        fprintf(stderr, "[ScreenCapture] Cannot create XShm image\n");
        XCloseDisplay(m_display);
        m_display = nullptr;
        return false;
    }

    m_shm_id = shmget(IPC_PRIVATE,
        m_image->bytes_per_line * m_height,
        IPC_CREAT | 0600);

    if (m_shm_id < 0) {
        fprintf(stderr, "[ScreenCapture] shmget failed\n");
        XDestroyImage(m_image);
        m_image = nullptr;
        XCloseDisplay(m_display);
        m_display = nullptr;
        return false;
    }

    m_shm_info.shmid = m_shm_id;
    m_shm_info.shmaddr = (char*)shmat(m_shm_id, nullptr, 0);
    m_shm_info.readOnly = False;

    if (m_shm_info.shmaddr == (void*)-1) {
        fprintf(stderr, "[ScreenCapture] shmat failed\n");
        shmctl(m_shm_id, IPC_RMID, nullptr);
        XDestroyImage(m_image);
        m_image = nullptr;
        XCloseDisplay(m_display);
        m_display = nullptr;
        return false;
    }

    m_image->data = m_shm_info.shmaddr;
    m_buffer = (uint8_t*)m_shm_info.shmaddr;

    XShmAttach(m_display, &m_shm_info);
    XSync(m_display, False);

    m_initialized = true;
    printf("[ScreenCapture] Initialized: %dx%d\n", m_width, m_height);
    return true;
}

void ScreenCapture::shutdown() {
    if (!m_initialized) return;

    if (m_display && m_image) {
        XShmDetach(m_display, &m_shm_info);
    }

    if (m_shm_info.shmaddr && m_shm_info.shmaddr != (void*)-1) {
        shmdt(m_shm_info.shmaddr);
    }

    if (m_shm_id >= 0) {
        shmctl(m_shm_id, IPC_RMID, nullptr);
    }

    if (m_image) {
        m_image->data = nullptr;
        XDestroyImage(m_image);
        m_image = nullptr;
    }

    if (m_display) {
        XCloseDisplay(m_display);
        m_display = nullptr;
    }

    m_initialized = false;
}

CapturedFrame ScreenCapture::capture() {
    CapturedFrame frame = {};

    if (!m_initialized) return frame;

    XShmGetImage(m_display, m_root_window, m_image, 0, 0, AllPlanes);

    frame.data = (const uint8_t*)m_buffer;
    frame.width = m_width;
    frame.height = m_height;
    frame.stride = m_image->bytes_per_line;

    return frame;
}
