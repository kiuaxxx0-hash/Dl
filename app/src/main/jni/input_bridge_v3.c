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

/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 * 
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */

#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <math.h>

#include "log.h"
#include "utils.h"
#include "environ/environ.h"
#include <stdint.h>
#include <stdbool.h>

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_FRAMEBUFFER_SIZE 1004
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007
#define EVENT_TYPE_WINDOW_SIZE 1008

#define BTA_GAMEPAD_BUTTON_COUNT 15
#define BTA_GAMEPAD_AXIS_COUNT 6

static volatile int bta_gamepad_present = 1;
static volatile int bta_gamepad_device_id = -1;
static char bta_gamepad_name[192] = "DroidBridge Android Controller";
static char bta_gamepad_guid[33] = "030000005e0400008e02000014010000";
static float bta_gamepad_axes[BTA_GAMEPAD_AXIS_COUNT] = {0.0f, 0.0f, 0.0f, 0.0f, -1.0f, -1.0f};
static unsigned char bta_gamepad_buttons[BTA_GAMEPAD_BUTTON_COUNT] = {0};
static unsigned char bta_gamepad_hat = 0;

static void bta_copy_jstring(JNIEnv *env, jstring src, char *dst, size_t dst_size, const char *fallback) {
    if (dst == NULL || dst_size == 0) return;
    const char *value = NULL;
    if (src != NULL) {
        value = (*env)->GetStringUTFChars(env, src, NULL);
    }
    if (value == NULL || value[0] == '\0') {
        value = fallback != NULL ? fallback : "DroidBridge Android Controller";
        snprintf(dst, dst_size, "%s", value);
    } else {
        snprintf(dst, dst_size, "%s", value);
    }
    if (src != NULL && value != NULL && value != fallback) {
        (*env)->ReleaseStringUTFChars(env, src, value);
    }
}

static void bta_set_identity(JNIEnv *env, jint deviceId, jstring name, jstring descriptor) {
    bta_gamepad_present = 1;
    bta_gamepad_device_id = deviceId;
    bta_copy_jstring(env, name, bta_gamepad_name, sizeof(bta_gamepad_name), "DroidBridge Android Controller");
    // Use a stable Xbox 360 SDL GUID so BTA's gamepad mapping layer accepts the controller.
    // The visible name still comes from the real Android InputDevice.
    snprintf(bta_gamepad_guid, sizeof(bta_gamepad_guid), "%s", "030000005e0400008e02000014010000");
    __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "identity id=%d name=%s guid=%s", deviceId, bta_gamepad_name, bta_gamepad_guid);
}

