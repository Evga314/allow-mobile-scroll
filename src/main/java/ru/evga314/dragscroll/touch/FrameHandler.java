package ru.evga314.dragscroll.touch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.access.MouseHandlerAccess;
import ru.evga314.dragscroll.compat.ChatCompat;
import ru.evga314.dragscroll.compat.ClothCompat;
import ru.evga314.dragscroll.compat.FancyMenuCompat;
import ru.evga314.dragscroll.compat.MalilibCompat;
import ru.evga314.dragscroll.compat.ShulkerCompat;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;

/**
 * Once per frame (MouseHandler.handleAccumulatedMovement): applies the
 * finger's movement since the last frame to the list being dragged, drives
 * the scroll bars the mod owns, advances inertia, and ends the touch after
 * the finger lifted.
 */
public final class FrameHandler {
    private FrameHandler() {
    }

    /** A sideways step smaller than this (px) is noise, not a scroll. */
    private static final double SIDEWAYS_NOISE_PX = 4.0;

    private static double guiX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledXPos(mc.getWindow());
    }

    private static double guiY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledYPos(mc.getWindow());
    }

    /** Before vanilla handles the frame's accumulated movement. */
    public static void beforeMovement(MouseHandlerAccess mouse) {
        TouchState.moved = false;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        boolean noOverlay = mc.gui.overlay() == null;
        ScreenKind kind = ScreenKind.of(screen);

        if (MalilibCompat.isMalilibScreen(screen)) {
            if (screen != TouchState.lastScreen) {
                // Coming from another screen: drop its gesture.
                TouchState.lastScreen = screen;
                DeferredClick.cancel();
                TouchState.endSession();
                ScrollMemory.clearPressSnapshots();
            }
            if (noOverlay) {
                MalilibCompat.onFrame(screen, guiX(), guiY());
            }
            return;
        }
        if (kind.malilibFamily) {
            return;
        }
        FancyMenuCompat.tickCoast();
        if (FancyMenuCompat.isArmed()) {
            if (noOverlay) {
                FancyMenuCompat.onFrame(guiX(), guiY());
            }
            return;
        }
        if (Widgets.isVanillaInputOnly(screen)) {
            DeferredClick.cancel();
            TouchState.endSession();
            TouchState.currentTouchDragged = false;
            TouchState.nativeControlHeld = false;
            TouchState.nativeSliderHeld = false;
            TouchState.nativeScrollbarHeld = false;
            TouchState.sliderGestureLocked = false;
            ScrollMemory.clearPressSnapshots();
            return;
        }
        if (!TouchState.leftButtonHeld) {
            Inertia.applyFrame(screen);
        }
        if (driveOwnedScrollbar(mouse, screen, kind)) {
            return;
        }
        // A native slider or scroll bar moves by vanilla's own drag.
        if (TouchState.nativeControlHeld) {
            return;
        }
        if (!noOverlay) {
            resetGesture();
            TouchState.wasHeld = false;
            TouchState.lastScreen = null;
            return;
        }
        boolean held = TouchState.leftButtonHeld;
        // Never carry a gesture or a press snapshot into another screen.
        if (screen != TouchState.lastScreen) {
            TouchState.lastScreen = screen;
            resetGesture();
            TouchState.wasHeld = held;
            return;
        }
        if (!held && !ClothCompat.isSyntheticTouch(screen)) {
            endTouch(screen);
            return;
        }
        applyDrag(screen);
    }

    /**
     * Scroll bars the mod drives itself follow the finger while it is down.
     * After the lift the cursor stays where the next touch will teleport it,
     * and a frame between that teleport and the press used to jump the list.
     * Returns true when this frame belonged to such a bar.
     */
    private static boolean driveOwnedScrollbar(MouseHandlerAccess mouse, Screen screen, ScreenKind kind) {
        if (TouchState.nativeScrollbarHeld && TouchState.leftButtonHeld && (kind.cloth || kind.shulkerConfig || kind.sodium)) {
            if (kind.cloth) {
                ClothCompat.applyThumb(screen, guiY());
            } else if (kind.shulkerConfig) {
                ShulkerCompat.applyThumb(screen, guiY());
            } else {
                SodiumCompat.applyThumb(screen, guiX(), guiY());
            }
            mouse.dragscroll$clearAccumulatedMovement();
            TouchState.moved = true;
            return true;
        }
        if (kind.trender && TrenderCompat.barLocked) {
            if (!TouchState.leftButtonHeld && TrenderCompat.isIdleAfterRelease(System.nanoTime())) {
                TrenderCompat.releaseBar();
                TouchState.nativeScrollbarHeld = false;
                TouchState.nativeControlHeld = false;
                if (Debug.on()) Debug.log("FrameHandler", "TRENDER_BAR_IDLE_CLEAR");
                return false;
            }
            // The thumb is moved in onMove with the live finger Y. The cursor Y
            // here is frozen at the touch point on launchers; re-applying it
            // every frame dragged the thumb back to the press point.
            TouchState.active = false;
            mouse.dragscroll$clearAccumulatedMovement();
            TouchState.moved = true;
            return true;
        }
        return false;
    }

    /** Drops the gesture and every per-touch reference. */
    private static void resetGesture() {
        DeferredClick.cancel();
        TouchState.clearTracking();
        TouchState.clearPressPoint();
        TouchState.clear();
        Inertia.stop();
        ScrollMemory.clearPressSnapshots();
        TrenderCompat.reset();
        SodiumCompat.reset();
        ClothCompat.THUMB.reset();
        ClothCompat.disarmTouch();
    }

    /**
     * The finger lifted. A tap gets its held-back click; a drag session is
     * kept for the next touch (the launcher makes a new press for every
     * finger contact).
     */
    private static void endTouch(Screen screen) {
        if (Inertia.active) {
            // The coast owns lockedScroll; the widget is not re-read now.
            DeferredClick.finish();
            TouchState.clearTracking();
            TouchState.currentTouchDragged = false;
            ScrollMemory.clearPressSnapshots();
            TouchState.clearPressPoint();
            TouchState.wasHeld = false;
            return;
        }
        DeferredClick.finish();
        AbstractScrollArea locked = TouchState.lockedArea;
        if (TouchState.active && locked != null) {
            try {
                if (TouchState.moved) {
                    locked.setScrollAmount(TouchState.lockedScroll);
                }
                ScrollMemory.remember(locked, locked.scrollAmount());
            } catch (Throwable ignored) {
            }
        }
        // A plain tap may have no session; remembering every list still gives
        // the next touch a clean baseline.
        ScrollMemory.rememberAll(screen);
        TouchState.clearTracking();
        if (TouchState.active && (locked != null || TouchState.fallbackScreen == screen)) {
            if (locked != null) {
                try {
                    TouchState.lockedScroll = locked.scrollAmount();
                } catch (Throwable ignored) {
                }
            }
        } else {
            TouchState.active = false;
            TouchState.lockedArea = null;
            TouchState.lockedScroll = 0.0;
        }
        TouchState.moved = false;
        TouchState.currentTouchDragged = false;
        ScrollMemory.clearPressSnapshots();
        TouchState.clearPressPoint();
        TouchState.wasHeld = false;
    }

    /** The finger is down: scroll by its movement since the last frame. */
    private static void applyDrag(Screen screen) {
        double x = guiX();
        double y = guiY();
        boolean fingerDown = !TouchState.wasHeld;
        TouchState.wasHeld = true;
        // The launcher has just teleported the cursor to this touch: a new
        // origin, never a step.
        if (fingerDown || Double.isNaN(TouchState.lastX)) {
            TouchState.lastX = x;
            TouchState.lastY = y;
            return;
        }
        double dx = x - TouchState.lastX;
        double dy = y - TouchState.lastY;
        TouchState.lastX = x;
        TouchState.lastY = y;
        // Below the drag threshold the touch is still a tap (touch jitter).
        if (!MoveHandler.isVerticalDrag(screen, x, y)) {
            return;
        }

        ScreenKind kind = ScreenKind.of(screen);
        AbstractScrollArea area;
        if (TouchState.active && TouchState.lockedArea != null) {
            // The session continues across the launcher's releases.
            area = TouchState.lockedArea;
            TouchState.currentTouchDragged = true;
            DeferredClick.cancel();
            if (Inertia.active) {
                Inertia.catchForDrag();
            }
        } else if (TouchState.active && TouchState.fallbackScreen == screen) {
            area = null;
            TouchState.currentTouchDragged = true;
            DeferredClick.cancel();
            if (kind.sodium) {
                SodiumCompat.lockContent(TouchState.pressX, TouchState.pressY);
            }
        } else {
            area = Widgets.findScrollArea(screen, x, y);
            if (area != null) {
                Drags.beginList(area);
                TouchState.currentTouchDragged = true;
            } else if (kind.customScroll && !Widgets.hasMultipleScrollLists(screen)
                    && !ChatCompat.isOutsideChatArea(screen, TouchState.pressX, TouchState.pressY)) {
                // Only screen-level scrollers; a screen with several lists
                // (Mod Menu) must never be scrolled as a whole.
                if (kind.sodium) {
                    SodiumCompat.lockContent(TouchState.pressX, TouchState.pressY);
                }
                Drags.beginScreen(screen);
                TouchState.currentTouchDragged = true;
                DeferredClick.cancel();
            } else {
                return;
            }
        }

        // Sideways noise is dropped; only the vertical step scrolls.
        double step = Math.abs(dx) > Math.abs(dy) * 1.25 && Math.abs(dy) < SIDEWAYS_NOISE_PX ? 0.0 : dy;
        Inertia.pushSample(step);
        boolean sodiumLocked = SodiumCompat.contentLocked && kind.sodium;
        Inertia.guiX = sodiumLocked ? SodiumCompat.lockX : x;
        Inertia.guiY = sodiumLocked ? SodiumCompat.lockY : y;
        // The wheel emulator sends its notches to the list the finger scrolls.
        TouchState.rememberScrollTarget(screen, Inertia.guiX, Inertia.guiY);
        TouchState.moved = true;

        if (area == null) {
            // Fractional mouseScrolled units: whole 12 px notches made the
            // drag and the scroll bar thumb jerky.
            boolean inverted = ScreenScroll.isInverted(screen, TouchState.pressX);
            TouchState.fallbackInverted = inverted;
            ScreenScroll.dispatch(screen, Inertia.guiX, Inertia.guiY, ScreenScroll.unitsFor(screen, step, inverted));
            return;
        }
        // 1:1 finger tracking, same sign as the coast.
        double max = area.maxScrollAmount();
        TouchState.lockedScroll = Math.max(0.0, Math.min(max, TouchState.lockedScroll - step));
        area.setScrollAmount(TouchState.lockedScroll);
        ScrollMemory.remember(area, TouchState.lockedScroll);
    }

    /**
     * After vanilla handled the frame: vanilla's own drag may have moved the
     * dragged list, so the drag position is applied again.
     */
    public static void afterMovement() {
        if (TouchState.active && TouchState.moved && TouchState.lockedArea != null && !Inertia.active) {
            try {
                TouchState.lockedArea.setScrollAmount(TouchState.lockedScroll);
                ScrollMemory.remember(TouchState.lockedArea, TouchState.lockedScroll);
            } catch (Throwable ignored) {
            }
        }
    }
}
