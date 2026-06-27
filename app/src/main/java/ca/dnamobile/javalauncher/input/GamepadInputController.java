/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.input;

import android.content.Context;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;
import net.kdt.pojavlaunch.Logger;

import java.util.EnumMap;

/**
 * Built-in Android controller support.
 *
 * In menu mode:
 * - left stick moves the visible cursor
 * - right stick X can still move the cursor horizontally, while right stick Y sends Minecraft mouse-wheel scroll
 * - D-pad never moves the visible cursor unless that exact D-pad direction is manually mapped to a Cursor action
 * - A/R2/D-pad center are guarded to left-click even if old saved prefs mapped them to Enter
 */
public final class GamepadInputController {
    private static final String TAG = "GamepadInputController";

    /**
     * BTA has its own controller UI/input type system. When this is enabled,
     * Android controller events update the BTA-only LWJGL virtual controller
     * instead of being translated through DroidBridge's keyboard/mouse mapper.
     */
    public static volatile boolean btaNativeControllerBridgeEnabled = false;

    private static volatile boolean loggedBtaRightStickAxisChoice;
    private static volatile boolean loggedBtaRightStickMenuSample;

    private static final float DEADZONE = 0.25f;
    private static final float TRIGGER_THRESHOLD = 0.50f;
    private static final float HAT_THRESHOLD = 0.85f;

    // Base values. User sensitivity prefs multiply these.
    private static final float BASE_GAME_CAMERA_SENSITIVITY = 18f;
    private static final float BASE_MENU_CURSOR_SENSITIVITY = 26f;
    private static final float BASE_MENU_SCROLL_SENSITIVITY = 0.16f;
    private static final float BTA_MENU_SCROLL_SENSITIVITY = 0.18f;
    private static final float BASE_DPAD_CURSOR_STEP = 14f;
    private static final float CURSOR_ACTION_BASE_STEP = 72f;

    // When Minecraft closes a GUI/inventory and re-grabs the pointer, Android can
    // still report the stick value that was being used to move the menu cursor.
    // If the right stick was already neutral, only swallow a tiny settle window so
    // the first real camera movement after leaving the menu is not lost. If the
    // stick was active, keep the stale-input guard but time it out so the camera
    // cannot stay stuck until the user moves the stick a second time.
    private static final long GAME_REGRAB_CAMERA_SETTLE_NANOS = 120_000_000L;
    private static final long GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS = 650_000_000L;

    private static final int DIRECTION_NONE = -1;
    private static final int DIRECTION_EAST = 0;
    private static final int DIRECTION_NORTH_EAST = 1;
    private static final int DIRECTION_NORTH = 2;
    private static final int DIRECTION_NORTH_WEST = 3;
    private static final int DIRECTION_WEST = 4;
    private static final int DIRECTION_SOUTH_WEST = 5;
    private static final int DIRECTION_SOUTH = 6;
    private static final int DIRECTION_SOUTH_EAST = 7;

    public interface MappingRequestListener {
        void onRequestControllerMapping();
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    @NonNull private final View hostView;
    private final Context context;
    private final GamepadMappingStore mappingStore;
    private final MappingRequestListener mappingRequestListener;
    private final EnumMap<GamepadButton, ActiveMappedAction[]> activeButtonActions = new EnumMap<>(GamepadButton.class);

    private boolean removed;
    private long lastFrameNanos = System.nanoTime();

    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;

    @Nullable private InputDevice activeDevice;

    private int currentDirection = DIRECTION_NONE;

    private boolean hatUp;
    private boolean hatDown;
    private boolean hatLeft;
    private boolean hatRight;
    private boolean leftTriggerDown;
    private boolean rightTriggerDown;

