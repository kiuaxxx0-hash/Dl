/*
 * Derived from PojavLauncher native bridge code.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>
#include <pthread.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/renderer_config.h"
#include "ctxbridges/virgl_bridge.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include <time.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

static bool env_enabled(const char* value);
typedef const char* (*db_native_glfw_abi_version_fn)(void);
typedef int (*db_native_glfw_has_required_exports_fn)(void);
typedef void (*db_native_glfw_configure_global_fn)(const char*, const char*, int);
typedef void (*db_native_glfw_surface_window_fn)(ANativeWindow*, int, int);
typedef void (*db_native_glfw_void_fn)(void);
typedef void* (*db_native_glfw_create_context_fn)(void*);
typedef void (*db_native_glfw_make_current_fn)(void*);
typedef void (*db_native_glfw_swap_buffers_fn)(void);

static void* db_native_glfw_handle = NULL;
static int db_native_glfw_surface_attached = 0;
static uint64_t db_native_glfw_swap_counter = 0;
static void* db_fast_exit_watchdog_thread(void* unused) {
    (void) unused;
    sleep(4);
    printf("EGLBridge: fast exit watchdog exiting cleanly before Minecraft shutdown watchdog\n");
    fflush(stdout);
    _exit(0);
    return NULL;
}

static void db_arm_fast_exit_watchdog(void) {
    pthread_t thread;
    int rc = pthread_create(&thread, NULL, db_fast_exit_watchdog_thread, NULL);
    if (rc == 0) {
        pthread_detach(thread);
        printf("EGLBridge: fast exit watchdog armed\n");
        fflush(stdout);
    } else {
        printf("EGLBridge: fast exit watchdog failed to arm rc=%d\n", rc);
        fflush(stdout);
    }
}


static int db_native_glfw_enabled(void) {
    const char* value = getenv("DROIDBRIDGE_NATIVE_GLFW_KGSL");
    return env_enabled(value);
}

static void db_native_glfw_log_exports(void* handle) {
    if (handle == NULL) return;
    db_native_glfw_abi_version_fn version =
            (db_native_glfw_abi_version_fn) dlsym(handle, "droidbridge_native_glfw_abi_version");
    db_native_glfw_has_required_exports_fn has_exports =
            (db_native_glfw_has_required_exports_fn) dlsym(handle, "droidbridge_native_glfw_has_required_exports");
    printf("DroidBridgeNativeGLFW: loaded ABI=%s requiredExports=%d\n",
           version != NULL ? version() : "<missing-version-symbol>",
           has_exports != NULL ? has_exports() : 0);
}

static void* db_native_glfw_open(void) {
    if (db_native_glfw_handle != NULL) return db_native_glfw_handle;

    const char* explicit_path = getenv("DROIDBRIDGE_NATIVE_GLFW_LIB");
    if (explicit_path != NULL && explicit_path[0] != '\0') {
        dlerror();
        db_native_glfw_handle = dlopen(explicit_path, RTLD_NOW | RTLD_GLOBAL);
        if (db_native_glfw_handle != NULL) {
            printf("DroidBridgeNativeGLFW: dlopen explicit %s handle=%p\n", explicit_path, db_native_glfw_handle);
            db_native_glfw_log_exports(db_native_glfw_handle);
            return db_native_glfw_handle;
        }
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: explicit dlopen failed %s error=%s\n", explicit_path, error ? error : "unknown");
    }

    dlerror();
    db_native_glfw_handle = dlopen("libdroidbridge_native_glfw_v82.so", RTLD_NOW | RTLD_GLOBAL);
    if (db_native_glfw_handle != NULL) {
        printf("DroidBridgeNativeGLFW: dlopen soname handle=%p\n", db_native_glfw_handle);
        db_native_glfw_log_exports(db_native_glfw_handle);
        return db_native_glfw_handle;
    }

    const char* error = dlerror();
    printf("DroidBridgeNativeGLFW: dlopen libdroidbridge_native_glfw_v82.so failed: %s\n", error ? error : "unknown");
    return NULL;
}

static void db_native_glfw_configure_if_available(void* handle) {
    if (handle == NULL) return;
    db_native_glfw_configure_global_fn configure =
            (db_native_glfw_configure_global_fn) dlsym(handle, "droidbridge_native_glfw_configure_global");
    if (configure == NULL) {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_configure_global error=%s\n", error ? error : "unknown");
        return;
    }

    const char* egl = getenv("DROIDBRIDGE_NATIVE_GLFW_EGL");
    if (egl == NULL || egl[0] == '\0') egl = getenv("DROIDBRIDGE_MESA_EGL");
    if (egl == NULL || egl[0] == '\0') egl = getenv("POJAVEXEC_EGL");
    if (egl == NULL || egl[0] == '\0') egl = "libEGL_mesa.so";

    const char* driver = getenv("DROIDBRIDGE_NATIVE_GLFW_DRIVER");
    if (driver == NULL || driver[0] == '\0') driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    if (driver == NULL || driver[0] == '\0') driver = "kgsl";

    configure(egl, driver, 1);
    printf("DroidBridgeNativeGLFW: configured egl=%s driver=%s desktopGl=1\n", egl, driver);
}

static void db_native_glfw_attach_existing_window(const char* reason) {
    if (!db_native_glfw_enabled()) return;
    if (db_native_glfw_surface_attached) {
        printf("DroidBridgeNativeGLFW: surface already attached, skip reason=%s\n", reason ? reason : "unknown");
    }

    void* handle = db_native_glfw_open();
    db_native_glfw_configure_if_available(handle);
    if (handle == NULL) return;

    if (pojav_environ->pojavWindow == NULL) {
        printf("DroidBridgeNativeGLFW: no ANativeWindow available for reason=%s\n", reason ? reason : "unknown");
        return;
    }

    if (!db_native_glfw_surface_attached) {
        int width = ANativeWindow_getWidth(pojav_environ->pojavWindow);
        int height = ANativeWindow_getHeight(pojav_environ->pojavWindow);
        if (width <= 0) width = pojav_environ->savedWidth > 0 ? pojav_environ->savedWidth : 1;
        if (height <= 0) height = pojav_environ->savedHeight > 0 ? pojav_environ->savedHeight : 1;

        db_native_glfw_surface_window_fn surface_window =
                (db_native_glfw_surface_window_fn) dlsym(handle, "droidbridge_native_glfw_surface_window");
        if (surface_window == NULL) {
            const char* error = dlerror();
            printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_surface_window error=%s\n", error ? error : "unknown");
            return;
        }

        surface_window(pojav_environ->pojavWindow, width, height);
        db_native_glfw_surface_attached = 1;
        printf("DroidBridgeNativeGLFW: %s handed Surface to nativeglfw size=%dx%d\n",
               reason ? reason : "unknown", width, height);
    }

    db_native_glfw_make_current_fn make_current =
            (db_native_glfw_make_current_fn) dlsym(handle, "droidbridge_native_glfw_make_current");
    if (make_current != NULL) {
        make_current((void*) pojav_environ->pojavWindow);
        printf("DroidBridgeNativeGLFW: %s made nativeglfw context current\n", reason ? reason : "unknown");
    } else {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_make_current error=%s\n", error ? error : "unknown");
    }
}

__attribute__((visibility("default"), used)) void* droidbridge_pojavexec_native_glfw_acquire_opengl_handle(void) {
    if (!db_native_glfw_enabled()) return NULL;

    void* handle = db_native_glfw_open();
    if (handle == NULL) return NULL;

    if (!db_native_glfw_surface_attached && pojav_environ != NULL && pojav_environ->pojavWindow != NULL) {
        db_native_glfw_attach_existing_window("linkerhook-acquire");
    } else {
        db_native_glfw_configure_if_available(handle);
    }

    db_native_glfw_make_current_fn make_current =
            (db_native_glfw_make_current_fn) dlsym(handle, "droidbridge_native_glfw_make_current");
    if (make_current != NULL) {
        make_current(pojav_environ != NULL ? (void*) pojav_environ->pojavWindow : NULL);
    }

    typedef void* (*db_native_glfw_acquire_opengl_handle_fn)(void);
    db_native_glfw_acquire_opengl_handle_fn acquire =
            (db_native_glfw_acquire_opengl_handle_fn) dlsym(handle, "droidbridge_native_glfw_acquire_opengl_handle");
    if (acquire == NULL) {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: pojavexec acquire missing native acquire symbol error=%s\n", error ? error : "unknown");
        return NULL;
    }

    void* egl_handle = acquire();
    printf("DroidBridgeNativeGLFW: pojavexec acquire_opengl_handle returned %p\n", egl_handle);
    return egl_handle;
}


EGLConfig config;
struct PotatoBridge potatoBridge;

void* loadTurnipVulkan(void);
void calculateFPS(void);
void load_vulkan(void);

EXTERNAL_API void pojavTerminate(void) {
    printf("EGLBridge: Terminating\n");
    fflush(stdout);
    db_arm_fast_exit_watchdog();

    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_void_fn terminate =
                    (db_native_glfw_void_fn) dlsym(handle, "droidbridge_native_glfw_terminate");
            if (terminate != NULL) terminate();
        }
        return;
    }

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

        case RENDERER_VK_ZINK: {
            // Nothing to do here.
        } break;
    }
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow(JNIEnv* env,
                                                                                 ABI_COMPAT jclass clazz,
                                                                                 jobject surface) {
    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);

    if (db_native_glfw_enabled()) {
        db_native_glfw_surface_attached = 0;
        db_native_glfw_attach_existing_window("setupBridgeWindow");
        return;
    }

    if (br_setup_window) {
        br_setup_window();
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                            ABI_COMPAT jclass clazz) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_void_fn surface_destroyed =
                    (db_native_glfw_void_fn) dlsym(handle, "droidbridge_native_glfw_surface_destroyed");
            if (surface_destroyed != NULL) surface_destroyed();
        }
        db_native_glfw_surface_attached = 0;
    }

    if (pojav_environ->pojavWindow != NULL) {
        ANativeWindow_release(pojav_environ->pojavWindow);
        pojav_environ->pojavWindow = NULL;
    }
}

EXTERNAL_API void* pojavGetCurrentContext(void) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_create_context_fn create_context =
                    (db_native_glfw_create_context_fn) dlsym(handle, "droidbridge_native_glfw_create_context");
            if (create_context != NULL) return create_context(NULL);
        }
        return pojav_environ->pojavWindow;
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        return virglGetCurrentContext();
    }

    return br_get_current();
}

static void set_vulkan_ptr(void* ptr) {
    if (ptr == NULL) {
        unsetenv("VULKAN_PTR");
        return;
    }

    char envval[64];
    sprintf(envval, "%" PRIxPTR, (uintptr_t) ptr);
    setenv("VULKAN_PTR", envval, 1);
}

static void* try_open_vulkan_loader(void) {
    dlerror();
    void* vulkanPtr = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so.1, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* firstError = dlerror();
    printf("OSMDroid: libvulkan.so.1 failed: %s\n", firstError != NULL ? firstError : "unknown error");

    dlerror();
    vulkanPtr = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* secondError = dlerror();
    printf("OSMDroid: libvulkan.so failed: %s\n", secondError != NULL ? secondError : "unknown error");
    return NULL;
}

void load_vulkan(void) {
    const char* zinkPreferSystemDriver = getenv("POJAV_ZINK_PREFER_SYSTEM_DRIVER");
    int deviceApiLevel = android_get_device_api_level();

    if (zinkPreferSystemDriver == NULL && deviceApiLevel >= 28) {
#ifdef ADRENO_POSSIBLE
        void* result = loadTurnipVulkan();
        if (result != NULL) {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }

    printf("OSMDroid: Loading Vulkan regularly...\n");
    void* vulkanPtr = try_open_vulkan_loader();
    set_vulkan_ptr(vulkanPtr);
}

static bool env_is(const char* value, const char* expected) {
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

static bool env_enabled(const char* value) {
    return value != NULL
            && value[0] != '\0'
            && strcmp(value, "0") != 0
            && strcmp(value, "false") != 0
            && strcmp(value, "FALSE") != 0;
}


static bool is_droidbridge_mesa_zink_turnip(const char* renderer,
                                            const char* mesa_mode,
                                            const char* mesa_driver,
                                            const char* renderer_mesa_mode,
                                            const char* droidbridge_mesa) {
    if (!env_enabled(droidbridge_mesa)) {
        return false;
    }

    return env_is(mesa_mode, "zink_turnip")
            || env_is(renderer_mesa_mode, "zink_turnip")
            || (env_is(renderer, "vulkan_zink") && env_is(mesa_driver, "zink"));
}

static void configure_droidbridge_mesa_zink_turnip_desktop_gl(void) {
    printf("EGLBridge: Using DroidBridge Mesa zink_turnip desktop GL bridge\n");

    setenv("DROIDBRIDGE_MESA", "1", 1);
    setenv("DROIDBRIDGE_MESA_MODE", "zink_turnip", 1);
    setenv("DROIDBRIDGE_MESA_DRIVER", "zink", 1);
    setenv("POJAV_RENDERER_MESA_MODE", "zink_turnip", 1);

    setenv("GALLIUM_DRIVER", "zink", 1);
    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
    setenv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT", 1);
    setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);

    /*
     * Critical: Minecraft 26.x compiles desktop GLSL 330+ shaders.
     * A GLES 3.x context reports only GLSL ES versions and then crashes in Mesa.
     * These flags are consumed by DroidBridge's EGL/GL bridge implementation.
     */
    setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
    setenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1", 1);
    setenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1", 1);
    unsetenv("LIBGL_ES");
}