static float bta_clampf(float v) {
    if (isnan(v) || isinf(v)) return 0.0f;
    if (v < -1.0f) return -1.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static float bta_trigger_to_glfw(float v) {
    if (isnan(v) || isinf(v)) v = 0.0f;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return (v * 2.0f) - 1.0f;
}

static int bta_android_key_to_gamepad_button(jint keyCode) {
    switch (keyCode) {
        case 96: return 0;   // A
        case 97: return 1;   // B
        case 99: return 2;   // X
        case 100: return 3;  // Y
        case 102: return 4;  // L1
        case 103: return 5;  // R1
        case 104: return -1; // L2 is an axis only; do not mirror it to LB/hotbar
        case 105: return -1; // R2 is an axis only; do not mirror it to RB/hotbar
        case 109: return 6;  // SELECT/BACK
        case 108: return 7;  // START
        case 110: return 8;  // MODE/GUIDE
        case 106: return 9;  // L3
        case 107: return 10; // R3
        case 19: return 11;  // DPAD_UP
        case 22: return 12;  // DPAD_RIGHT
        case 20: return 13;  // DPAD_DOWN
        case 21: return 14;  // DPAD_LEFT
        case 23: return 0;   // DPAD_CENTER -> A
        default: return -1;
    }
}

static void registerFunctions(JNIEnv *env);
static void registerBtaGamepadFunctions(JNIEnv *env);

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving DVM environ...");
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        (*vm)->GetEnv(vm, (void**) &pojav_environ->dalvikJNIEnvPtr_ANDROID, JNI_VERSION_1_4);
        pojav_environ->bridgeClazz = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->NewGlobalRef(pojav_environ->dalvikJNIEnvPtr_ANDROID,(*pojav_environ->dalvikJNIEnvPtr_ANDROID) ->FindClass(pojav_environ->dalvikJNIEnvPtr_ANDROID,"org/lwjgl/glfw/CallbackBridge"));
        pojav_environ->method_accessAndroidClipboard = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        pojav_environ->method_onGrabStateChanged = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        pojav_environ->method_onCursorShapeChanged = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "onCursorShapeChanged", "(I)V");
        pojav_environ->method_onNativeCursorPosSilentlyChanged = (*pojav_environ->dalvikJNIEnvPtr_ANDROID)->GetStaticMethodID(pojav_environ->dalvikJNIEnvPtr_ANDROID, pojav_environ->bridgeClazz, "onNativeCursorPosSilentlyChanged", "(FF)V");
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        __android_log_print(ANDROID_LOG_INFO, "Native", "Saving JVM environ...");
        pojav_environ->runtimeJavaVMPtr = vm;
        (*vm)->GetEnv(vm, (void**) &pojav_environ->runtimeJNIEnvPtr_JRE, JNI_VERSION_1_4);
        pojav_environ->vmGlfwClass = (*pojav_environ->runtimeJNIEnvPtr_JRE)->NewGlobalRef(pojav_environ->runtimeJNIEnvPtr_JRE, (*pojav_environ->runtimeJNIEnvPtr_JRE)->FindClass(pojav_environ->runtimeJNIEnvPtr_JRE, "org/lwjgl/glfw/GLFW"));
        pojav_environ->method_glftSetWindowAttrib = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticMethodID(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
        pojav_environ->method_internalWindowSizeChanged = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticMethodID(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, "internalWindowSizeChanged", "(JII)V");
        jfieldID field_keyDownBuffer = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticFieldID(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject keyDownBufferJ = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticObjectField(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, field_keyDownBuffer);
        pojav_environ->keyDownBuffer = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetDirectBufferAddress(pojav_environ->runtimeJNIEnvPtr_JRE, keyDownBufferJ);
        jfieldID field_mouseDownBuffer = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticFieldID(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
        jobject mouseDownBufferJ = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetStaticObjectField(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, field_mouseDownBuffer);
        pojav_environ->mouseDownBuffer = (*pojav_environ->runtimeJNIEnvPtr_JRE)->GetDirectBufferAddress(pojav_environ->runtimeJNIEnvPtr_JRE, mouseDownBufferJ);
        hookExec();
        installLwjglDlopenHook();
        installEMUIIteratorMititgation();

        // Register the BTA-only gamepad native readers in the OpenJDK/LWJGL JVM.
        // Android button/axis events are written from the Dalvik side through CallbackBridge,
        // while BTA polls gamepad state from the OpenJDK side through DroidBridgeBtaGamepad.
        registerBtaGamepadFunctions(pojav_environ->runtimeJNIEnvPtr_JRE);

        // Disable SDL3 HIDAPI joystick backend to prevent double detection.
        // When both HIDAPI and the Android Input API backend are active, they can both detect
        // the same controller and trigger two SDL_JOYSTICKDEVICEADDED events. The second event
        // causes crashes in SDL_UpdateJoysticks because SDL3 ends up with two conflicting
        // joystick entries for the same physical device.
        // Set this hint before any SDL_Init(SDL_INIT_JOYSTICK) call (Controlify via JNA, or
        // our initializeControllerSubsystems). SDL hints are process-wide and persist.
        void *sdl3_handle_hint = dlopen("libSDL3.so", RTLD_NOW);
        if (sdl3_handle_hint != NULL) {
            typedef int (*SDL_SetHint_t)(const char *name, const char *value);
            SDL_SetHint_t sdl_set_hint = (SDL_SetHint_t)dlsym(sdl3_handle_hint, "SDL_SetHint");
            if (sdl_set_hint != NULL) {
                sdl_set_hint("SDL_JOYSTICK_HIDAPI", "0");
                __android_log_print(ANDROID_LOG_INFO, "SDL_Hint", "Disabled HIDAPI joystick backend to prevent double detection");
            } else {
                __android_log_print(ANDROID_LOG_WARN, "SDL_Hint", "SDL_SetHint symbol not found");
            }
        } else {
            __android_log_print(ANDROID_LOG_WARN, "SDL_Hint", "libSDL3.so not loaded yet, HIDAPI hint not set");
        }
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    pojav_environ->isGrabbing = JNI_FALSE;

    return JNI_VERSION_1_4;
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    void** oldCallback = (void**) &pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
}

ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(FramebufferSize)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)
ADD_CALLBACK_WWIN(WindowSize)

#undef ADD_CALLBACK_WWIN

#define GLFW_ARROW_CURSOR 0x00036001
#define GLFW_IBEAM_CURSOR 0x00036002
#define DROIDBRIDGE_CURSOR_HANDLE_MAGIC ((jlong)0x4442435500000000ULL)

static jint cursor_shape_from_handle(jlong cursor) {
    if (cursor == 0) return GLFW_ARROW_CURSOR;
    if ((cursor & (jlong)0xffffffff00000000ULL) == DROIDBRIDGE_CURSOR_HANDLE_MAGIC) {
        return (jint)(cursor & 0xffffffffLL);
    }
    return GLFW_ARROW_CURSOR;
}

static JNIEnv* get_attached_dalvik_env(void) {
    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL) return NULL;

    JNIEnv *dalvikEnv = NULL;
    jint env_result = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(
            pojav_environ->dalvikJavaVMPtr,
            (void**) &dalvikEnv,
            JNI_VERSION_1_4
    );
    if (env_result == JNI_EDETACHED) {
        env_result = (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(
                pojav_environ->dalvikJavaVMPtr,
                (void**) &dalvikEnv,
                NULL
        );
    }
    if (env_result != JNI_OK) return NULL;
    return dalvikEnv;
}

static void notify_android_cursor_shape(jint shape) {
    if (pojav_environ == NULL
            || pojav_environ->bridgeClazz == NULL
            || pojav_environ->method_onCursorShapeChanged == NULL) {
        return;
    }

    JNIEnv *dalvikEnv = get_attached_dalvik_env();
    if (dalvikEnv == NULL) return;

    (*dalvikEnv)->CallStaticVoidMethod(
            dalvikEnv,
            pojav_environ->bridgeClazz,
            pojav_environ->method_onCursorShapeChanged,
            shape
    );
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
    }
}

