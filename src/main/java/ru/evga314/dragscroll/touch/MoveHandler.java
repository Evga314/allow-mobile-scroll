package ru.evga314.dragscroll.touch;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.access.MouseHandlerAccess;
import ru.evga314.dragscroll.compat.ChatCompat;
import ru.evga314.dragscroll.compat.ClothCompat;
import ru.evga314.dragscroll.compat.MalilibCompat;
import ru.evga314.dragscroll.compat.ShulkerCompat;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.compat.XaeroMapZoomOverlay;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.wheel.MouseWheelEmulator;

/**
 * Cursor movement (MouseHandler.onMove, before MouseHandler stores it).
 *
 * <p>Controls the mod drives itself (wheel, MaLiLib, Xaero slider, TRender
 * bar) are moved here with the live finger position. For the generic drag
 * this decides the moment a touch turns from a tap into a drag; the scroll
 * itself is applied once per frame by {@link FrameHandler}.
 */
public final class MoveHandler {
    private MoveHandler() {
    }

    /** Horizontal travel that commits a slider press to the slider. */
    private static final double SLIDER_LOCK_DOMINANCE = 0.75;
    /** Vertical travel (times the drag threshold) that hands a slider touch to scrolling. */
    private static final double SLIDER_HANDOFF_FACTOR = 2.5;
    /** Movement must be this vertical to scroll or to leave a slider: |dy| > |dx| * 1.15. */
    private static final double SLIDER_AXIS_DOMINANCE = 1.15;

    /** Last cursor position, to tell a finger move from the launcher's teleport. */
    private static double prevX = Double.NaN;
    private static double prevY = Double.NaN;

