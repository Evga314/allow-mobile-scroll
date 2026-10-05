package ru.evga314.dragscroll.touch;

/**
 * Camera jump after a menu button returns to the game (pause menu "Back to
 * Game", sign "Done", ...).
 *
 * <p>The launcher sends a tap as press + release 33 ms later and puts its
 * cursor back on the tap point for the release. Its relative steps are
 * measured from the last position it sent (the tap point), which the grab
 * does not update. Depending on which comes first, the grab or the release,
 * the first in-game step carries a jump:
 * <ul>
 * <li>Mojo before 2026-10-02: the grab resets the cursor to 0,0, jump =
 * (0 - tap point), the camera turns towards the top-left corner;</li>
 * <li>Mojo since 2026-10-02 (SDL cursor warp): the cursor follows the game's
 * warp to the window center, jump = (center - tap point).</li>
 * </ul>
 * That one step is dropped here; nothing else is touched.
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
    private static double centerX;
    private static double centerY;
    private static int cleanSteps;

    /** Left button press, any screen. */
    public static void onLeftPress() {
        lastPressNs = System.nanoTime();
    }

    /**
     * MouseHandler.grabMouse, before vanilla moves the cursor to the center.
     * {@code xpos}/{@code ypos} are the last cursor position in the menu.
     */
    public static void onGrab(double xpos, double ypos, double centerX, double centerY) {
        if (lastPressNs == 0L || System.nanoTime() - lastPressNs > TAP_TO_GRAB_NS) {
            return;
        }
        armed = true;
        tapX = xpos;
        tapY = ypos;
        GrabJumpGuard.centerX = centerX;
        GrabJumpGuard.centerY = centerY;
        cleanSteps = 0;
        if (Debug.on()) Debug.log("GrabJumpGuard", "ARMED tap=" + xpos + "," + ypos
                + " center=" + centerX + "," + centerY);
    }

    public static void onRelease() {
        armed = false;
    }

    /** A grabbed (relative) step. Returns true when it is the launcher's jump. */
    public static boolean isStaleJump(double xrel, double yrel) {
        if (!armed || xrel == 0.0 && yrel == 0.0) {
            return false;
        }
        if (matches(xrel, yrel, -tapX, -tapY) || matches(xrel, yrel, centerX - tapX, centerY - tapY)) {
            armed = false;
            if (Debug.on()) Debug.log("GrabJumpGuard", "DROPPED rel=" + xrel + "," + yrel);
            return true;
        }
        if (++cleanSteps >= MAX_CLEAN_STEPS) {
            armed = false;
        }
        return false;
    }

    private static boolean matches(double xrel, double yrel, double jumpX, double jumpY) {
        double tolerance = Math.max(MIN_TOLERANCE_PX, Math.hypot(jumpX, jumpY) * TOLERANCE_SHARE);
        return Math.hypot(xrel - jumpX, yrel - jumpY) <= tolerance;
    }
}
