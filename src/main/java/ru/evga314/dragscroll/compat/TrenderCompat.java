package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import ru.evga314.dragscroll.touch.DeferredClick;
import ru.evga314.dragscroll.touch.Debug;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * tr7zw's TRender (and the LibGui/Cotton it is forked from): the config
 * screens of EntityCulling, Skin Layers 3D, Wavey Capes, ... Their lists
 * (WListPanel + WScrollBar) are not Minecraft widgets at all, so the page
 * scrolls through mouseScrolled and the bar thumb is driven here.
 *
 * <p>The launcher sends extra RELEASE/PRESS pairs while the finger is still
 * on the thin bar; the bar lock below keeps one physical swipe on the bar.
 */
public final class TrenderCompat {
    private TrenderCompat() {
    }

    /** A launcher RELEASE/PRESS pair inside one finger hold arrives within this. */
    private static final long SYNTHETIC_GAP_NS = 40_000_000L;
    /** After a real finger-up, the lock is dropped once this has passed. */
    private static final long IDLE_CLEAR_NS = 5_000_000L;

    /** The current swipe belongs to the scroll bar. */
    public static boolean barLocked;
    /** X of the bar the swipe is locked to (0 = unknown). */
    public static double barLockX;
    /** The vanilla click on the bar was already sent for this swipe. */
    public static boolean barClickSent;
    /** Last time the thumb moved; bridges a synthetic PRESS without a RELEASE. */
    public static long barLastMoveNs;
    /** Real finger-up time (0 while the finger is down). */
    public static long barReleaseNs;
    /** The WScrollBar that received the press (set by TrenderScrollBarMixin). */
    public static Object grabbedBar;

    private static boolean thumbArmed;
    private static double thumbOffset;
    /** Fractional mouseScrolled units not sent yet (WScrollBar drops fractions). */
    private static double scrollCarry;

    /** Drops the bar lock and grab (keeps the scroll carry). */
    public static void releaseBar() {
        barLocked = false;
        barClickSent = false;
        barLockX = 0.0;
        barReleaseNs = 0L;
        thumbArmed = false;
        grabbedBar = null;
    }

    public static void reset() {
        releaseBar();
        barLastMoveNs = 0L;
        thumbOffset = 0.0;
        scrollCarry = 0.0;
    }

    /**
     * A new PRESS while the bar is locked is part of the same swipe: the
     * launcher's synthetic RELEASE/PRESS, or a PRESS without any release.
     */
    public static boolean isSyntheticRepress(long nowNs) {
        if (!barLocked) {
            return false;
        }
        if (barReleaseNs != 0L) {
            return nowNs - barReleaseNs < SYNTHETIC_GAP_NS;
        }
        return barLastMoveNs != 0L && nowNs - barLastMoveNs < SYNTHETIC_GAP_NS;
    }

    /** The finger was lifted long enough ago that the bar lock is stale. */
    public static boolean isIdleAfterRelease(long nowNs) {
        return barReleaseNs != 0L && nowNs - barReleaseNs > IDLE_CLEAR_NS;
    }

