package ru.evga314.dragscroll;

import net.minecraft.client.gui.screens.Screen;

/**
 * Drives masa/malilib GuiScrollBar the same way vanilla bars are driven:
 * grab on press with offset (no teleport), drag 1:1, release on lift.
 * malilib only starts its own drag if the cursor was on the thumb last frame
 * ({@code wasMouseOver}); a finger on the track then fails after the first
 * gesture. We set dragging and the value ourselves.
 */
public final class MalilibScrollbar {
    private static Object grabbedBar;
    private static int barTop;
    private static int barHeight;
    private static int maxValue;
    private static double grabOffsetY;

    private MalilibScrollbar() {
    }

    public static boolean grab(Screen screen, double x, double y) {
        release();
        Object list = findListWidget(screen);
        if (list == null) {
            return false;
        }
        Integer posX = intField(list, "posX");
        Integer browserWidth = intField(list, "browserWidth");
        Integer posY = intField(list, "posY");
        Integer browserHeight = intField(list, "browserHeight");
        if (posX == null || browserWidth == null || posY == null || browserHeight == null) {
            return false;
        }
        double left = posX + browserWidth - 20;
        double right = posX + browserWidth + 8;
        // also accept a screen-edge bar (litematica overlay layout)
        boolean onListBar = x >= left && x <= right && y >= posY && y <= posY + browserHeight;
        boolean onScreenEdge = screen.width > 0 && x >= screen.width - 22;
        if (!onListBar && !onScreenEdge) {
            return false;
        }

        Object bar = field(list, "scrollBar");
        if (bar == null) {
            bar = invoke(list, "getScrollbar");
        }
        if (bar == null) {
            return false;
        }
        grabbedBar = bar;
        barTop = posY;
        barHeight = Math.max(1, browserHeight);
        Object max = invoke(bar, "getMaxValue");
        if (!(max instanceof Number)) {
            max = field(bar, "maxValue");
        }
        maxValue = max instanceof Number n ? Math.max(0, n.intValue()) : 0;
        int current = readCurrentValue(bar);
        double currentT = maxValue > 0 ? current / (double) maxValue : 0.0;
        double thumbY = barTop + currentT * barHeight;
        grabOffsetY = y - thumbY;
        return true;
    }

    public static boolean isGrabbed() {
        return grabbedBar != null;
    }

    public static void dragTo(double y) {
        if (grabbedBar == null) {
            return;
        }
        applyY(y);
    }

    public static void release() {
        grabbedBar = null;
        maxValue = 0;
        grabOffsetY = 0.0;
    }

    private static int readCurrentValue(Object bar) {
        Object v = invoke(bar, "getValue");
        if (!(v instanceof Number)) {
            v = field(bar, "currentValue");
        }
        if (!(v instanceof Number)) {
            v = field(bar, "value");
        }
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static void applyY(double y) {
        if (grabbedBar == null || maxValue <= 0) {
            return;
        }
        double t = (y - grabOffsetY - barTop) / (double) barHeight;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        int value = (int) Math.round(t * maxValue);
        invoke(grabbedBar, "setValue", value);
        setInt(grabbedBar, "currentValue", value);
        setInt(grabbedBar, "value", value);
    }

    private static Object findListWidget(Object screen) {
        Object list = invoke(screen, "getListWidget");
        if (list != null) {
            return list;
        }
        return field(screen, "widget");
    }

    private static Object field(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            try {
                java.lang.reflect.Field f = walk.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void setInt(Object obj, String name, int value) {
        if (obj == null) {
            return;
        }
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            try {
                java.lang.reflect.Field f = walk.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.set(obj, value);
                }
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Integer intField(Object obj, String name) {
        Object v = field(obj, name);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Object invoke(Object obj, String name, Object... args) {
        if (obj == null) {
            return null;
        }
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Boolean) types[i] = boolean.class;
            else if (a instanceof Integer) types[i] = int.class;
            else if (a instanceof Double) types[i] = double.class;
            else types[i] = a.getClass();
        }
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            try {
                java.lang.reflect.Method m = walk.getMethod(name, types);
                m.setAccessible(true);
                return m.invoke(obj, args);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method m = walk.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(obj, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
