package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;

/**
 * State of the current finger and of the scroll session it belongs to.
 *
 * <p>A mobile launcher turns every finger contact into a new LMB press and
 * teleports the cursor to the new finger first. The teleport is never a drag,
 * and one list swipe is often several contacts. So the <b>touch</b> (one
 * contact) and the <b>session</b> (one list being scrolled, which survives
 * releases) are tracked separately.
 */
public final class TouchState {
    private TouchState() {
    }

    // --- The current finger ---

    /**
     * A finger is down. Tracked by the mod: MouseHandler.isLeftPressed() does
     * not follow the launcher's synthetic presses reliably.
     */
    public static boolean leftButtonHeld;
    /** The current contact moved past the drag threshold and scrolls. */
    public static boolean currentTouchDragged;
    /** A native scroll bar, slider or other control owns the current contact. */
    public static boolean nativeControlHeld;
    /** The native control is a slider (may still hand the touch to scrolling). */
    public static boolean nativeSliderHeld;
    /** The native control is a scroll bar (keeps the touch until release). */
    public static boolean nativeScrollbarHeld;
    /** The user committed to a horizontal slider drag; vertical jitter must not steal it. */
    public static boolean sliderGestureLocked;

    /** Where the current contact went down, GUI px (NaN between touches). */
    public static double pressX = Double.NaN;
    public static double pressY = Double.NaN;
    /** Last finger position used for the frame delta. */
    public static double lastX = Double.NaN;
    public static double lastY = Double.NaN;
    /** The previous frame saw the finger down. */
    public static boolean wasHeld;
    /** Screen of the previous frame; a change drops the whole gesture. */
    public static Screen lastScreen;

    // --- The scroll session ---

    /** A list or screen is being scrolled; survives the gap between contacts. */
    public static boolean active;
    /** This frame's movement scrolled something. */
    public static boolean moved;
    /** The AbstractScrollArea being scrolled, or null for a screen-level scroll. */
    public static AbstractScrollArea lockedArea;
    /** Scroll offset the drag keeps the locked area at. */
    public static double lockedScroll;
    /** Screen scrolled through mouseScrolled (custom lists without an AbstractScrollArea). */
    public static Screen fallbackScreen;
    /** The fallback screen's mouseScrolled runs opposite to vanilla lists. */
    public static boolean fallbackInverted;

    // --- Targets for the mouse-wheel emulator ---

    /** Last point the finger scrolled, so wheel notches reach the same list. */
    public static Screen lastScrollTargetScreen;
    public static double lastScrollTargetX = Double.NaN;
    public static double lastScrollTargetY = Double.NaN;
    /** Cursor position before the launcher's last teleport (a new finger). */
    public static double cursorBeforeTeleportX = Double.NaN;
    public static double cursorBeforeTeleportY = Double.NaN;

    /** SDL3 reports the left button as 1; 0 is accepted from layers that still use the GLFW value. */
    public static boolean isLeftButton(int button) {
        return button == 0 || button == 1;
    }

    public static boolean hasPressPoint() {
        return !Double.isNaN(pressX) && !Double.isNaN(pressY);
    }

    public static void clearPressPoint() {
        pressX = Double.NaN;
        pressY = Double.NaN;
    }

    public static void clearTracking() {
        lastX = Double.NaN;
        lastY = Double.NaN;
    }

    public static void rememberScrollTarget(Screen screen, double x, double y) {
        lastScrollTargetScreen = screen;
        lastScrollTargetX = x;
        lastScrollTargetY = y;
    }

    public static void rememberCursorBeforeTeleport(double x, double y) {
        cursorBeforeTeleportX = x;
        cursorBeforeTeleportY = y;
    }

    /** Drops the scroll session (not the finger state). */
    public static void endSession() {
        active = false;
        lockedArea = null;
        fallbackScreen = null;
    }

    /** Full wipe of finger, session and wheel targets (screen change, hard reset). */
    public static void clear() {
        leftButtonHeld = false;
        currentTouchDragged = false;
        nativeControlHeld = false;
        nativeSliderHeld = false;
        nativeScrollbarHeld = false;
        sliderGestureLocked = false;
        active = false;
        moved = false;
        lockedArea = null;
        lockedScroll = 0.0;
        fallbackScreen = null;
        fallbackInverted = false;
        lastScrollTargetScreen = null;
        lastScrollTargetX = Double.NaN;
        lastScrollTargetY = Double.NaN;
        cursorBeforeTeleportX = Double.NaN;
        cursorBeforeTeleportY = Double.NaN;
    }
}