    /**
     * Whole mouseScrolled units to send now. WScrollBar.onMouseScroll casts
     * the delta to int, so fractions are carried over to later moves.
     */
    public static double consumeScrollDelta(double delta) {
        scrollCarry += delta;
        double emit = Math.rint(scrollCarry);
        if (emit == 0.0) {
            return 0.0;
        }
        scrollCarry -= emit;
        return emit;
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    /**
     * {x, width} of the centred config panel. TRender/LibGui roots are a
     * centred modal of 200 to 400 px, not a share of the screen width.
     */
    private static int[] panel(Screen screen) {
        int w = screen == null ? 0 : screen.width;
        int panelW = Math.min(Math.max(w - 48, 200), 400);
        if (w > 0 && panelW > w - 24) {
            panelW = Math.max(200, w - 24);
        }
        int panelX = w > panelW ? (w - panelW) / 2 : 0;
        return new int[] {panelX, panelW};
    }

    /** X of the bar centre, used as the fixed x of the bar pointer events. */
    public static double barCenterX(Screen screen, double fallbackX) {
        if (screen == null || screen.width <= 0) {
            return fallbackX;
        }
        int[] p = panel(screen);
        return p[0] + p[1] - 4.0;
    }

    /**
     * The finger is in the bar strip: the 8 px WScrollBar at the panel's right
     * edge, a fat-finger margin left of it, and the gap to the screen edge.
     * The checkboxes of the rows stay left of it.
     */
    public static boolean isBarStrip(Screen screen, double x) {
        if (screen.width <= 0) {
            return false;
        }
        int[] p = panel(screen);
        return x >= p[0] + p[1] - 48.0 && x <= p[0] + p[1] + 12.0;
    }

    // =====================================================================
    // Thumb
    // =====================================================================

    /**
     * WScrollBars of the attached widget tree. Hidden tab cards keep their
     * bars in fields too; only children and scroll bar accessors are walked.
     */
    private static void collectBars(Object node, IdentityHashMap<Object, Boolean> seen, List<Object> out) {
        if (node == null || seen.containsKey(node)) {
            return;
        }
        seen.put(node, Boolean.TRUE);
        String n = node.getClass().getName();
        if (n.contains("WScrollBar") || n.endsWith("ScrollBar")) {
            out.add(node);
        }
        if (node instanceof Screen) {
            collectBars(Reflect.invokePublic(node, "getDescription"), seen, out);
        }
        for (String accessor : new String[] {"getRootPanel", "getChildren", "getScrollBar"}) {
            collectChild(Reflect.invokePublic(node, accessor), seen, out);
        }
        // Such a field may be declared (shadowed) on several levels; all are walked.
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String field : new String[] {"children", "widgets", "scrollBar", "scrollbar"}) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(field);
                    f.setAccessible(true);
                    collectChild(f.get(node), seen, out);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collectChild(Object child, IdentityHashMap<Object, Boolean> seen, List<Object> out) {
        if (child instanceof Iterable<?> it) {
            for (Object o : it) {
                collectBars(o, seen, out);
            }
        } else if (child != null) {
            collectBars(child, seen, out);
        }
    }

    /**
     * The bar the swipe drives: the one captured on press, else the visible
     * bar with content that lies under the locked x and the finger.
     */
    private static Object pickBar(Screen screen, double guiY) {
        if (grabbedBar != null) {
            return grabbedBar;
        }
        List<Object> bars = new ArrayList<>();
        collectBars(screen, new IdentityHashMap<>(), bars);
        Object best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Object bar : bars) {
            double max = Reflect.invokeNumber(bar, "getMaxValue", "getMax");
            double height = Reflect.invokeNumber(bar, "getHeight");
            double absX = Reflect.invokeNumber(bar, "getAbsoluteX", "getX");
            double absY = Reflect.invokeNumber(bar, "getAbsoluteY", "getY");
            double width = Reflect.invokeNumber(bar, "getWidth");
            if (Double.isNaN(max) || max <= 0) {
                continue;
            }
            if (Reflect.invokePublic(bar, "isVisible") instanceof Boolean visible && !visible) {
                continue;
            }
            if (Double.isNaN(height) || height < 8) {
                continue;
            }
            if (Double.isNaN(width)) {
                width = 8;
            }
            double score = max;
            if (!Double.isNaN(absX) && barLockX != 0.0 && barLockX >= absX - 24 && barLockX <= absX + width + 24) {
                score += 5000;
            }
            if (!Double.isNaN(absY) && guiY >= absY - 12 && guiY <= absY + height + 12) {
                score += 5000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = bar;
            }
        }
        return best;
    }

    /**
     * Moves the bar so its thumb follows the finger. The first call records
     * the full offset between the finger and the thumb centre and keeps the
     * thumb where it is: pressing anywhere on the track never makes it jump.
     */
    public static boolean applyThumb(Screen screen, double guiY) {
        Object bar = pickBar(screen, guiY);
        if (bar == null) {
            return false;
        }
        double max = Reflect.invokeNumber(bar, "getMaxValue", "getMax");
        double window = Reflect.invokeNumber(bar, "getWindow");
        double value = Reflect.invokeNumber(bar, "getValue");
        double height = Reflect.invokeNumber(bar, "getHeight");
        double absY = Reflect.invokeNumber(bar, "getAbsoluteY", "getY");
        if (Double.isNaN(max) || max <= 0 || Double.isNaN(height) || height < 8) {
            return false;
        }
        if (Double.isNaN(window) || window < 1) {
            window = 1;
        }
        if (Double.isNaN(value)) {
            value = 0;
        }
        if (Double.isNaN(absY)) {
            absY = 32;
        }
        double range = Math.max(1.0, max - window);
        double handle = Math.max(8.0, height * (window / Math.max(max, 1.0)));
        double travel = Math.max(1.0, height - handle);
        if (!thumbArmed) {
            double thumbCenter = absY + (value / range) * travel + handle * 0.5;
            thumbOffset = guiY - thumbCenter;
            thumbArmed = true;
            grabbedBar = bar;
            if (Debug.on()) Debug.log("TrenderCompat.thumb", "ARM offset=" + thumbOffset
                    + " value=" + value + " max=" + max + " window=" + window);
            return true;
        }
        double t = Math.max(0.0, Math.min(1.0, ((guiY - thumbOffset - absY) - handle * 0.5) / travel));
        int target = (int) Math.round(t * range);
        try {
            bar.getClass().getMethod("setValue", int.class).invoke(bar, target);
        } catch (Throwable ignored) {
            return false;
        }
        Object parent = Reflect.invokePublic(bar, "getParent");
        if (parent != null) {
            Reflect.invokePublic(parent, "layout");
        }
        if (Debug.on()) Debug.log("TrenderCompat.thumb", "SET value=" + target + " max=" + max + " y=" + guiY);
        return true;
    }

    /** Sends a synthetic pointer event to the screen at the locked bar. */
    public static void barPointer(Screen screen, double x, double y, Pointer action) {
        if (screen == null) {
            return;
        }
        try {
            MouseButtonEvent last = DeferredClick.lastPointerEvent();
            int button = last != null ? last.button() : 1;
            MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
            DeferredClick.setLastPointerEvent(event);
            switch (action) {
                case PRESS -> screen.mouseClicked(event, false);
                case DRAG -> screen.mouseDragged(event, 0.0, 0.0);
                case RELEASE -> screen.mouseReleased(event);
            }
        } catch (Throwable ignored) {
        }
    }

    public enum Pointer {
        PRESS, DRAG, RELEASE
    }
}
