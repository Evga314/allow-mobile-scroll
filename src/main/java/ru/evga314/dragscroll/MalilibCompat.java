package ru.evga314.dragscroll;

import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Touch support for MaLiLib list screens (Litematica, MiniHUD, Tweakeroo,
 * Item Scroller and every other mod built on MaLiLib's GuiBase).
 *
 * MaLiLib lists are not AbstractScrollArea. A WidgetListBase scrolls by whole
 * entries: its GuiScrollBar value is the index of the first visible row, and
 * the bar itself is only moved from render(), relative to the mouse position
 * of the previous frame. On a touch launcher the cursor teleports to the
 * finger and the press arrives before any frame was drawn there, so MaLiLib's
 * own "was the mouse over the thumb last frame" test fails and the bar never
 * starts dragging.
 *
 * This class owns the whole touch on such a list:
 * <ul>
 *   <li>Thumb / track: the bar is driven with MaLiLib's exact geometry
 *       (same thumb height and travel as GuiScrollBar.render), so the thumb
 *       stays under the finger 1:1. A press on the track outside the thumb
 *       first centres the thumb on the finger.</li>
 *   <li>Content: a vertical drag moves the list 1:1 with the finger, in
 *       whole rows (the only granularity MaLiLib can draw), with inertia.</li>
 *   <li>Taps are deferred until the finger lifts, then replayed BEFORE the
 *       release reaches MaLiLib, so buttons, text fields and sliders get a
 *       normal press/release pair.</li>
 * </ul>
 * Everything is reflection-only: MaLiLib is an optional dependency, and if
 * its internals ever change the screen simply falls back to vanilla input.
 */
public final class MalilibCompat {
    private MalilibCompat() {
    }

    private static final String GUI_BASE = "fi.dy.masa.malilib.gui.GuiBase";
    private static final String GUI_LIST_BASE = "fi.dy.masa.malilib.gui.GuiListBase";
    private static final String WIDGET_LIST_BASE = "fi.dy.masa.malilib.gui.widgets.WidgetListBase";
    private static final String WIDGET_CONTAINER = "fi.dy.masa.malilib.gui.widgets.WidgetContainer";
    private static final String WIDGET_SLIDER = "fi.dy.masa.malilib.gui.widgets.WidgetSlider";
    private static final String WIDGET_DROPDOWN = "fi.dy.masa.malilib.gui.widgets.WidgetDropDownList";

    /** Click handling decided at press time, used by the Screen.mouseClicked redirect. */
    public static final int CLICK_VANILLA = 0;
    public static final int CLICK_DEFER = 1;
    public static final int CLICK_SWALLOW = 2;

    private enum Mode {
        /** No touch on a MaLiLib screen. */
        IDLE,
        /** The touch is left to MaLiLib untouched. */
        VANILLA,
        /** Finger down on list content; tap or drag not decided yet (click deferred). */
        PENDING,
        /** The touch has become a list drag. */
        CONTENT,
        /** The touch owns the list's scroll bar. */
        BAR
    }

    /** Grace period for a launcher RELEASE/PRESS pair in the middle of one finger hold. */
    private static final long BAR_REGRAB_NS = 50_000_000L;

    // --- Touch state ---
    private static Mode mode = Mode.IDLE;
    private static Screen touchScreen;
    private static Object touchList;
    private static double pressX;
    private static double pressY;
    private static double lastY;
    private static double contentPos;
    private static double entryHeight = 1.0;
    private static boolean pressOnSlider;
    private static int barStartValue;
    private static double barStartY;
    private static int barMax;
    private static int barTravel;
    private static Object regrabList;
    private static Screen regrabScreen;
    private static long barReleaseNs;

    // --- Inertia target ---
    private static Screen coastScreen;
    private static Object coastList;
    private static double coastPos;
    private static double coastEntryHeight = 1.0;

    // =====================================================================
    // Screen classification
    // =====================================================================

    private static final ClassValue<Boolean> MALILIB_SCREEN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return extendsClassNamed(type, GUI_BASE);
        }
    };

    /** True for any screen built on MaLiLib's GuiBase. */
    public static boolean isMalilibScreen(Screen screen) {
        return screen != null && MALILIB_SCREEN.get(screen.getClass());
    }

    private static boolean extendsClassNamed(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInstanceOfNamed(Object obj, String name) {
        return obj != null && extendsClassNamed(obj.getClass(), name);
    }

    // =====================================================================
    // Reflection handles (resolved once from the running MaLiLib)
    // =====================================================================

    private static boolean resolved;
    private static boolean unavailable;
    private static Class<?> listClass;
    private static Field fScrollBar;
    private static Field fListContents;
    private static Field fListWidgets;
    private static Field fPosX;
    private static Field fPosY;
    private static Field fBrowserWidth;
    private static Field fBrowserHeight;
    private static Field fEntriesStartY;
    private static Field fEntriesOffsetY;
    private static Field fBrowserEntryHeight;
    private static Method mEntryHeightFor;
    private static Method mGetValue;
    private static Method mSetValue;
    private static Method mGetMaxValue;
    private static Field fBarDragging;
    private static Field fBarMouseOver;
    private static Field fGuiListWidget;
    private static Field fGuiWidgets;
    private static Field fSubWidgets;
    private static Field fDropDownOpen;

    private static boolean resolve(Object list) {
        if (resolved) {
            return !unavailable;
        }
        resolved = true;
        try {
            Class<?> c = list.getClass();
            while (c != null && !c.getName().equals(WIDGET_LIST_BASE)) {
                c = c.getSuperclass();
            }
            if (c == null) {
                throw new IllegalStateException("WidgetListBase not found");
            }
            listClass = c;
            fScrollBar = field(c, "scrollBar");
            fListContents = field(c, "listContents");
            fListWidgets = field(c, "listWidgets");
            fPosX = field(c, "posX");
            fPosY = field(c, "posY");
            fBrowserWidth = field(c, "browserWidth");
            fBrowserHeight = field(c, "browserHeight");
            fEntriesStartY = field(c, "browserEntriesStartY");
            fEntriesOffsetY = field(c, "browserEntriesOffsetY");
            fBrowserEntryHeight = field(c, "browserEntryHeight");
            mEntryHeightFor = c.getDeclaredMethod("getBrowserEntryHeightFor", Object.class);
            mEntryHeightFor.setAccessible(true);

            Class<?> barClass = fScrollBar.getType();
            mGetValue = barClass.getMethod("getValue");
            mSetValue = barClass.getMethod("setValue", int.class);
            mGetMaxValue = barClass.getMethod("getMaxValue");
            fBarDragging = field(barClass, "dragging");
            fBarMouseOver = field(barClass, "mouseOver");
            return true;
        } catch (Throwable t) {
            unavailable = true;
            DragScrollClient.LOGGER.warn("MaLiLib list support disabled: unexpected MaLiLib version ({})", t.toString());
            return false;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Field declared by the first class in the hierarchy with the given name. */
    private static Field fieldOfNamedClass(Class<?> start, String className, String fieldName) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(className)) {
                try {
                    return field(c, fieldName);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Lists whose rows all have browserEntryHeight. Only lists that override
     * getBrowserEntryHeightFor need the per-row sum.
     */
    private static final ClassValue<Boolean> CONSTANT_ROW_HEIGHT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && !c.getName().equals(WIDGET_LIST_BASE); c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("getBrowserEntryHeightFor") && m.getParameterCount() == 1) {
                        return false;
                    }
                }
            }
            return true;
        }
    };

    private static int intField(Field f, Object obj) throws IllegalAccessException {
        return f.getInt(obj);
    }

    private static Object bar(Object list) throws IllegalAccessException {
        return fScrollBar.get(list);
    }

    private static int getValue(Object list) {
        try {
            return ((Number) mGetValue.invoke(bar(list))).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int getMax(Object list) {
        try {
            return ((Number) mGetMaxValue.invoke(bar(list))).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void setValue(Object list, int value) {
        try {
            Object bar = bar(list);
            if (((Number) mGetValue.invoke(bar)).intValue() != value) {
                // GuiScrollBar clamps to [0, max]; the list rebuilds its rows
                // on the next frame when it sees the new value.
                mSetValue.invoke(bar, value);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Stop MaLiLib from starting its own frame-delayed drag. Its hover flag is
     * last frame's cursor: after a touch it still says "over the thumb" while
     * the next finger is already somewhere else.
     */
    private static void clearBarState(Object list) {
        if (list == null || unavailable) {
            return;
        }
        try {
            Object bar = bar(list);
            fBarDragging.setBoolean(bar, false);
            fBarMouseOver.setBoolean(bar, false);
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // Finding the list and its geometry
    // =====================================================================

    private static Object findList(Screen screen) {
        try {
            if (fGuiListWidget == null) {
                fGuiListWidget = fieldOfNamedClass(screen.getClass(), GUI_LIST_BASE, "widget");
            }
            if (fGuiListWidget != null && fGuiListWidget.getDeclaringClass().isInstance(screen)) {
                Object w = fGuiListWidget.get(screen);
                if (isInstanceOfNamed(w, WIDGET_LIST_BASE)) {
                    return w;
                }
            }
        } catch (Throwable ignored) {
        }
        // A GuiBase that keeps its list in a field of its own.
        for (Class<?> c = screen.getClass(); c != null && !c.getName().equals(GUI_BASE); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (isInstanceOfNamed(v, WIDGET_LIST_BASE)) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** Snapshot of WidgetListBase + GuiScrollBar geometry, mirroring their render code. */
    private static final class Geom {
        int posX;
        int posY;
        int width;
        int height;
        int barX;
        int barY;
        int barH;
        int thumbH;
        int travel;
        int thumbTop;
        int value;
        int max;
        double rowHeight;

        boolean onBarColumn(double x, double y) {
            // The bar is 8 px wide. Rows end 2 px left of it, the screen
            // margin is to its right: widen only where nothing else lives.
            return x >= barX - 2 && x <= barX + 8 + 10 && y >= barY - 2 && y <= barY + barH + 2;
        }

        boolean onThumb(double y) {
            return y >= thumbTop - 3 && y <= thumbTop + thumbH + 3;
        }

        boolean inList(double x, double y) {
            return x >= posX && x < posX + width && y >= posY && y < posY + height;
        }
    }

    private static Geom geom(Object list) {
        try {
            Geom g = new Geom();
            g.posX = intField(fPosX, list);
            g.posY = intField(fPosY, list);
            g.width = intField(fBrowserWidth, list);
            g.height = intField(fBrowserHeight, list);
            int offsetY = intField(fEntriesOffsetY, list);
            int rowH = Math.max(1, intField(fBrowserEntryHeight, list));

            // WidgetListBase.drawContents
            int scrollbarHeight = g.height - offsetY - 8;
            List<?> contents = (List<?>) fListContents.get(list);
            int count = contents == null ? 0 : contents.size();
            long total;
            if (count == 0 || CONSTANT_ROW_HEIGHT.get(list.getClass())) {
                total = (long) count * rowH;
            } else {
                total = 0;
                for (Object entry : contents) {
                    total += ((Number) mEntryHeightFor.invoke(list, entry)).intValue();
                }
            }
            int totalHeight = (int) Math.min(Integer.MAX_VALUE, Math.max(total, scrollbarHeight));
            g.rowHeight = count > 0 ? Math.max(1.0, (double) total / count) : rowH;
            g.barX = g.posX + g.width - 9;
            g.barY = intField(fEntriesStartY, list) + offsetY;
            g.barH = scrollbarHeight;

            // GuiScrollBar.render
            int slideHeight = scrollbarHeight - 2;
            if (slideHeight <= 0 || totalHeight <= 0) {
                return null;
            }
            float relative = Math.min(1.0F, (float) slideHeight / (float) totalHeight);
            g.thumbH = (int) (relative * slideHeight);
            g.travel = slideHeight - g.thumbH;
            g.value = getValue(list);
            g.max = getMax(list);
            g.thumbTop = g.barY + 1 + (g.max > 0 ? (int) ((g.value / (float) g.max) * g.travel) : 0);
            return g;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean anyOpenDropDown(Screen screen) {
        try {
            if (fGuiWidgets == null) {
                fGuiWidgets = fieldOfNamedClass(screen.getClass(), GUI_BASE, "widgets");
            }
            if (fGuiWidgets != null) {
                Object widgets = fGuiWidgets.get(screen);
                if (widgets instanceof List<?> l) {
                    for (Object w : l) {
                        if (isOpenDropDown(w)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isOpenDropDown(Object w) {
        if (!isInstanceOfNamed(w, WIDGET_DROPDOWN)) {
            return false;
        }
        try {
            if (fDropDownOpen == null) {
                fDropDownOpen = fieldOfNamedClass(w.getClass(), WIDGET_DROPDOWN, "isOpen");
            }
            return fDropDownOpen != null && fDropDownOpen.getBoolean(w);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the press landed on a MaLiLib slider inside one of the visible rows. */
    private static boolean sliderAt(Object list, double x, double y) {
        try {
            Object rows = fListWidgets.get(list);
            if (!(rows instanceof List<?> l)) {
                return false;
            }
            for (Object row : l) {
                if (containsSliderAt(row, (int) x, (int) y, 0)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean containsSliderAt(Object widget, int x, int y, int depth) {
        if (widget == null || depth > 4) {
            return false;
        }
        if (isInstanceOfNamed(widget, WIDGET_SLIDER)) {
            try {
                Method over = widget.getClass().getMethod("isMouseOver", int.class, int.class);
                return Boolean.TRUE.equals(over.invoke(widget, x, y));
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (isInstanceOfNamed(widget, WIDGET_CONTAINER)) {
            try {
                if (fSubWidgets == null) {
                    fSubWidgets = fieldOfNamedClass(widget.getClass(), WIDGET_CONTAINER, "subWidgets");
                }
                Object subs = fSubWidgets == null ? null : fSubWidgets.get(widget);
                if (subs instanceof List<?> l) {
                    for (Object sub : l) {
                        if (containsSliderAt(sub, x, y, depth + 1)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // =====================================================================
    // Touch lifecycle (called from MouseHandlerMixin)
    // =====================================================================

    /**
     * Finger down. Runs before Minecraft dispatches the click.
     *
     * @param alreadyHeld true when no release was seen since the previous
     *                    press (the launcher re-sent PRESS mid-hold)
     */
    public static void onPress(Screen screen, double x, double y, boolean alreadyHeld) {
        long now = System.nanoTime();
        DragScrollState.cancelPendingClick();
        DragScrollState.active = false;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = null;
        DragScrollState.fallbackScrollScreen = null;
        DragScrollState.currentTouchDragged = false;
        DragScrollState.nativeSliderHeld = false;
        DragScrollState.sliderGestureLocked = false;
        DragScrollState.clearPressSnapshot();

        // Any touch stops a coasting list at once; a following fast swipe
        // may still stack on the residual velocity.
        if (DragScrollState.inertiaActive
                || Math.abs(DragScrollState.scrollVelocity) > DragScrollState.INERTIA_STOP) {
            DragScrollState.stopCoastKeepResidual();
        }
        coastList = null;
        coastScreen = null;

        // Same finger still on the bar: the launcher sent PRESS again without
        // a release, or a RELEASE/PRESS pair a few milliseconds apart. Keep
        // the grab, re-based on the current thumb position.
        boolean sameHold = mode == Mode.BAR && alreadyHeld && touchScreen == screen;
        boolean quickRegrab = regrabList != null && regrabScreen == screen
                && now - barReleaseNs < BAR_REGRAB_NS;
        if ((sameHold || quickRegrab) && !unavailable) {
            Object list = sameHold ? touchList : regrabList;
            Geom g = list == null ? null : geom(list);
            if (g != null && g.max > 0 && g.travel > 0) {
                enterBar(screen, list, g, y, false);
                regrabList = null;
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat", "BAR_REGRAB value=" + g.value);
                return;
            }
        }
        regrabList = null;
        regrabScreen = null;

        mode = Mode.VANILLA;
        touchScreen = screen;
        touchList = null;
        pressX = x;
        pressY = y;
        lastY = y;
        pressOnSlider = false;
        DragScrollState.nativeControlHeld = false;
        DragScrollState.nativeScrollbarHeld = false;

        Object list = findList(screen);
        if (list == null || !resolve(list)) {
            return;
        }
        clearBarState(list);
        if (anyOpenDropDown(screen)) {
            // An open drop-down lies on top of the list; it gets the raw touch.
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat", "OPEN_DROPDOWN_VANILLA");
            return;
        }
        Geom g = geom(list);
        if (g == null || g.max <= 0) {
            // Nothing to scroll: the screen behaves exactly like without this mod.
            return;
        }
        if (g.travel > 0 && g.onBarColumn(x, y)) {
            enterBar(screen, list, g, y, !g.onThumb(y));
            return;
        }
        if (g.inList(x, y)) {
            mode = Mode.PENDING;
            touchList = list;
            entryHeight = g.rowHeight;
            pressOnSlider = sliderAt(list, x, y);
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat",
                    "PENDING value=" + g.value + " max=" + g.max + " row=" + g.rowHeight
                            + " slider=" + pressOnSlider);
        }
    }

    private static void enterBar(Screen screen, Object list, Geom g, double y, boolean jump) {
        mode = Mode.BAR;
        touchScreen = screen;
        touchList = list;
        barMax = g.max;
        barTravel = g.travel;
        int value = g.value;
        if (jump) {
            // Track press: centre the thumb on the finger, then drag from there.
            double t = (y - (g.barY + 1) - g.thumbH / 2.0) / g.travel;
            value = clamp((int) Math.round(t * g.max), 0, g.max);
            setValue(list, value);
        }
        barStartValue = value;
        barStartY = y;
        clearBarState(list);
        DragScrollState.nativeControlHeld = true;
        DragScrollState.nativeScrollbarHeld = true;
        DragScrollState.stopInertia();
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat",
                "BAR_GRAB value=" + value + " max=" + g.max + " travel=" + g.travel
                        + " thumbTop=" + g.thumbTop + " thumbH=" + g.thumbH + " jump=" + jump);
    }

    /** How the Screen.mouseClicked call of this press must be dispatched. */
    public static int clickDecision(Screen screen) {
        if (screen != touchScreen) {
            return CLICK_VANILLA;
        }
        return switch (mode) {
            case BAR -> CLICK_SWALLOW;
            case PENDING -> CLICK_DEFER;
            default -> CLICK_VANILLA;
        };
    }

    /** Cursor moved (raw onMove, already converted to GUI coordinates). */
    public static void onMove(Screen screen, double x, double y) {
        if (screen != touchScreen || !DragScrollState.leftButtonHeld) {
            return;
        }
        switch (mode) {
            case BAR -> applyBar(y);
            case CONTENT -> applyContent(y);
            case PENDING -> classify(x, y);
            default -> {
            }
        }
    }

    /** Once per frame, before Minecraft handles the accumulated movement. */
    public static void onFrame(Screen screen, double x, double y) {
        if (touchScreen != null && screen != touchScreen) {
            reset();
            return;
        }
        if (DragScrollState.leftButtonHeld) {
            if (mode == Mode.BAR) {
                applyBar(y);
            }
        } else if (DragScrollState.inertiaActive && coastList != null) {
            DragScrollClient.applyInertiaFrame(screen);
        }
    }

    private static void classify(double x, double y) {
        double dx = x - pressX;
        double dy = y - pressY;
        double threshold = DragScrollConfig.getDragThreshold();

        // A clearly horizontal move that started on a slider is a slider
        // adjustment: give the slider its press now and step out of the way.
        double horizThreshold = Math.max(threshold * 1.75, 7.0);
        if (pressOnSlider && Math.abs(dx) >= horizThreshold && Math.abs(dx) > Math.abs(dy) * 1.15) {
            mode = Mode.VANILLA;
            clearBarState(touchList);
            DragScrollState.finishPendingClick();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat", "SLIDER_ENGAGE dx=" + dx + " dy=" + dy);
            return;
        }

        double vertThreshold = pressOnSlider ? Math.max(threshold * 1.5, 6.0) : threshold;
        double dominance = pressOnSlider ? 1.15 : 0.35;
        if (Math.abs(dy) < vertThreshold || Math.abs(dy) <= Math.abs(dx) * dominance) {
            return;
        }

        mode = Mode.CONTENT;
        DragScrollState.cancelPendingClick();
        DragScrollState.currentTouchDragged = true;
        DragScrollState.catchInertiaForDrag();
        contentPos = getValue(touchList) * entryHeight;
        // Measure from the press point, so the travel below the threshold is
        // not lost and the rows keep their position under the finger.
        lastY = pressY;
        applyContent(y);
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat", "CONTENT_DRAG dy=" + dy);
    }

    private static void applyContent(double y) {
        double step = y - lastY;
        lastY = y;
        if (step == 0.0 || touchList == null) {
            return;
        }
        int max = getMax(touchList);
        contentPos = clampD(contentPos - step, 0.0, max * entryHeight);
        setValue(touchList, clamp((int) Math.round(contentPos / entryHeight), 0, max));
        DragScrollState.pushVelocitySample(step);
    }

    private static void applyBar(double y) {
        if (touchList == null || barTravel <= 0) {
            return;
        }
        // GuiScrollBar.handleDrag: value = start + dy * max / travel. Rounded
        // instead of truncated so the thumb is centred on the finger both ways.
        double v = barStartValue + (y - barStartY) * barMax / (double) barTravel;
        setValue(touchList, clamp((int) Math.round(v), 0, barMax));
    }

    /**
     * Finger up. Runs before Minecraft dispatches the release, so a replayed
     * tap reaches MaLiLib as a normal press followed by this release.
     */
    public static void onRelease(Screen screen) {
        Mode ended = mode;
        if (screen == touchScreen) {
            switch (ended) {
                case BAR -> {
                    clearBarState(touchList);
                    regrabList = touchList;
                    regrabScreen = screen;
                    barReleaseNs = System.nanoTime();
                    DragScrollState.stopInertia();
                }
                case PENDING -> {
                    clearBarState(touchList);
                    DragScrollState.finishPendingClick();
                    DragScrollState.stopInertia();
                }
                case CONTENT -> {
                    double v = DragScrollState.computeReleaseVelocity();
                    if (Math.abs(v) > 0.01) {
                        coastScreen = screen;
                        coastList = touchList;
                        coastPos = contentPos;
                        coastEntryHeight = entryHeight;
                        DragScrollState.scrollVelocity = v;
                        DragScrollState.inertiaActive = true;
                        DragScrollClient.markInertiaStarted();
                        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MalilibCompat", "INERTIA_START v=" + v);
                    } else {
                        DragScrollState.stopInertia();
                    }
                }
                default -> DragScrollState.stopInertia();
            }
        }
        mode = Mode.IDLE;
        touchList = null;
        pressOnSlider = false;
        DragScrollState.currentTouchDragged = false;
        DragScrollState.nativeControlHeld = false;
        DragScrollState.nativeScrollbarHeld = false;
    }

    /** True when an inertia coast on this screen belongs to a MaLiLib list. */
    public static boolean hasCoast(Screen screen) {
        return coastList != null && coastScreen == screen;
    }

    /**
     * Advance the coast by one frame. Returns false when the list reached an
     * end (or vanished), so the caller stops the inertia.
     */
    public static boolean applyCoast(Screen screen, double frameDy) {
        if (!hasCoast(screen) || findList(screen) != coastList) {
            coastList = null;
            return false;
        }
        int max = getMax(coastList);
        double next = coastPos - frameDy;
        double limit = max * coastEntryHeight;
        boolean hitEnd = next <= 0.0 || next >= limit;
        coastPos = clampD(next, 0.0, limit);
        setValue(coastList, clamp((int) Math.round(coastPos / coastEntryHeight), 0, max));
        if (hitEnd) {
            coastList = null;
            return false;
        }
        return true;
    }

    /** Screen changed or hard reset. */
    public static void reset() {
        if (mode == Mode.PENDING) {
            DragScrollState.cancelPendingClick();
        }
        if (mode == Mode.BAR) {
            DragScrollState.nativeControlHeld = false;
            DragScrollState.nativeScrollbarHeld = false;
        }
        mode = Mode.IDLE;
        touchScreen = null;
        touchList = null;
        pressOnSlider = false;
        regrabList = null;
        regrabScreen = null;
        coastList = null;
        coastScreen = null;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
