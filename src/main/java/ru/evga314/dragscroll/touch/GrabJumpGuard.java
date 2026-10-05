package ru.evga314.dragscroll.touch;

/**
 * Camera jump after a menu button returns to the game (pause menu "Back to
 * Game", sign "Done", ...).
 *
 * <p>The launcher sends a tap as press + release 33 ms later and puts its
 * cursor back on the tap point for the release. Grabbing the mouse resets
 * that cursor to 0,0 but not the position the launcher measures its
 * relative steps from. When the grab lands after the release, the first
 * in-game step is (0 - tap point): the camera turns towards the top-left
 * corner. That one step is dropped here; nothing else is touched.
 */
public final class GrabJumpGuard {
    private GrabJumpGuard() {
    }

    /** A grab this soon after a left press counts as caused by that tap. */
    private static final long TAP_TO_GRAB_NS = 1_000_000_000L;
    /** Allowed mismatch: the finger's own first step rides on the jump. */
    private static final double MIN_TOLERANCE_PX = 64.0;
    private static final double TOLERANCE_SHARE = 0.2;
    /** Real camera steps after which the jump can no longer come. */
    private static final int MAX_CLEAN_STEPS = 10;

    private static long lastPressNs;
    private static boolean armed;
    private static double tapX;
    private static double tapY;
    private static int cleanSteps;

    /** Left button press, any screen. */
    public static void onLeftPress() {
        lastPressNs = System.nanoTime();
    }

    /**
     * MouseHandler.grabMouse, before vanilla moves the cursor to the center.
     * {@code xpos}/{@code ypos} are the last cursor position in the menu.
     */
    public static void onGrab(double xpos, double ypos) {
        if (lastPressNs == 0L || System.nanoTime() - lastPressNs > TAP_TO_GRAB_NS) {
            return;
        }
        armed = true;
        tapX = xpos;
        tapY = ypos;
        cleanSteps = 0;
        if (Debug.on()) Debug.log("GrabJumpGuard", "ARMED tap=" + xpos + "," + ypos);
    }

    public static void onRelease() {
        armed = false;
    }

    /** A grabbed (relative) step. Returns true when it is the launcher's jump. */
    public static boolean isStaleJump(double xrel, double yrel) {
        if (!armed || xrel == 0.0 && yrel == 0.0) {
            return false;
        }
        double tolerance = Math.max(MIN_TOLERANCE_PX, Math.hypot(tapX, tapY) * TOLERANCE_SHARE);
        if (Math.hypot(xrel + tapX, yrel + tapY) <= tolerance) {
            armed = false;
            if (Debug.on()) Debug.log("GrabJumpGuard", "DROPPED rel=" + xrel + "," + yrel);
            return true;
        }
        if (++cleanSteps >= MAX_CLEAN_STEPS) {
            armed = false;
        }
        return false;
    }
}
