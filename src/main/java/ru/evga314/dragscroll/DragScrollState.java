package ru.evga314.dragscroll;

import net.minecraft.client.gui.components.AbstractScrollArea;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * State used by the mobile drag-scroll adapter.
 *
 * The launcher turns each finger touch into a fresh LMB press and also moves
 * the mouse cursor to the new finger position. That cursor teleport is not
 * part of the user's drag and must never become a scroll delta.
 */
public final class DragScrollState {
    private DragScrollState() {
    }

    public static boolean active = false;
    /** SDL3 reports the left mouse button as 1; 0 is retained for compatibility with layers that expose the legacy value. */
    public static boolean leftButtonHeld = false;
    /** True only while the current physical touch has actually become a drag.
     *  The persistent scroll session may remain active between separate touches. */
    public static boolean currentTouchDragged = false;
    /** True while native scrollbar/slider handling owns the current touch. */
    public static boolean nativeControlHeld = false;
    /** True when the current native control is a slider that may hand off to scrolling. */
    public static boolean nativeSliderHeld = false;
    /** True when the current native control is a scrollbar that must keep ownership. */
    public static boolean nativeScrollbarHeld = false;
    /**
     * Once the user has clearly committed to horizontal slider manipulation
     * (deferred horizontal engage, or a strong horizontal move on a direct
     * slider), this touch stays on the slider until release. Vertical jitter
     * must not hand the gesture back to list scrolling mid-drag.
     */
    public static boolean sliderGestureLocked = false;

    public static boolean isLeftButton(int button) {
        return button == 0 || button == 1;
    }
    public static boolean moved = false;

    public static AbstractScrollArea lockedArea = null;
    public static double baseScroll = 0.0;
    public static double lockedScroll = 0.0;

    /** Screen-level fallback used by custom scroll widgets which are not AbstractScrollArea. */
    public static net.minecraft.client.gui.screens.Screen fallbackScrollScreen = null;
    public static boolean fallbackScrollInverted = false;

    // --- Inertia (scrollVelocity in pixels per client-tick ≈ px*20/s) ---
    public static final int TRACK_SAMPLES = 24;
    public static double scrollVelocity = 0.0;
    /** Recent finger deltas with timestamps — velocity = Δy / Δt over ~100 ms. */
    private static final double[] trackDy = new double[TRACK_SAMPLES];
    private static final long[] trackNs = new long[TRACK_SAMPLES];
    private static int trackIdx = 0;
    private static int trackCount = 0;
    public static boolean inertiaActive = false;
    public static double residualVelocity = 0.0;
    public static double inertiaGuiX = 0.0;
    public static double inertiaGuiY = 0.0;
    public static final double INERTIA_FRICTION = 0.92;
    public static final double INERTIA_STOP = 0.08;
    public static final double INERTIA_STOP_PIXEL = 0.05;
    public static final double INERTIA_MAX_VEL = 48.0;
    /** Window used to estimate fling speed (seconds). */
    public static final double FLING_WINDOW_SEC = 0.10;
    /**
     * Min fling speed in px/tick (~3 ≈ 60 px/s). Slower releases = no coast.
     */
    public static final double INERTIA_MIN_FLICK = 3.0;
    /** Stack residual only when the new fling is at least this fast (px/tick). */
    public static final double INERTIA_STACK_MIN = 8.0;

    /**
     * Last known scroll amount per AbstractScrollArea instance.
     * Survives across separate touches on the same list so that a new press
     * does not snap the list back to an outdated baseScroll.
     */
    private static final Map<AbstractScrollArea, Double> lastScrollByArea = new WeakHashMap<>();
    /**
     * Position at the instant a new LMB touch is received, before Minecraft
     * handles the click. Restored only if that touch turns into a drag.
     */
    private static final Map<AbstractScrollArea, Double> PRESS_SNAPSHOT = new WeakHashMap<>();

    public static void snapshot(AbstractScrollArea area) {
        if (area != null) {
            try {
                PRESS_SNAPSHOT.put(area, area.scrollAmount());
            } catch (Throwable ignored) {
            }
        }
    }

