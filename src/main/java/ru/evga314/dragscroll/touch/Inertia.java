package ru.evga314.dragscroll.touch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.compat.MalilibCompat;
import ru.evga314.dragscroll.config.DragScrollConfig;

/**
 * List inertia: after a fast swipe the list keeps moving and slows down.
 *
 * <p>Velocities are in GUI px per client tick (1/20 s), like vanilla motion.
 * The coast advances once per frame with the real frame time, so it follows
 * the menu frame rate. During a coast the scroll position is owned by
 * {@link TouchState#lockedScroll}; the widget is never re-read mid-coast,
 * which made the tail stutter against smooth-scroll mods.
 */
public final class Inertia {
    private Inertia() {
    }

    public static final double TICKS_PER_SECOND = 20.0;
    /** Velocity kept per tick while coasting. */
    public static final double FRICTION = 0.92;
    /** The coast ends below this velocity (px/tick). */
    public static final double STOP_VELOCITY = 0.08;
    /** ... together with a frame step below this (px). */
    private static final double STOP_STEP = 0.05;
    public static final double MAX_VELOCITY = 48.0;
    /** Slower releases (px/tick, about 60 px/s) do not coast. */
    public static final double MIN_FLICK = 3.0;
    /** A new fling adds the leftover velocity only when at least this fast. */
    private static final double STACK_MIN = 8.0;
    /** Continuous decay rate (1/s): e^(-rate / 20) == FRICTION per tick. */
    private static final double DECAY_PER_SECOND = -TICKS_PER_SECOND * Math.log(FRICTION);

    /** The list is coasting. */
    public static boolean active;
    /** Current coast velocity (px/tick). */
    public static double velocity;
    /** Velocity of the previous fling, stacked onto a following fast fling. */
    public static double residual;
    /** Point passed to mouseScrolled while coasting a screen-level scroll. */
    public static double guiX;
    public static double guiY;

    private static final VelocityTracker TRACKER = new VelocityTracker();
    private static long lastFrameNs;
    private static long lastApplyNs;

    /** Velocity after coasting {@code dt} seconds. */
    public static double decay(double v, double dt) {
        return v * Math.exp(-DECAY_PER_SECOND * dt);
    }

    public static void pushSample(double stepDy) {
        TRACKER.push(stepDy);
    }

    /** A coast is running or has just been stopped with speed left. */
    public static boolean isMoving() {
        return active || Math.abs(velocity) > STOP_VELOCITY;
    }

    public static void start(double releaseVelocity) {
        velocity = releaseVelocity;
        active = true;
        lastFrameNs = 0L;
        lastApplyNs = 0L;
    }

    /** Stops the coast and forgets the leftover velocity. */
    public static void stop() {
        active = false;
        velocity = 0.0;
        residual = 0.0;
        guiX = 0.0;
        guiY = 0.0;
        lastFrameNs = 0L;
        TRACKER.clear();
    }

    /**
     * A finger touched the coasting list: stop it at once, but keep the
     * velocity so a following swipe can stack on it. A tap or a long hold
     * later calls {@link #stop()} and drops it.
     */
    public static void stopCoastKeepResidual() {
        if (Math.abs(velocity) > Math.abs(residual)) {
            residual = velocity;
        }
        active = false;
        velocity = 0.0;
        TRACKER.clear();
    }

    /** A drag starts: end the coast, keep its speed only for a later fast fling. */
    public static void catchForDrag() {
        if (isMoving() && Math.abs(velocity) > Math.abs(residual)) {
            residual = velocity;
        }
        active = false;
        TRACKER.clear();
    }

    /**
     * Coast velocity for the finger lift, or 0. A slow lift does not coast; a
     * fast one coasts with the finger's speed times the configured strength,
     * plus most of the previous fling's speed when it goes the same way.
     */
    public static double computeReleaseVelocity() {
        double v = TRACKER.pixelsPerSecond() / TICKS_PER_SECOND;
        if (Math.abs(v) < MIN_FLICK) {
            residual = 0.0;
            velocity = 0.0;
            return 0.0;
        }
        v *= DragScrollConfig.getInertiaStrengthMultiplier();
        if (Math.abs(v) >= STACK_MIN && residual != 0.0 && v * residual > 0.0) {
            v += residual * 0.75;
        } else if (v * residual < 0.0) {
            residual = 0.0;
        }
        v = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, v));
        residual = v;
        velocity = v;
        return v;
    }

    /** Advances the coast by one frame of {@code screen}. */
    public static void applyFrame(Screen screen) {
        if (!active) {
            lastFrameNs = 0L;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null || screen == null || client.gui.screen() != screen || client.gui.overlay() != null) {
            stop();
            return;
        }
        // Grabbing a scroll bar or another control freezes the list at once.
        if (TouchState.nativeScrollbarHeld || TouchState.nativeControlHeld) {
            stop();
            return;
        }
        // A finger is down: the coast pauses until the touch becomes a drag or lifts.
        if (TouchState.currentTouchDragged || TouchState.leftButtonHeld) {
            lastFrameNs = 0L;
            return;
        }
        long now = System.nanoTime();
        if (lastApplyNs != 0L && now - lastApplyNs < 1_000_000L) {
            return; // second call within the same frame
        }
        lastApplyNs = now;
        if (lastFrameNs == 0L) {
            lastFrameNs = now;
            return;
        }
        double dt = (now - lastFrameNs) / 1_000_000_000.0;
        lastFrameNs = now;
        if (dt <= 0.0 || dt > 0.1) {
            return;
        }

        double v = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, velocity));
        double decayed = decay(v, dt);
        double frameDy = 0.5 * (v + decayed) * TICKS_PER_SECOND * dt;
        if (Math.abs(decayed) < STOP_VELOCITY && Math.abs(frameDy) < STOP_STEP) {
            stop();
            return;
        }
        try {
            if (TouchState.lockedArea != null) {
                double next = TouchState.lockedScroll - frameDy;
                TouchState.lockedArea.setScrollAmount(next);
                TouchState.lockedScroll = next;
                ScrollMemory.remember(TouchState.lockedArea, next);
            } else if (MalilibCompat.hasCoast(screen)) {
                if (!MalilibCompat.applyCoast(screen, frameDy)) {
                    stop();
                    return;
                }
            } else if (TouchState.fallbackScreen == screen) {
                ScreenScroll.dispatch(screen, guiX, guiY,
                        ScreenScroll.unitsFor(screen, frameDy, TouchState.fallbackInverted));
            } else {
                stop();
                return;
            }
        } catch (Throwable t) {
            stop();
            return;
        }
        velocity = decayed;
        if (Math.abs(velocity) < STOP_VELOCITY) {
            stop();
        }
    }
}
