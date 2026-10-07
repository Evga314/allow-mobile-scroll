package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.touch.Widgets;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/**
 * Reese's Sodium Options. Its frames scroll through their own ScrollBarWidget,
 * whose mouseScrolled casts the amount to int (6 px per unit): the fractional
 * units of a finger drag did nothing, so small swipes never moved the list.
 * The mod sets the bar offset itself instead and drives the thumb, which the
 * launcher's late press otherwise turned into a jump to the finger.
 */
public final class ReesesCompat {
    private ReesesCompat() {
    }

    /** Content pixels one mouseScrolled unit moves (ScrollBarWidget.SCROLL_STEP). */
    public static final double PIXELS_PER_UNIT = 6.0;

    /** Extra hit box around the thin bar, in bar widths. */
    private static final double BAR_PAD_LEFT = 0.6;
    private static final double BAR_PAD_RIGHT = 1.0;

    private static Object grabbedBar;
    /** Sub-pixel rest of the drag, the bar offset is an int. */
    private static double carry;

    public static void resetGrab() {
        grabbedBar = null;
    }

    // =====================================================================
    // Bars
    // =====================================================================

    private static boolean isVerticalBar(Object node) {
        if (!Widgets.lowerName(node).endsWith("scrollbarwidget")) {
            return false;
        }
        Object mode = Reflect.read(node, "mode");
        return mode instanceof Enum<?> e && e.name().equals("VERTICAL");
    }

    private static ScreenRectangle rect(Object bar) {
        return bar instanceof GuiEventListener listener ? listener.getRectangle() : null;
    }

    /** The vertical bar whose frame (or the bar itself) contains the point. */
    private static Object barFor(Screen screen, double x, double y) {
        return find(screen, x, y, false, new IdentityHashMap<>());
    }

    /** The vertical bar under the point, with a finger margin. */
    private static Object barAt(Screen screen, double x, double y) {
        return find(screen, x, y, true, new IdentityHashMap<>());
    }

    private static Object find(Object node, double x, double y, boolean onBar, IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 512) {
            return null;
        }
        if (isVerticalBar(node) && (onBar ? isOnBar(node, x, y) : isInScrollArea(node, x, y))) {
            return node;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                Object found = find(child, x, y, onBar, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isOnBar(Object bar, double x, double y) {
        ScreenRectangle r = rect(bar);
        if (r == null || r.width() <= 0 || r.height() <= 0) {
            return false;
        }
        return x >= r.left() - r.width() * BAR_PAD_LEFT && x < r.right() + r.width() * BAR_PAD_RIGHT
                && y >= r.top() && y < r.bottom();
    }

    /** The bar's scroll area: its frame's viewport (extraScrollArea), or the bar. */
    private static boolean isInScrollArea(Object bar, double x, double y) {
        Object area = Reflect.read(bar, "extraScrollArea");
        if (area != null) {
            double ax = Reflect.invokeNumber(area, "x");
            double ay = Reflect.invokeNumber(area, "y");
            double aw = Reflect.invokeNumber(area, "width");
            double ah = Reflect.invokeNumber(area, "height");
            if (!Double.isNaN(ax) && !Double.isNaN(ay) && !Double.isNaN(aw) && !Double.isNaN(ah)
                    && x >= ax && x < ax + aw && y >= ay && y < ay + ah) {
                return true;
            }
        }
        return isOnBar(bar, x, y);
    }

    public static boolean isBarHover(Screen screen, double x, double y) {
        return screen != null && barAt(screen, x, y) != null;
    }

    private static int offset(Object bar) {
        double v = Reflect.invokeNumber(bar, "getOffset");
        return Double.isNaN(v) ? 0 : (int) v;
    }

    private static void setOffset(Object bar, int value) {
        try {
            Method m = Reflect.publicMethod(bar.getClass(), "setOffset", int.class);
            if (m != null) {
                m.invoke(bar, value);
            }
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // List
    // =====================================================================

    /**
     * Scrolls the frame at (x, y) by {@code units} mouseScrolled units, with
     * the same sign as Reese's own mouseScrolled (offset - units * 6), but
     * without dropping the fractions. Returns false when no frame is there.
     */
    public static boolean scroll(Screen screen, double x, double y, double units) {
        Object bar = barFor(screen, x, y);
        if (bar == null) {
            return false;
        }
        carry += units * PIXELS_PER_UNIT;
        int step = (int) carry;
        if (step != 0) {
            carry -= step;
            setOffset(bar, offset(bar) - step);
        }
        return true;
    }

    // =====================================================================
    // Thumb
    // =====================================================================

    /** Moves the held bar so its thumb follows the finger. */
    public static boolean applyThumb(Screen screen, double guiX, double guiY) {
        if (screen == null) {
            return false;
        }
        Object bar = grabbedBar != null ? grabbedBar : barAt(screen, guiX, guiY);
        if (bar == null) {
            return false;
        }
        grabbedBar = bar;
        ScreenRectangle r = rect(bar);
        double max = Reflect.readNumber(bar, "maxContentOffset");
        if (r == null || Double.isNaN(max) || max <= 0) {
            return false;
        }
        double target = ClothCompat.THUMB.map(guiY, r.top(), r.bottom(), offset(bar), max);
        setOffset(bar, (int) Math.round(target));
        return true;
    }
}
