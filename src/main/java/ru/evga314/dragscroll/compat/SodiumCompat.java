package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.touch.Widgets;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Sodium's option screens: a page list on the left and an option list on the
 * right, each with its own ScrollbarWidget. Neither is an AbstractScrollArea.
 * The page scrolls through mouseScrolled; the bar thumbs are driven here.
 */
public final class SodiumCompat {
    private SodiumCompat() {
    }

    /** Sodium widgets right of this x belong to the option list, whose bar gets a wider hit box. */
    public static final int OPTION_COLUMN_X = 140;

    /** The bar held by the current touch. */
    public static Object grabbedBar;

    /**
     * A screen-level scroll session is locked to the column it started in,
     * so later mouseScrolled calls go to the same list.
     */
    public static boolean contentLocked;
    public static double lockX;
    public static double lockY;

    private static boolean thumbArmed;
    private static double thumbOffset;

    /** Locks the session to the column of the press, unless already locked. */
    public static void lockContent(double x, double y) {
        if (!contentLocked) {
            lockX = x;
            lockY = y;
            contentLocked = true;
        }
    }

    public static void unlockContent() {
        contentLocked = false;
        lockX = 0.0;
        lockY = 0.0;
    }

    /** New touch: forget the grabbed bar. */
    public static void resetGrab() {
        grabbedBar = null;
        thumbArmed = false;
    }

    public static void reset() {
        resetGrab();
        contentLocked = false;
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    /**
     * {x, y, width, height} of a Sodium widget, or null. Sodium widgets
     * report their box through GuiEventListener.getRectangle().
     */
    static double[] bounds(Object node) {
        if (node instanceof AbstractWidget w) {
            return new double[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()};
        }
        if (node instanceof GuiEventListener listener) {
            try {
                ScreenRectangle r = listener.getRectangle();
                if (r.width() > 0 || r.height() > 0) {
                    return r.height() < 4 ? null : new double[] {r.left(), r.top(), r.width(), r.height()};
                }
            } catch (Throwable ignored) {
            }
        }
        // Widgets without a rectangle: public getters, if any.
        if (node == null) {
            return null;
        }
        double x = Reflect.invokeNumber(node, "getX");
        double y = Reflect.invokeNumber(node, "getY");
        double w = Reflect.invokeNumber(node, "getWidth");
        double h = Reflect.invokeNumber(node, "getHeight");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(w) || Double.isNaN(h) || h < 4) {
            return null;
        }
        return new double[] {x, y, w, h};
    }

    /**
     * Hit box of Sodium's own scroll bar widgets (their isMouseOver): +40 % of
     * the width to the left and +140 % to the right for the option list, so a
     * finger can grab the thin bar.
     */
    public static boolean isOverWideBar(GuiEventListener bar, double mouseX, double mouseY) {
        ScreenRectangle r = bar.getRectangle();
        int w = r.width() <= 0 ? 5 : r.width();
        boolean optionColumn = r.left() > OPTION_COLUMN_X;
        double padL = optionColumn ? w * 0.40 : 0;
        double padR = optionColumn ? w * 1.40 : 0;
        return mouseX >= r.left() - padL && mouseX < r.left() + w + padR
                && mouseY >= r.top() && mouseY < r.top() + r.height();
    }

    /**
     * Hit box of a bar with box {@code d}: the option list's bar is widened,
     * mostly to the right; other bars get {@code narrowPadLeft} / 6 px.
     */
    private static boolean pointOnBar(double[] d, double width, double x, double y, double narrowPadLeft) {
        boolean optionColumn = d[0] > OPTION_COLUMN_X;
        double padL = optionColumn ? width * 0.40 : narrowPadLeft;
        double padR = optionColumn ? width * 1.40 : 6;
        return x >= d[0] - padL && x <= d[0] + width + padR && y >= d[1] && y <= d[1] + d[3];
    }

    public static boolean isScrollbarHover(Screen screen, double x, double y) {
        if (screen == null) {
            return false;
        }
        double[] d = bounds(barByColumn(screen, x));
        if (d != null && pointOnBar(d, Math.max(1, d[2]), x, y, 6)) {
            return true;
        }
        return findBarAt(screen, x, y) != null;
    }

