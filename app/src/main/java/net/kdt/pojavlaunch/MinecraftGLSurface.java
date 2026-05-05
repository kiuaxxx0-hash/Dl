/*
 * Derived from PojavLauncher.
 *
 * Original project:
 * https://github.com/PojavLauncherTeam/PojavLauncher
 *
 * Original license: GNU Lesser General Public License v3.0.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * This file remains available under the terms of the GNU LGPLv3
 * unless the original file or bundled component states a different license.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import ca.dnamobile.javalauncher.controls.TouchHotbarHitbox;
import ca.dnamobile.javalauncher.input.GamepadMappingStore;
import net.kdt.pojavlaunch.utils.JREUtils;

import org.libsdl.app.SDLControllerManager;
import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

// Renders the game on Android's native SurfaceViews that are available giving the user options to
// render graphics on either Texture or native Surface
public class MinecraftGLSurface extends FrameLayout implements GrabListener {
    private View renderView;
    private TextureView textureView;
    private SurfaceView nativeSurfaceView;
    private Surface textureSurface;

    private int viewWidth = 1;
    private int viewHeight = 1;
    private int renderWidth = 1;
    private int renderHeight = 1;
    private float inputScaleX = 1.0f;
    private float inputScaleY = 1.0f;

    private SurfaceReadyListener surfaceReadyListener;
    private OnRenderingStartedListener renderingStartedListener;
    private boolean renderingStarted = false;
    private volatile boolean bridgeWindowAttached = false;
    private volatile boolean grabbed = false;

    private float lastTouchX;
    private float lastTouchY;
    private boolean trackingTouch;

    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private boolean touchMovedPastSlop;
    private boolean touchUiTapCandidate;
    private boolean touchLongPressAttackActive;
    @Nullable private Runnable touchLongPressRunnable;
    public static volatile boolean sdlEnabled = false;

    private static final long POINTER_REGRAB_RELATIVE_SUPPRESS_NANOS = 220_000_000L;

    private final Set<Integer> hardwareKeysDown = new HashSet<>();
    private final Set<Integer> hardwareMouseButtonsDown = new HashSet<>();
    private long suppressRelativeCursorUntilNanos;

    // Some Android devices dispatch physical mouse clicks as normal touch DOWN/UP
    // events instead of generic ACTION_BUTTON_PRESS/RELEASE events. Keep the
    // hardware mouse path separate from finger touch so Minecraft menus/keybinds
    // receive a clean GLFW mouse click while touch camera controls keep their
    // existing long-press/drag behavior.

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setFocusableInTouchMode(true);
        CallbackBridge.init(context);
        CallbackBridge.addGrabListener(this);
        setOnCapturedPointerListener(this::handleCapturedPointer);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
    }

    public void start(boolean isAlreadyRunning) {
        if (renderView != null) return;

        renderingStarted = false;
        boolean useNativeSurfaceView = LauncherPreferences.isUseNativeSurfaceView(getContext());

        if (useNativeSurfaceView) {
            startNativeSurfaceView(isAlreadyRunning);
        } else {
            startTextureView(isAlreadyRunning);
        }
    }

    private void startTextureView(boolean isAlreadyRunning) {
        textureView = new TextureView(getContext());
        renderView = textureView;

        textureView.setOpaque(true);
        textureView.setAlpha(1.0f);
        textureView.setFocusable(false);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private boolean called = isAlreadyRunning;

            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                RenderSize size = updateScaledSizeFromView(width, height);
                surface.setDefaultBufferSize(size.renderWidth, size.renderHeight);

                releaseTextureSurface();
                textureSurface = new Surface(surface);

                if (called) {
                    attachBridgeWindow(textureSurface, size);
                    return;
                }

                called = true;
                realStart(textureSurface, size, false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                RenderSize size = updateScaledSizeFromView(width, height);
                surface.setDefaultBufferSize(size.renderWidth, size.renderHeight);
                refreshSize(size);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                bridgeWindowAttached = false;
                JREUtils.releaseBridgeWindow();
                releaseTextureSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                notifyRenderingStartedOnce();
            }
        });

        addRenderView(textureView);
    }

    private void startNativeSurfaceView(boolean isAlreadyRunning) {
        nativeSurfaceView = new SurfaceView(getContext());
        renderView = nativeSurfaceView;

        nativeSurfaceView.setFocusable(false);
        nativeSurfaceView.setZOrderOnTop(false);
        nativeSurfaceView.setZOrderMediaOverlay(false);

        nativeSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            private boolean called = isAlreadyRunning;

            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                RenderSize size = updateScaledSizeFromView(nativeSurfaceView.getWidth(), nativeSurfaceView.getHeight());
                applyNativeSurfaceBufferSize(holder, size);

                if (called) {
                    attachBridgeWindow(holder.getSurface(), size);
                    notifyRenderingStartedSoon();
                    return;
                }

                called = true;
                realStart(holder.getSurface(), size, true);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                RenderSize size = updateScaledSizeFromView(nativeSurfaceView.getWidth(), nativeSurfaceView.getHeight());
                applyNativeSurfaceBufferSize(holder, size);
                refreshSize(size);
                if (holder.getSurface().isValid()) {
                    JREUtils.setupBridgeWindow(holder.getSurface());
                    bridgeWindowAttached = true;
                    notifyRenderingStartedSoon();
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                bridgeWindowAttached = false;
                JREUtils.releaseBridgeWindow();
            }
        });

        addRenderView(nativeSurfaceView);
    }

    private void addRenderView(@NonNull View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        addView(child, lp);
        child.requestLayout();
    }
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (w <= 0 || h <= 0) return;
        if (w == oldw && h == oldh) return;

        post(this::refreshSize);
        postDelayed(this::refreshSize, 120L);
        postDelayed(this::refreshSize, 350L);
    }

    public void refreshSize() {
        int width = safeWidth(getWidth());
        int height = safeHeight(getHeight());

        if (width <= 1 && renderView != null) width = safeWidth(renderView.getWidth());
        if (height <= 1 && renderView != null) height = safeHeight(renderView.getHeight());

        RenderSize size = updateScaledSizeFromView(width, height);
        refreshSize(size);
    }

    private void refreshSize(@NonNull RenderSize size) {
        updateSizeFields(size);

        if (textureView != null && textureView.getSurfaceTexture() != null) {
            textureView.getSurfaceTexture().setDefaultBufferSize(size.renderWidth, size.renderHeight);
        }

        if (nativeSurfaceView != null) {
            applyNativeSurfaceBufferSize(nativeSurfaceView.getHolder(), size);
        }

        if (bridgeWindowAttached) {
            CallbackBridge.sendUpdateWindowSize(size.renderWidth, size.renderHeight);
        }
    }

    private void realStart(@NonNull Surface surface, @NonNull RenderSize size, boolean assumeRenderingStarted) {
        attachBridgeWindow(surface, size);

        if (assumeRenderingStarted) {
            notifyRenderingStartedSoon();
        }

        if (surfaceReadyListener != null) {
            new Thread(surfaceReadyListener::isReady, "JVM Main thread").start();
        }
    }

    private void attachBridgeWindow(@NonNull Surface surface, @NonNull RenderSize size) {
        JREUtils.setupBridgeWindow(surface);
        bridgeWindowAttached = true;

        updateSizeFields(size);
        CallbackBridge.mouseX = size.renderWidth / 2f;
        CallbackBridge.mouseY = size.renderHeight / 2f;
        CallbackBridge.sendUpdateWindowSize(size.renderWidth, size.renderHeight);
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    @NonNull
    private RenderSize updateScaledSizeFromView(int width, int height) {
        int safeViewWidth = safeWidth(width);
        int safeViewHeight = safeHeight(height);
        int percent = LauncherPreferences.getGameResolutionScalePercent(getContext());
        int safeRenderWidth = Math.max(1, Math.round(safeViewWidth * (percent / 100.0f)));
        int safeRenderHeight = Math.max(1, Math.round(safeViewHeight * (percent / 100.0f)));

        viewWidth = safeViewWidth;
        viewHeight = safeViewHeight;
        renderWidth = safeRenderWidth;
        renderHeight = safeRenderHeight;
        inputScaleX = renderWidth / (float) Math.max(1, viewWidth);
        inputScaleY = renderHeight / (float) Math.max(1, viewHeight);

        RenderSize size = new RenderSize(viewWidth, viewHeight, renderWidth, renderHeight);
        updateSizeFields(size);
        return size;
    }

    private void applyNativeSurfaceBufferSize(@NonNull SurfaceHolder holder, @NonNull RenderSize size) {
        try {
            if (size.renderWidth == size.viewWidth && size.renderHeight == size.viewHeight) {
                holder.setSizeFromLayout();
            } else {
                holder.setFixedSize(size.renderWidth, size.renderHeight);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateSizeFields(@NonNull RenderSize size) {
        CallbackBridge.windowWidth = Math.max(1, size.renderWidth);
        CallbackBridge.windowHeight = Math.max(1, size.renderHeight);

        // Keep physical* in the same coordinate space currently used by the rest of this bridge.
        // Touch/mouse input is explicitly scaled from Android view coordinates into this render space below.
        CallbackBridge.physicalWidth = CallbackBridge.windowWidth;
        CallbackBridge.physicalHeight = CallbackBridge.windowHeight;
    }

    private int safeWidth(int width) {
        return Math.max(1, width > 0 ? width : getWidth());
    }

    private int safeHeight(int height) {
        return Math.max(1, height > 0 ? height : getHeight());
    }

    private float scaleInputX(float x) {
        return x * inputScaleX;
    }

    private float scaleInputY(float y) {
        return y * inputScaleY;
    }

    private float scaleDeltaX(float dx) {
        return dx * inputScaleX;
    }

    private float scaleDeltaY(float dy) {
        return dy * inputScaleY;
    }

    private static final class RenderSize {
        final int viewWidth;
        final int viewHeight;
        final int renderWidth;
        final int renderHeight;

        RenderSize(int viewWidth, int viewHeight, int renderWidth, int renderHeight) {
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            this.renderWidth = Math.max(1, renderWidth);
            this.renderHeight = Math.max(1, renderHeight);
        }
    }

    private void notifyRenderingStartedSoon() {
        postDelayed(this::notifyRenderingStartedOnce, 100);
    }

    private void notifyRenderingStartedOnce() {
        if (renderingStarted) return;
        renderingStarted = true;
        if (renderingStartedListener != null) renderingStartedListener.isStarted();
    }

    private void releaseTextureSurface() {
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        if (isHardwareMouseLikeEvent(event) && handleHardwareMouseTouchEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Entry point used by TouchControlsOverlay. Calling this directly avoids Android
     * routing the event into the TextureView/SurfaceView child and bypassing this bridge.
     */
    public boolean handleTouchFromOverlay(@NonNull MotionEvent event) {
        return handleTouchEventInternal(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouchEventInternal(event);
    }

    private boolean handleTouchEventInternal(@NonNull MotionEvent event) {
        if (isHardwareMouseLikeEvent(event)) {
            return handleHardwareMouseTouchEvent(event);
        }

        requestFocusIfNeeded();
        markTouchInputMode();

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackingTouch = true;
                touchMovedPastSlop = false;
                touchLongPressAttackActive = false;
                touchDownX = x;
                touchDownY = y;
                lastTouchX = x;
                lastTouchY = y;
                touchUiTapCandidate = isLikelyHotbarTap(x, y);
                if (grabbed) {
                    // Critical: never send an absolute cursor position on ACTION_DOWN while
                    // Minecraft is grabbing the mouse. In grabbed mode, Minecraft treats
                    // cursor movement as camera movement, so warping to the finger location
                    // makes the camera jump toward the touched edge/corner. Store the finger
                    // position only; send relative deltas after the drag passes touch slop.
                    if (!touchUiTapCandidate) {
                        scheduleTouchLongPressAttack();
                    }
                } else {
                    // Menu/inventory mode behaves like a normal mouse click.
                    sendAbsoluteCursor(x, y);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!trackingTouch) {
                    trackingTouch = true;
                    touchMovedPastSlop = false;
                    touchLongPressAttackActive = false;
                    touchDownX = x;
                    touchDownY = y;
                    lastTouchX = x;
                    lastTouchY = y;
                    touchUiTapCandidate = isLikelyHotbarTap(x, y);
                    if (grabbed) {
                        if (!touchUiTapCandidate) {
                            scheduleTouchLongPressAttack();
                        }
                    } else {
                        sendAbsoluteCursor(x, y);
                    }
                    return true;
                }

                if (grabbed) {
                    float totalDx = x - touchDownX;
                    float totalDy = y - touchDownY;
                    if (!touchMovedPastSlop
                            && ((totalDx * totalDx) + (totalDy * totalDy)) > (touchSlop * touchSlop)) {
                        touchMovedPastSlop = true;
                        cancelTouchLongPressAttack(true);
                    }

                    if (touchMovedPastSlop) {
                        sendRelativeCursor(x - lastTouchX, y - lastTouchY);
                    }
                } else {
                    sendAbsoluteCursor(x, y);
                }

                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_UP:
                if (grabbed) {
                    cancelTouchLongPressAttack(false);
                    if (touchLongPressAttackActive) {
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    } else if (trackingTouch && !touchMovedPastSlop) {
                        if (touchUiTapCandidate || isLikelyHotbarTap(x, y)) {
                            sendHotbarSlotIfNeeded(x, y);
                        } else {
                            sendAttackTap();
                        }
                    }
                } else {
                    sendAbsoluteCursor(x, y);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                }
                resetTouchTracking();
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (grabbed) {
                    cancelTouchLongPressAttack(true);
                } else {
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                }
                resetTouchTracking();
                return true;

            default:
                return true;
        }
    }

    private boolean handleHardwareMouseTouchEvent(@NonNull MotionEvent event) {
        int pointerIndex = findMousePointerIndex(event);
        if (pointerIndex < 0) pointerIndex = 0;

        requestFocusIfNeeded();
        markHardwarePointerInputMode();

        float x = safeEventX(event, pointerIndex);
        float y = safeEventY(event, pointerIndex);
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                if (!grabbed) sendAbsoluteCursor(x, y);
                return sendPrimaryMouseDownIfNeeded(event);

            case MotionEvent.ACTION_UP:
                if (!grabbed) sendAbsoluteCursor(x, y);
                releaseMouseButtonsForEvent(event);
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (grabbed) {
                    float relX = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_X, pointerIndex);
                    float relY = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_Y, pointerIndex);
                    if (relX == 0f && relY == 0f) {
                        relX = x - lastTouchX;
                        relY = y - lastTouchY;
                    }
                    sendHardwareRelativeCursor(relX, relY);
                } else {
                    sendAbsoluteCursor(x, y);
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (!grabbed) sendAbsoluteCursor(x, y);
                return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (!grabbed) sendAbsoluteCursor(x, y);
                return sendMouseButtonUnconvertedTracked(event, false, pointerIndex);

            case MotionEvent.ACTION_CANCEL:
                releaseAllHardwareMouseButtons();
                return true;

            default:
                return false;
        }
    }

    private boolean sendPrimaryMouseDownIfNeeded(@NonNull MotionEvent event) {
        int buttonState = event.getButtonState();
        if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0 || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return sendHardwareMouseButtonTracked(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        }
        return false;
    }

    private void releaseMouseButtonsForEvent(@NonNull MotionEvent event) {
        int actionButton = androidMouseButtonToGlfw(event.getActionButton());
        if (actionButton >= 0) {
            sendHardwareMouseButtonTracked(actionButton, false);
            return;
        }

        // ACTION_UP often arrives after Android has already cleared buttonState,
        // so release every button we still believe is held. This prevents stuck
        // attack/use buttons without creating duplicate releases for normal devices.
        releaseAllHardwareMouseButtons();
    }

    private void releaseAllHardwareMouseButtons() {
        if (hardwareMouseButtonsDown.isEmpty()) return;
        for (Integer button : new HashSet<>(hardwareMouseButtonsDown)) {
            if (button != null) sendMouseButton(button, false);
        }
        hardwareMouseButtonsDown.clear();
    }

    private boolean sendHardwareMouseButtonTracked(int glfwButton, boolean status) {
        if (glfwButton < 0) return false;

        if (status) {
            if (!hardwareMouseButtonsDown.add(glfwButton)) {
                return true;
            }
        } else {
            if (!hardwareMouseButtonsDown.remove(glfwButton)) {
                return true;
            }
        }

        sendMouseButton(glfwButton, status);
        return true;
    }

    private static float safeEventX(@NonNull MotionEvent event, int pointerIndex) {
        try {
            if (pointerIndex >= 0 && pointerIndex < event.getPointerCount()) return event.getX(pointerIndex);
        } catch (Throwable ignored) {
        }
        return event.getX();
    }

    private static float safeEventY(@NonNull MotionEvent event, int pointerIndex) {
        try {
            if (pointerIndex >= 0 && pointerIndex < event.getPointerCount()) return event.getY(pointerIndex);
        } catch (Throwable ignored) {
        }
        return event.getY();
    }

    private static boolean isHardwareMouseLikeEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true;
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }

        return false;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (isGamepadMotionEvent(event)) {
            if (sdlEnabled && SDLControllerManager.handleJoystickMotionEvent(event)) {
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        int pointerIndex = findMousePointerIndex(event);
        if (pointerIndex < 0) {
            return super.dispatchGenericMotionEvent(event);
        }

        requestFocusIfNeeded();
        markHardwarePointerInputMode();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                if (grabbed) {
                    float relX = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_X, pointerIndex);
                    float relY = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_Y, pointerIndex);
                    if (relX == 0f && relY == 0f) {
                        relX = event.getX(pointerIndex) - lastTouchX;
                        relY = event.getY(pointerIndex) - lastTouchY;
                    }
                    sendHardwareRelativeCursor(relX, relY);
                    lastTouchX = event.getX(pointerIndex);
                    lastTouchY = event.getY(pointerIndex);
                } else {
                    sendAbsoluteCursor(event.getX(pointerIndex), event.getY(pointerIndex));
                }
                return true;

            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                );
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (!grabbed) sendAbsoluteCursor(event.getX(pointerIndex), event.getY(pointerIndex));
                return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (!grabbed) sendAbsoluteCursor(event.getX(pointerIndex), event.getY(pointerIndex));
                return sendMouseButtonUnconvertedTracked(event, false, pointerIndex);

            default:
                return super.dispatchGenericMotionEvent(event);
        }
    }

    private boolean handleCapturedPointer(View view, MotionEvent event) {
        requestFocusIfNeeded();
        markHardwarePointerInputMode();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float relX = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_X, 0);
                float relY = getRelativeAxis(event, MotionEvent.AXIS_RELATIVE_Y, 0);
                if (relX == 0f && relY == 0f) {
                    relX = event.getX();
                    relY = event.getY();
                }
                sendHardwareRelativeCursor(relX, relY);
                return true;

            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                );
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (!grabbed) sendAbsoluteCursor(event.getX(), event.getY());
                return sendMouseButtonUnconvertedTracked(event, true, 0);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (!grabbed) sendAbsoluteCursor(event.getX(), event.getY());
                return sendMouseButtonUnconvertedTracked(event, false, 0);

            default:
                return false;
        }
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static int findMousePointerIndex(@NonNull MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE || toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                return i;
            }
        }

        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return 0;
        }

        return -1;
    }

    private static float getRelativeAxis(@NonNull MotionEvent event, int axis, int pointerIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            float value = event.getAxisValue(axis, pointerIndex);
            if (value != 0f) return value;
        }
        return event.getAxisValue(axis);
    }

    private boolean isLikelyHotbarTap(float x, float y) {
        return hotbarSlotForTouch(x, y) >= 0;
    }

    private int hotbarSlotForTouch(float x, float y) {
        return TouchHotbarHitbox.slotForTouch(
                getContext(),
                getWidth(),
                getHeight(),
                CallbackBridge.physicalWidth,
                CallbackBridge.physicalHeight,
                x,
                y
        );
    }


    private void scheduleTouchLongPressAttack() {
        cancelTouchLongPressAttack(false);
        touchLongPressRunnable = () -> {
            if (!trackingTouch || touchMovedPastSlop || touchUiTapCandidate || touchLongPressAttackActive) {
                return;
            }
            touchLongPressAttackActive = true;
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        };
        touchHandler.postDelayed(touchLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelTouchLongPressAttack(boolean releaseActivePress) {
        if (touchLongPressRunnable != null) {
            touchHandler.removeCallbacks(touchLongPressRunnable);
            touchLongPressRunnable = null;
        }
        if (releaseActivePress && touchLongPressAttackActive) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
            touchLongPressAttackActive = false;
        }
    }

    private void sendAttackTap() {
        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
    }

    private boolean sendHotbarSlotIfNeeded(float x, float y) {
        int slot = hotbarSlotForTouch(x, y);
        if (slot < 0) return false;
        sendKeyTap(49 + slot); // GLFW_KEY_1 through GLFW_KEY_9
        return true;
    }

    private void sendKeyTap(int keyCode) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.setModifiers(keyCode, true);
        CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
        CallbackBridge.setModifiers(keyCode, false);
    }

    private void sendTapClickAt(float x, float y) {
        CallbackBridge.setInputReady(true);
        float clampedX = mapViewXToCursorX(x);
        float clampedY = mapViewYToCursorY(y);
        // Keep the grabbed-mode invariant: do not call sendCursorPos() for a tap.
        // putMouseEventWithCoords() carries the click coordinates without first warping
        // the grabbed cursor, which prevents touch taps from snapping the camera.
        CallbackBridge.putMouseEventWithCoords(
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT,
                clampedX,
                clampedY
        );
    }

    private void resetTouchTracking() {
        trackingTouch = false;
        touchMovedPastSlop = false;
        touchUiTapCandidate = false;
        touchLongPressAttackActive = false;
        touchDownX = 0f;
        touchDownY = 0f;
        lastTouchX = 0f;
        lastTouchY = 0f;
    }

    private void sendAbsoluteCursor(float x, float y) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX = mapViewXToCursorX(x);
        CallbackBridge.mouseY = mapViewYToCursorY(y);
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private float mapViewXToCursorX(float x) {
        return mapViewCoordinateToCursorCoordinate(
                x,
                Math.max(1f, viewWidth),
                Math.max(1f, CallbackBridge.windowWidth)
        );
    }

    private float mapViewYToCursorY(float y) {
        return mapViewCoordinateToCursorCoordinate(
                y,
                Math.max(1f, viewHeight),
                Math.max(1f, CallbackBridge.windowHeight)
        );
    }

    private static float mapViewCoordinateToCursorCoordinate(float value, float viewSize, float bridgeSize) {
        // Android MotionEvent coordinates are effectively 0..viewSize-1 and
        // GLFW menu coordinates are 0..bridgeSize-1. The old scaleInputX/Y math
        // used size/size, which leaves the last GUI pixel difficult or impossible
        // to hit on the right/bottom edge, especially with SurfaceView and
        // resolution scaling. Map edge-to-edge instead.
        float maxView = Math.max(0f, viewSize - 1f);
        float maxBridge = Math.max(0f, bridgeSize - 1f);
        if (maxView <= 0f || maxBridge <= 0f) return 0f;
        return clamp(clamp(value, 0f, maxView) * maxBridge / maxView, 0f, maxBridge);
    }

    private void sendRelativeCursor(float dx, float dy) {
        if (shouldSuppressRelativeCursor()) {
            return;
        }

        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX += scaleDeltaX(dx);
        CallbackBridge.mouseY += scaleDeltaY(dy);
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private boolean shouldSuppressRelativeCursor() {
        long until = suppressRelativeCursorUntilNanos;
        return grabbed && until > 0L && System.nanoTime() < until;
    }

    private void sendHardwareRelativeCursor(float dx, float dy) {
        float mouseDpiScale = GamepadMappingStore.get(getContext()).getHardwareMouseDpiScaleMultiplier();
        sendRelativeCursor(dx * mouseDpiScale, dy * mouseDpiScale);
    }

    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = androidMouseButtonToGlfw(button);
        if (glfwButton < 0) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }

    private boolean sendMouseButtonUnconvertedTracked(@NonNull MotionEvent event, boolean status, int pointerIndex) {
        int glfwButton = androidMouseButtonToGlfw(event.getActionButton());

        if (glfwButton < 0 && status) {
            int buttonState = event.getButtonState();
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            } else if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
            } else if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
            }
        }

        if (glfwButton < 0 && !status) {
            // Some devices report ACTION_BUTTON_RELEASE with actionButton == 0.
            // Release the only held button if there is exactly one, otherwise fall
            // back to primary for normal left-click ACTION_UP sequences.
            if (hardwareMouseButtonsDown.size() == 1) {
                for (Integer held : hardwareMouseButtonsDown) {
                    if (held != null) {
                        glfwButton = held;
                        break;
                    }
                }
            } else if (hardwareMouseButtonsDown.contains(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT)) {
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            }
        }

        if (glfwButton < 0) return false;
        return sendHardwareMouseButtonTracked(glfwButton, status);
    }

    private static int androidMouseButtonToGlfw(int button) {
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            case MotionEvent.BUTTON_TERTIARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
            case MotionEvent.BUTTON_SECONDARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
            default:
                return -1;
        }
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.sendMouseButton(button, status);
    }

    private void requestFocusIfNeeded() {
        if (!hasFocus()) requestFocus();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        grabbed = isGrabbing;
        post(() -> {
            if (isGrabbing) {
                // Important for old Minecraft builds such as beta 1.7.3:
                // do not recenter and send an absolute cursor position when the
                // game re-grabs input after a GUI closes. Those builds can treat
                // the center warp as real mouse movement, which snaps the camera
                // straight to the sky/floor. Keep the current virtual cursor as
                // the relative-input baseline and only send movement when the
                // user actually moves touch/mouse/controller again.
                suppressRelativeCursorUntilNanos = System.nanoTime() + POINTER_REGRAB_RELATIVE_SUPPRESS_NANOS;
                cancelTouchLongPressAttack(true);
                resetTouchTracking();
                releaseAllHardwareMouseButtons();

                if (hasRealExternalPointerDevice()) {
                    safeRequestPointerCapture();
                } else {
                    // Touch/controller-only mode should not hold Android pointer capture.
                    safeReleasePointerCapture();
                }
            } else {
                suppressRelativeCursorUntilNanos = 0L;
                safeReleasePointerCapture();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (grabbed && hasRealExternalPointerDevice()) safeRequestPointerCapture();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && grabbed && hasRealExternalPointerDevice()) {
            safeRequestPointerCapture();
        } else if (!hasWindowFocus || !hasRealExternalPointerDevice()) {
            safeReleasePointerCapture();
        }
    }

    private void markTouchInputMode() {
        // If a user disconnects a mouse/keyboard and goes back to touch, do not
        // keep a stale pointer-capture state around. This avoids the touch layer
        // feeling frozen after hardware disconnects.
        if (!hasRealExternalPointerDevice()) {
            safeReleasePointerCapture();
        }
    }

    private void markHardwarePointerInputMode() {
        if (grabbed && hasRealExternalPointerDevice()) {
            safeRequestPointerCapture();
        }
    }

    private void safeRequestPointerCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasWindowFocus() || !isShown()) return;
        if (!hasRealExternalPointerDevice()) return;
        try {
            requestPointerCapture();
        } catch (Throwable ignored) {
        }
    }

    private void safeReleasePointerCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            releasePointerCapture();
        } catch (Throwable ignored) {
        }
    }

    private void releaseActiveHardwareInput() {
        releaseAllHardwareMouseButtons();

        if (!hardwareKeysDown.isEmpty()) {
            for (Integer key : new HashSet<>(hardwareKeysDown)) {
                if (key == null) continue;
                CallbackBridge.setInputReady(true);
                CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), false);
                CallbackBridge.setModifiers(key, false);
            }
            hardwareKeysDown.clear();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouchLongPressAttack(true);
        releaseActiveHardwareInput();
        safeReleasePointerCapture();
        CallbackBridge.removeGrabListener(this);
        bridgeWindowAttached = false;
        JREUtils.releaseBridgeWindow();
        releaseTextureSurface();
        super.onDetachedFromWindow();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(@Nullable SurfaceReadyListener listener) {
        this.surfaceReadyListener = listener;
    }

    public interface OnRenderingStartedListener {
        void isStarted();
    }

    public void setOnRenderingStartedListener(@Nullable OnRenderingStartedListener listener) {
        this.renderingStartedListener = listener;
    }


    /**
     * Lets the Activity route KEYCODE_BACK from a physical keyboard into Minecraft
     * before Android treats it as a launcher/system Back press. This is needed for
     * keyboards that report Escape as BACK instead of KEYCODE_ESCAPE.
     */
    public boolean handleKeyEventFromActivity(@NonNull KeyEvent event) {
        return handlePhysicalKeyboardEvent(event);
    }

    /**
     * Compatibility helper for Activity-side back guards.
     *
     * Use this before opening launcher/gamepad dialogs so a physical keyboard Esc
     * key that Android reports as BACK can still reach Minecraft as GLFW Escape.
     */
    public static boolean shouldRouteBackKeyToMinecraft(@Nullable KeyEvent event) {
        if (event == null) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) return true;
        return isPhysicalKeyboardBackAsEsc(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handlePhysicalKeyboardEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handlePhysicalKeyboardEvent(@NonNull KeyEvent event) {
        if (!isPhysicalKeyboardEvent(event)) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        int glfwKey = androidKeyCodeToGlfw(event.getKeyCode());
        if (glfwKey < 0) return false;

        // Movement keys only need the first down + final up. Ignoring repeat
        // events avoids flooding the bridge and prevents InputDispatcher ANRs
        // when Android generates key repeats while the JVM/render thread is busy.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        boolean down = action == KeyEvent.ACTION_DOWN;
        try {
            CallbackBridge.setInputReady(true);

            // Keep the original, stable in-world keyboard behavior while Minecraft is
            // grabbing input. Only use the richer key callback in menu/keybind screens,
            // where Minecraft's Controls UI needs Android key/scancode/char metadata to
            // capture and display the physical key properly.
            if (down) {
                CallbackBridge.setModifiers(glfwKey, true);
            }

            int modsForEvent = CallbackBridge.getCurrentMods();
            boolean sent = false;
            if (!grabbed) {
                sent = sendPhysicalKeyByReflection(
                        event.getKeyCode(),
                        glfwKey,
                        resolveKeyChar(event),
                        safeScanCode(event),
                        modsForEvent,
                        down
                );
            }

            if (!sent) {
                CallbackBridge.sendKeyPress(glfwKey, modsForEvent, down);
            }

            if (!down) {
                CallbackBridge.setModifiers(glfwKey, false);
            }

            if (down) {
                hardwareKeysDown.add(glfwKey);
            } else {
                hardwareKeysDown.remove(glfwKey);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sendPhysicalKeyByReflection(
            int androidKeyCode,
            int glfwKey,
            char keyChar,
            int scanCode,
            int modifiers,
            boolean down
    ) {
        Class<?> bridgeClass = CallbackBridge.class;
        Object[][] attempts = new Object[][]{
                // Modern Pojav/Zalith bridge. This carries the key plus the Android
                // scancode/character data that Minecraft's keybind screen often uses
                // to name and save a newly pressed physical key.
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},

                // Some bridge forks kept the same signature but expect the raw Android key.
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{androidKeyCode, keyChar, scanCode, modifiers, down}},

                // Older method names/signatures seen in experimental bridge ports.
                {"sendKeyCode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"putKeyboardEvent", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"sendKeycode", new Class[]{int.class, int.class, boolean.class}, new Object[]{glfwKey, modifiers, down}}
        };

        for (Object[] attempt : attempts) {
            try {
                Method method = bridgeClass.getMethod((String) attempt[0], (Class<?>[]) attempt[1]);
                method.invoke(null, (Object[]) attempt[2]);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static char resolveKeyChar(@NonNull KeyEvent event) {
        try {
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        try {
            int unicode = event.getUnicodeChar();
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        return (char) 0;
    }

    private static int safeScanCode(@NonNull KeyEvent event) {
        try {
            return event.getScanCode();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean isPhysicalKeyboardEvent(@NonNull KeyEvent event) {
        // A lot of USB/Bluetooth keyboards report Esc as Android BACK instead of
        // KEYCODE_ESCAPE. Keep only physical-keyboard BACK inside Minecraft; real
        // Android navigation/back still falls through to GameActivity.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return isPhysicalKeyboardBackAsEsc(event);
        }

        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        if (isControllerLikeKeyCode(event.getKeyCode())) return false;

        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return false;
        }

        InputDevice device = event.getDevice();
        if (isGameControllerDevice(device)) return false;

        boolean sourceKeyboard = (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        boolean deviceKeyboard = device != null && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_NONE;
        if (!sourceKeyboard && !deviceKeyboard) return false;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                return true;
        }
    }

    public static boolean isPhysicalKeyboardBackAsEsc(@Nullable KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;

        InputDevice device = null;
        try {
            device = event.getDevice();
        } catch (Throwable ignored) {
        }
        if (isGameControllerDevice(device)) return false;

        int source = event.getSource();
        boolean sourceKeyboard = (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        boolean deviceKeyboard = device != null && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_NONE;
        boolean nonVirtualDevice = event.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD;
        boolean hasHardwareScanCode = safeScanCode(event) != 0;

        // System navigation BACK usually has a virtual device and no useful scancode.
        // Hardware keyboard Esc-as-Back normally has a keyboard source/device and/or
        // a non-zero scancode. This keeps launcher Back dialogs from opening when a
        // physical keyboard user presses Esc.
        return (sourceKeyboard || deviceKeyboard) && (nonVirtualDevice || hasHardwareScanCode);
    }

    private static boolean isControllerLikeKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE) {
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }


    private static boolean hasRealExternalPointerDevice() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (isRealExternalPointerDevice(device)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isRealExternalPointerDevice(@Nullable InputDevice device) {
        if (device == null) return false;
        if (isGameControllerDevice(device)) return false;

        int sources = device.getSources();
        boolean hasMouseSource = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (sources & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
        if (!hasMouseSource) return false;

        String name = safeLower(device.getName());
        if (looksLikeControllerName(name) || looksLikeVirtualTouchName(name)) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return device.isExternal() || looksLikeMouseName(name);
        }
        return true;
    }

    private static boolean isGameControllerDevice(@Nullable InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        return looksLikeControllerName(safeLower(device.getName()));
    }

    private static boolean looksLikeControllerName(@NonNull String name) {
        return name.contains("controller")
                || name.contains("gamepad")
                || name.contains("joystick")
                || name.contains("xbox")
                || name.contains("dualshock")
                || name.contains("dualsense")
                || name.contains("playstation")
                || name.contains("8bitdo")
                || name.contains("gamesir")
                || name.contains("ipega")
                || name.contains("backbone")
                || name.contains("kishi")
                || name.contains("odin")
                || name.contains("retroid")
                || name.contains("anbernic")
                || name.contains("aya")
                || name.contains("gpd")
                || name.contains("legion go")
                || name.contains("steam deck")
                || name.contains("razer raiju")
                || name.contains("moga");
    }

    private static boolean looksLikeVirtualTouchName(@NonNull String name) {
        return name.contains("virtual")
                || name.contains("touch")
                || name.contains("touchpad")
                || name.contains("touchscreen")
                || name.contains("touch mapping")
                || name.contains("touchmapping")
                || name.contains("uinput")
                || name.contains("gpio")
                || name.contains("keypad");
    }

    private static boolean looksLikeMouseName(@NonNull String name) {
        return name.contains("mouse")
                || name.contains("trackball")
                || name.contains("trackpad")
                || name.contains("receiver")
                || name.contains("logitech")
                || name.contains("razer")
                || name.contains("microsoft")
                || name.contains("hid-compliant");
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    private static int androidKeyCodeToGlfw(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 'A' + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return '0' + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 290 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return 320 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_APOSTROPHE: return 39;
            case KeyEvent.KEYCODE_COMMA: return 44;
            case KeyEvent.KEYCODE_MINUS: return 45;
            case KeyEvent.KEYCODE_PERIOD: return 46;
            case KeyEvent.KEYCODE_SLASH: return 47;
            case KeyEvent.KEYCODE_SEMICOLON: return 59;
            case KeyEvent.KEYCODE_EQUALS: return 61;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 91;
            case KeyEvent.KEYCODE_BACKSLASH: return 92;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 93;
            case KeyEvent.KEYCODE_GRAVE: return 96;

            case KeyEvent.KEYCODE_ESCAPE: return 256;
            case KeyEvent.KEYCODE_BACK: return 256; // Some physical keyboards report Esc as Android BACK.
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 257;
            case KeyEvent.KEYCODE_TAB: return 258;
            case KeyEvent.KEYCODE_DEL: return 259;
            case KeyEvent.KEYCODE_INSERT: return 260;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 261;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 262;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 263;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 264;
            case KeyEvent.KEYCODE_DPAD_UP: return 265;
            case KeyEvent.KEYCODE_PAGE_UP: return 266;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 267;
            case KeyEvent.KEYCODE_MOVE_HOME: return 268;
            case KeyEvent.KEYCODE_MOVE_END: return 269;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 280;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 281;
            case KeyEvent.KEYCODE_NUM_LOCK: return 282;
            case KeyEvent.KEYCODE_SYSRQ: return 283;
            case KeyEvent.KEYCODE_BREAK: return 284;

            case KeyEvent.KEYCODE_NUMPAD_DOT: return 330;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return 331;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 332;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 333;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 334;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return 336;
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN: return 320;
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN: return 321;

            case KeyEvent.KEYCODE_SHIFT_LEFT: return 340;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 341;
            case KeyEvent.KEYCODE_ALT_LEFT: return 342;
            case KeyEvent.KEYCODE_META_LEFT: return 343;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 344;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 345;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 346;
            case KeyEvent.KEYCODE_META_RIGHT: return 347;
            case KeyEvent.KEYCODE_MENU: return 348;
            default: return -1;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (sdlEnabled && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (SDLControllerManager.handleJoystickMotionEvent(event)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (sdlEnabled && SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            if (SDLControllerManager.onNativePadDown(deviceId, keyCode)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int deviceId = event.getDeviceId();
        if (sdlEnabled && SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            if (SDLControllerManager.onNativePadUp(deviceId, keyCode)) {
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

}