    // I love cheese
    private boolean lastGameMode;
    private boolean requireRightStickNeutralBeforeCamera;
    private long suppressCameraUntilNanos;
    private float menuScrollAccumulator;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            tick(frameTimeNanos);
            if (!removed) {
                choreographer.postFrameCallback(this);
            }
        }
    };

    public GamepadInputController(@NonNull View hostView) {
        this(hostView, null);
    }

    /**
     * BTA reads gamepad state from the OpenJDK/LWJGL side before the user has
     * moved a stick. Seed the native bridge from Android InputDevice at launch
     * so BTA's own controller menu can see a real controller immediately.
     */
    public static void initializeBtaNativeControllerBridge(@NonNull Context context) {
        BtaNativeControllerBridge.initializeFromAndroidDevices(context);
    }

    /**
     * Direct BTA route used by GameActivity before DroidBridge's normal launcher mapper.
     * This lets BTA's own controller system see real Android/Odin/Xbox controller state
     * instead of translated keyboard/mouse events.
     */
    public static boolean feedBtaNativeControllerMotion(@NonNull MotionEvent event) {
        if (!btaNativeControllerBridgeEnabled) return false;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;

        InputDevice device = event.getDevice();
        if (device == null && event.getDeviceId() >= 0) {
            device = InputDevice.getDevice(event.getDeviceId());
        }

        int source = event.getSource();
        boolean fromController = ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || isAndroidGameController(device);
        if (!fromController) return false;

        InputDevice axisDevice = device;

        float lx = readAxis(event, axisDevice, MotionEvent.AXIS_X);
        float ly = readAxis(event, axisDevice, MotionEvent.AXIS_Y);

        float[] rightStick = readAndroidRightStickPair(event, axisDevice);
        float rx = rightStick[0];
        float ry = rightStick[1];

        float lt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_LTRIGGER);
        float rt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_RTRIGGER);
        if (lt == 0f) lt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_GAS);

        float hx = readAxis(event, axisDevice, MotionEvent.AXIS_HAT_X);
        float hy = readAxis(event, axisDevice, MotionEvent.AXIS_HAT_Y);

        BtaNativeControllerBridge.updateLatestMenuRightStickY(ry);

        return BtaNativeControllerBridge.updateMotion(
                axisDevice,
                lx, ly,
                rx, ry,
                lt, rt,
                hy < -HAT_THRESHOLD,
                hx > HAT_THRESHOLD,
                hy > HAT_THRESHOLD,
                hx < -HAT_THRESHOLD
        );
    }

    public static boolean feedBtaNativeControllerKey(@NonNull KeyEvent event) {
        if (!btaNativeControllerBridgeEnabled) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) return true;

        InputDevice device = event.getDevice();
        if (device == null && event.getDeviceId() >= 0) {
            device = InputDevice.getDevice(event.getDeviceId());
        }

        int source = event.getSource();
        boolean fromController = ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD)
                || isAndroidGameController(device)
                || isKnownControllerKey(event.getKeyCode());
        if (!fromController) return false;

        return BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), action == KeyEvent.ACTION_DOWN);
    }

    private static float readAxis(@NonNull MotionEvent event, @Nullable InputDevice device, int axis) {
        InputDevice.MotionRange range = device != null ? getMotionRangeCompat(device, axis, event.getSource()) : null;

        float value = event.getAxisValue(axis);
        float flat = range != null ? Math.max(range.getFlat(), DEADZONE) : DEADZONE;
        return Math.abs(value) > flat ? value : 0f;
    }

    private static float readPositiveAxis(@NonNull MotionEvent event, @Nullable InputDevice device, int axis) {
        InputDevice.MotionRange range = device != null ? getMotionRangeCompat(device, axis, event.getSource()) : null;

        float value = event.getAxisValue(axis);
        float flat = range != null ? Math.max(range.getFlat(), 0.05f) : 0.05f;
        if (Math.abs(value) <= flat) return 0f;
        if (value < 0f) return 0f;
        return Math.min(value, 1f);
    }

    /**
     * Android controllers are inconsistent: some pads report the right stick on
     * Z/RZ, while others expose Z/RZ as resting trigger axes at -1 and use RX/RY
     * for the real right stick. BTA then sees the right stick jammed to top-left
     * and menu scrolling never receives Y movement. Prefer the centered pair when
     * Z/RZ looks like trigger rest and RX/RY is available.
     */
    @NonNull
    private static float[] readAndroidRightStickPair(@NonNull MotionEvent event, @Nullable InputDevice device) {
        float z = readAxis(event, device, MotionEvent.AXIS_Z);
        float rz = readAxis(event, device, MotionEvent.AXIS_RZ);
        float rx = readAxis(event, device, MotionEvent.AXIS_RX);
        float ry = readAxis(event, device, MotionEvent.AXIS_RY);

        boolean hasZR = hasAxis(device, event.getSource(), MotionEvent.AXIS_Z)
                || hasAxis(device, event.getSource(), MotionEvent.AXIS_RZ);
        boolean hasRXRY = hasAxis(device, event.getSource(), MotionEvent.AXIS_RX)
                || hasAxis(device, event.getSource(), MotionEvent.AXIS_RY);

        boolean zrLooksLikeTriggerRest = hasRXRY
                && z < -0.70f
                && rz < -0.70f
                && Math.abs(rx) <= DEADZONE
                && Math.abs(ry) <= DEADZONE;

        if (zrLooksLikeTriggerRest || (!hasZR && hasRXRY)) {
            logRightStickAxisChoiceOnce(device, "RX/RY", z, rz, rx, ry);
            return new float[]{rx, ry};
        }

        if (hasZR) {
            logRightStickAxisChoiceOnce(device, "Z/RZ", z, rz, rx, ry);
            return new float[]{z, rz};
        }

        logRightStickAxisChoiceOnce(device, "RX/RY fallback", z, rz, rx, ry);
        return new float[]{rx, ry};
    }

    private static boolean hasAxis(@Nullable InputDevice device, int source, int axis) {
        if (device == null) return false;
        return getMotionRangeCompat(device, axis, source) != null;
    }

    @Nullable
    private static InputDevice.MotionRange getMotionRangeCompat(@NonNull InputDevice device, int axis, int source) {
        InputDevice.MotionRange range = device.getMotionRange(axis, source);
        return range != null ? range : device.getMotionRange(axis);
    }

    private static void logRightStickAxisChoiceOnce(
            @Nullable InputDevice device,
            @NonNull String pair,
            float z,
            float rz,
            float rx,
            float ry
    ) {
        if (!btaNativeControllerBridgeEnabled || loggedBtaRightStickAxisChoice) return;
        loggedBtaRightStickAxisChoice = true;
        String name = device != null ? device.getName() : "unknown";
        try {
            Logger.appendToLog("BTA controller bridge: Android right-stick axis pair=" + pair
                    + " device=" + name
                    + " z/rz=" + z + "/" + rz
                    + " rx/ry=" + rx + "/" + ry);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isKnownControllerKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }


    public GamepadInputController(@NonNull View hostView, MappingRequestListener mappingRequestListener) {
        this.hostView = hostView;
        context = hostView.getContext().getApplicationContext();
        mappingStore = GamepadMappingStore.get(hostView.getContext());
        this.mappingRequestListener = mappingRequestListener;

        hostView.setFocusable(true);
        hostView.setFocusableInTouchMode(true);
        hostView.requestFocus();

        if (btaNativeControllerBridgeEnabled) {
            BtaNativeControllerBridge.initializeFromAndroidDevices(hostView.getContext());
        }

        org.lwjgl.glfw.CallbackBridge.sendCursorPos(
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth) / 2f,
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight) / 2f
        );

        lastGameMode = isGameMode();
        choreographer.postFrameCallback(frameCallback);
    }

    public void removeSelf() {
        removed = true;
        releaseDirection();
        releaseAllMappedButtons();
    }

    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (!isGamepadKeyEvent(event)) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }

        InputDevice device = event.getDevice();
        rememberDevice(device);

        if (btaNativeControllerBridgeEnabled
                && BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), action == KeyEvent.ACTION_DOWN)) {
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_UP && mappingRequestListener != null) {
                mappingRequestListener.onRequestControllerMapping();
            }
            return true;
        }

        GamepadButton button = GamepadButton.fromAndroidKeyCode(event.getKeyCode());
        if (button == null) return false;

        // Android can generate very fast repeat KeyEvents for D-pad directions.
        // The controller bridge keeps button/hat state itself, so repeated ACTION_DOWN
        // events are not needed and can make a remapped D-pad look like a mouse again.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        boolean down = action == KeyEvent.ACTION_DOWN;
        if (btaNativeControllerBridgeEnabled
                && BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), down)) {
            return true;
        }

        sendMappedButton(button, down, device);
        return true;
    }

    public boolean handleMotionEvent(@NonNull MotionEvent event) {
        // Do not ever claim normal touchscreen/mouse events. This class is only for
        // physical controller axes/buttons. Check gamepad first because some
        // handhelds expose mixed sources like JOYSTICK | MOUSE/TOUCHPAD.
        if (!isGamepadMotionEvent(event)) return false;
        if (isPointerMotionEvent(event)) return false;

        InputDevice device = event.getDevice();
        if (device == null) return false;
        rememberDevice(device);

        leftX = getCenteredAxis(event, device, MotionEvent.AXIS_X);
        leftY = getCenteredAxis(event, device, MotionEvent.AXIS_Y);

        float[] rightStick = readAndroidRightStickPair(event, device);
        rightX = rightStick[0];
        rightY = rightStick[1];

        float hatX = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X);
        float hatY = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y);
        float leftTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_LTRIGGER);
        float rightTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_RTRIGGER);
        if (leftTriggerAxis == 0f) leftTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_BRAKE);
        if (rightTriggerAxis == 0f) rightTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_GAS);

        boolean hatLeftNow = hatX < -HAT_THRESHOLD;
        boolean hatRightNow = hatX > HAT_THRESHOLD;
        boolean hatUpNow = hatY < -HAT_THRESHOLD;
        boolean hatDownNow = hatY > HAT_THRESHOLD;

        if (btaNativeControllerBridgeEnabled) {
            BtaNativeControllerBridge.updateLatestMenuRightStickY(rightY);
            if (BtaNativeControllerBridge.updateMotion(
                    device,
                    leftX, leftY,
                    rightX, rightY,
                    Math.max(0f, leftTriggerAxis),
                    Math.max(0f, rightTriggerAxis),
                    hatUpNow, hatRightNow, hatDownNow, hatLeftNow)) {
                return true;
            }
        }

        updateDirection();

        updateHatButton(GamepadButton.DPAD_LEFT, hatLeftNow, device);
        updateHatButton(GamepadButton.DPAD_RIGHT, hatRightNow, device);
        updateHatButton(GamepadButton.DPAD_UP, hatUpNow, device);
        updateHatButton(GamepadButton.DPAD_DOWN, hatDownNow, device);

        updateTrigger(true, leftTriggerAxis > TRIGGER_THRESHOLD, device);
        updateTrigger(false, rightTriggerAxis > TRIGGER_THRESHOLD, device);

        return true;
    }

    private void rememberDevice(@Nullable InputDevice device) {
        if (device == null) return;
        activeDevice = device;
        mappingStore.rememberDevice(device);
    }

    private static boolean isGamepadKeyEvent(@NonNull KeyEvent event) {
        int source = event.getSource();
        InputDevice device = event.getDevice();

        boolean fromGamepad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (device != null && ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK));

        if (fromGamepad) return true;

        return GamepadButton.fromAndroidKeyCode(event.getKeyCode()) != null
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU;
    }


    private static boolean isAndroidGameController(@Nullable InputDevice device) {
        if (device == null) return false;

        int sources;
        try {
            sources = device.getSources();
        } catch (Throwable ignored) {
            return false;
        }

        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) return true;

        String name;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            return false;
        }

        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.contains("controller")
                || lower.contains("gamepad")
                || lower.contains("joystick")
                || lower.contains("xbox")
                || lower.contains("playstation")
                || lower.contains("dualsense")
                || lower.contains("dualshock")
                || lower.contains("odin")
                || lower.contains("retroid")
                || lower.contains("anbernic")
                || lower.contains("8bitdo")
                || lower.contains("gamesir")
                || lower.contains("razer");
    }

    private static boolean isPointerMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (gamepad) return false;

        return (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
                || event.getPointerCount() > 1;
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    private static float getCenteredAxis(@NonNull MotionEvent event, @NonNull InputDevice device, int axis) {
        InputDevice.MotionRange range = getMotionRangeCompat(device, axis, event.getSource());
        if (range == null) return 0f;

        float value = event.getAxisValue(axis);
        float flat = Math.max(range.getFlat(), DEADZONE);
        return Math.abs(value) > flat ? value : 0f;
    }

    private boolean isGameMode() {
        return org.lwjgl.glfw.CallbackBridge.isGrabbing() || mappingStore.isForceGameMode();
    }

    private void tick(long frameTimeNanos) {
        float deltaScale = (frameTimeNanos - lastFrameNanos) / 16_666_666f;
        if (deltaScale <= 0f || deltaScale > 4f) deltaScale = 1f;

        boolean gameMode = isGameMode();
        if (gameMode != lastGameMode) {
            handleInputModeChanged(gameMode, frameTimeNanos);
            lastGameMode = gameMode;
            // Do not let the frame that changed modes consume stale delta/axis data.
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (btaNativeControllerBridgeEnabled) {
            if (gameMode) {
                BtaNativeControllerBridge.resetMenuScroll();
            } else {
                BtaNativeControllerBridge.tickMenuScroll(deltaScale);
            }
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (gameMode) {
            tickCamera(deltaScale, frameTimeNanos);
        } else {
            tickMenuCursor(deltaScale);
        }

        lastFrameNanos = frameTimeNanos;
    }

    private void handleInputModeChanged(boolean gameMode, long frameTimeNanos) {
        releaseDirection();
        menuScrollAccumulator = 0f;

        if (gameMode) {
            blockCameraAfterMenuClose(frameTimeNanos, "Minecraft input re-grabbed");
        } else {
            requireRightStickNeutralBeforeCamera = false;
            suppressCameraUntilNanos = 0L;
        }
    }

    private void prepareForLikelyMenuCloseFromController(@NonNull GamepadButton button) {
        blockCameraAfterMenuClose(System.nanoTime(), "Menu close requested by " + button);
    }

    private void blockCameraAfterMenuClose(long nowNanos, @NonNull String reason) {
        boolean rightStickWasActive = !isRightStickNeutral();

        clearLeftStickAxes();
        if (!rightStickWasActive) {
            clearRightStickAxes();
        }
        releaseDirection();

        requireRightStickNeutralBeforeCamera = rightStickWasActive;
        suppressCameraUntilNanos = Math.max(
                suppressCameraUntilNanos,
                nowNanos + (rightStickWasActive
                        ? GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS
                        : GAME_REGRAB_CAMERA_SETTLE_NANOS)
        );

        Logging.i(TAG, reason + (rightStickWasActive
                ? "; right stick was active, guarding camera until neutral or timeout"
                : "; right stick was neutral, short camera settle only"));
    }

    private boolean isRightStickNeutral() {
        return rightX == 0f && rightY == 0f;
    }

    private void clearLeftStickAxes() {
        leftX = 0f;
        leftY = 0f;
    }

    private void clearRightStickAxes() {
        rightX = 0f;
        rightY = 0f;
        menuScrollAccumulator = 0f;
    }

    private void tickCamera(float deltaScale, long frameTimeNanos) {
        if (shouldBlockCameraInput(frameTimeNanos)) return;
        if (rightX == 0f && rightY == 0f) return;

        float magnitude = Math.min(1f, (float) Math.sqrt(rightX * rightX + rightY * rightY));
        float acceleration = magnitude * magnitude;

        float sensitivity = BASE_GAME_CAMERA_SENSITIVITY
                * mappingStore.getGameCameraSensitivityMultiplier();

        // Do NOT apply resolution-scale compensation here.
        // Minecraft camera look already interprets this as grabbed mouse/camera
        // movement, and multiplying it by the render-resolution scale makes the
        // right stick feel slow when the user lowers game resolution.
        float deltaX = rightX * acceleration * sensitivity * deltaScale;
        float deltaY = rightY * acceleration * sensitivity * deltaScale;

        org.lwjgl.glfw.CallbackBridge.mouseX += deltaX;
        org.lwjgl.glfw.CallbackBridge.mouseY += deltaY;
        org.lwjgl.glfw.CallbackBridge.sendCursorPos(org.lwjgl.glfw.CallbackBridge.mouseX, org.lwjgl.glfw.CallbackBridge.mouseY);
    }

    private boolean shouldBlockCameraInput(long frameTimeNanos) {
        boolean rightStickNeutral = isRightStickNeutral();
        boolean timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;

        if (requireRightStickNeutralBeforeCamera && (rightStickNeutral || !timedSuppressActive)) {
            // Clear the latch as soon as the stale stick returns neutral. Also clear
            // it after the stale timeout so a noisy controller cannot leave camera
            // input stuck until the user moves the right stick a second time.
            requireRightStickNeutralBeforeCamera = false;

            if (rightStickNeutral && timedSuppressActive) {
                suppressCameraUntilNanos = Math.min(
                        suppressCameraUntilNanos,
                        frameTimeNanos + GAME_REGRAB_CAMERA_SETTLE_NANOS
                );
                timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;
            }
        }

        return timedSuppressActive || requireRightStickNeutralBeforeCamera;
    }

    private void tickMenuCursor(float deltaScale) {
        // Match launcher behavior users expect in Minecraft menus: the right stick's
        // vertical axis is a mouse wheel, not a second vertical cursor stick. This
        // makes mod lists, world lists, inventory recipe/book panels, and normal
        // Minecraft scrollbars react when the user pushes the right stick up/down.
        tickMenuRightStickScroll(deltaScale);

        float x = Math.abs(rightX) > Math.abs(leftX) ? rightX : leftX;
        float y = leftY;

        float dx = 0f;
        float dy = 0f;

        float sensitivityMultiplier = mappingStore.getMenuCursorSensitivityMultiplier();
        float menuResolutionScale = menuCursorResolutionScale();

        if (x != 0f || y != 0f) {
            float magnitude = Math.min(1f, (float) Math.sqrt(x * x + y * y));
            float acceleration = Math.max(0.35f, magnitude * magnitude);
            float sensitivity = BASE_MENU_CURSOR_SENSITIVITY * sensitivityMultiplier * menuResolutionScale;
            dx += x * acceleration * sensitivity * deltaScale;
            dy += y * acceleration * sensitivity * deltaScale;
        }

        // Only repeat D-pad cursor movement when that D-pad direction is actually mapped
        // to a Cursor action. This fixes remapped D-pad buttons still behaving like a joystick.
        float cursorRepeatScale = (BASE_DPAD_CURSOR_STEP / CURSOR_ACTION_BASE_STEP)
                * sensitivityMultiplier
                * menuResolutionScale
                * deltaScale;
        float[] dpadDelta = addDpadCursorRepeat(cursorRepeatScale);
        dx += dpadDelta[0];
        dy += dpadDelta[1];

        if (dx != 0f || dy != 0f) {
            GamepadAction.moveCursorBy(dx, dy);
        }
    }

    private void tickMenuRightStickScroll(float deltaScale) {
        if (rightY == 0f) {
            menuScrollAccumulator = 0f;
            return;
        }

        float strength = Math.min(1f, Math.abs(rightY));
        float acceleration = Math.max(0.35f, strength * strength);
        float sensitivity = BASE_MENU_SCROLL_SENSITIVITY
                * mappingStore.getMenuCursorSensitivityMultiplier();

        // Android right-stick up is negative Y. GLFW/Minecraft mouse-wheel up is
        // positive Y, so invert the joystick axis before sending the scroll event.
        menuScrollAccumulator += -rightY * acceleration * sensitivity * deltaScale;

        if (Math.abs(menuScrollAccumulator) < 1f) return;

        int scrollSteps = (int) menuScrollAccumulator;
        menuScrollAccumulator -= scrollSteps;

        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.sendScroll(0d, scrollSteps);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send right-stick menu scroll", throwable);
        }
    }

    /**
     * Menu cursor coordinates are visual/window coordinates, so lower render
     * resolution can make the visible cursor travel too far. Keep this correction
     * limited to menu cursor movement only; grabbed in-game camera movement must
     * stay unscaled.
     */
    private float menuCursorResolutionScale() {
        try {
            float windowWidth = org.lwjgl.glfw.CallbackBridge.windowWidth;
            float physicalWidth = org.lwjgl.glfw.CallbackBridge.physicalWidth;
            if (windowWidth > 1f && physicalWidth > 1f) {
                float ratio = windowWidth / physicalWidth;
                if (ratio > 0.05f && ratio < 4f && Math.abs(ratio - 1f) > 0.025f) {
                    return clamp(ratio, 0.35f, 2.5f);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            int percent = LauncherPreferences.getGameResolutionScalePercent(context);
            if (percent > 0) return clamp(percent / 100f, 0.35f, 2.5f);
        } catch (Throwable ignored) {
        }

        return 1f;
    }

    @NonNull
    private float[] addDpadCursorRepeat(float scale) {
        float[] delta = new float[]{0f, 0f};
        addCursorRepeat(delta, GamepadButton.DPAD_LEFT, hatLeft, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_RIGHT, hatRight, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_UP, hatUp, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_DOWN, hatDown, scale);
        return delta;
    }

    private void addCursorRepeat(
            @NonNull float[] delta,
            @NonNull GamepadButton button,
            boolean pressed,
            float scale
    ) {
        if (!pressed) return;
        GamepadAction[] actions = mappingStore.getButtonActions(button, false, activeDevice);
        for (GamepadAction action : actions) {
            if (action == null || !action.isCursorAction()) continue;
            delta[0] += action.getCursorDx() * scale;
            delta[1] += action.getCursorDy() * scale;
        }
    }

    private void updateDirection() {
        if (!isGameMode()) {
            releaseDirection();
            return;
        }

        int newDirection = directionFor(leftX, leftY);
        if (newDirection == currentDirection) return;

        sendDirectional(currentDirection, false);
        currentDirection = newDirection;
        sendDirectional(currentDirection, true);
    }

    private void releaseDirection() {
        sendDirectional(currentDirection, false);
        currentDirection = DIRECTION_NONE;
    }

    private static int directionFor(float x, float y) {
        if (Math.sqrt(x * x + y * y) < DEADZONE) return DIRECTION_NONE;

        double angle = Math.toDegrees(Math.atan2(-y, x));
        if (angle < 0) angle += 360.0;

        return ((int) ((angle + 22.5) / 45.0)) % 8;
    }

    private void sendDirectional(int direction, boolean isDown) {
        switch (direction) {
            case DIRECTION_NORTH:
                GamepadAction.FORWARD.perform(isDown);
                break;
            case DIRECTION_NORTH_EAST:
                GamepadAction.FORWARD.perform(isDown);
                GamepadAction.RIGHT.perform(isDown);
                break;
            case DIRECTION_EAST:
                GamepadAction.RIGHT.perform(isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                GamepadAction.RIGHT.perform(isDown);
                GamepadAction.BACKWARD.perform(isDown);
                break;
            case DIRECTION_SOUTH:
                GamepadAction.BACKWARD.perform(isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                GamepadAction.BACKWARD.perform(isDown);
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_WEST:
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_NORTH_WEST:
                GamepadAction.FORWARD.perform(isDown);
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_NONE:
            default:
                break;
        }
    }

    private void sendMappedButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @Nullable InputDevice device
    ) {
        ActiveMappedAction[] mapped;

        if (isDown) {
            mapped = resolveMappedActions(button, device);
            activeButtonActions.put(button, mapped);
        } else {
            mapped = activeButtonActions.remove(button);
            if (mapped == null) {
                // Fallback for devices that send an UP without the matching DOWN.
                mapped = resolveMappedActions(button, device);
            }
        }

        if (isDown && containsMenuEscape(mapped)) {
            prepareForLikelyMenuCloseFromController(button);
        }

        Logging.i(TAG, "Button=" + button + ", down=" + isDown
                + ", profile=" + mappingStore.profileKeyForDevice(device)
                + ", actions=" + describeMappedActions(mapped)
                + ", cursor=" + org.lwjgl.glfw.CallbackBridge.mouseX + ","
                + org.lwjgl.glfw.CallbackBridge.mouseY);

        performMappedActions(mapped, isDown);
    }

    @NonNull
    private ActiveMappedAction[] resolveMappedActions(
            @NonNull GamepadButton button,
            @Nullable InputDevice device
    ) {
        boolean gameMode = isGameMode();
        GamepadAction[] actions = mappingStore.getButtonActions(button, gameMode, device);
        ActiveMappedAction[] mapped = new ActiveMappedAction[actions.length];

        for (int slot = 0; slot < actions.length; slot++) {
            GamepadAction action = actions[slot] == null ? GamepadAction.NONE : actions[slot];

            // Guard against old saved prefs from earlier patches where menu A/R2 were ENTER.
            // Those prefs survive reinstall/rebuild and make it look like A is not mapped to click.
            if (!gameMode && (button == GamepadButton.BUTTON_A
                    || button == GamepadButton.BUTTON_R2
                    || button == GamepadButton.DPAD_CENTER)
                    && action == GamepadAction.ENTER) {
                Logging.i(TAG, "Overriding old menu " + button + " slot " + slot + " ENTER mapping to MOUSE_LEFT");
                action = GamepadAction.MOUSE_LEFT;
            }

            // Menu sliders need a real held mouse button, not a one-frame/pulsed
            // click. When A/R2 is held over a Minecraft slider, the left stick keeps
            // moving the cursor while GLFW still sees mouse-left held down, so the
            // slider can drag just like it does with a physical mouse. Normal menu
            // button clicks still work because ACTION_UP releases the same button.
            boolean pulseMenuMouseClick = false;
            mapped[slot] = new ActiveMappedAction(action, pulseMenuMouseClick, gameMode);
        }
        return mapped;
    }

    private static boolean containsMenuEscape(@NonNull ActiveMappedAction[] mapped) {
        for (ActiveMappedAction action : mapped) {
            if (action != null && !action.gameMode && action.action == GamepadAction.ESCAPE) {
                return true;
            }
        }
        return false;
    }

    private void performMappedActions(@NonNull ActiveMappedAction[] mapped, boolean isDown) {
        if (isDown) {
            for (ActiveMappedAction action : mapped) {
                if (action != null && action.action != GamepadAction.NONE) {
                    action.action.perform(true, action.pulseMenuMouseClick, hostView);
                }
            }
        } else {
            for (int i = mapped.length - 1; i >= 0; i--) {
                ActiveMappedAction action = mapped[i];
                if (action != null && action.action != GamepadAction.NONE) {
                    action.action.perform(false, action.pulseMenuMouseClick, hostView);
                }
            }
        }
    }

    @NonNull
    private static String describeMappedActions(@NonNull ActiveMappedAction[] mapped) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mapped.length; i++) {
            ActiveMappedAction action = mapped[i];
            if (action == null || action.action == GamepadAction.NONE) continue;
            if (builder.length() > 0) builder.append(" + ");
            builder.append(i).append(":").append(action.action.name());
        }
        return builder.length() == 0 ? "NONE" : builder.toString();
    }

    private void releaseAllMappedButtons() {
        if (activeButtonActions.isEmpty()) return;
        for (ActiveMappedAction[] mapped : activeButtonActions.values()) {
            if (mapped != null) {
                performMappedActions(mapped, false);
            }
        }
        activeButtonActions.clear();
    }

    private void updateHatButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @NonNull InputDevice device
    ) {
        switch (button) {
            case DPAD_UP:
                if (hatUp == isDown) return;
                hatUp = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_DOWN:
                if (hatDown == isDown) return;
                hatDown = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_LEFT:
                if (hatLeft == isDown) return;
                hatLeft = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_RIGHT:
                if (hatRight == isDown) return;
                hatRight = isDown;
                sendMappedButton(button, isDown, device);
                break;
            default:
                break;
        }
    }

    private void updateTrigger(boolean left, boolean isDown, @NonNull InputDevice device) {
        if (left) {
            if (leftTriggerDown == isDown) return;
            leftTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_L2, isDown, device);
        } else {
            if (rightTriggerDown == isDown) return;
            rightTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_R2, isDown, device);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ActiveMappedAction {
        @NonNull final GamepadAction action;
        final boolean pulseMenuMouseClick;
        final boolean gameMode;

        ActiveMappedAction(
                @NonNull GamepadAction action,
                boolean pulseMenuMouseClick,
                boolean gameMode
        ) {
            this.action = action;
            this.pulseMenuMouseClick = pulseMenuMouseClick;
            this.gameMode = gameMode;
        }
    }

    private static final class BtaNativeControllerBridge {
        private static volatile boolean initialized;
        private static volatile boolean loggedMotion;
        private static volatile boolean loggedButton;
        private static volatile boolean loggedMenuScroll;
        private static volatile float latestMenuRightStickY;
        private static float menuScrollAccumulator;

        private BtaNativeControllerBridge() {
        }

        static void initializeFromAndroidDevices(@NonNull Context context) {
            if (initialized) return;
            initialized = true;
            try {
                int selectedId = -1;
                String selectedName = null;
                String selectedDescriptor = null;
                int count = 0;

                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice device = InputDevice.getDevice(id);
                    if (!isAndroidGameController(device)) continue;

                    count++;
                    appendBta("BTA controller bridge: Android controller id=" + id
                            + " name=" + device.getName()
                            + " descriptor=" + device.getDescriptor()
                            + " sources=0x" + Integer.toHexString(device.getSources()));

                    if (selectedId < 0) {
                        selectedId = id;
                        selectedName = device.getName();
                        selectedDescriptor = device.getDescriptor();
                    }
                }

                if (selectedId >= 0) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            selectedId,
                            selectedName,
                            selectedDescriptor
                    );
                    org.lwjgl.glfw.CallbackBridge.sendBtaGamepadMotion(
                            selectedId,
                            selectedName,
                            selectedDescriptor,
                            0f, 0f,
                            0f, 0f,
                            0f, 0f,
                            false, false, false, false
                    );
                    appendBta("BTA controller bridge: initialized native gamepad from Android device count="
                            + count + " selected=" + selectedName);
                } else {
                    // Keep BTA's controller UI visible even if Android does not expose the device
                    // until the first motion/key event. The first real controller event replaces this.
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            -1,
                            "DroidBridge Android Controller",
                            "droidbridge-bta-controller"
                    );
                    appendBta("BTA controller bridge: no Android controller devices found at launch; using visible fallback until first controller event");
                }
            } catch (Throwable throwable) {
                appendBta("BTA controller bridge: Android device initialization failed: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }

        static void updateLatestMenuRightStickY(float rightY) {
            latestMenuRightStickY = rightY;
            if (!loggedBtaRightStickMenuSample && Math.abs(rightY) > DEADZONE) {
                loggedBtaRightStickMenuSample = true;
                appendBta("BTA controller bridge: right-stick Y sample for menu scroll=" + rightY);
            }
        }

        static void resetMenuScroll() {
            menuScrollAccumulator = 0f;
        }

        static void tickMenuScroll(float deltaScale) {
            float rightY = latestMenuRightStickY;
            if (Math.abs(rightY) <= DEADZONE) {
                menuScrollAccumulator = 0f;
                return;
            }

            float strength = Math.min(1f, Math.abs(rightY));
            float acceleration = Math.max(0.35f, strength * strength);

            // Android right-stick up is negative Y. GLFW/Minecraft mouse-wheel up is
            // positive Y, so invert the joystick axis before sending the scroll event.
            menuScrollAccumulator += -rightY * acceleration * BTA_MENU_SCROLL_SENSITIVITY * deltaScale;

            if (Math.abs(menuScrollAccumulator) < 1f) return;

            int scrollSteps = (int) menuScrollAccumulator;
            menuScrollAccumulator -= scrollSteps;

            try {
                org.lwjgl.glfw.CallbackBridge.setInputReady(true);
                org.lwjgl.glfw.CallbackBridge.sendScroll(0d, scrollSteps);
                if (!loggedMenuScroll) {
                    loggedMenuScroll = true;
                    appendBta("BTA controller bridge: right-stick menu scroll routed as GLFW scroll");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA right-stick menu scroll failed", throwable);
            }
        }

        static boolean updateMotion(
                @Nullable InputDevice device,
                float leftX,
                float leftY,
                float rightX,
                float rightY,
                float leftTrigger,
                float rightTrigger,
                boolean hatUp,
                boolean hatRight,
                boolean hatDown,
                boolean hatLeft
        ) {
            try {
                if (device != null) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            device.getId(),
                            device.getName(),
                            device.getDescriptor()
                    );
                }
                org.lwjgl.glfw.CallbackBridge.sendBtaGamepadMotion(
                        device != null ? device.getId() : -1,
                        device != null ? device.getName() : "DroidBridge Android Controller",
                        device != null ? device.getDescriptor() : "droidbridge-bta-controller",
                        leftX,
                        leftY,
                        rightX,
                        rightY,
                        leftTrigger,
                        rightTrigger,
                        hatUp,
                        hatRight,
                        hatDown,
                        hatLeft
                );
                if (!loggedMotion) {
                    loggedMotion = true;
                    appendBta("BTA controller bridge: first Android motion event delivered to native gamepad state"
                            + (device != null ? " device=" + device.getName() : ""));
                }
                return true;
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native controller bridge motion update failed", throwable);
                return false;
            }
        }

        static boolean updateButton(@Nullable InputDevice device, int androidKeyCode, boolean down) {
            try {
                if (device != null) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            device.getId(),
                            device.getName(),
                            device.getDescriptor()
                    );
                }
                org.lwjgl.glfw.CallbackBridge.sendBtaGamepadButton(
                        device != null ? device.getId() : -1,
                        device != null ? device.getName() : "DroidBridge Android Controller",
                        device != null ? device.getDescriptor() : "droidbridge-bta-controller",
                        androidKeyCode,
                        down
                );
                if (!loggedButton) {
                    loggedButton = true;
                    appendBta("BTA controller bridge: first Android button event delivered to native gamepad state key="
                            + androidKeyCode + " down=" + down
                            + (device != null ? " device=" + device.getName() : ""));
                }
                return true;
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native controller bridge button update failed", throwable);
                return false;
            }
        }

        private static boolean isAndroidGameController(@Nullable InputDevice device) {
            if (device == null) return false;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
            if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;

            String name = device.getName();
            if (name == null) return false;
            String lower = name.toLowerCase(java.util.Locale.US);
            return lower.contains("controller")
                    || lower.contains("gamepad")
                    || lower.contains("joystick")
                    || lower.contains("xbox")
                    || lower.contains("playstation")
                    || lower.contains("dualsense")
                    || lower.contains("dualshock")
                    || lower.contains("odin")
                    || lower.contains("retroid")
                    || lower.contains("anbernic")
                    || lower.contains("8bitdo")
                    || lower.contains("gamesir")
                    || lower.contains("razer");
        }

        private static void appendBta(@NonNull String message) {
            try {
                Logger.appendToLog(message);
            } catch (Throwable ignored) {
            }
            Logging.i(TAG, message);
        }
    }

}
