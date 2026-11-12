#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

//
// Created by maks on 17.09.2022.
//

#define STATE_RENDERER_ALIVE 0
#define STATE_RENDERER_NEW_WINDOW 1
static const char* g_LogTag = "GLBridge";
static __thread render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;
static __thread unsigned int g_swapCount;
static __thread bool g_loggedNullSwap;

static void logEglFailure(const char* stage) {
    EGLint error = eglGetError_p();
    __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                        "SD-EGL-DIAG %s failed: EGL=0x%04x display=%p current=%p surface=%p",
                        stage, error, g_EglDisplay, currentBundle,
                        currentBundle ? currentBundle->surface : EGL_NO_SURFACE);
}

bool gl_init() {
    EGLint major = 0, minor = 0;
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "SD-EGL-DIAG gl_init begin");
    dlsym_EGL();
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        logEglFailure("eglGetDisplay");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, &major, &minor) != EGL_TRUE) {
        logEglFailure("eglInitialize");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG eglInitialize OK display=%p version=%d.%d",
                        g_EglDisplay, major, minor);
    return true;
}

render_window_t* gl_init_context(render_window_t *share) {
    render_window_t* bundle = calloc(1, sizeof(render_window_t));
    if (bundle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "SD-EGL-DIAG gl_init_context: allocation failed");
        return NULL;
    }

    EGLint egl_attributes[] = { EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE,
        EGL_WINDOW_BIT|EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
    EGLint num_configs = 0;
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG gl_init_context begin display=%p share=%p",
                        g_EglDisplay, share);

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE) {
        logEglFailure("eglChooseConfig(count)");
        free(bundle);
        return NULL;
    }
    if (num_configs == 0) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                            "SD-EGL-DIAG eglChooseConfig returned zero configs");
        free(bundle);
        return NULL;
    }

    if (eglChooseConfig_p(g_EglDisplay, egl_attributes, &bundle->config, 1, &num_configs) != EGL_TRUE
            || num_configs < 1 || bundle->config == NULL) {
        logEglFailure("eglChooseConfig(select)");
        free(bundle);
        return NULL;
    }
    if (eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format) != EGL_TRUE) {
        logEglFailure("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID)");
        free(bundle);
        return NULL;
    }

    const char* renderer = getenv("POJAV_RENDERER");
    EGLBoolean bindResult = (renderer != NULL && strncmp(renderer, "opengles3_desktopgl", 19) == 0)
            ? eglBindAPI_p(EGL_OPENGL_API) : eglBindAPI_p(EGL_OPENGL_ES_API);
    if (!bindResult) {
        logEglFailure("eglBindAPI");
        free(bundle);
        return NULL;
    }

    const char* esEnv = getenv("LIBGL_ES");
    int libgl_es = esEnv == NULL ? 2 : (int) strtol(esEnv, NULL, 0);
    if (libgl_es < 1 || libgl_es > 3) libgl_es = 2;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config,
            share == NULL ? EGL_NO_CONTEXT : share->context, egl_context_attributes);

    if (bundle->context == EGL_NO_CONTEXT) {
        logEglFailure("eglCreateContext");
        free(bundle);
        return NULL;
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG eglCreateContext OK context=%p ES=%d config=%p format=%d",
                        bundle->context, libgl_es, bundle->config, bundle->format);
    return bundle;
}

void gl_swap_surface(render_window_t* bundle) {
    if (bundle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "SD-EGL-DIAG gl_swap_surface called with null bundle");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG surface transition begin bundle=%p oldSurface=%p oldWindow=%p newWindow=%p",
                        bundle, bundle->surface, bundle->nativeSurface, bundle->newNativeSurface);
    if (bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
        bundle->nativeSurface = NULL;
    }
    if (bundle->surface != NULL) {
        if (eglDestroySurface_p(g_EglDisplay, bundle->surface) != EGL_TRUE) {
            logEglFailure("eglDestroySurface");
        }
        bundle->surface = EGL_NO_SURFACE;
    }
    if (bundle->newNativeSurface != NULL) {
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        int width = ANativeWindow_getWidth(bundle->nativeSurface);
        int height = ANativeWindow_getHeight(bundle->nativeSurface);
        int geometry = ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG creating window surface window=%p size=%dx%d format=%d geometryResult=%d",
                            bundle->nativeSurface, width, height, bundle->format, geometry);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
        if (bundle->surface == EGL_NO_SURFACE) {
            logEglFailure("eglCreateWindowSurface");
            return;
        }
    } else {
        const EGLint pbuffer_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        bundle->nativeSurface = NULL;
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
        if (bundle->surface == EGL_NO_SURFACE) {
            logEglFailure("eglCreatePbufferSurface");
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG created 1x1 pbuffer surface=%p", bundle->surface);
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG surface transition complete surface=%p window=%p",
                        bundle->surface, bundle->nativeSurface);
}

