package ru.evga314.dragscroll.compat;

/**
 * A scroll bar thumb held by the finger. The first frame records the offset
 * between the finger and the thumb centre, so the thumb never jumps to the
 * finger; later frames move it by the finger's travel along the track.
 *
 * <p>Used for scroll bars the mod drives itself because the owning mod's
 * own drag misses touch input (Cloth Config, Shulker Box Tooltip).
 */
public final class ThumbGrab {
    private boolean armed;
    private double offset;

    public boolean isArmed() {
        return armed;
    }

    public void reset() {
        armed = false;
        offset = 0.0;
    }

    /**
     * Scroll offset for a finger at {@code fingerY} on a track from
     * {@code top} to {@code bottom}. The first call only arms the grab and
     * returns {@code current}.
     */
    public double map(double fingerY, double top, double bottom, double current, double max) {
        double track = bottom - top;
        if (track < 8.0 || max <= 0.0) {
            return current;
        }
        double thumbH = Math.max(16.0, track * track / (track + max));
        if (thumbH > track * 0.85) {
            thumbH = track * 0.5;
        }
        double usable = Math.max(1.0, track - thumbH);
        if (!armed) {
            double visualT = clamp01(current / max);
            offset = fingerY - (top + thumbH * 0.5 + visualT * usable);
            armed = true;
            return current;
        }
        double t = clamp01((fingerY - offset - top - thumbH * 0.5) / usable);
        return t * max;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
