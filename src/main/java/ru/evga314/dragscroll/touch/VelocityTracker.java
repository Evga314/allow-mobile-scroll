package ru.evga314.dragscroll.touch;

/**
 * Finger speed at the moment of release.
 *
 * <p>The speed is the total travel over the last {@link #WINDOW_SEC}, not the
 * gap between two events: launchers deliver several touch events in one
 * frame, and their tiny time gaps would read as a very fast fling.
 */
public final class VelocityTracker {
    /** Window used to measure the fling, in seconds. */
    public static final double WINDOW_SEC = 0.10;

    private static final int SAMPLES = 24;

    private final double[] dy = new double[SAMPLES];
    private final long[] ns = new long[SAMPLES];
    private int next;
    private int count;

    /** Records one finger step (GUI px). */
    public void push(double step) {
        dy[next] = step;
        ns[next] = System.nanoTime();
        next = (next + 1) % SAMPLES;
        if (count < SAMPLES) {
            count++;
        }
    }

    public void clear() {
        next = 0;
        count = 0;
        java.util.Arrays.fill(dy, 0.0);
        java.util.Arrays.fill(ns, 0L);
    }

    /** Travel over the last window, in GUI px per second; 0 without samples. */
    public double pixelsPerSecond() {
        long now = System.nanoTime();
        long windowNs = (long) (WINDOW_SEC * 1_000_000_000.0);
        double sum = 0.0;
        long oldest = now;
        int used = 0;
        for (int i = 0; i < count; i++) {
            int index = (next - 1 - i + SAMPLES * 2) % SAMPLES;
            if (now - ns[index] > windowNs) {
                break;
            }
            sum += dy[index];
            oldest = ns[index];
            used++;
        }
        if (used == 0) {
            return 0.0;
        }
        double dt = (now - oldest) / 1_000_000_000.0;
        // A slow drag that arrived as one batch must not count as a fling.
        if (dt < WINDOW_SEC * 0.5) {
            dt = WINDOW_SEC;
        }
        return sum / dt;
    }
}
