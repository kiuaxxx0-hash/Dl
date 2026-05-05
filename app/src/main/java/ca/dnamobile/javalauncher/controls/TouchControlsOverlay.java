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

package ca.dnamobile.javalauncher.controls;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.util.SparseArray;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.UUID;

import org.lwjgl.glfw.CallbackBridge;

import ca.dnamobile.javalauncher.feature.log.Logging;
import net.kdt.pojavlaunch.MinecraftGLSurface;

/**
 * Runtime/editor overlay. It deliberately avoids XML so it can be injected over the
 * existing Minecraft surface without rewriting activity_game.xml.
 */
public final class TouchControlsOverlay extends FrameLayout implements TouchControlButtonView.Listener {
    public interface AppMenuListener {
        void onTouchControlsMenuRequested();
    }

    private static final String TAG = "TouchControlsOverlay";

    private boolean editMode;
    private boolean controlsVisible = true;
    private boolean rebuildPending;
    @Nullable private File layoutFile;
    @NonNull private TouchControlsLayoutData layoutData = TouchControlsLayoutData.defaultLayout();
    @Nullable private AppMenuListener appMenuListener;
    @Nullable private View passthroughTarget;

    /**
     * Runtime multi-touch routing:
     * - pointers that begin on a touch button are owned by that button
     * - the first pointer that begins on empty space is forwarded to MinecraftGLSurface as
     *   a clean single-pointer mouse/camera stream
     *
     * Android may reorder pointer indexes when a finger lifts/re-enters. Tracking by
     * pointer ID keeps the camera finger stable while other fingers hold buttons.
     *
     * Do not forward the full MotionEvent or a split multi-pointer stream to Minecraft
     * while buttons are held. The game surface expects a normal DOWN/MOVE/UP sequence
     * for camera look; sending ACTION_POINTER_DOWN/UP while another finger owns a button
     * can make the camera jump left/right when the look finger is lifted and placed back.
     */
    private static final int NO_POINTER_ID = -1;
    private static final int MOUSE_BUTTON_LEFT = 0;

    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final int cameraTouchSlop;

    /** Pointer ID for the right-thumb look/attack stream. */
    private int cameraPointerId = NO_POINTER_ID;
    private float cameraDownX;
    private float cameraDownY;
    private float cameraLastX;
    private float cameraLastY;
    private boolean cameraMovedPastSlop;
    private boolean cameraLongPressAttackActive;
    @Nullable private Runnable cameraLongPressRunnable;

    /** GUI fallback: used only when Minecraft is not grabbing the mouse. */
    private int passthroughPointerId = NO_POINTER_ID;
    private long passthroughDownTime;

    /** In-game hotbar touch routing. Keep this separate from camera/buttons. */
    private int hotbarPointerId = NO_POINTER_ID;
    private int hotbarLastSlot = -1;

    @NonNull private final SparseArray<TouchControlButtonView> controlPointerTargets = new SparseArray<>();

    private final Paint hotbarDebugFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TouchControlsOverlay(@NonNull Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        setMotionEventSplittingEnabled(true);
        setWillNotDraw(false);

        hotbarDebugFillPaint.setColor(0x44FFEB3B);
        hotbarDebugFillPaint.setStyle(Paint.Style.FILL);

        hotbarDebugStrokePaint.setColor(Color.YELLOW);
        hotbarDebugStrokePaint.setStyle(Paint.Style.STROKE);
        hotbarDebugStrokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);

