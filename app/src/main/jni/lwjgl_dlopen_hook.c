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

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>
#include "droidbridge_renderspec.h"
#include "ctxbridges/egl_loader.h"
#include "driver_helper/nsbypass.h"

#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern void* maybe_load_vulkan(void);

#define DB_OPENGL_PROXY_SONAME "libGLDroidBridge.so"
#define DB_OPENGL_PROXY_ALT_SONAME "libGLDroidBridgeMesa.so"
#define TAG "LwjglLinkerHook"

static const char* basename_or_self(const char* filename) {
    if (filename == NULL) return "";
    const char* base = strrchr(filename, '/');
    return base != NULL ? base + 1 : filename;
}

/**
 * Returns true when the requested library name is a Vulkan loader soname.
 * Accepts both direct names and full paths.
 */
static bool is_vulkan_loader_name(const char* filename) {
    const char* base = basename_or_self(filename);
    return strcmp(base, "libvulkan.so") == 0 ||
           strcmp(base, "libvulkan.so.1") == 0;
}

/**
 * DroidBridge OpenGL proxy names.
 *
 * LWJGL requests this sentinel name so DroidBridge can return its configured
 * RenderSpec EGL provider instead of loading a random system libGL.
 */
static bool is_droidbridge_opengl_proxy_name(const char* filename) {
    if (filename == NULL) return false;
    const char* base = basename_or_self(filename);

    /* Exact DroidBridge-owned sentinel names. */
    if (strcmp(base, DB_OPENGL_PROXY_SONAME) == 0 ||
        strcmp(base, DB_OPENGL_PROXY_ALT_SONAME) == 0) {
        return true;
    }

    /* Be tolerant of LWJGL name-mapping differences, such as GLDroidBridge,
     * /full/path/libGLDroidBridge.so, or accidental liblib... wrapping. */
    if (strstr(base, "GLDroidBridge") != NULL ||
        strstr(base, "DroidBridgeMesa") != NULL) {
        return true;
    }


    return false;
}

static const char* first_non_empty(const char* a, const char* b, const char* c,
                                   const char* d, const char* e) {
    if (a != NULL && a[0] != '\0') return a;
    if (b != NULL && b[0] != '\0') return b;
    if (c != NULL && c[0] != '\0') return c;
    if (d != NULL && d[0] != '\0') return d;
    if (e != NULL && e[0] != '\0') return e;
    return NULL;
}

static void* try_dlopen_with_log(const char* library, int mode) {
    if (library == NULL || library[0] == '\0') return NULL;

    dlerror();
    void* handle = dlopen(library, mode);
    if (handle != NULL) {
        printf("LWJGL linkerhook: DroidBridge RenderSpec using %s handle=%p\n", library, handle);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook: DroidBridge RenderSpec failed to open %s: %s\n",
           library,
           err != NULL ? err : "unknown");
    return NULL;
}

static bool env_enabled_lwjgl_hook(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0;
}


static void* acquire_native_glfw_opengl_handle_v82(void) {
    if (!env_enabled_lwjgl_hook("DROIDBRIDGE_NATIVE_GLFW_KGSL")) return NULL;

    typedef void* (*bridge_acquire_fn_t)(void);
    dlerror();
    bridge_acquire_fn_t bridge_acquire =
            (bridge_acquire_fn_t)dlsym(RTLD_DEFAULT, "droidbridge_pojavexec_native_glfw_acquire_opengl_handle");
    if (bridge_acquire != NULL) {
        void* bridge_handle = bridge_acquire();
        if (bridge_handle != NULL) {
            printf("LWJGL linkerhook-v82: using NativeGLFW handle from libpojavexec bridge handle=%p\n", bridge_handle);
            return bridge_handle;
        }
        printf("LWJGL linkerhook-v82: libpojavexec bridge returned NULL; trying direct NativeGLFW acquire\n");
    } else {
        const char* bridge_error = dlerror();
        printf("LWJGL linkerhook-v82: libpojavexec bridge acquire symbol missing error=%s\n", bridge_error ? bridge_error : "unknown");
    }

    const char* native_dir = first_non_empty(getenv("POJAV_NATIVEDIR"),
                                             getenv("DROIDBRIDGE_MESA_NATIVE_DIR"),
                                             getenv("DROIDBRIDGE_APP_NATIVE_DIR"),
                                             getenv("DROIDBRIDGE_LIBRARY_PATH"),
                                             NULL);
    char absolute_path[1024];
    const char* library = "libdroidbridge_native_glfw_v82.so";
    if (native_dir != NULL && native_dir[0] != '\0') {
        snprintf(absolute_path, sizeof(absolute_path), "%s/%s", native_dir, library);
        library = absolute_path;
    }

    dlerror();
    void* native_glfw = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
    if (native_glfw == NULL && library == absolute_path) {
        const char* first_error = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW absolute dlopen failed %s error=%s\n",
               library, first_error ? first_error : "unknown");
        dlerror();
        native_glfw = dlopen("libdroidbridge_native_glfw_v82.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (native_glfw == NULL) {
        const char* err = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW dlopen failed error=%s\n", err ? err : "unknown");
        return NULL;
    }

    typedef void* (*acquire_fn_t)(void);
    dlerror();
    acquire_fn_t acquire = (acquire_fn_t)dlsym(native_glfw, "droidbridge_native_glfw_acquire_opengl_handle");
    if (acquire == NULL) {
        const char* err = dlerror();
        printf("LWJGL linkerhook-v82: NativeGLFW missing acquire_opengl_handle error=%s\n", err ? err : "unknown");
        return NULL;
    }

    void* egl_handle = acquire();
    if (egl_handle != NULL) {
        printf("LWJGL linkerhook-v82: using NativeGLFW EGL handle for OpenGL handle=%p\n", egl_handle);
        return egl_handle;
    }

    printf("LWJGL linkerhook-v82: NativeGLFW acquire returned NULL; falling back\n");
    return NULL;
}