int pojavInitOpenGL(void) {
    const char* forceVsync = getenv("FORCE_VSYNC");
    if (forceVsync != NULL && strcmp(forceVsync, "true") == 0) {
        pojav_environ->force_vsync = true;
    }

    const char* renderer = getenv("POJAV_RENDERER");
    if (renderer == NULL) {
        printf("EGLBridge: POJAV_RENDERER is not set\n");
        return 0;
    }

    /*
     * Some launcher-side rollback paths accidentally pass the numeric renderer
     * sentinel "-1" while the selected instance still needs an OpenGL backend.
     * Letting that path continue can load Turnip but never install an OSMesa/GL
     * bridge table, which later crashes Minecraft with "There is no OpenGL
     * context current in the current thread". Treat -1 as Vulkan Zink when an
     * OSMesa payload is present.
     */
    if (strcmp(renderer, "-1") == 0) {
        printf("EGLBridge-v52: POJAV_RENDERER=-1 fallback -> vulkan_zink/libOSMesa_8.so\n");
        setenv("POJAV_RENDERER", "vulkan_zink", 1);
        if (getenv("LIB_MESA_NAME") == NULL || getenv("LIB_MESA_NAME")[0] == '\0') {
            setenv("LIB_MESA_NAME", "libOSMesa_8.so", 1);
        }
        renderer = getenv("POJAV_RENDERER");
    }

    /*
     * v57 hard guard: the Java-side legacy Freedreno alias can still be followed
     * by older launch code that copies the selected renderer library into
     * POJAVEXEC_EGL. The latest failed log showed exactly this mismatch:
     *   POJAV_RENDERER=vulkan_zink
     *   POJAVEXEC_EGL=libEGL_mesa.so
     *   LIB_MESA_NAME=libOSMesa_8.so
     * That route creates an OSMesa context but LWJGL later reports no current
     * OpenGL context. If the renderer is Vulkan Zink and libOSMesa_8 is the Mesa
     * frontend, force every bridge-library variable back to libOSMesa_8.so before
     * any bridge table is selected.
     */
    const char* lib_mesa_name_v57 = getenv("LIB_MESA_NAME");
    const char* pojavexec_egl_v57 = getenv("POJAVEXEC_EGL");
    if (renderer != NULL && strcmp(renderer, "vulkan_zink") == 0
            && lib_mesa_name_v57 != NULL
            && strstr(lib_mesa_name_v57, "libOSMesa_8.so") != NULL
            && (pojavexec_egl_v57 == NULL || strcmp(pojavexec_egl_v57, "libOSMesa_8.so") != 0)) {
        printf("EGLBridge-v61: forcing Vulkan Zink POJAVEXEC_EGL from %s to libOSMesa_8.so\n",
               pojavexec_egl_v57 != NULL ? pojavexec_egl_v57 : "<null>");
        setenv("POJAVEXEC_EGL", "libOSMesa_8.so", 1);
        setenv("POJAV_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("POJAVEXEC_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("POJAV_RENDERER_LIBRARY", "libOSMesa_8.so", 1);
        setenv("POJAVEXEC_RENDERER", "libOSMesa_8.so", 1);
        setenv("OSMESA_LIB", "libOSMesa_8.so", 1);
        setenv("POJAV_OSMESA_LIBRARY", "libOSMesa_8.so", 1);
        setenv("OSMESA_LIBRARY", "libOSMesa_8.so", 1);
        setenv("LIBGL_OSMESA", "libOSMesa_8.so", 1);
        unsetenv("DROIDBRIDGE_MESA");
        unsetenv("DROIDBRIDGE_MESA_MODE");
        unsetenv("DROIDBRIDGE_MESA_DRIVER");
        unsetenv("DROIDBRIDGE_MESA_EGL");
        unsetenv("DROIDBRIDGE_MESA_GL");
        unsetenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL");
        unsetenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK");
    }

    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    const char* renderer_mesa_mode = getenv("POJAV_RENDERER_MESA_MODE");
    const char* droidbridge_mesa = getenv("DROIDBRIDGE_MESA");

    printf("EGLBridge-v69: env POJAV_RENDERER=%s DROIDBRIDGE_MESA_MODE=%s DROIDBRIDGE_MESA_DRIVER=%s POJAV_RENDERER_MESA_MODE=%s DROIDBRIDGE_MESA=%s POJAVEXEC_EGL=%s LIB_MESA_NAME=%s\n",
           renderer != NULL ? renderer : "<null>",
           mesa_mode != NULL ? mesa_mode : "<null>",
           mesa_driver != NULL ? mesa_driver : "<null>",
           renderer_mesa_mode != NULL ? renderer_mesa_mode : "<null>",
           droidbridge_mesa != NULL ? droidbridge_mesa : "<null>",
           getenv("POJAVEXEC_EGL") != NULL ? getenv("POJAVEXEC_EGL") : "<null>",
           getenv("LIB_MESA_NAME") != NULL ? getenv("LIB_MESA_NAME") : "<null>");

    bool mesa_zink_turnip = is_droidbridge_mesa_zink_turnip(
            renderer,
            mesa_mode,
            mesa_driver,
            renderer_mesa_mode,
            droidbridge_mesa
    );

    /*
     * Direct KGSL must be selected by POJAV_RENDERER itself.
     * Do NOT infer it from stale Mesa env variables. The previous v42/v43 test did
     * exactly that and broke Vulkan Zink by forcing Freedreno while
     * POJAV_RENDERER was still vulkan_zink.
     */
    bool direct_mesa_kgsl = (renderer != NULL && strcmp(renderer, "freedreno_kgsl") == 0);

    if (direct_mesa_kgsl) {
        /* v69: match Mojo Launcher on Adreno 740. Mojo's working log uses
         * MESA_LOADER_DRIVER_OVERRIDE=kgsl and does not force Turnip/Zink for
         * the freedreno_kgsl renderer.  The v65-v68 Zink/Turnip path boots but
         * keeps the same sky/cloud artifacts on 8 Gen 2 and can black-screen on
         * 8 Gen 3.
         */
        printf("EGLBridge-v69: direct Freedreno Mojo KGSL selected; using libEGL_mesa.so with MESA_LOADER_DRIVER_OVERRIDE=kgsl (no Turnip/Zink).\n");
        set_vulkan_ptr(NULL);
        setenv("DROIDBRIDGE_MESA", "1", 1);
        setenv("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl", 1);
        setenv("DROIDBRIDGE_MESA_DRIVER", "kgsl", 1);
        setenv("POJAV_RENDERER_MESA_MODE", "freedreno_kgsl", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        unsetenv("GALLIUM_DRIVER");
        setenv("POJAVEXEC_EGL", "libEGL_mesa.so", 1);
        setenv("LIB_MESA_NAME", "libEGL_mesa.so", 1);
        setenv("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY", "1", 1);
        setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
        setenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1", 1);
        setenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1", 1);
        setenv("LIBGL_ES", "2", 1);
        setenv("LIBGL_MIPMAP", "3", 1);
        setenv("LIBGL_NOINTOVLHACK", "1", 1);
        setenv("LIBGL_NORMALIZE", "1", 1);
        setenv("LIBGL_NOERROR", "1", 1);
        setenv("EGL_PLATFORM", "android", 1);
        setenv("FORCE_VSYNC", "false", 1);
        setenv("allow_higher_compat_version", "true", 1);
        setenv("force_glsl_extensions_warn", "true", 1);
        setenv("allow_glsl_extension_directive_midshader", "true", 1);
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_TURNIP_ZINK_V68");
        unsetenv("POJAV_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_USE_CUSTOM_TURNIP");
        unsetenv("DROIDBRIDGE_EGL_FORCE_RGBX8888");
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888");
        unsetenv("MESA_EXTENSION_OVERRIDE");
        unsetenv("DROIDBRIDGE_MESA_SAFE_SWAPS");
        setenv("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1", 1);
    } else {
        load_vulkan();
    }

    if (mesa_zink_turnip) {
        /*
         * DroidBridge intentionally keeps POJAV_RENDERER=opengles3 for this path
         * so libpojavexec does not enter the legacy OSMesa vulkan_zink renderer.
         * However, the normal opengles branch creates a GLES context, which cannot
         * run Minecraft 26.x desktop GLSL shaders. Treat zink_turnip as a Mesa
         * desktop-GL bridge here before the generic opengles path can claim it.
         */
        configure_droidbridge_mesa_zink_turnip_desktop_gl();
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    } else if (strncmp("opengles", renderer, 8) == 0) {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }

    if (strcmp(renderer, "freedreno_kgsl") == 0) {
        printf("EGLBridge-v69: Using DroidBridge Mesa freedreno_kgsl GL bridge backend=kgsl/mojo\n");
        setenv("DROIDBRIDGE_MESA", "1", 1);
        setenv("POJAV_RENDERER_MESA_MODE", "freedreno_kgsl", 1);
        setenv("DROIDBRIDGE_MESA_DRIVER", "kgsl", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        unsetenv("GALLIUM_DRIVER");
        setenv("LIBGL_ES", "2", 1);
        setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
        pojav_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }

    if (strcmp(renderer, "custom_gallium") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "vulkan_zink") == 0 && !mesa_zink_turnip) {
        printf("EGLBridge: Using DroidBridge/Mesa Zink Vulkan bridge\n");
        setenv("POJAVEXEC_EGL", "libOSMesa_8.so", 1);
        setenv("POJAV_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("POJAVEXEC_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("LIB_MESA_NAME", "libOSMesa_8.so", 1);
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
        setenv("POJAV_RENDERER_MESA_MODE", "zink_turnip", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_freedreno") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        setenv("GALLIUM_DRIVER", "freedreno", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_panfrost") == 0) {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("GALLIUM_DRIVER", "panfrost", 1);
        setenv("MESA_DISK_CACHE_SINGLE_FILE", "1", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_virgl") == 0) {
        pojav_environ->config_renderer = RENDERER_VIRGL;
        setenv("GALLIUM_DRIVER", "virpipe", 1);
        setenv("OSMESA_NO_FLUSH_FRONTBUFFER", "1", false);
        setenv("MESA_GL_VERSION_OVERRIDE", "4.3", 1);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "430", 1);

        const char* noFlushFrontbuffer = getenv("OSMESA_NO_FLUSH_FRONTBUFFER");
        if (noFlushFrontbuffer != NULL && strcmp(noFlushFrontbuffer, "1") == 0) {
            printf("VirGL: OSMesa buffer flush is DISABLED!\n");
        }

        loadSymbolsVirGL();
        virglInit();
        return 0;
    }

    if (br_init()) {
        br_setup_window();
    }

    return 0;
}

EXTERNAL_API int pojavInit(void) {
    if (db_native_glfw_enabled()) {
        printf("DroidBridgeNativeGLFW: pojavInit using NativeGLFW instead of GLBridge\n");
        if (pojav_environ->pojavWindow != NULL) {
            ANativeWindow_acquire(pojav_environ->pojavWindow);
            pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
            pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
            ANativeWindow_setBuffersGeometry(
                    pojav_environ->pojavWindow,
                    pojav_environ->savedWidth,
                    pojav_environ->savedHeight,
                    AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
            );
            db_native_glfw_attach_existing_window("pojavInit");
        } else {
            printf("DroidBridgeNativeGLFW: pojavInit had no ANativeWindow yet\n");
        }
        return 1;
    }

    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(
            pojav_environ->pojavWindow,
            pojav_environ->savedWidth,
            pojav_environ->savedHeight,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
    );
    pojavInitOpenGL();
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) {
        return;
    }

    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            break;

        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in pojavCreateContext */
            break;

        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers(void) {
    calculateFPS();

    if (db_native_glfw_enabled()) {
        db_native_glfw_swap_counter++;
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_swap_buffers_fn swap_buffers =
                    (db_native_glfw_swap_buffers_fn) dlsym(handle, "droidbridge_native_glfw_swap_buffers");
            if (swap_buffers != NULL) {
                if (db_native_glfw_swap_counter <= 5 || (db_native_glfw_swap_counter % 120ULL) == 0ULL) {
                    printf("DroidBridgeNativeGLFW: pojavSwapBuffers frame=%" PRIu64 " -> native\n", db_native_glfw_swap_counter);
                    fflush(stdout);
                }
                swap_buffers();
            } else {
                const char* error = dlerror();
                printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_swap_buffers error=%s\n", error ? error : "unknown");
                fflush(stdout);
            }
        } else {
            printf("DroidBridgeNativeGLFW: pojavSwapBuffers frame=%" PRIu64 " no native handle\n", db_native_glfw_swap_counter);
            fflush(stdout);
        }
        return;
    }

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_swap_buffers();
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapBuffers();
    }
}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            if (!db_native_glfw_surface_attached) {
                db_native_glfw_attach_existing_window("pojavMakeCurrent");
            }
            db_native_glfw_make_current_fn make_current =
                    (db_native_glfw_make_current_fn) dlsym(handle, "droidbridge_native_glfw_make_current");
            if (make_current != NULL) make_current(window);
        }
        return;
    }

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_make_current((basic_render_window_t*) window);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglMakeCurrent(window);
    }
}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_attach_existing_window("pojavCreateContext");
            db_native_glfw_configure_if_available(handle);
            db_native_glfw_create_context_fn create_context =
                    (db_native_glfw_create_context_fn) dlsym(handle, "droidbridge_native_glfw_create_context");
            if (create_context != NULL) {
                void* ctx = create_context(contextSrc);
                printf("DroidBridgeNativeGLFW: pojavCreateContext returned %p\n", ctx);
                return ctx != NULL ? ctx : (void*) pojav_environ->pojavWindow;
            }
        }
        return (void*) pojav_environ->pojavWindow;
    }

    if (pojav_environ->config_renderer == RENDERER_VULKAN) {
        return (void*) pojav_environ->pojavWindow;
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        return virglCreateContext(contextSrc);
    }

    return br_init_context((basic_render_window_t*) contextSrc);
}

void* maybe_load_vulkan(void) {
    const char* current = getenv("VULKAN_PTR");
    if (current == NULL || current[0] == '\0') {
        load_vulkan();
        current = getenv("VULKAN_PTR");
    }

    if (current == NULL || current[0] == '\0') {
        printf("OSMDroid: maybe_load_vulkan(): no Vulkan pointer available\n");
        return NULL;
    }

    return (void*) strtoull(current, NULL, 16);
}

static int frameCount = 0;
static int fps = 0;
static time_t lastTime = 0;

void calculateFPS(void) {
    frameCount++;
    time_t currentTime = time(NULL);

    if (currentTime != lastTime) {
        lastTime = currentTime;
        fps = frameCount;
        frameCount = 0;
    }
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getCurrentFps(JNIEnv* env, jclass clazz) {
    return fps;
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_org_lwjgl_vulkan_VK_onVKFrame(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz) {
    calculateFPS();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK ||
        pojav_environ->config_renderer == RENDERER_GL4ES) {
        br_swap_interval(interval);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapInterval(interval);
    }
}
