package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * REI's config screen (REIConfigScreen). Its lists are REI widgets around
 * Cloth Config's ScrollingContainer, not AbstractScrollArea: the page scrolls
 * through mouseScrolled (one unit = Cloth's scroll step), and the bar thumbs
 * are driven here. The containers are only held by the widgets' fields
 * (captured by REI's anonymous scroller widgets), so the widget tree is
 * searched for fields of that type.
 */
public final class ReiCompat {
    private ReiCompat() {
    }

    private static final String SCROLLING_CONTAINER = "me.shedaniel.clothconfig2.api.scroll.ScrollingContainer";

    /** Finger margin left of the 6 px bar, and right of it. */
    private static final double BAR_PAD_LEFT = 10.0;
    private static final double BAR_PAD_RIGHT = 4.0;

    /** ScrollingContainer fields of a widget class (its whole hierarchy). */
    private static final ClassValue<Field[]> CONTAINER_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Reflect.extendsClass(f.getType(), SCROLLING_CONTAINER)) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        fields.add(f);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return fields.toArray(new Field[0]);
        }
    };

    private static Object grabbedContainer;

    public static void resetGrab() {
        grabbedContainer = null;
    }

    private static List<Object> containers(Screen screen) {
        List<Object> out = new ArrayList<>();
        collect(screen, new IdentityHashMap<>(), out);
        return out;
    }

    private static void collect(Object node, IdentityHashMap<Object, Boolean> seen, List<Object> out) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 1024) {
            return;
        }
        for (Field f : CONTAINER_FIELDS.get(node.getClass())) {
            try {
                Object c = f.get(node);
                if (c != null && !out.contains(c)) {
                    out.add(c);
                }
            } catch (Throwable ignored) {
            }
        }
        if (node instanceof ContainerEventHandler container) {
            try {
                for (GuiEventListener child : container.children()) {
                    collect(child, seen, out);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** {x, y, width, height} of a container's bounds (a Cloth math Rectangle). */
    private static double[] bounds(Object container) {
        Object r = Reflect.invokePublic(container, "getBounds");
        if (r == null) {
            return null;
        }
        double x = Reflect.readNumber(r, "x");
        double y = Reflect.readNumber(r, "y");
        double w = Reflect.readNumber(r, "width");
        double h = Reflect.readNumber(r, "height");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(w) || Double.isNaN(h) || h < 8) {
            return null;
        }
        return new double[] {x, y, w, h};
    }

    private static double maxScroll(Object container) {
        return Reflect.invokeNumber(container, "getMaxScroll");
    }

    /** The container whose bar is under the point (with a finger margin). */
    private static Object barAt(Screen screen, double x, double y) {
        for (Object c : containers(screen)) {
            double[] d = bounds(c);
            double max = maxScroll(c);
            if (d == null || Double.isNaN(max) || max <= 0) {
                continue;
            }
            double right = d[0] + d[2];
            double barLeft = right - 6.0;
            if (x >= barLeft - BAR_PAD_LEFT && x <= right + BAR_PAD_RIGHT && y >= d[1] && y <= d[1] + d[3]) {
                return c;
            }
        }
        return null;
    }

    public static boolean isBarHover(Screen screen, double x, double y) {
        return screen != null && barAt(screen, x, y) != null;
    }

    /** Moves the held container so its thumb follows the finger. */
    public static boolean applyThumb(Screen screen, double guiX, double guiY) {
        if (screen == null) {
            return false;
        }
        Object c = grabbedContainer != null ? grabbedContainer : barAt(screen, guiX, guiY);
        if (c == null) {
            return false;
        }
        grabbedContainer = c;
        double[] d = bounds(c);
        double max = maxScroll(c);
        if (d == null || Double.isNaN(max) || max <= 0) {
            return false;
        }
        double current = Reflect.invokeNumber(c, "scrollAmount");
        if (Double.isNaN(current)) {
            current = 0.0;
        }
        double target = ClothCompat.THUMB.map(guiY, d[1], d[1] + d[3], current, max);
        try {
            Method m = Reflect.publicMethod(c.getClass(), "scrollTo", double.class, boolean.class);
            if (m != null) {
                m.invoke(c, target, false);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