    /** The bar of the list in the column of {@code guiX}. */
    private static Object barByColumn(Screen screen, double guiX) {
        Object pageList = findNamed(screen, "pagelistwidget", new IdentityHashMap<>());
        Object optionList = findNamed(screen, "optionlistwidget", new IdentityHashMap<>());
        Object pageBar = pageList == null ? null : Reflect.read(pageList, "scrollbar");
        Object optionBar = optionList == null ? null : Reflect.read(optionList, "scrollbar");
        if (pageBar == null && optionBar == null) {
            return null;
        }
        int splitX = 160;
        double[] optionDim = bounds(optionList);
        double[] pageDim = bounds(pageList);
        if (optionDim != null) {
            splitX = (int) optionDim[0];
        } else if (pageDim != null) {
            splitX = (int) (pageDim[0] + pageDim[2]);
        }
        if (guiX < splitX) {
            return pageBar != null ? pageBar : optionBar;
        }
        return optionBar != null ? optionBar : pageBar;
    }

    private static Object findNamed(Object node, String classNeedle, IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 64) {
            return null;
        }
        if (Widgets.lowerName(node).contains(classNeedle)) {
            return node;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                Object found = findNamed(child, classNeedle, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The bar under the point whose centre is closest to it horizontally. */
    private static Object findBarAt(Screen screen, double x, double y) {
        List<Object> bars = new ArrayList<>();
        collectBars(screen, new IdentityHashMap<>(), bars);
        Object best = null;
        double bestDist = Double.MAX_VALUE;
        for (Object bar : bars) {
            double[] d = bounds(bar);
            if (d == null || !pointOnBar(d, d[2], x, y, 4)) {
                continue;
            }
            double distX = Math.abs(x - (d[0] + d[2] * 0.5));
            if (distX < bestDist) {
                bestDist = distX;
                best = bar;
            }
        }
        return best;
    }

    private static void collectBars(Object node, IdentityHashMap<Object, Boolean> seen, List<Object> out) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 256) {
            return;
        }
        String name = Widgets.lowerName(node);
        if (name.contains("scrollbarwidget") || (name.contains("sodium") && name.contains("scrollbar"))) {
            out.add(node);
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                collectBars(child, seen, out);
            }
        }
    }

    // =====================================================================
    // Thumb
    // =====================================================================

    /**
     * Moves the grabbed bar so its thumb follows the finger. The first call
     * picks the bar (by column, else under the finger) and records the offset
     * between the finger and the thumb, so the thumb never jumps.
     */
    public static boolean applyThumb(Screen screen, double guiX, double guiY) {
        if (screen == null) {
            return false;
        }
        Object bar = grabbedBar;
        if (bar == null) {
            bar = barByColumn(screen, guiX);
        }
        if (bar == null) {
            bar = findBarAt(screen, guiX, guiY);
        }
        if (bar == null) {
            return false;
        }
        grabbedBar = bar;
        double[] dim = bounds(bar);
        double top = dim != null ? dim[1] : 32;
        double height = dim != null ? dim[3] : Math.max(8, screen.height - 64);
        if (height < 8) {
            height = Math.max(8, screen.height - 64);
        }
        double total = Reflect.readNumber(bar, "total");
        double visible = Reflect.readNumber(bar, "visible");
        if (Double.isNaN(total) || total <= 1) {
            return false;
        }
        if (Double.isNaN(visible) || visible <= 0) {
            visible = height;
        }
        double max = Math.max(1.0, total - visible);
        if (!thumbArmed) {
            double current = Reflect.readNumber(bar, "scrollAmount");
            if (Double.isNaN(current)) {
                current = Reflect.invokeNumber(bar, "getScrollAmount");
            }
            if (Double.isNaN(current)) {
                current = 0;
            }
            double visualT = Math.max(0.0, Math.min(1.0, current / max));
            thumbOffset = guiY - (top + visualT * height);
            thumbArmed = true;
            return true;
        }
        double t = Math.max(0.0, Math.min(1.0, (guiY - thumbOffset - top) / height));
        int target = (int) Math.round(t * max);
        if (!Reflect.invokeScrollSetter(bar, target)) {
            Reflect.writeNumber(bar, target, "scrollAmount");
        }
        return true;
    }
}
