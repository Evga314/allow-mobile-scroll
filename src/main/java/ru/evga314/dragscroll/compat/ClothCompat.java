package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.touch.Widgets;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/**
 * Cloth Config. Its entry list is not an AbstractScrollArea and its scroll
 * bar misses touch input, so the mod scrolls the page through mouseScrolled
 * and drives the bar thumb itself.
 */
public final class ClothCompat {
    private ClothCompat() {
    }

    /** The thumb grab, shared with Shulker Box Tooltip. */
    public static final ThumbGrab THUMB = new ThumbGrab();

    /**
     * Width of the right-hand band of a Cloth screen that belongs to the
     * scroll bar (the bar plus a fat-finger margin).
     */
    public static final int BAR_BAND_WIDTH = 120;

    /**
     * How long after a press the touch still counts as held when the
     * launcher's release arrives before the first movement.
     */
    private static final long SYNTHETIC_TOUCH_NS = 1_000_000_000L;

    private static Screen touchScreen;
    private static long touchPressNs;

    private static Method scrollStep;
    private static boolean scrollStepResolved;

    // =====================================================================
    // Touch window
    // =====================================================================

    /** A press on a Cloth screen: the touch counts as held for a moment. */
    public static void armTouch(Screen screen) {
        touchScreen = screen;
        touchPressNs = System.nanoTime();
    }

    public static void disarmTouch() {
        touchScreen = null;
        touchPressNs = 0L;
    }

    /**
     * True shortly after a press on this Cloth screen, even when the launcher
     * already reported the release: Cloth still expects the drag to go on.
     */
    public static boolean isSyntheticTouch(Screen screen) {
        return touchScreen != null && touchScreen == screen
                && System.nanoTime() - touchPressNs < SYNTHETIC_TOUCH_NS;
    }

    // =====================================================================
    // Scroll step
    // =====================================================================

    /**
     * Content pixels one mouseScrolled unit moves, read from Cloth itself
     * (16 px in 26.3), or -1 when unknown. With the generic 12 px the lists
     * ran a third ahead of the finger.
     */
    public static double scrollStep(Object anyClothObject) {
        if (!scrollStepResolved) {
            scrollStepResolved = true;
            try {
                Class<?> init = Class.forName("me.shedaniel.clothconfig2.ClothConfigInitializer", false,
                        anyClothObject.getClass().getClassLoader());
                Method m = init.getMethod("getScrollStep");
                if (m.getReturnType() == double.class && Modifier.isStatic(m.getModifiers())) {
                    scrollStep = m;
                }
            } catch (Throwable ignored) {
            }
        }
        if (scrollStep == null) {
            return -1.0;
        }
        try {
            return (Double) scrollStep.invoke(null);
        } catch (Throwable t) {
            return -1.0;
        }
    }

    // =====================================================================
    // Scroll bar
    // =====================================================================

    /** The finger is on (or right next to) the bar of Cloth's entry list. */
    public static boolean isScrollbarHover(Screen screen, double x, double y) {
        return findScrollbar(screen, x, y, new IdentityHashMap<>());
    }

    /** The finger is on the bar, or anywhere in the band at the right edge. */
    public static boolean isBarBand(Screen screen, double x, double y) {
        return isScrollbarHover(screen, x, y) || x >= screen.width - BAR_BAND_WIDTH;
    }

    private static boolean isListWidget(Object node) {
        String name = Widgets.lowerName(node);
        return name.contains("clothconfig")
                && (name.contains("listwidget") || name.contains("entrylist") || name.contains("dynamic"));
    }

    private static boolean findScrollbar(Object node, double x, double y, IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 24) {
            return false;
        }
        if (isListWidget(node)) {
            double left;
            double top;
            double right;
            double bottom;
            if (node instanceof AbstractWidget w) {
                left = w.getX();
                top = w.getY();
                right = w.getX() + w.getWidth();
                bottom = w.getY() + w.getHeight();
            } else {
                left = Reflect.readNumber(node, "left", "x", "posX", "x0");
                top = Reflect.readNumber(node, "top", "y", "posY", "y0");
                right = Reflect.readNumber(node, "right", "x1");
                bottom = Reflect.readNumber(node, "bottom", "y1");
                double width = Reflect.readNumber(node, "width");
                double height = Reflect.readNumber(node, "height");
                if (Double.isNaN(right) && !Double.isNaN(left) && !Double.isNaN(width)) {
                    right = left + width;
                }
                if (Double.isNaN(bottom) && !Double.isNaN(top) && !Double.isNaN(height)) {
                    bottom = top + height;
                }
            }
            if (!Double.isNaN(left) && !Double.isNaN(right) && !Double.isNaN(top) && !Double.isNaN(bottom)
                    && x >= right - 20.0 && x <= right + 4.0 && y >= top && y <= bottom) {
                return true;
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (findScrollbar(child, x, y, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object findListWidget(Object node, IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 24) {
            return null;
        }
        if (isListWidget(node)) {
            return node;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                Object found = findListWidget(child, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Moves the list so the grabbed thumb follows the finger at {@code guiY}. */
    public static boolean applyThumb(Screen screen, double guiY) {
        if (screen == null) {
            return false;
        }
        Object list = findListWidget(screen, new IdentityHashMap<>());
        if (list == null) {
            list = screen;
        }
        double top;
        double bottom;
        if (list instanceof AbstractWidget w) {
            top = w.getY();
            bottom = w.getY() + w.getHeight();
        } else {
            top = Reflect.readNumber(list, "top", "y", "posY", "y0");
            bottom = Reflect.readNumber(list, "bottom", "y1");
            double height = Reflect.readNumber(list, "height");
            if (Double.isNaN(bottom) && !Double.isNaN(top) && !Double.isNaN(height)) {
                bottom = top + height;
            }
        }
        if (Double.isNaN(top) || Double.isNaN(bottom) || bottom - top < 8.0) {
            top = 32;
            bottom = Math.max(top + 8, screen.height - 32);
        }
        double max = Reflect.invokeNumber(list, "getMaxScroll", "getMaxScrollPosition", "getMaxScrollAmount");
        if (Double.isNaN(max) || max <= 0) {
            max = Reflect.readNumber(list, "maxScroll", "maxScrollPosition");
        }
        if (Double.isNaN(max) || max <= 0) {
            return false;
        }
        double current = Reflect.invokeNumber(list, "getScroll", "getScrollAmount",
                "getScrollPosition", "getMaxScrollPosition");
        if (Double.isNaN(current)) {
            current = Reflect.readNumber(list, "scroll", "scrollAmount", "target", "scrollTarget");
        }
        if (Double.isNaN(current)) {
            current = 0;
        }
        double target = THUMB.map(guiY, top, bottom, current, max);
        Reflect.invokeScrollSetter(list, target);
        Reflect.writeNumber(list, target, "scroll", "scrollAmount", "target", "scrollTarget", "pendingScroll");
        return true;
    }
}