    public static Double pressSnapshot(AbstractScrollArea area) {
        return area == null ? null : PRESS_SNAPSHOT.get(area);
    }

    public static void clearPressSnapshot() {
        PRESS_SNAPSHOT.clear();
    }

    public static void remember(AbstractScrollArea area, double scroll) {
        if (area != null) {
            lastScrollByArea.put(area, scroll);
        }
    }

    public static Double recall(AbstractScrollArea area) {
        return area == null ? null : lastScrollByArea.get(area);
    }

    public static void clearAreaMemory(AbstractScrollArea area) {
        if (area != null) {
            lastScrollByArea.remove(area);
        }
    }

    /** Full session wipe (screen change / hard reset). */
    public static void clear() {
        active = false;
        leftButtonHeld = false;
        currentTouchDragged = false;
        nativeControlHeld = false;
        nativeSliderHeld = false;
        nativeScrollbarHeld = false;
        sliderGestureLocked = false;
        moved = false;
        lockedArea = null;
        baseScroll = 0.0;
        lockedScroll = 0.0;
        fallbackScrollScreen = null;
        fallbackScrollInverted = false;
        stopInertia();
        clearPressSnapshot();
        cancelPendingClick();
    }

    public static void reset() {
        active = false;
        leftButtonHeld = false;
        currentTouchDragged = false;
        nativeControlHeld = false;
        nativeSliderHeld = false;
        nativeScrollbarHeld = false;
        sliderGestureLocked = false;
        moved = false;
        lockedArea = null;
        baseScroll = 0.0;
        lockedScroll = 0.0;
        fallbackScrollScreen = null;
        fallbackScrollInverted = false;
        stopInertia();
        cancelPendingClick();
    }

    /** Stop coasting without wiping the rest of the scroll session. */
    public static void stopInertia() {
        inertiaActive = false;
        scrollVelocity = 0.0;
        residualVelocity = 0.0;
        inertiaGuiX = 0.0;
        inertiaGuiY = 0.0;
        trackIdx = 0;
        trackCount = 0;
        for (int i = 0; i < TRACK_SAMPLES; i++) {
            trackDy[i] = 0.0;
            trackNs[i] = 0L;
        }
    }

    /** Real drag begins: stop free-coast. Residual kept only for a later fast fling. */
    public static void catchInertiaForDrag() {
        if (inertiaActive || Math.abs(scrollVelocity) > INERTIA_STOP) {
            if (Math.abs(scrollVelocity) > Math.abs(residualVelocity)) {
                residualVelocity = scrollVelocity;
            }
        }
        inertiaActive = false;
        // Fresh motion window for this drag — do not mix with previous gesture samples.
        trackIdx = 0;
        trackCount = 0;
        for (int i = 0; i < TRACK_SAMPLES; i++) {
            trackDy[i] = 0.0;
            trackNs[i] = 0L;
        }
    }

    /**
     * Record a finger step. Velocity is NOT derived from inter-event gaps
     * (launcher batches many events in one frame → tiny dt → false "fast").
     * On release we integrate Δy over the last ~100 ms instead.
     */
    public static void pushVelocitySample(double stepDy) {
        long now = System.nanoTime();
        trackDy[trackIdx] = stepDy;
        trackNs[trackIdx] = now;
        trackIdx = (trackIdx + 1) % TRACK_SAMPLES;
        if (trackCount < TRACK_SAMPLES) {
            trackCount++;
        }
    }