void gl_make_current(render_window_t* bundle) {
    if (bundle == NULL) {
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
            __android_log_print(ANDROID_LOG_INFO, g_LogTag, "SD-EGL-DIAG detached current EGL context");
        } else {
            logEglFailure("eglMakeCurrent(detach)");
        }
        return;
    }
    bool hasSetMainWindow = false;
    if (pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = bundle;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG main window bundle=%p nativeWindow=%p",
                            bundle, pojav_environ->pojavWindow);
    }
    __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                        "SD-EGL-DIAG makeCurrent begin bundle=%p context=%p surface=%p native=%p",
                        bundle, bundle->context, bundle->surface, bundle->newNativeSurface);
    if (bundle->surface == EGL_NO_SURFACE || bundle->surface == NULL) {
        gl_swap_surface(bundle);
    }
    if (bundle->surface == EGL_NO_SURFACE || bundle->surface == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "SD-EGL-DIAG makeCurrent aborted: no EGL surface");
        return;
    }
    if (eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context) == EGL_TRUE) {
        currentBundle = bundle;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG eglMakeCurrent OK bundle=%p surface=%p",
                            bundle, bundle->surface);
    } else {
        logEglFailure("eglMakeCurrent(bind)");
        if (hasSetMainWindow) {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface(pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
    }
}

void gl_swap_buffers() {
    if (currentBundle == NULL) {
        if (!g_loggedNullSwap) {
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                                "SD-EGL-DIAG swap requested with no current EGL bundle");
            g_loggedNullSwap = true;
        }
        return;
    }
    if (currentBundle->surface == EGL_NO_SURFACE || currentBundle->surface == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                            "SD-EGL-DIAG swap requested with no EGL surface bundle=%p", currentBundle);
        return;
    }

    if (currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG applying new native window bundle=%p", currentBundle);
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
            logEglFailure("eglMakeCurrent(detach-for-window-change)");
            return;
        }
        gl_swap_surface(currentBundle);
        if (currentBundle->surface == EGL_NO_SURFACE || currentBundle->surface == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                                "SD-EGL-DIAG new window transition produced no surface");
            return;
        }
        if (eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                             currentBundle->surface, currentBundle->context) != EGL_TRUE) {
            logEglFailure("eglMakeCurrent(rebind-new-window)");
            return;
        }
        currentBundle->state = STATE_RENDERER_ALIVE;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG new window rebound surface=%p", currentBundle->surface);
    }

    EGLBoolean swapped = eglSwapBuffers_p(g_EglDisplay, currentBundle->surface);
    if (swapped == EGL_TRUE) {
        g_swapCount++;
        if (g_swapCount <= 3 || (g_swapCount % 300) == 0) {
            __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                                "SD-EGL-DIAG eglSwapBuffers OK frame=%u surface=%p",
                                g_swapCount, currentBundle->surface);
        }
        return;
    }

    EGLint error = eglGetError_p();
    __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                        "SD-EGL-DIAG eglSwapBuffers FAILED frame=%u EGL=0x%04x surface=%p window=%p",
                        g_swapCount + 1, error, currentBundle->surface, currentBundle->nativeSurface);
    if (error == EGL_BAD_SURFACE) {
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
            logEglFailure("eglMakeCurrent(detach-after-bad-surface)");
            return;
        }
        currentBundle->newNativeSurface = NULL;
        gl_swap_surface(currentBundle);
        if (currentBundle->surface == EGL_NO_SURFACE || currentBundle->surface == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                                "SD-EGL-DIAG bad-surface recovery could not create pbuffer");
            return;
        }
        if (eglMakeCurrent_p(g_EglDisplay, currentBundle->surface,
                             currentBundle->surface, currentBundle->context) != EGL_TRUE) {
            logEglFailure("eglMakeCurrent(bad-surface-recovery)");
            return;
        }
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG bad-surface recovery complete; awaiting window change");
    }
}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if (pojav_environ->force_vsync) swapInterval = 1;
    if (eglSwapInterval_p(g_EglDisplay, swapInterval) != EGL_TRUE) {
        logEglFailure("eglSwapInterval");
    } else {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag,
                            "SD-EGL-DIAG swap interval=%d", swapInterval);
    }
}