static void notify_android_cursor_pos_silently(jfloat x, jfloat y) {
    if (pojav_environ == NULL
            || pojav_environ->bridgeClazz == NULL
            || pojav_environ->method_onNativeCursorPosSilentlyChanged == NULL) {
        return;
    }

    JNIEnv *dalvikEnv = get_attached_dalvik_env();
    if (dalvikEnv == NULL) return;

    (*dalvikEnv)->CallStaticVoidMethod(
            dalvikEnv,
            pojav_environ->bridgeClazz,
            pojav_environ->method_onNativeCursorPosSilentlyChanged,
            x,
            y
    );
    if ((*dalvikEnv)->ExceptionCheck(dalvikEnv)) {
        (*dalvikEnv)->ExceptionClear(dalvikEnv);
    }
}

JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwCreateStandardCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint shape) {
    return (jlong)(DROIDBRIDGE_CURSOR_HANDLE_MAGIC | ((jlong) shape & 0xffffffffLL));
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwDestroyCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        __attribute__((unused)) jlong cursor) {
    // Android has no native desktop cursor object to destroy.
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetCursor(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        __attribute__((unused)) jlong window,
        jlong cursor) {
    notify_android_cursor_shape(cursor_shape_from_handle(cursor));
}

void handleFramebufferSizeJava(long window, int w, int h) {
    (*pojav_environ->runtimeJNIEnvPtr_JRE)->CallStaticVoidMethod(pojav_environ->runtimeJNIEnvPtr_JRE, pojav_environ->vmGlfwClass, pojav_environ->method_internalWindowSizeChanged, (long)window, w, h);
}

void pojavPumpEvents(void* window) {
    if(pojav_environ->shouldUpdateMouse) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }

    size_t index = pojav_environ->outEventIndex;
    size_t targetIndex = pojav_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = pojav_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            case EVENT_TYPE_CURSOR_ENTER:
                if(pojav_environ->GLFW_invoke_CursorEnter) pojav_environ->GLFW_invoke_CursorEnter(window, event.i1);
                break;
            case EVENT_TYPE_SCROLL:
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_FRAMEBUFFER_SIZE:
                handleFramebufferSizeJava(pojav_environ->showingWindow, event.i1, event.i2);
                if(pojav_environ->GLFW_invoke_FramebufferSize) pojav_environ->GLFW_invoke_FramebufferSize(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_WINDOW_SIZE:
                handleFramebufferSizeJava(pojav_environ->showingWindow, event.i1, event.i2);
                if(pojav_environ->GLFW_invoke_WindowSize) pojav_environ->GLFW_invoke_WindowSize(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    size_t counter = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire);
    size_t index = pojav_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    pojav_environ->inEventCount = counter;
    pojav_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    pojav_environ->outEventIndex = pojav_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&pojav_environ->eventCounter, pojav_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
        jobject ypos) {
*(double*)(*env)->GetDirectBufferAddress(env, xpos) = pojav_environ->cursorX;
*(double*)(*env)->GetDirectBufferAddress(env, ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
*xpos = pojav_environ->cursorX;
*ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
jdoubleArray xpos, jdoubleArray ypos) {
(*env)->SetDoubleArrayRegion(env, xpos, 0,1, &pojav_environ->cursorX);
(*env)->SetDoubleArrayRegion(env, ypos, 0,1, &pojav_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
        jdouble ypos) {
pojav_environ->cLastX = pojav_environ->cursorX = xpos;
pojav_environ->cLastY = pojav_environ->cursorY = ypos;
notify_android_cursor_pos_silently((jfloat)xpos, (jfloat)ypos);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
        jdouble ypos) {
JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++pojav_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        pojav_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_acquire);
}

/**
 * This function is meant as a substitute for SharedLibraryUtil.getLibraryPath() that just returns 0
 * (thus making the parent Java function return null). This is done to avoid using the LWJGL's default function,
 * which will hang the crappy EMUI linker by dlopen()ing inside of dl_iterate_phdr().
 * @return 0, to make the parent Java function return null immediately.
 * For reference: https://github.com/PojavLauncherTeam/lwjgl3/blob/fix_huawei_hang/modules/lwjgl/core/src/main/java/org/lwjgl/system/SharedLibraryUtil.java
 */
jint getLibraryPath_fix(__attribute__((unused)) JNIEnv *env,
                        __attribute__((unused)) jclass class,
                        __attribute__((unused)) jlong pLibAddress,
                        __attribute__((unused)) jlong sOutAddress,
                        __attribute__((unused)) jint bufSize){
    return 0;
}

/**
 * Install the linker hang mitigation that is meant to prevent linker hangs on old EMUI firmware.
 */
void installEMUIIteratorMititgation() {
    if(getenv("POJAV_EMUI_ITERATOR_MITIGATE") == NULL) return;
    __android_log_print(ANDROID_LOG_INFO, "EMUIIteratorFix", "Installing...");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass sharedLibraryUtil = (*env)->FindClass(env, "org/lwjgl/system/SharedLibraryUtil");
    if(sharedLibraryUtil == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod getLibraryPathMethod[] = {
            {"getLibraryPath", "(JJI)I", &getLibraryPath_fix}
    };
    if((*env)->RegisterNatives(env, sharedLibraryUtil, getLibraryPathMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EMUIIteratorFix", "Failed to register the mitigation method");
        (*env)->ExceptionClear(env);
    }
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}
// Cursor shapes are desktop-only. Android has no native cursor to change.
void critical_set_cursor_shape(jint shape) {
    // Critical-native path cannot safely call back into Java. The non-critical
    // GLFW cursor hooks below are the normal path used by Minecraft menus.
    (void) shape;
}
void noncritical_set_cursor_shape(__attribute__((unused)) JNIEnv* env,
                                  __attribute__((unused)) jclass clazz,
                                  jint shape) {
    notify_android_cursor_shape(shape);
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, jint action, jbyteArray copySrc) {
#ifdef DEBUG
    LOGD("Debug: Clipboard access is going on\n", pojav_environ->isUseStackQueueCall);
#endif

    JNIEnv *dalvikEnv;
    // Attach to the Android (ART) JVM to call clipboard APIs.
    // We intentionally do NOT call DetachCurrentThread afterwards: SDL3 also attaches the
    // Render thread to ART and caches the JNIEnv in its mThreadKey TLS slot. Calling
    // DetachCurrentThread here invalidates that cached env, causing the next
    // SDL_UpdateJoysticks() call to use a stale pointer and crash with SIGSEGV at 0xb8
    // (DeleteLocalRef on a null JNINativeInterface_). The ART attachment for this thread
    // is safe to leave live — ART will clean it up when the thread exits.
    (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    assert(dalvikEnv != NULL);
    assert(pojav_environ->bridgeClazz != NULL);

    LOGD("Clipboard: Converting string\n");
    char *copySrcC;
    jstring copyDst = NULL;
    if (copySrc) {
        copySrcC = (char *)((*env)->GetByteArrayElements(env, copySrc, NULL));
        copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
    }

    LOGD("Clipboard: Calling 2nd\n");
    // Extract the result and delete its ART local ref explicitly, since we are no longer
    // relying on DetachCurrentThread to clean up ART's local ref table for this thread.
    jstring artResult = (jstring) (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_accessAndroidClipboard, action, copyDst);
    jstring pasteDst = convertStringJVM(dalvikEnv, env, artResult);
    if (artResult != NULL) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, artResult);
    }

    if (copySrc) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
        (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
    }
    return pasteDst;
}
JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetCursorShape(JNIEnv* env, jclass clazz, jint shape) {
(void)env; (void)clazz;
notify_android_cursor_shape(shape);
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    LOGD("Debug: Changing input state, isReady=%d, pojav_environ->isUseStackQueueCall=%d\n", inputReady, pojav_environ->isUseStackQueueCall);
#endif
    __android_log_print(ANDROID_LOG_INFO, "NativeInput", "Input ready: %i", inputReady);
    pojav_environ->isInputReady = inputReady;
    return pojav_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
JNIEnv *dalvikEnv;
// Same as nativeClipboard: do NOT call DetachCurrentThread — SDL3 shares this thread's
// ART attachment via mThreadKey and a detach here would corrupt SDL3's cached JNIEnv.
(*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
(*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grabbing);
pojav_environ->isGrabbing = grabbing;
}

jboolean critical_send_char(jchar codepoint) {
    if (pojav_environ->GLFW_invoke_Char && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR, codepoint, 0, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Char((void*) pojav_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (pojav_environ->GLFW_invoke_CharMods && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_CharMods((void*) pojav_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
// Need to add this back after for Snapshot builds
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (pojav_environ->GLFW_invoke_CursorEnter && pojav_environ->isInputReady) {
        pojav_environ->GLFW_invoke_CursorEnter(pojav_environ->showingWindow, entered);
    }
}
*/

void critical_send_cursor_pos(jfloat x, jfloat y) {
#ifdef DEBUG
    LOGD("Sending cursor position \n");
#endif
    if (pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady) {
#ifdef DEBUG
        LOGD("pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady \n");
#endif
        if (!pojav_environ->isCursorEntered) {
            if (pojav_environ->GLFW_invoke_CursorEnter) {
                pojav_environ->isCursorEntered = true;
                if (pojav_environ->isUseStackQueueCall) {
                    sendData(EVENT_TYPE_CURSOR_ENTER, 1, 0, 0, 0);
                } else {
                    pojav_environ->GLFW_invoke_CursorEnter((void*) pojav_environ->showingWindow, 1);
                }
            } else if (pojav_environ->isGrabbing) {
                // Some Minecraft versions does not use GLFWCursorEnterCallback
                // This is a smart check, as Minecraft will not in grab mode if already not.
                pojav_environ->isCursorEntered = true;
            }
        }

        if (!pojav_environ->isUseStackQueueCall) {
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) (x), (double) (y));
        } else {
            pojav_environ->cursorX = x;
            pojav_environ->cursorY = y;
        }
    }
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz,  jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}

void critical_set_cursor_pos_silently(jfloat x, jfloat y) {
    if (pojav_environ == NULL) return;

    // Reset both the live cursor and the last-pumped cursor cache without
    // invoking GLFW_invoke_CursorPos and without setting shouldUpdateMouse.
    // This is used on GUI -> grabbed transitions so the last menu cursor
    // position cannot be interpreted as a giant first camera-look delta.
    pojav_environ->cursorX = x;
    pojav_environ->cursorY = y;
    pojav_environ->cLastX = x;
    pojav_environ->cLastY = y;
    pojav_environ->shouldUpdateMouse = false;
}

void noncritical_set_cursor_pos_silently(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jfloat x, jfloat y) {
    critical_set_cursor_pos_silently(x, y);
}
#define max(a,b) \
   ({ __typeof__ (a) _a = (a); \
       __typeof__ (b) _b = (b); \
     _a > _b ? _a : _b; })
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_Key && pojav_environ->isInputReady) {
        pojav_environ->keyDownBuffer[max(0, key-31)] = (jbyte) action;
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_KEY, key, scancode, action, mods);
        } else {
            pojav_environ->GLFW_invoke_Key((void*) pojav_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_MouseButton && pojav_environ->isInputReady) {
        pojav_environ->mouseDownBuffer[max(0, button)] = (jbyte) action;
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
        } else {
            pojav_environ->GLFW_invoke_MouseButton((void*) pojav_environ->showingWindow, button, action, mods);
        }
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_send_screen_size(jint width, jint height) {
    pojav_environ->savedWidth = width;
    pojav_environ->savedHeight = height;
    if (pojav_environ->isInputReady) {
        if (pojav_environ->GLFW_invoke_FramebufferSize) {
            if (pojav_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_FRAMEBUFFER_SIZE, width, height, 0, 0);
            } else {
                pojav_environ->GLFW_invoke_FramebufferSize((void*) pojav_environ->showingWindow, width, height);
            }
        }

        if (pojav_environ->GLFW_invoke_WindowSize) {
            if (pojav_environ->isUseStackQueueCall) {
                sendData(EVENT_TYPE_WINDOW_SIZE, width, height, 0, 0);
            } else {
                pojav_environ->GLFW_invoke_WindowSize((void*) pojav_environ->showingWindow, width, height);
            }
        }
    }
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Scroll((void*) pojav_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
pojav_environ->showingWindow = (long) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
// Check for stack queue no longer necessary here as the JVM crash's origin is resolved
if (!pojav_environ->showingWindow) {
// If the window is not shown, there is nothing to do yet.
return;
}

// We cannot use pojav_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
// on the thread that loaded pojavexec (which is the thread that first references the GLFW class)
// But this method is only called from the Android UI thread
// Technically the better solution would be to have a permanently attached env pointer stored
// in environ for the Android UI thread but this is the only place that uses it
// (very rarely, only in lifecycle callbacks) so i dont care
JavaVM* jvm = pojav_environ->runtimeJavaVMPtr;
JNIEnv *jvm_env = NULL;
jint env_result = (*jvm)->GetEnv(jvm, (void**)&jvm_env, JNI_VERSION_1_4);
if(env_result == JNI_EDETACHED) {
env_result = (*jvm)->AttachCurrentThread(jvm, &jvm_env, NULL);
}
if(env_result != JNI_OK) {
printf("input_bridge nativeSetWindowAttrib() JNI call failed: %i\n", env_result);
return;
}
(*jvm_env)->CallStaticVoidMethod(
        jvm_env, pojav_environ->vmGlfwClass,
pojav_environ->method_glftSetWindowAttrib,
(jlong) pojav_environ->showingWindow, attrib, value
);

// Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jboolean present) {
    bta_gamepad_present = present ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "set present=%d", bta_gamepad_present);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor) {
    bta_set_identity(env, deviceId, name, descriptor);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor,
        jfloat leftX,
        jfloat leftY,
        jfloat rightX,
        jfloat rightY,
        jfloat leftTrigger,
        jfloat rightTrigger,
        jboolean hatUp,
        jboolean hatRight,
        jboolean hatDown,
        jboolean hatLeft) {
    bta_set_identity(env, deviceId, name, descriptor);

    bta_gamepad_axes[0] = bta_clampf(leftX);
    bta_gamepad_axes[1] = bta_clampf(leftY);
    bta_gamepad_axes[2] = bta_clampf(rightX);
    bta_gamepad_axes[3] = bta_clampf(rightY);
    bta_gamepad_axes[4] = bta_trigger_to_glfw(leftTrigger);
    bta_gamepad_axes[5] = bta_trigger_to_glfw(rightTrigger);

    unsigned char hat = 0;
    if (hatUp) hat |= 1;
    if (hatRight) hat |= 2;
    if (hatDown) hat |= 4;
    if (hatLeft) hat |= 8;
    bta_gamepad_hat = hat;

    bta_gamepad_buttons[11] = hatUp ? 1 : 0;
    bta_gamepad_buttons[12] = hatRight ? 1 : 0;
    bta_gamepad_buttons[13] = hatDown ? 1 : 0;
    bta_gamepad_buttons[14] = hatLeft ? 1 : 0;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint deviceId,
        jstring name,
        jstring descriptor,
        jint androidKeyCode,
        jboolean down) {
    bta_set_identity(env, deviceId, name, descriptor);
    if (androidKeyCode == 104) { // KEYCODE_BUTTON_L2
        bta_gamepad_axes[4] = down ? 1.0f : -1.0f;
    } else if (androidKeyCode == 105) { // KEYCODE_BUTTON_R2
        bta_gamepad_axes[5] = down ? 1.0f : -1.0f;
    }

    int button = bta_android_key_to_gamepad_button(androidKeyCode);
    if (button >= 0 && button < BTA_GAMEPAD_BUTTON_COUNT) {
        bta_gamepad_buttons[button] = down ? 1 : 0;
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "button key=%d mapped=%d down=%d", androidKeyCode, button, down ? 1 : 0);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "button key=%d unmapped down=%d", androidKeyCode, down ? 1 : 0);
    }
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeIsPresent(
        __attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    return (jid == 0 && bta_gamepad_present) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeName(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    if (jid != 0 || !bta_gamepad_present) return NULL;
    return (*env)->NewStringUTF(env, bta_gamepad_name);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeGuid(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid) {
    if (jid != 0 || !bta_gamepad_present) return NULL;
    return (*env)->NewStringUTF(env, bta_gamepad_guid);
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadAxes(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    float *out = (float*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    for (int i = 0; i < BTA_GAMEPAD_AXIS_COUNT; i++) out[i] = bta_gamepad_axes[i];
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadButtons(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    unsigned char *out = (unsigned char*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    for (int i = 0; i < BTA_GAMEPAD_BUTTON_COUNT; i++) out[i] = bta_gamepad_buttons[i];
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadHats(
        JNIEnv* env,
        __attribute__((unused)) jclass clazz,
        jint jid,
        jobject outBuffer) {
    if (jid != 0 || !bta_gamepad_present || outBuffer == NULL) return JNI_FALSE;
    unsigned char *out = (unsigned char*) (*env)->GetDirectBufferAddress(env, outBuffer);
    if (out == NULL) return JNI_FALSE;
    out[0] = bta_gamepad_hat;
    return JNI_TRUE;
}

static void registerBtaGamepadFunctions(JNIEnv *env) {
    if (env == NULL) return;

    jclass gamepad_class = (*env)->FindClass(env, "org/lwjgl/glfw/DroidBridgeBtaGamepad");
    if (gamepad_class == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_WARN, "BTAControllerBridge", "DroidBridgeBtaGamepad class not found in runtime JVM yet");
        return;
    }

    static const JNINativeMethod bta_gamepad_fcns[] = {
            {"nativeIsPresent", "(I)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeIsPresent},
            {"nativeName", "(I)Ljava/lang/String;", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeName},
            {"nativeGuid", "(I)Ljava/lang/String;", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeGuid},
            {"nativeReadAxes", "(ILjava/nio/FloatBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadAxes},
            {"nativeReadButtons", "(ILjava/nio/ByteBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadButtons},
            {"nativeReadHats", "(ILjava/nio/ByteBuffer;)Z", (void*) Java_org_lwjgl_glfw_DroidBridgeBtaGamepad_nativeReadHats},
    };

    jint result = (*env)->RegisterNatives(
            env,
            gamepad_class,
            bta_gamepad_fcns,
            sizeof(bta_gamepad_fcns) / sizeof(bta_gamepad_fcns[0])
    );

    if (result == 0) {
        __android_log_print(ANDROID_LOG_INFO, "BTAControllerBridge", "registered DroidBridgeBtaGamepad runtime native readers");
    } else {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        __android_log_print(ANDROID_LOG_ERROR, "BTAControllerBridge", "failed registering DroidBridgeBtaGamepad natives result=%d", result);
    }
}

const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSetCursorShape", "(I)V", critical_set_cursor_shape},
        {"nativeSetCursorShape", "(I)V", noncritical_set_cursor_shape},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSetCursorPosSilently", "(FF)V", critical_set_cursor_pos_silently},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size},
        {"nativeBtaSetGamepadPresent", "(Z)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent},
        {"nativeBtaSetGamepadIdentity", "(ILjava/lang/String;Ljava/lang/String;)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity},
        {"nativeBtaSetGamepadMotion", "(ILjava/lang/String;Ljava/lang/String;FFFFFFZZZZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion},
        {"nativeBtaSetGamepadButton", "(ILjava/lang/String;Ljava/lang/String;IZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton},
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSetCursorShape", "(I)V", noncritical_set_cursor_shape},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSetCursorPosSilently", "(FF)V", noncritical_set_cursor_pos_silently},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size},
        {"nativeBtaSetGamepadPresent", "(Z)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadPresent},
        {"nativeBtaSetGamepadIdentity", "(ILjava/lang/String;Ljava/lang/String;)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadIdentity},
        {"nativeBtaSetGamepadMotion", "(ILjava/lang/String;Ljava/lang/String;FFFFFFZZZZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadMotion},
        {"nativeBtaSetGamepadButton", "(ILjava/lang/String;Ljava/lang/String;IZ)V", Java_org_lwjgl_glfw_CallbackBridge_nativeBtaSetGamepadButton},
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(env, "net/kdt/pojavlaunch/CriticalNativeTest");
    if(criticalNativeTest == NULL) {
        LOGD("No CriticalNativeTest class found !");
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool use_critical_cc = tryCriticalNative(env);
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");

    if (use_critical_cc) {
        __android_log_print(ANDROID_LOG_INFO, "pojavexec", "CriticalNative is available. Enjoy the 4.6x times faster input!");
    } else {
        __android_log_print(ANDROID_LOG_INFO, "pojavexec", "CriticalNative is not available. Upgrade, maybe?");
    }

    const JNINativeMethod *methods = use_critical_cc ? critical_fcns : noncritical_fcns;
    const jint method_count = use_critical_cc
                              ? sizeof(critical_fcns) / sizeof(critical_fcns[0])
                              : sizeof(noncritical_fcns) / sizeof(noncritical_fcns[0]);

    (*env)->RegisterNatives(env, bridge_class, methods, method_count);
}

#define SDL_INIT_JOYSTICK   0x00000200u
#define SDL_INIT_GAMEPAD    0x00002000u
#define SDL_INIT_EVENTS     0x00004000u

static inline void initSubsystem(void) {
    typedef int (*SDL_Init_Func)(uint32_t flags);
    typedef int (*SDL_SetHint_Func)(const char *name, const char *value);
    void* handle = dlopen("libSDL3.so", RTLD_NOW);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "SDL_Init", "Failed to dlopen libSDL3.so: %s", dlerror());
        return;
    }
    // Disable HIDAPI before SDL_Init to prevent double detection (Android Input API + HIDAPI
    // would both detect the same controller, causing two SDL_JOYSTICKDEVICEADDED events and
    // a crash in SDL_UpdateJoysticks).
    SDL_SetHint_Func SDL_SetHint = (SDL_SetHint_Func)dlsym(handle, "SDL_SetHint");
    if (SDL_SetHint != NULL) {
        SDL_SetHint("SDL_JOYSTICK_HIDAPI", "0");
        __android_log_print(ANDROID_LOG_INFO, "SDL_Init", "Disabled HIDAPI joystick backend before SDL_Init");
    }
    SDL_Init_Func SDL_Init = (SDL_Init_Func)dlsym(handle, "SDL_Init");
    if (SDL_Init == NULL) {
        __android_log_print(ANDROID_LOG_WARN, "SDL_Init", "Failed to find SDL_Init symbol");
        return;
    }
    int result = SDL_Init(SDL_INIT_GAMEPAD | SDL_INIT_JOYSTICK | SDL_INIT_EVENTS);
    __android_log_print(ANDROID_LOG_INFO, "SDL_Init", "SDL_Init result: %d", result);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Tools_00024SDL_initializeControllerSubsystems(JNIEnv *env, jclass clazz) {
(void)env; (void)clazz;
initSubsystem();
}