    /** Returns true when vanilla must not see this movement. */
    public static boolean onMove(MouseHandlerAccess mouse, double xpos, double ypos) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null || mc.getOverlay() != null) {
            return false;
        }
        Window window = mc.getWindow();
        double x = MouseHandler.getScaledXPos(window, xpos);
        double y = MouseHandler.getScaledYPos(window, ypos);
        ScreenKind kind = ScreenKind.of(screen);

        if (!MouseWheelEmulator.isGrabbing()) {
            trackTeleport(screen, x, y);
        }
        if (MouseWheelEmulator.isGrabbing() && TouchState.leftButtonHeld) {
            MouseWheelEmulator.onDrag(screen, x, y);
            mouse.dragscroll$clearAccumulatedMovement();
            return true;
        }
        if (MalilibCompat.isMalilibScreen(screen)) {
            MalilibCompat.onMove(screen, x, y);
            return false;
        }
        if (kind.malilibFamily) {
            return false;
        }
        if (XaeroMapZoomOverlay.isDragging()) {
            XaeroMapZoomOverlay.applyDrag(screen, y);
            mouse.dragscroll$clearAccumulatedMovement();
            return true;
        }
        if (kind.trender && TrenderCompat.barLocked) {
            if (!TrenderCompat.applyThumb(screen, y)) {
                double barX = TrenderCompat.barLockX != 0.0 ? TrenderCompat.barLockX : x;
                TrenderCompat.barPointer(screen, barX, y, TrenderCompat.Pointer.DRAG);
            }
            mouse.dragscroll$clearAccumulatedMovement();
            TouchState.moved = true;
            TrenderCompat.barLastMoveNs = System.nanoTime();
            if (Debug.on()) Debug.log("MoveHandler.onMove", "TRENDER_THUMB y=" + y);
            return true;
        }
        if (Widgets.isVanillaInputOnly(screen)) {
            return false;
        }
        // A native control owns the whole touch; only a slider may still hand
        // it over to scrolling.
        if (TouchState.nativeControlHeld) {
            if (!TouchState.nativeSliderHeld || TouchState.sliderGestureLocked || !sliderToScroll(mouse, x, y)) {
                return false;
            }
        }
        if (engageDeferredSlider(mouse, screen, x, y)) {
            return false;
        }
        detectDragStart(mouse, screen, kind, x, y);
        return false;
    }

    /**
     * Remembers the cursor before each teleport. The launcher moves the cursor
     * to every new finger (onto the wheel, for example); wheel notches must go
     * where the cursor was before that jump.
     */
    private static void trackTeleport(Screen screen, double x, double y) {
        if (!Double.isNaN(prevX) && !Double.isNaN(prevY)) {
            double jump = Math.abs(x - prevX) + Math.abs(y - prevY);
            double teleportThreshold = Math.max(64.0, screen.width / 8.0);
            // A point on the wheel itself is never remembered: the jitter
            // there would replace the real list position below it.
            boolean onWheel = MouseWheelEmulator.isOverWheel(screen, x, y);
            if (jump < teleportThreshold && !onWheel) {
                TouchState.rememberCursorBeforeTeleport(x, y);
            } else if (Debug.on()) {
                Debug.log("MoveHandler.onMove", (onWheel ? "SKIP_ON_WHEEL" : "TELEPORT_DETECTED") + " jump=" + jump
                        + " to=" + x + "," + y);
            }
        }
        prevX = x;
        prevY = y;
    }

    /**
     * A slider owns the touch but the finger moves vertically. A clear
     * horizontal move locks the touch to the slider; a strongly vertical one
     * over a list that can scroll hands it to the list. Returns true when the
     * touch became a scroll drag.
     */
    private static boolean sliderToScroll(MouseHandlerAccess mouse, double x, double y) {
        if (!TouchState.hasPressPoint()) {
            return false;
        }
        double fromX = x - TouchState.pressX;
        double fromY = y - TouchState.pressY;
        double threshold = DragScrollConfig.getDragThreshold();
        if (Math.abs(fromX) >= threshold && Math.abs(fromX) > Math.abs(fromY) * SLIDER_LOCK_DOMINANCE) {
            TouchState.sliderGestureLocked = true;
            if (Debug.on()) Debug.log("MoveHandler.onMove", "SLIDER_GESTURE_LOCKED dx=" + fromX + " dy=" + fromY);
            return false;
        }
        if (Math.abs(fromY) < threshold * SLIDER_HANDOFF_FACTOR
                || Math.abs(fromY) <= Math.abs(fromX) * SLIDER_AXIS_DOMINANCE) {
            return false;
        }
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || Minecraft.getInstance().getOverlay() != null) {
            return false;
        }
        AbstractScrollArea area = Widgets.findScrollArea(screen, x, y);
        boolean areaScrolls = area != null && Widgets.canScroll(area);
        boolean customScroll = ScreenKind.of(screen).customScroll;
        if (!areaScrolls && !customScroll) {
            return false;
        }
        TouchState.nativeControlHeld = false;
        TouchState.nativeSliderHeld = false;
        TouchState.sliderGestureLocked = false;
        TouchState.active = false;
        mouse.dragscroll$clearAccumulatedMovement();
        if (areaScrolls) {
            Drags.beginList(area);
        } else {
            Drags.beginScreen(screen);
        }
        startDragFromPress();
        if (Debug.on()) Debug.log("MoveHandler.onMove", "SLIDER_TO_SCROLL_HANDOFF dx=" + fromX + " dy=" + fromY);
        return true;
    }

    /**
     * The held-back press landed on a slider in a scrollable list, and the
     * finger moves clearly sideways: the user wants the slider. The press is
     * replayed so the slider starts its own drag, and the touch stays on it.
     * The threshold is stricter than the drag threshold so vertical swipes
     * starting on a slider do not click it. This also works while a scroll
     * session is active, or the first scroll would block every slider.
     */
    private static boolean engageDeferredSlider(MouseHandlerAccess mouse, Screen screen, double x, double y) {
        if (!DeferredClick.isPending() || !TouchState.leftButtonHeld || !TouchState.hasPressPoint()) {
            return false;
        }
        double dx = x - TouchState.pressX;
        double dy = y - TouchState.pressY;
        double horizThreshold = Math.max(DragScrollConfig.getDragThreshold() * 1.75, 7.0);
        if (Math.abs(dx) < horizThreshold || Math.abs(dx) <= Math.abs(dy) * SLIDER_AXIS_DOMINANCE
                || !Widgets.isSliderTarget(screen, TouchState.pressX, TouchState.pressY)) {
            return false;
        }
        DeferredClick.finish();
        TouchState.endSession();
        TouchState.moved = false;
        TouchState.lockedScroll = 0.0;
        TouchState.nativeControlHeld = true;
        TouchState.nativeSliderHeld = true;
        TouchState.nativeScrollbarHeld = false;
        // Committed for the rest of the touch: vertical noise must not cancel it.
        TouchState.sliderGestureLocked = true;
        TouchState.currentTouchDragged = false;
        mouse.dragscroll$clearAccumulatedMovement();
        if (Debug.on()) Debug.log("MoveHandler.onMove", "DEFERRED_SLIDER_HORIZONTAL_ENGAGE dx=" + dx + " dy=" + dy);
        return true;
    }

    /**
     * The first movement that makes a waiting touch a drag. Runs before
     * vanilla handles this move, so the list is restored to its pre-click
     * position right away, not one frame later.
     */
    private static void detectDragStart(MouseHandlerAccess mouse, Screen screen, ScreenKind kind, double x, double y) {
        boolean held = TouchState.leftButtonHeld || ClothCompat.isSyntheticTouch(screen);
        if (!held || TouchState.active) {
            return;
        }
        // A move exactly at the press point is the launcher's teleport. When it
        // arrives after the press it is already in the accumulated movement.
        if (TouchState.hasPressPoint()
                && Math.abs(x - TouchState.pressX) < 0.01 && Math.abs(y - TouchState.pressY) < 0.01) {
            mouse.dragscroll$clearAccumulatedMovement();
            return;
        }
        if (!isVerticalDrag(screen, x, y)) {
            return;
        }
        double px = TouchState.pressX;
        double py = TouchState.pressY;
        AbstractScrollArea area = Widgets.findScrollArea(screen, x, y);
        if (area != null) {
            Drags.beginList(area);
            startDragFromPress();
        } else if (kind.cloth && ClothCompat.isBarBand(screen, px, py)) {
            takeCustomThumb();
            ClothCompat.applyThumb(screen, y);
            if (Debug.on()) Debug.log("MoveHandler.onMove", "CLOTH_THUMB_NATIVE");
        } else if (kind.shulkerConfig && ShulkerCompat.isScrollbarHover(screen, px, py)) {
            takeCustomThumb();
            ShulkerCompat.applyThumb(screen, y);
            if (Debug.on()) Debug.log("MoveHandler.onMove", "SHULKER_THUMB_NATIVE");
        } else if (kind.sodium && SodiumCompat.isScrollbarHover(screen, px, py)) {
            takeCustomThumb();
            Minecraft mc = Minecraft.getInstance();
            SodiumCompat.applyThumb(screen, mc.mouseHandler.getScaledXPos(mc.getWindow()), y);
            if (Debug.on()) Debug.log("MoveHandler.onMove", "SODIUM_THUMB_NATIVE");
        } else if (kind.customScroll && !Widgets.hasMultipleScrollLists(screen)
                && !ChatCompat.isOutsideChatArea(screen, px, py)) {
            if (kind.columnLock) {
                SodiumCompat.lockContent(px, py);
            }
            startDragFromPress();
        }
    }

    /**
     * The finger went past the drag threshold, mostly vertically. Tiny touch
     * jitter must not cancel a tap. A press on a slider needs a clearer
     * vertical move, or a slider adjustment with some vertical noise would
     * scroll the row away under the finger.
     */
    static boolean isVerticalDrag(Screen screen, double x, double y) {
        if (!TouchState.hasPressPoint()) {
            return false;
        }
        double fromX = x - TouchState.pressX;
        double fromY = y - TouchState.pressY;
        double threshold = DragScrollConfig.getDragThreshold();
        if (Math.abs(fromY) < threshold || Math.abs(fromY) < Math.abs(fromX) * 0.35) {
            return false;
        }
        if (DeferredClick.isPending() && Widgets.isSliderTarget(screen, TouchState.pressX, TouchState.pressY)) {
            double vertThreshold = Math.max(threshold * 1.5, 6.0);
            return Math.abs(fromY) >= vertThreshold && Math.abs(fromY) > Math.abs(fromX) * SLIDER_AXIS_DOMINANCE;
        }
        return true;
    }

    /**
     * The touch is a drag now. The press point becomes the origin of the
     * frame delta, so this first movement is not lost.
     */
    private static void startDragFromPress() {
        TouchState.currentTouchDragged = true;
        DeferredClick.cancel();
        TouchState.lastX = TouchState.pressX;
        TouchState.lastY = TouchState.pressY;
        TouchState.wasHeld = true;
    }

    /** Cloth / Shulker / Sodium thumb found only on the first move. */
    private static void takeCustomThumb() {
        TouchState.nativeControlHeld = true;
        TouchState.nativeScrollbarHeld = true;
        TouchState.active = false;
        TouchState.currentTouchDragged = false;
        DeferredClick.cancel();
    }
}