        hotbarDebugSlotPaint.setColor(0xCCFF9800);
        hotbarDebugSlotPaint.setStyle(Paint.Style.STROKE);
        hotbarDebugSlotPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);

        hotbarDebugTextPaint.setColor(Color.WHITE);
        hotbarDebugTextPaint.setTextAlign(Paint.Align.CENTER);
        hotbarDebugTextPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        hotbarDebugTextPaint.setShadowLayer(3f, 0f, 0f, Color.BLACK);

        cameraTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setPassthroughTarget(@Nullable View passthroughTarget) {
        this.passthroughTarget = passthroughTarget;
    }

    public void setAppMenuListener(@Nullable AppMenuListener appMenuListener) {
        this.appMenuListener = appMenuListener;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        rebuildWhenSized();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        setVisibility(VISIBLE);
        applyControlsVisualState();
    }

    public void toggleControlVisible() {
        setControlsVisible(!controlsVisible);
        ControlsPreferences.setTouchControlsEnabled(getContext(), controlsVisible);
    }

    public void loadSelectedLayout() {
        layoutFile = TouchControlsStore.getSelectedLayoutFile(getContext());
        layoutData = TouchControlsStore.loadLayout(layoutFile);
        rebuildWhenSized();
    }

    public void loadLayout(@NonNull File file) {
        layoutFile = file;
        layoutData = TouchControlsStore.loadLayout(file);
        ControlsPreferences.setSelectedLayoutPath(getContext(), file.getAbsolutePath());
        rebuildWhenSized();
    }

    public void saveLayout() {
        try {
            File target = layoutFile != null ? layoutFile : TouchControlsStore.getSelectedLayoutFile(getContext());
            TouchControlsStore.saveLayout(target, layoutData);
            layoutFile = target;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to save touch controls", throwable);
            Toast.makeText(getContext(), "Unable to save touch controls: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void addControl(@NonNull TouchControlData data) {
        layoutData.controls.add(data);
        saveLayout();
        rebuildWhenSized();
    }

    @NonNull
    public TouchControlsLayoutData getLayoutData() {
        return layoutData;
    }

    private void rebuildWhenSized() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            if (!rebuildPending) {
                rebuildPending = true;
                post(() -> {
                    rebuildPending = false;
                    if (getWidth() > 1 && getHeight() > 1) {
                        rebuild();
                    }
                });
            }
            return;
        }
        rebuild();
    }

    private void rebuild() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            rebuildWhenSized();
            return;
        }

        removeAllViews();
        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());
        LayoutMetrics metrics = layoutMetrics(parentWidth, parentHeight);
        float density = getResources().getDisplayMetrics().density;
        for (TouchControlData control : layoutData.controls) {
            if (!editMode && !control.visibleInGame) continue;
            TouchControlButtonView button = new TouchControlButtonView(getContext(), control, this);
            button.setEditMode(editMode);
            button.setVisibility(editMode || controlsVisible ? VISIBLE : INVISIBLE);
            int width = Math.min(metrics.toScreenWidth(control.width), parentWidth);
            int height = Math.min(metrics.toScreenHeight(control.height), parentHeight);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            addView(button, params);

            float fallbackX = metrics.toScreenX(control, width);
            float fallbackY = metrics.toScreenY(control, height);
            float resolvedX = control.rawX == null
                    ? fallbackX
                    : ExpressionResolver.resolve(control.rawX, fallbackX, parentWidth, parentHeight, width, height, density, layoutData.preferredScale, metrics.formulaPixelScale);
            float resolvedY = control.rawY == null
                    ? fallbackY
                    : ExpressionResolver.resolve(control.rawY, fallbackY, parentWidth, parentHeight, width, height, density, layoutData.preferredScale, metrics.formulaPixelScale);
            resolvedX = Math.max(0f, Math.min(Math.max(0, parentWidth - width), resolvedX));
            resolvedY = Math.max(0f, Math.min(Math.max(0, parentHeight - height), resolvedY));
            button.setX(resolvedX);
            button.setY(resolvedY);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rebuildWhenSized();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) rebuildWhenSized();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        drawHotbarHitboxDebug(canvas);
    }

    private void drawHotbarHitboxDebug(@NonNull Canvas canvas) {
        if (!ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) return;

        TouchHotbarHitbox.Result hitbox = TouchHotbarHitbox.calculate(
                getContext(),
                getWidth(),
                getHeight(),
                CallbackBridge.physicalWidth,
                CallbackBridge.physicalHeight
        );

        RectF touchBounds = hitbox.touchBounds;
        RectF hotbarBounds = hitbox.hotbarBounds;

        canvas.drawRect(touchBounds, hotbarDebugFillPaint);
        canvas.drawRect(touchBounds, hotbarDebugStrokePaint);

        for (int i = 0; i <= TouchHotbarHitbox.SLOT_COUNT; i++) {
            float x = hotbarBounds.left + (i * hitbox.slotWidth);
            canvas.drawLine(x, touchBounds.top, x, touchBounds.bottom, hotbarDebugSlotPaint);
        }

        float textY = touchBounds.top - (6f * getResources().getDisplayMetrics().density);
        if (textY < hotbarDebugTextPaint.getTextSize() + 2f) {
            textY = touchBounds.bottom + hotbarDebugTextPaint.getTextSize() + 4f;
        }

        canvas.drawText(
                "Hotbar hitbox  scale=" + hitbox.scale
                        + "  xOff=" + ControlsPreferences.getHotbarXOffsetDp(getContext())
                        + "dp  yOff=" + ControlsPreferences.getHotbarYOffsetDp(getContext()) + "dp",
                hotbarBounds.centerX(),
                textY,
                hotbarDebugTextPaint
        );

        for (int slot = 0; slot < TouchHotbarHitbox.SLOT_COUNT; slot++) {
            float centerX = hotbarBounds.left + (slot * hitbox.slotWidth) + (hitbox.slotWidth / 2f);
            canvas.drawText(
                    String.valueOf(slot + 1),
                    centerX,
                    touchBounds.centerY() + (hotbarDebugTextPaint.getTextSize() / 3f),
                    hotbarDebugTextPaint
            );
        }

        postInvalidateOnAnimation();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        // Keep the full-screen overlay attached whether visual controls are shown or hidden.
        // Hidden controls should still allow hotbar taps, camera dragging, and menu passthrough
        // through the same safe routing path instead of dropping to the raw SurfaceView path.
        if (editMode) {
            return super.dispatchTouchEvent(event);
        }

        if (isHardwarePointerEvent(event)) {
            return dispatchWholeTouchEventToPassthrough(event) || super.dispatchTouchEvent(event);
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) {
            invalidate();
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                clearRuntimeTouchRouting();
                return routePointerDown(event, actionIndex);

            case MotionEvent.ACTION_POINTER_DOWN:
                routePointerDown(event, actionIndex);
                return hasActiveTouchRoute();

            case MotionEvent.ACTION_MOVE:
                dispatchActiveControlPointers(event, MotionEvent.ACTION_MOVE);
                dispatchActiveHotbarPointer(event);
                dispatchActiveCameraPointer(event);
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                routePointerUp(event, actionIndex, false);
                return true;

            case MotionEvent.ACTION_UP:
                routePointerUp(event, actionIndex, true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dispatchCancelToControlPointers(event);
                cancelCameraPointer(true);
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_CANCEL);
                clearRuntimeTouchRouting();
                return true;

            default:
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
        if (!editMode && isHardwarePointerEvent(event)) {
            return dispatchWholeGenericEventToPassthrough(event) || super.dispatchGenericMotionEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!editMode && MinecraftGLSurface.shouldRouteBackKeyToMinecraft(event)) {
            MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
            if (minecraftSurface != null && minecraftSurface.handleKeyEventFromActivity(event)) {
                return true;
            }
            if (passthroughTarget != null && passthroughTarget.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return editMode && super.onTouchEvent(event);
    }

    @Override
    public void onChanged() {
        saveLayout();
    }

    @Override
    public void onMoveRequested(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float proposedX,
            float proposedY
    ) {
        float[] snapped = resolveDraggedPosition(view, proposedX, proposedY);
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());

        // X/Y are stored in the layout's own units. JavaLauncher layouts use dp;
        // imported default_touch.json / Pojav-style layouts use source-canvas px.
        data.x = metrics.fromScreenX(data, snapped[0], view.getWidth());
        data.y = metrics.fromScreenY(snapped[1]);
        data.rawX = null;
        data.rawY = null;

        view.setX(snapped[0]);
        view.setY(snapped[1]);
        saveLayout();
    }

    @Override
    public void onEditRequested(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        showEditDialog(view, data);
    }

    @Override
    public void onMenuRequested() {
        if (appMenuListener != null) {
            appMenuListener.onTouchControlsMenuRequested();
        }
    }

    @Override
    public void onToggleControlsRequested() {
        toggleControlVisible();
    }

    @NonNull
    private float[] resolveDraggedPosition(@NonNull View movingView, float proposedX, float proposedY) {
        int width = movingView.getWidth();
        int height = movingView.getHeight();
        float x = clamp(proposedX, 0f, Math.max(0, getWidth() - width));
        float y = clamp(proposedY, 0f, Math.max(0, getHeight() - height));

        if (!ControlsPreferences.isSnapControlsEnabled(getContext())) {
            return new float[]{x, y};
        }

        float threshold = 12f * getResources().getDisplayMetrics().density;
        float bestX = x;
        float bestY = y;
        float bestXDelta = threshold + 1f;
        float bestYDelta = threshold + 1f;

        // Screen edges are useful snap targets too.
        float[] screenXTargets = new float[]{0f, (getWidth() - width) / 2f, Math.max(0f, getWidth() - width)};
        for (float target : screenXTargets) {
            float delta = Math.abs(x - target);
            if (delta <= threshold && delta < bestXDelta) {
                bestX = target;
                bestXDelta = delta;
            }
        }
        float[] screenYTargets = new float[]{0f, (getHeight() - height) / 2f, Math.max(0f, getHeight() - height)};
        for (float target : screenYTargets) {
            float delta = Math.abs(y - target);
            if (delta <= threshold && delta < bestYDelta) {
                bestY = target;
                bestYDelta = delta;
            }
        }

        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == movingView || other.getVisibility() != VISIBLE) continue;

            float otherLeft = other.getX();
            float otherTop = other.getY();
            float otherRight = otherLeft + other.getWidth();
            float otherBottom = otherTop + other.getHeight();
            float otherCenterX = otherLeft + other.getWidth() / 2f;
            float otherCenterY = otherTop + other.getHeight() / 2f;

            float[] xTargets = new float[]{
                    otherLeft,
                    otherRight,
                    otherLeft - width,
                    otherRight - width,
                    otherCenterX - width / 2f
            };
            for (float target : xTargets) {
                float delta = Math.abs(x - target);
                if (delta <= threshold && delta < bestXDelta) {
                    bestX = target;
                    bestXDelta = delta;
                }
            }

            float[] yTargets = new float[]{
                    otherTop,
                    otherBottom,
                    otherTop - height,
                    otherBottom - height,
                    otherCenterY - height / 2f
            };
            for (float target : yTargets) {
                float delta = Math.abs(y - target);
                if (delta <= threshold && delta < bestYDelta) {
                    bestY = target;
                    bestYDelta = delta;
                }
            }
        }

        return new float[]{
                clamp(bestX, 0f, Math.max(0, getWidth() - width)),
                clamp(bestY, 0f, Math.max(0, getHeight() - height))
        };
    }

    private void showEditDialog(@NonNull TouchControlButtonView editingView, @NonNull TouchControlData data) {
        Context context = getContext();

        String originalId = data.id;
        String originalLabel = data.label;
        String originalAction = data.action;
        int originalKeyCode = data.keyCode;
        int[] originalKeyCodes = data.normalizedKeyCodes().clone();
        int originalMouseButton = data.mouseButton;
        int originalScrollY = data.scrollY;
        float originalX = data.x;
        float originalY = data.y;
        float originalWidth = data.width;
        float originalHeight = data.height;
        float originalSizePercent = data.sizePercent;
        float originalOpacity = data.opacity;
        float originalCornerRadius = data.cornerRadius;
        float originalStrokeWidth = data.strokeWidth;
        int originalStrokeColor = data.strokeColor;
        int originalBackgroundColor = data.backgroundColor;
        boolean originalToggle = data.toggle;
        boolean originalVisibleInGame = data.visibleInGame;
        boolean originalVisibleInMenu = data.visibleInMenu;
        String originalRawX = data.rawX;
        String originalRawY = data.rawY;

        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int parentWidthUnits = Math.max(1, Math.round(metrics.maxLayoutXUnits()));
        int parentHeightUnits = Math.max(1, Math.round(metrics.maxLayoutYUnits()));

        // Imported Zalith/Mojo/Amethyst controls may still have dynamic rawX/rawY
        // formulas. Show their current resolved position converted back into the
        // layout's own units. Saving clears rawX/rawY and keeps the button exactly
        // where the user sees it.
        float initialLayoutX = data.rawX == null ? data.x : metrics.fromScreenX(data, editingView.getX(), editingView.getWidth());
        float initialLayoutY = data.rawY == null ? data.y : metrics.fromScreenY(editingView.getY());

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18f);
        layout.setPadding(padding, dp(8f), padding, dp(10f));
        scrollView.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(context);
        summary.setText("Editing: " + (data.label == null || data.label.trim().isEmpty() ? "Button" : data.label.trim()));
        summary.setTextColor(0xFFE0E0E0);
        summary.setTextSize(15f);
        summary.setPadding(0, 0, 0, dp(10f));
        layout.addView(summary);

        addSectionHeader(layout, "Identity", "Set the display name, action, ID, and optional key combo.");

        EditText label = textField(context, "Button label", data.label, false);
        addFieldRow(layout, "Label", label);

        EditText idField = textField(context, "Stable button ID", data.id, false);
        addFieldRow(layout, "Button ID", idField);

        String[] actionLabels = TouchInputBinding.actionLabels();
        String[] actionValues = TouchInputBinding.actionValues();
        Spinner actionSpinner = new Spinner(context);
        ArrayAdapter<String> actionAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, actionLabels);
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        actionSpinner.setAdapter(actionAdapter);
        actionSpinner.setSelection(TouchInputBinding.actionIndex(data.action));
        addSpinnerRow(layout, "Action", actionSpinner);

        Spinner bindingSpinner = new Spinner(context);
        addSpinnerRow(layout, "Binding", bindingSpinner);
        final TouchInputBinding.Option[][] currentOptions = new TouchInputBinding.Option[1][];

        // Keep the raw numeric key list internally for saving, but do not show it
        // as the main UI. Users should see friendly names like "W + Left Ctrl",
        // not GLFW key IDs like "87, 341".
        EditText keyCodes = textField(context, "Internal key IDs", joinKeyCodes(data.normalizedKeyCodes()), false);
        keyCodes.setVisibility(GONE);
        layout.addView(keyCodes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        ));

        TextView boundKeys = valueLabel(context, "Bound keys: " + TouchInputBinding.friendlyKeyCombo(data.normalizedKeyCodes()));
        layout.addView(boundKeys);

        Button addSelectedBinding = new Button(context);
        addSelectedBinding.setText("Add selected key to this button");
        addSelectedBinding.setAllCaps(false);
        layout.addView(addSelectedBinding, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button clearSelectedBindings = new Button(context);
        clearSelectedBindings.setText("Clear bound keys");
        clearSelectedBindings.setAllCaps(false);
        layout.addView(clearSelectedBindings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AdapterView.OnItemSelectedListener actionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String action = actionValues[Math.max(0, Math.min(position, actionValues.length - 1))];
                TouchInputBinding.Option[] options = TouchInputBinding.optionsForAction(action);
                currentOptions[0] = options;
                ArrayAdapter<String> bindingAdapter = new ArrayAdapter<>(
                        context,
                        android.R.layout.simple_spinner_item,
                        TouchInputBinding.optionLabels(options)
                );
                bindingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                bindingSpinner.setAdapter(bindingAdapter);
                boolean keyAction = TouchControlActions.KEY.equals(action);
                int selected = keyAction && TouchControlActions.KEY.equals(data.action)
                        || TouchControlActions.MOUSE.equals(action) && TouchControlActions.MOUSE.equals(data.action)
                        || TouchControlActions.SCROLL.equals(action) && TouchControlActions.SCROLL.equals(data.action)
                        ? TouchInputBinding.selectedOptionIndex(action, data)
                        : 0;
                bindingSpinner.setSelection(selected, false);
                bindingSpinner.setEnabled(options.length > 1);
                keyCodes.setVisibility(GONE);
                boundKeys.setVisibility(keyAction ? VISIBLE : GONE);
                addSelectedBinding.setVisibility(keyAction ? VISIBLE : GONE);
                clearSelectedBindings.setVisibility(keyAction ? VISIBLE : GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        actionSpinner.setOnItemSelectedListener(actionListener);
        actionListener.onItemSelected(actionSpinner, actionSpinner, actionSpinner.getSelectedItemPosition(), 0L);

        addSelectedBinding.setOnClickListener(v -> {
            TouchInputBinding.Option[] options = currentOptions[0] != null ? currentOptions[0] : TouchInputBinding.optionsForAction(TouchControlActions.KEY);
            if (options.length == 0) return;
            int index = Math.max(0, Math.min(bindingSpinner.getSelectedItemPosition(), options.length - 1));
            int value = options[index].value;
            keyCodes.setText(appendKeyCodeText(keyCodes.getText() == null ? "" : keyCodes.getText().toString(), value));
            boundKeys.setText("Bound keys: " + TouchInputBinding.friendlyKeyCombo(
                    parseKeyCodes(keyCodes.getText() == null ? "" : keyCodes.getText().toString(), value)
            ));
        });

        clearSelectedBindings.setOnClickListener(v -> {
            keyCodes.setText("");
            boundKeys.setText("Bound keys: No keys bound");
        });

        addSectionHeader(layout, "Position", "X/Y use the same layout units as Width/Height.");
        EditText x = textField(context, "X position", String.valueOf(Math.round(initialLayoutX)), true);
        addFieldRow(layout, "X", x);
        SeekBar xSlider = addSlider(layout, parentWidthUnits, Math.round(initialLayoutX));
        EditText y = textField(context, "Y position", String.valueOf(Math.round(initialLayoutY)), true);
        addFieldRow(layout, "Y", y);
        SeekBar ySlider = addSlider(layout, parentHeightUnits, Math.round(initialLayoutY));

        addSectionHeader(layout, "Size", "Resize both dimensions together or tune width and height separately.");
        EditText width = textField(context, "Width", String.valueOf(Math.round(data.width)), true);
        addFieldRow(layout, "Width", width);
        SeekBar widthSlider = addSlider(layout, 400, Math.round(data.width));
        EditText height = textField(context, "Height", String.valueOf(Math.round(data.height)), true);
        addFieldRow(layout, "Height", height);
        SeekBar heightSlider = addSlider(layout, 400, Math.round(data.height));

        int initialPercent = Math.round(clamp(data.sizePercent, 30f, 250f));
        TextView sizeLabel = valueLabel(context, "Button size: " + initialPercent + "%");
        layout.addView(sizeLabel);
        SeekBar sizeSlider = addSlider(layout, 250, initialPercent);

        addSectionHeader(layout, "Appearance", "Adjust opacity, rounded corners, and stroke width.");
        EditText opacity = textField(context, "Opacity 0.15 - 1.0", String.valueOf(data.opacity), false);
        addFieldRow(layout, "Opacity", opacity);
        SeekBar opacitySlider = addSlider(layout, 100, Math.round(clamp(data.opacity, 0.15f, 1f) * 100f));

        EditText cornerRadius = textField(context, "Corner radius", String.valueOf(Math.round(data.cornerRadius)), true);
        addFieldRow(layout, "Corner radius", cornerRadius);
        SeekBar cornerSlider = addSlider(layout, 100, Math.round(data.cornerRadius));

        EditText strokeWidth = textField(context, "Stroke width", String.valueOf(Math.round(data.strokeWidth)), true);
        addFieldRow(layout, "Stroke", strokeWidth);
        SeekBar strokeSlider = addSlider(layout, 20, Math.round(data.strokeWidth));

        EditText strokeColor = textField(context, "#AARRGGBB or #RRGGBB", formatColor(data.strokeColor), false);
        addFieldRow(layout, "Border colour", strokeColor);

        CheckBox toggle = new CheckBox(context);
        toggle.setText("Toggle button");
        toggle.setTextColor(0xFFE0E0E0);
        toggle.setChecked(data.toggle);
        layout.addView(toggle);

        CheckBox visibleInGame = new CheckBox(context);
        visibleInGame.setText("Visible in game");
        visibleInGame.setTextColor(0xFFE0E0E0);
        visibleInGame.setChecked(data.visibleInGame);
        layout.addView(visibleInGame);

        CheckBox visibleInMenu = new CheckBox(context);
        visibleInMenu.setText("Visible in menu");
        visibleInMenu.setTextColor(0xFFE0E0E0);
        visibleInMenu.setChecked(data.visibleInMenu);
        layout.addView(visibleInMenu);

        CheckBox virtualMouse = new CheckBox(context);
        virtualMouse.setText("Show virtual cursor");
        virtualMouse.setTextColor(0xFFE0E0E0);
        virtualMouse.setChecked(ControlsPreferences.isVirtualMouseEnabled(context));
        virtualMouse.setOnCheckedChangeListener((buttonView, isChecked) -> ControlsPreferences.setVirtualMouseEnabled(context, isChecked));
        layout.addView(virtualMouse);

        Button copyButton = new Button(context);
        copyButton.setText("Copy button");
        copyButton.setAllCaps(false);
        layout.addView(copyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final boolean[] sliderChangingText = new boolean[]{false};
        final boolean[] textChangingSlider = new boolean[]{false};
        final boolean[] accepted = new boolean[]{false};
        final boolean[] deleted = new boolean[]{false};
        final AlertDialog[] dialogRef = new AlertDialog[1];
        final float[] currentPercent = new float[]{initialPercent};
        float baseWidth = Math.max(24f, data.width * 100f / Math.max(1f, initialPercent));
        float baseHeight = Math.max(24f, data.height * 100f / Math.max(1f, initialPercent));

        Runnable applyPreview = () -> {
            data.label = label.getText() == null ? data.label : label.getText().toString().trim();
            if (data.label.isEmpty()) data.label = "Button";
            data.x = parseFloat(x, data.x);
            data.y = parseFloat(y, data.y);
            data.width = Math.max(24f, parseFloat(width, data.width));
            data.height = Math.max(24f, parseFloat(height, data.height));
            data.opacity = Math.max(0.15f, Math.min(1f, parseFloat(opacity, data.opacity)));
            data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
            data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
            data.strokeColor = parseColorValue(strokeColor.getText() == null ? "" : strokeColor.getText().toString(), data.strokeColor);
            data.rawX = null;
            data.rawY = null;
            summary.setText("Editing: " + data.label);
            applyControlPreview(editingView, data);
        };

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (sliderChangingText[0]) return;
                applyPreview.run();
                textChangingSlider[0] = true;
                setSliderProgress(xSlider, Math.round(parseFloat(x, data.x)));
                setSliderProgress(ySlider, Math.round(parseFloat(y, data.y)));
                setSliderProgress(widthSlider, Math.round(parseFloat(width, data.width)));
                setSliderProgress(heightSlider, Math.round(parseFloat(height, data.height)));
                setSliderProgress(opacitySlider, Math.round(Math.max(0.15f, Math.min(1f, parseFloat(opacity, data.opacity))) * 100f));
                setSliderProgress(cornerSlider, Math.round(parseFloat(cornerRadius, data.cornerRadius)));
                setSliderProgress(strokeSlider, Math.round(parseFloat(strokeWidth, data.strokeWidth)));
                textChangingSlider[0] = false;
            }
        };
        label.addTextChangedListener(watcher);
        x.addTextChangedListener(watcher);
        y.addTextChangedListener(watcher);
        width.addTextChangedListener(watcher);
        height.addTextChangedListener(watcher);
        opacity.addTextChangedListener(watcher);
        cornerRadius.addTextChangedListener(watcher);
        strokeWidth.addTextChangedListener(watcher);
        strokeColor.addTextChangedListener(watcher);

        addPreviewSliderListener(xSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(x, xSlider); });
        addPreviewSliderListener(ySlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(y, ySlider); });
        addPreviewSliderListener(widthSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(width, widthSlider); });
        addPreviewSliderListener(heightSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(height, heightSlider); });
        addPreviewSliderListener(opacitySlider, dialogRef, () -> {
            if (!textChangingSlider[0]) {
                sliderChangingText[0] = true;
                opacity.setText(String.valueOf(Math.max(15, opacitySlider.getProgress()) / 100f));
                sliderChangingText[0] = false;
                applyPreview.run();
            }
        });
        addPreviewSliderListener(cornerSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(cornerRadius, cornerSlider); });
        addPreviewSliderListener(strokeSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(strokeWidth, strokeSlider); });

        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = Math.max(30, progress);
                currentPercent[0] = percent;
                data.sizePercent = percent;
                sizeLabel.setText("Button size: " + percent + "%");
                if (fromUser) {
                    sliderChangingText[0] = true;
                    width.setText(String.valueOf(Math.max(24, Math.round(baseWidth * percent / 100f))));
                    height.setText(String.valueOf(Math.max(24, Math.round(baseHeight * percent / 100f))));
                    sliderChangingText[0] = false;
                    applyPreview.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], false); }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Edit touch button")
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Delete", null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialogRef[0] = dialog;

        copyButton.setOnClickListener(v -> {
            TouchControlData copy = data.copy();
            copy.id = UUID.randomUUID().toString();
            copy.label = data.label + " Copy";
            copy.x = data.x + 24f;
            copy.y = data.y + 24f;
            layoutData.controls.add(copy);
            accepted[0] = true;
            saveLayout();
            rebuildWhenSized();
            Toast.makeText(context, "Copied " + data.label, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnShowListener(shown -> {
            styleDialogWindow(dialog);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                deleted[0] = true;
                layoutData.controls.remove(data);
                saveLayout();
                rebuildWhenSized();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String oldLabel = originalLabel;
                String newLabel = label.getText() == null ? oldLabel : label.getText().toString().trim();
                int actionPosition = Math.max(0, actionSpinner.getSelectedItemPosition());
                String action = actionValues[Math.min(actionPosition, actionValues.length - 1)];
                TouchInputBinding.Option[] options = currentOptions[0] != null ? currentOptions[0] : TouchInputBinding.optionsForAction(action);
                int bindingPosition = Math.max(0, bindingSpinner.getSelectedItemPosition());
                TouchInputBinding.Option option = options[Math.min(bindingPosition, options.length - 1)];
                TouchInputBinding.applyOption(data, action, option);

                if (TouchControlActions.KEY.equals(action)) {
                    int[] parsedKeys = parseKeyCodes(keyCodes.getText() == null ? "" : keyCodes.getText().toString(), option.value);
                    data.setKeyCodes(parsedKeys);
                }

                // Preserve exactly what the user typed in the Label field.
                // Changing the binding should never rename an existing/custom button.
                data.id = safeControlId(idField.getText() == null ? "" : idField.getText().toString());
                data.label = newLabel.trim().isEmpty() ? "Button" : newLabel.trim();
                data.x = parseFloat(x, data.x);
                data.y = parseFloat(y, data.y);
                data.width = Math.max(24f, parseFloat(width, data.width));
                data.height = Math.max(24f, parseFloat(height, data.height));
                data.sizePercent = clamp(currentPercent[0], 30f, 250f);
                data.opacity = Math.max(0.15f, Math.min(1f, parseFloat(opacity, data.opacity)));
                data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
                data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
                data.strokeColor = parseColorValue(strokeColor.getText() == null ? "" : strokeColor.getText().toString(), data.strokeColor);
                data.toggle = toggle.isChecked();
                data.visibleInGame = visibleInGame.isChecked();
                data.visibleInMenu = visibleInMenu.isChecked();
                data.rawX = null;
                data.rawY = null;
                accepted[0] = true;
                saveLayout();
                rebuildWhenSized();
                dialog.dismiss();
            });
        });

        dialog.setOnDismissListener(dismissed -> {
            setEditDialogPreviewAlpha(dialog, false);
            if (!accepted[0] && !deleted[0]) {
                data.id = originalId;
                data.label = originalLabel;
                data.action = originalAction;
                data.keyCode = originalKeyCode;
                data.keyCodes = originalKeyCodes;
                data.mouseButton = originalMouseButton;
                data.scrollY = originalScrollY;
                data.x = originalX;
                data.y = originalY;
                data.width = originalWidth;
                data.height = originalHeight;
                data.sizePercent = originalSizePercent;
                data.opacity = originalOpacity;
                data.cornerRadius = originalCornerRadius;
                data.strokeWidth = originalStrokeWidth;
                data.strokeColor = originalStrokeColor;
                data.backgroundColor = originalBackgroundColor;
                data.toggle = originalToggle;
                data.visibleInGame = originalVisibleInGame;
                data.visibleInMenu = originalVisibleInMenu;
                data.rawX = originalRawX;
                data.rawY = originalRawY;
                rebuildWhenSized();
            }
        });

        dialog.show();
    }

    @NonNull
    private LayoutMetrics layoutMetrics(float parentWidth, float parentHeight) {
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        float safeParentWidth = Math.max(1f, parentWidth);
        float safeParentHeight = Math.max(1f, parentHeight);

        if (layoutData.usesPixelCoordinates()) {
            float sourceWidth = layoutData.resolvedSourceWidth(safeParentWidth);
            float sourceHeight = layoutData.resolvedSourceHeight(safeParentHeight);
            float[] realDisplay = realDisplaySize(safeParentWidth, safeParentHeight);

            float parentScale = Math.min(
                    safeParentWidth / Math.max(1f, sourceWidth),
                    safeParentHeight / Math.max(1f, sourceHeight)
            );
            float realScale = Math.min(
                    realDisplay[0] / Math.max(1f, sourceWidth),
                    realDisplay[1] / Math.max(1f, sourceHeight)
            );

            float uniformScale = Math.max(parentScale, realScale);

            // Legacy default_touch.json files from a 1920x1080 device normally land
            // as an 854x480-ish logical canvas. Ultrawide devices can report a shorter
            // app surface even though the physical game buffer is still 1080p-class,
            // which made the overlay shrink vertically. Keep those layouts at least at
            // their original 1080p scale when the current display is 1080p-class.
            boolean legacy1080pCanvas = sourceWidth <= 960f
                    && sourceHeight <= 540f
                    && Math.max(safeParentWidth, realDisplay[0]) >= 1800f
                    && Math.max(safeParentHeight, realDisplay[1]) >= 900f;
            if (legacy1080pCanvas) {
                uniformScale = Math.max(uniformScale, 1080f / Math.max(1f, sourceHeight));
            }

            return new LayoutMetrics(
                    true,
                    safeParentWidth,
                    safeParentHeight,
                    sourceWidth,
                    sourceHeight,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    uniformScale
            );
        }

        return new LayoutMetrics(
                false,
                safeParentWidth,
                safeParentHeight,
                safeParentWidth / density,
                safeParentHeight / density,
                density,
                density,
                density,
                density
        );
    }

    @NonNull
    private float[] realDisplaySize(float parentWidth, float parentHeight) {
        float realWidth = Math.max(1f, parentWidth);
        float realHeight = Math.max(1f, parentHeight);

        try {
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect current = windowManager.getCurrentWindowMetrics().getBounds();
                    Rect maximum = windowManager.getMaximumWindowMetrics().getBounds();
                    realWidth = Math.max(realWidth, Math.max(current.width(), maximum.width()));
                    realHeight = Math.max(realHeight, Math.max(current.height(), maximum.height()));
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    //noinspection deprecation
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    realWidth = Math.max(realWidth, metrics.widthPixels);
                    realHeight = Math.max(realHeight, metrics.heightPixels);
                }
            }
        } catch (Throwable ignored) {
        }

        boolean landscape = parentWidth >= parentHeight;
        float longSide = Math.max(realWidth, realHeight);
        float shortSide = Math.min(realWidth, realHeight);
        return landscape ? new float[]{longSide, shortSide} : new float[]{shortSide, longSide};
    }

    private static final class LayoutMetrics {
        final boolean pixelCoordinates;
        final float parentWidth;
        final float parentHeight;
        final float sourceWidth;
        final float sourceHeight;
        final float xScale;
        final float yScale;
        final float sizeScale;
        final float formulaPixelScale;

        LayoutMetrics(
                boolean pixelCoordinates,
                float parentWidth,
                float parentHeight,
                float sourceWidth,
                float sourceHeight,
                float xScale,
                float yScale,
                float sizeScale,
                float formulaPixelScale
        ) {
            this.pixelCoordinates = pixelCoordinates;
            this.parentWidth = Math.max(1f, parentWidth);
            this.parentHeight = Math.max(1f, parentHeight);
            this.sourceWidth = Math.max(1f, sourceWidth);
            this.sourceHeight = Math.max(1f, sourceHeight);
            this.xScale = Math.max(0.1f, xScale);
            this.yScale = Math.max(0.1f, yScale);
            this.sizeScale = Math.max(0.1f, sizeScale);
            this.formulaPixelScale = Math.max(0.1f, formulaPixelScale);
        }

        int toScreenWidth(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        int toScreenHeight(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        float toScreenX(@NonNull TouchControlData control, float screenControlWidth) {
            if (!pixelCoordinates) return control.x * xScale;

            float sourceControlWidth = Math.max(1f, control.width);
            float sourceCenterX = control.x + (sourceControlWidth / 2f);
            float rightThreshold = sourceWidth * 0.58f;
            float leftThreshold = sourceWidth * 0.42f;

            if (sourceCenterX >= rightThreshold) {
                float sourceRightOffset = Math.max(0f, sourceWidth - control.x - sourceControlWidth);
                return parentWidth - (sourceRightOffset * sizeScale) - screenControlWidth;
            }

            if (sourceCenterX <= leftThreshold) {
                return control.x * sizeScale;
            }

            float sourceCenterOffset = sourceCenterX - (sourceWidth / 2f);
            return (parentWidth / 2f) + (sourceCenterOffset * sizeScale) - (screenControlWidth / 2f);
        }

        float toScreenY(@NonNull TouchControlData control, float screenControlHeight) {
            if (!pixelCoordinates) return control.y * yScale;
            return control.y * sizeScale;
        }

        float fromScreenX(@NonNull TouchControlData control, float screenX, float screenControlWidth) {
            if (!pixelCoordinates) return screenX / xScale;

            float sourceControlWidth = Math.max(1f, control.width);
            float sourceCenterX = control.x + (sourceControlWidth / 2f);
            float rightThreshold = sourceWidth * 0.58f;
            float leftThreshold = sourceWidth * 0.42f;

            if (sourceCenterX >= rightThreshold) {
                float screenRightOffset = Math.max(0f, parentWidth - screenX - screenControlWidth);
                return sourceWidth - (screenRightOffset / sizeScale) - sourceControlWidth;
            }

            if (sourceCenterX <= leftThreshold) {
                return screenX / sizeScale;
            }

            float screenCenter = screenX + (screenControlWidth / 2f);
            return (sourceWidth / 2f) + ((screenCenter - (parentWidth / 2f)) / sizeScale) - (sourceControlWidth / 2f);
        }

        float fromScreenY(float screenY) {
            return screenY / (pixelCoordinates ? sizeScale : yScale);
        }

        float maxLayoutXUnits() {
            return pixelCoordinates ? sourceWidth : parentWidth / xScale;
        }

        float maxLayoutYUnits() {
            return pixelCoordinates ? sourceHeight : parentHeight / yScale;
        }
    }

    private void addSectionHeader(@NonNull LinearLayout parent, @NonNull String title, @Nullable String subtitle) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextColor(Color.WHITE);
        header.setTextSize(16f);
        header.setPadding(0, dp(14f), 0, dp(2f));
        parent.addView(header);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView sub = new TextView(getContext());
            sub.setText(subtitle);
            sub.setTextColor(0xFFBDBDBD);
            sub.setTextSize(12f);
            sub.setPadding(0, 0, 0, dp(6f));
            parent.addView(sub);
        }
    }

    private void addFieldRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull EditText field) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4f), 0, dp(4f));
        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
        row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        parent.addView(row);
    }

    private void addSpinnerRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull Spinner spinner) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4f), 0, dp(4f));
        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
        row.addView(spinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        parent.addView(row);
    }

    @NonNull
    private TextView valueLabel(@NonNull Context context, @NonNull String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        label.setPadding(0, dp(4f), 0, dp(2f));
        return label;
    }

    @NonNull
    private SeekBar addSlider(@NonNull LinearLayout parent, int max, int value) {
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(Math.max(1, max));
        setSliderProgress(slider, value);
        parent.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return slider;
    }

    private void addPreviewSliderListener(@NonNull SeekBar slider, @NonNull AlertDialog[] dialogRef, @NonNull Runnable onUserChange) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) onUserChange.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], false); }
        });
    }

    private void setTextFromSlider(@NonNull EditText field, @NonNull SeekBar slider) {
        field.setText(String.valueOf(slider.getProgress()));
    }

    private void setSliderProgress(@NonNull SeekBar slider, int value) {
        int progress = Math.max(0, Math.min(slider.getMax(), value));
        if (slider.getProgress() != progress) slider.setProgress(progress);
    }

    private void setEditDialogPreviewAlpha(@Nullable AlertDialog dialog, boolean previewing) {
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setDimAmount(previewing ? 0.02f : 0.32f);
        dialog.getWindow().getDecorView().setAlpha(previewing ? 0.12f : 1.0f);
    }

    private void applyControlPreview(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int width = metrics.toScreenWidth(data.width);
        int height = metrics.toScreenHeight(data.height);
        width = Math.min(width, Math.max(1, getWidth()));
        height = Math.min(height, Math.max(1, getHeight()));

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }

        view.setX(clamp(metrics.toScreenX(data, width), 0f, Math.max(0f, getWidth() - width)));
        view.setY(clamp(metrics.toScreenY(data, height), 0f, Math.max(0f, getHeight() - height)));
        view.setText(data.label);
        view.setAlpha(Math.max(0.15f, Math.min(1f, data.opacity)) * ControlsPreferences.getGlobalOpacity(getContext()));
        view.refreshVisualState();
        view.requestLayout();
        view.invalidate();
    }

    private void styleDialogWindow(@NonNull AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(makeDialogBackground());
    }

    @NonNull
    private GradientDrawable makeDialogBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xEE202124);
        drawable.setCornerRadius(dp(22f));
        drawable.setStroke(Math.max(1, dp(1f)), 0x44FFFFFF);
        return drawable;
    }

    private int[] parseKeyCodes(@NonNull String text, int fallback) {
        String[] parts = text.split("[,\\s]+");
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            try { values.add(Integer.parseInt(part.trim())); } catch (Throwable ignored) { }
        }
        if (values.isEmpty()) return new int[]{fallback};
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    @NonNull
    private String joinKeyCodes(@NonNull int[] codes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(codes[i]);
        }
        return builder.toString();
    }

    @NonNull
    private String appendKeyCodeText(@NonNull String current, int value) {
        String trimmed = current.trim();
        if (trimmed.isEmpty()) return String.valueOf(value);
        for (String part : trimmed.split("[,\\s]+")) {
            try { if (Integer.parseInt(part.trim()) == value) return trimmed; } catch (Throwable ignored) { }
        }
        return trimmed + ", " + value;
    }

    @NonNull
    private String safeControlId(@NonNull String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? UUID.randomUUID().toString() : trimmed;
    }

    @NonNull
    private static String formatColor(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }

    private static int parseColorValue(@NonNull String value, int fallback) {
        String text = value.trim();
        if (text.isEmpty()) return fallback;
        try {
            if (!text.startsWith("#")) text = "#" + text;
            if (text.length() == 7) {
                // #RRGGBB => force fully opaque border.
                return (int) (0xFF000000L | Long.parseLong(text.substring(1), 16));
            }
            if (text.length() == 9) {
                return (int) Long.parseLong(text.substring(1), 16);
            }
            return Color.parseColor(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static TextView labelView(@NonNull Context context, @NonNull String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13f);
        view.setPadding(0, 12, 0, 0);
        return view;
    }

    @NonNull
    private static EditText textField(@NonNull Context context, @NonNull String hint, @NonNull String value, boolean number) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setText(value);
        if (number) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        return field;
    }

    private static float parseFloat(@NonNull EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText() == null ? "" : field.getText().toString().trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean routePointerDown(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;

        int pointerId = event.getPointerId(pointerIndex);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        boolean grabbed = isMouseGrabbed();

        if (grabbed) {
            // Virtual cursor visibility must never change touch routing.
            // In a grabbed world, the real Minecraft hotbar still needs to win
            // so normal touch-only users can select slots whether the cursor is
            // shown or hidden.
            int hotbarSlot = hotbarSlotForTouch(x, y);
            if (hotbarSlot >= 0) {
                startHotbarPointer(pointerId, hotbarSlot);
                return true;
            }
        }

        TouchControlButtonView control = controlsVisible ? findControlUnder(x, y) : null;
        if (control != null) {
            controlPointerTargets.put(pointerId, control);
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, control);
            return true;
        }

        // In-game look/attack: track one empty-space pointer by ID and send
        // relative deltas. Do not switch this into absolute mouse mode just
        // because the virtual cursor is visible.
        if (grabbed) {
            if (cameraPointerId == NO_POINTER_ID) {
                startCameraPointer(event, pointerIndex, pointerId);
                return true;
            }
            return hasActiveTouchRoute();
        }

        // Menus/inventory are not grabbed, so empty screen touches pass through
        // to Minecraft as an absolute GUI click. If the passthrough target was
        // not wired for any reason, return false so Android can continue hit
        // testing lower siblings instead of the overlay eating the touch.
        if (passthroughPointerId == NO_POINTER_ID && passthroughTarget != null) {
            passthroughPointerId = pointerId;
            passthroughDownTime = event.getEventTime();
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_DOWN);
            return true;
        }

        return false;
    }

    private void routePointerUp(@NonNull MotionEvent event, int pointerIndex, boolean finalUp) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            if (finalUp) clearRuntimeTouchRouting();
            return;
        }

        int pointerId = event.getPointerId(pointerIndex);

        if (pointerId == cameraPointerId) {
            finishCameraPointer(event, pointerIndex, false);
        }

        if (pointerId == hotbarPointerId) {
            finishHotbarPointer();
        }

        if (pointerId == passthroughPointerId) {
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_UP);
            passthroughPointerId = NO_POINTER_ID;
            passthroughDownTime = 0L;
        }

        TouchControlButtonView control = controlPointerTargets.get(pointerId);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, control);
            controlPointerTargets.remove(pointerId);
        }

        if (finalUp) {
            clearRuntimeTouchRouting();
        }
    }

    private void dispatchCancelToControlPointers(@NonNull MotionEvent event) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_CANCEL, control);
            }
        }
    }

    private void dispatchActiveControlPointers(@NonNull MotionEvent event, int action) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, action, control);
            }
        }
    }

    private void startCameraPointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        cameraPointerId = pointerId;
        cameraDownX = event.getX(pointerIndex);
        cameraDownY = event.getY(pointerIndex);
        cameraLastX = cameraDownX;
        cameraLastY = cameraDownY;
        cameraMovedPastSlop = false;
        cameraLongPressAttackActive = false;
        scheduleCameraLongPressAttack();
    }

    private void dispatchActiveCameraPointer(@NonNull MotionEvent event) {
        if (cameraPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(cameraPointerId);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - cameraLastX;
        float dy = y - cameraLastY;
        cameraLastX = x;
        cameraLastY = y;

        float totalDx = x - cameraDownX;
        float totalDy = y - cameraDownY;
        if (!cameraMovedPastSlop && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            cameraMovedPastSlop = true;
            cancelCameraLongPressAttack(false);
        }

        if (dx == 0f && dy == 0f) return;
        sendRelativeCameraDelta(dx, dy);
    }

    private void finishCameraPointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount() && !cancelled) {
            dispatchActiveCameraPointer(event);
        }

        cancelCameraLongPressAttack(cancelled);

        if (!cancelled && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        } else if (!cancelled && !cameraMovedPastSlop) {
            // Quick tap on the look area acts like the attack button.
            sendLeftMouse(true);
            sendLeftMouse(false);
        }

        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraDownX = cameraDownY = cameraLastX = cameraLastY = 0f;
        cameraMovedPastSlop = false;
    }

    private void cancelCameraPointer(boolean sendRelease) {
        if (sendRelease && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        }
        cancelCameraLongPressAttack(true);
        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraMovedPastSlop = false;
    }

    private void scheduleCameraLongPressAttack() {
        cancelCameraLongPressAttack(false);
        cameraLongPressRunnable = () -> {
            if (cameraPointerId == NO_POINTER_ID || cameraMovedPastSlop || cameraLongPressAttackActive) return;
            cameraLongPressAttackActive = true;
            sendLeftMouse(true);
        };
        gestureHandler.postDelayed(cameraLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelCameraLongPressAttack(boolean cancelActivePress) {
        if (cameraLongPressRunnable != null) {
            gestureHandler.removeCallbacks(cameraLongPressRunnable);
            cameraLongPressRunnable = null;
        }
        if (cancelActivePress && cameraLongPressAttackActive) {
            sendLeftMouse(false);
            cameraLongPressAttackActive = false;
        }
    }

    private void startHotbarPointer(int pointerId, int slot) {
        hotbarPointerId = pointerId;
        hotbarLastSlot = slot;
        sendKeyTap(49 + slot); // GLFW_KEY_1 through GLFW_KEY_9
    }

    private void dispatchActiveHotbarPointer(@NonNull MotionEvent event) {
        if (hotbarPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(hotbarPointerId);
        if (pointerIndex < 0) return;

        int slot = hotbarSlotForTouch(event.getX(pointerIndex), event.getY(pointerIndex));
        if (slot < 0 || slot == hotbarLastSlot) return;

        hotbarLastSlot = slot;
        sendKeyTap(49 + slot);
    }

    private void finishHotbarPointer() {
        hotbarPointerId = NO_POINTER_ID;
        hotbarLastSlot = -1;
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

    private void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send hotbar key tap", throwable);
        }
    }

    private void sendAbsoluteCursor(float x, float y) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = clamp(x, 0f, Math.max(1, CallbackBridge.windowWidth));
            CallbackBridge.mouseY = clamp(y, 0f, Math.max(1, CallbackBridge.windowHeight));
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send virtual mouse cursor", throwable);
        }
    }

    private void sendRelativeCameraDelta(float dx, float dy) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX += dx;
            CallbackBridge.mouseY += dy;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send camera touch delta", throwable);
        }
    }

    private void sendLeftMouse(boolean down) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendMouseButton(MOUSE_BUTTON_LEFT, down);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send touch attack", throwable);
        }
    }

    private static boolean isMouseGrabbed() {
        try {
            return CallbackBridge.isGrabbing();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void applyControlsVisualState() {
        boolean childVisible = editMode || controlsVisible;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                child.setVisibility(childVisible ? VISIBLE : INVISIBLE);
            }
        }
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceTarget() {
        View current = passthroughTarget;
        while (current != null) {
            if (current instanceof MinecraftGLSurface) {
                return (MinecraftGLSurface) current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        View root = getRootView();
        MinecraftGLSurface found = findMinecraftSurfaceInTree(root);
        if (found != null) return found;

        ViewParent parent = getParent();
        return parent instanceof View ? findMinecraftSurfaceInTree((View) parent) : null;
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceInTree(@Nullable View view) {
        if (view == null || view == this) return null;
        if (view instanceof MinecraftGLSurface) return (MinecraftGLSurface) view;
        if (!(view instanceof ViewGroup)) return null;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            MinecraftGLSurface found = findMinecraftSurfaceInTree(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean dispatchActivePassthroughPointer(@NonNull MotionEvent event, int action) {
        if (passthroughPointerId == NO_POINTER_ID) {
            return false;
        }

        int pointerIndex = event.findPointerIndex(passthroughPointerId);
        if (pointerIndex < 0) {
            return false;
        }

        return dispatchSinglePointerToPassthrough(event, pointerIndex, action);
    }

    private boolean dispatchSinglePointerToPassthrough(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) {
            return false;
        }

        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        long downTime = passthroughDownTime > 0L ? passthroughDownTime : source.getDownTime();
        MotionEvent single = MotionEvent.obtain(
                downTime,
                source.getEventTime(),
                action,
                source.getX(pointerIndex),
                source.getY(pointerIndex),
                source.getMetaState()
        );
        int sourceClass = source.getSource();
        single.setSource(sourceClass != 0 ? sourceClass : InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (minecraftSurface != null) {
                return minecraftSurface.handleTouchFromOverlay(single);
            }
            return passthroughTarget != null && passthroughTarget.dispatchTouchEvent(single);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch passthrough touch event", throwable);
            return false;
        } finally {
            single.recycle();
        }
    }

    private boolean dispatchWholeTouchEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            if (copy.getSource() == 0) copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            return minecraftSurface != null
                    ? minecraftSurface.handleTouchFromOverlay(copy)
                    : passthroughTarget != null && passthroughTarget.dispatchTouchEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch whole passthrough touch event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    private boolean dispatchWholeGenericEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            return minecraftSurface != null
                    ? minecraftSurface.dispatchGenericMotionEvent(copy)
                    : passthroughTarget != null && passthroughTarget.dispatchGenericMotionEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch hardware pointer passthrough event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    private static boolean isHardwarePointerEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true;
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }

    private void dispatchSinglePointerToControl(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action,
            @NonNull TouchControlButtonView control
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) return;

        float localX = source.getX(pointerIndex) - control.getX();
        float localY = source.getY(pointerIndex) - control.getY();
        MotionEvent single = MotionEvent.obtain(
                source.getDownTime(),
                source.getEventTime(),
                action,
                localX,
                localY,
                source.getMetaState()
        );
        try {
            control.dispatchTouchEvent(single);
        } finally {
            single.recycle();
        }
    }

    @Nullable
    private TouchControlButtonView findControlUnder(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            if (child.getVisibility() != VISIBLE) continue;
            if (x >= child.getX()
                    && x <= child.getX() + child.getWidth()
                    && y >= child.getY()
                    && y <= child.getY() + child.getHeight()) {
                return (TouchControlButtonView) child;
            }
        }
        return null;
    }

    private void clearRuntimeTouchRouting() {
        cancelCameraPointer(true);
        finishHotbarPointer();
        passthroughPointerId = NO_POINTER_ID;
        passthroughDownTime = 0L;
        controlPointerTargets.clear();
    }


    private boolean hasActiveTouchRoute() {
        return cameraPointerId != NO_POINTER_ID
                || hotbarPointerId != NO_POINTER_ID
                || passthroughPointerId != NO_POINTER_ID
                || controlPointerTargets.size() > 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        clearRuntimeTouchRouting();
        super.onDetachedFromWindow();
    }
}