static const char* droidbridge_filename_only(const char* path) {
    if (path == NULL || path[0] == '\0') return path;
    const char* slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}


static bool string_equals(const char* value, const char* expected) {
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

static void* try_existing_egl_loader_handle_for_direct_mesa(void) {
    const char* renderer = getenv("POJAV_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");

    if (!string_equals(renderer, "freedreno_kgsl") &&
        !string_equals(mesa_mode, "freedreno_kgsl")) {
        return NULL;
    }
    if (mesa_driver != NULL && mesa_driver[0] != '\0' &&
        strcmp(mesa_driver, "kgsl") != 0 &&
        strcmp(mesa_driver, "zink") != 0) {
        return NULL;
    }

    void* existing = droidbridge_egl_get_handle();
    if (existing == NULL) {
        printf("LWJGL linkerhook-v69: direct Freedreno has no existing EGL loader handle yet; using fallback loader\n");
        return NULL;
    }

    void* current = NULL;
    if (eglGetCurrentContext_p != NULL) {
        current = (void*) eglGetCurrentContext_p();
    }

    printf("LWJGL linkerhook-v69: reusing existing GLBridge EGL handle=%p loaded=%s currentContext=%p for direct Freedreno\n",
           existing,
           droidbridge_egl_get_loaded_name(),
           current);
    return existing;
}

static void* try_namespace_dlopen_with_log(const char* library, int mode) {
    if (library == NULL || library[0] == '\0') return NULL;
    if (!env_enabled_lwjgl_hook("DROIDBRIDGE_MESA_NAMESPACE")) return NULL;

    const char* namespace_path = getenv("DROIDBRIDGE_MESA_NAMESPACE_PATH");
    if (namespace_path == NULL || namespace_path[0] == '\0') {
        namespace_path = getenv("DROIDBRIDGE_MESA_NATIVE_DIR");
    }
    if (namespace_path == NULL || namespace_path[0] == '\0') return NULL;

    if (!linker_ns_load(namespace_path)) {
        printf("LWJGL linkerhook-v69: namespace load failed path=%s library=%s\n",
               namespace_path,
               library);
        return NULL;
    }

    int dl_mode = mode;
    if ((dl_mode & RTLD_NOW) == 0 && (dl_mode & RTLD_LAZY) == 0) {
        dl_mode |= RTLD_NOW;
    }
    dl_mode |= RTLD_GLOBAL;

    const char* short_name = droidbridge_filename_only(library);
    if (short_name == NULL || short_name[0] == '\0') short_name = library;

    dlerror();
    void* handle = linker_ns_dlopen(short_name, dl_mode);
    if (handle != NULL) {
        printf("LWJGL linkerhook-v69: DroidBridge RenderSpec using namespace %s path=%s handle=%p\n",
               short_name,
               namespace_path,
               handle);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook-v69: namespace short-name load failed library=%s path=%s error=%s\n",
           short_name,
           namespace_path,
           err != NULL ? err : "unknown");

    const char* absolute = library;
    if (absolute != NULL && strchr(absolute, '/') != NULL) {
        dlerror();
        handle = linker_ns_dlopen(absolute, dl_mode);
        if (handle != NULL) {
            printf("LWJGL linkerhook-v69: DroidBridge RenderSpec using namespace absolute %s path=%s handle=%p\n",
                   absolute,
                   namespace_path,
                   handle);
            return handle;
        }
        err = dlerror();
        printf("LWJGL linkerhook-v69: namespace absolute load failed library=%s path=%s error=%s\n",
               absolute,
               namespace_path,
               err != NULL ? err : "unknown");
    }

    return NULL;
}

static void* acquire_configured_droidbridge_renderspec(void) {
    const droidbridge_renderspec_t* rspec = droidbridge_renderspec_get();
    if (rspec == NULL || !rspec->configured || rspec->egl_acquire == NULL || rspec->egl_path == NULL) {
        printf("LWJGL linkerhook-v74: DroidBridge RenderSpec is not configured yet; using env fallback (this should not happen on v74 Freedreno)\n");
        return NULL;
    }

    void* handle = rspec->egl_acquire(rspec->egl_path);
    if (handle != NULL) {
        printf("LWJGL linkerhook-v74: replacing OpenGL with configured RenderSpec driver EGL=%s handle=%p forceGles=%d overrideMajor=%d\n",
               rspec->egl_path,
               handle,
               rspec->force_gles_context,
               rspec->override_major_version);
        return handle;
    }

    const char* err = dlerror();
    printf("LWJGL linkerhook: configured DroidBridge RenderSpec failed for %s: %s\n",
           rspec->egl_path,
           err != NULL ? err : "unknown");
    return NULL;
}

static void* acquire_droidbridge_opengl_handle(int mode) {
    void* native_glfw_handle = acquire_native_glfw_opengl_handle_v82();
    if (native_glfw_handle != NULL) return native_glfw_handle;

    void* configured_handle = acquire_configured_droidbridge_renderspec();
    if (configured_handle != NULL) return configured_handle;
    void* existing_egl_handle = try_existing_egl_loader_handle_for_direct_mesa();
    if (existing_egl_handle != NULL) return existing_egl_handle;

    int dl_mode = mode;
    if ((dl_mode & RTLD_NOW) == 0 && (dl_mode & RTLD_LAZY) == 0) {
        dl_mode |= RTLD_NOW;
    }
    dl_mode |= RTLD_GLOBAL;

    const char* renderer = getenv("POJAV_RENDERER");
    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    const char* renderer_mesa_mode = getenv("POJAV_RENDERER_MESA_MODE");

    printf("LWJGL linkerhook: DroidBridge RenderSpec request renderer=%s mesaMode=%s mesaDriver=%s rendererMesaMode=%s\n",
           renderer != NULL ? renderer : "",
           mesa_mode != NULL ? mesa_mode : "",
           mesa_driver != NULL ? mesa_driver : "",
           renderer_mesa_mode != NULL ? renderer_mesa_mode : "");

    const char* preferred = first_non_empty(
            getenv("DROIDBRIDGE_RENDERSPEC_EGL"),
            getenv("DROIDBRIDGE_MESA_EGL"),
            getenv("POJAVEXEC_EGL"),
            getenv("POJAV_EGL_LIBRARY"),
            getenv("POJAVEXEC_EGL_LIBRARY")
    );

    void* handle = try_namespace_dlopen_with_log(preferred, dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log(preferred, dl_mode);
    if (handle != NULL) return handle;

    handle = try_namespace_dlopen_with_log("libEGL_mesa.so", dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL_mesa.so", dl_mode);
    if (handle != NULL) return handle;

    handle = try_dlopen_with_log("libEGL.so", dl_mode);
    if (handle != NULL) return handle;

    printf("LWJGL linkerhook: DroidBridge RenderSpec failed; returning NULL for OpenGL proxy\n");
    return NULL;
}
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv* env,
                            __attribute__((unused)) jclass clazz,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;
    int mode = (int) jmode;

    if (is_vulkan_loader_name(filename)) {
        printf("LWJGL linkerhook: intercepted Vulkan load for %s\n", filename);

        void* handle = maybe_load_vulkan();
        if (handle != NULL) {
            printf("LWJGL linkerhook: using custom/system Vulkan handle %p for %s\n",
                   handle,
                   filename);
            return (jlong) handle;
        }

        printf("LWJGL linkerhook: maybe_load_vulkan() returned NULL, falling back to dlopen(%s)\n",
               filename);
    }

    if (is_droidbridge_opengl_proxy_name(filename)) {
        printf("LWJGL linkerhook-v74: matched DroidBridge OpenGL proxy filename=%s\n",
               filename != NULL ? filename : "");
        void* handle = acquire_droidbridge_opengl_handle(mode);
        if (handle != NULL) {
            return (jlong) handle;
        }
    }

    return (jlong) dlopen(filename, mode);
}

void installLwjglDlopenHook(void) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Installing LWJGL dlopen() hook");

    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if (dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR,
                            TAG,
                            "Failed to find org/lwjgl/system/linux/DynamicLinkLoader");
        (*env)->ExceptionClear(env);
        return;
    }

    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };

    if ((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,
                            TAG,
                            "Failed to register hooked ndlopen() implementation");
        (*env)->ExceptionClear(env);
    }
}