    /**
     * Fling speed = total displacement over the last FLING_WINDOW_SEC, converted
     * to px/tick. Robust against touch-event batching.
     */
    public static double averageReleaseVelocity() {
        if (trackCount <= 0) {
            return 0.0;
        }
        long now = System.nanoTime();
        long windowNs = (long) (FLING_WINDOW_SEC * 1_000_000_000.0);
        double sumDy = 0.0;
        long oldestNs = now;
        int used = 0;
        for (int i = 0; i < trackCount; i++) {
            int index = (trackIdx - 1 - i + TRACK_SAMPLES * 2) % TRACK_SAMPLES;
            long t = trackNs[index];
            if (t == 0L) {
                continue;
            }
            if (now - t > windowNs) {
                break;
            }
            sumDy += trackDy[index];
            oldestNs = t;
            used++;
        }
        if (used <= 0) {
            return 0.0;
        }
        double dt = (now - oldestNs) / 1_000_000_000.0;
        // If everything fell into one frame, use the window length as minimum dt
        // so a slow drag that arrives as one big batch is not treated as a fling.
        if (dt < FLING_WINDOW_SEC * 0.5) {
            dt = FLING_WINDOW_SEC;
        }
        // px/s → px/tick
        return sumDy / (dt * 20.0);
    }

    /**
     * Slow lift → no coast. Fast fling → coast proportional to real finger speed.
     * Residual stacks only on another fast fling in the same direction.
     */
    public static double computeReleaseVelocity() {
        double releaseVel = averageReleaseVelocity();

        if (Math.abs(releaseVel) < INERTIA_MIN_FLICK) {
            residualVelocity = 0.0;
            scrollVelocity = 0.0;
            return 0.0;
        }

        // User-configurable strength (default 70% = 30% softer than the raw fling).
        releaseVel *= DragScrollConfig.getInertiaStrengthMultiplier();

        if (Math.abs(releaseVel) >= INERTIA_STACK_MIN
                && residualVelocity != 0.0
                && releaseVel * residualVelocity > 0.0) {
            releaseVel = releaseVel + residualVelocity * 0.75;
        } else if (releaseVel * residualVelocity < 0.0) {
            residualVelocity = 0.0;
        }

        if (releaseVel > INERTIA_MAX_VEL) releaseVel = INERTIA_MAX_VEL;
        if (releaseVel < -INERTIA_MAX_VEL) releaseVel = -INERTIA_MAX_VEL;
        residualVelocity = releaseVel;
        scrollVelocity = releaseVel;
        return releaseVel;
    }

    // The initial screen click is delayed while a possible drag is being determined.
    // Delaying the whole screen dispatch also covers custom buttons that do not extend
    // AbstractButton. A normal tap is replayed with the original event unchanged.
    private static net.minecraft.client.gui.screens.Screen pendingScreen;
    private static net.minecraft.client.input.MouseButtonEvent pendingEvent;
    private static boolean pendingDoubleClick;
    private static boolean replaying;

    private static long debugSequence = 0L;

    public static long debugEvent(String source, String message) {
        if (!DragScrollConfig.isDebugEnabled()) {
            return 0L;
        }
        long id = ++debugSequence;
        ru.evga314.dragscroll.DragScrollClient.LOGGER.info("[DragScroll DEBUG #{}] {} {}", id, source, message);
        return id;
    }

    public static boolean isReplaying() {
        return replaying;
    }

    public static boolean isPendingScreenClick() {
        return pendingScreen != null && pendingEvent != null;
    }

    public static void deferScreenClick(
            net.minecraft.client.gui.screens.Screen screen,
            net.minecraft.client.input.MouseButtonEvent event,
            boolean doubleClick) {
        pendingScreen = screen;
        pendingEvent = event;
        pendingDoubleClick = doubleClick;
    }

    public static void cancelPendingClick() {
        pendingScreen = null;
        pendingEvent = null;
        pendingDoubleClick = false;
    }

    public static void finishPendingClick() {
        // A drag cancels the pending click at the exact moment the drag is
        // activated. Therefore a pending click still present at release is a
        // genuine tap and must be replayed even if the transient drag flag was
        // changed by a nearby mouse-movement callback.
        if (pendingScreen == null || pendingEvent == null) {
            cancelPendingClick();
            return;
        }

        net.minecraft.client.gui.screens.Screen screen = pendingScreen;
        net.minecraft.client.input.MouseButtonEvent event = pendingEvent;
        boolean doubleClick = pendingDoubleClick;
        cancelPendingClick();

        try {
            replaying = true;
            screen.mouseClicked(event, doubleClick);
        } catch (Throwable ignored) {
        } finally {
            replaying = false;
        }
    }
}
