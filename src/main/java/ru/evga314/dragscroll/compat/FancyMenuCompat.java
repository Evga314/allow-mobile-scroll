package ru.evga314.dragscroll.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.Inertia;
import ru.evga314.dragscroll.touch.TouchState;
import ru.evga314.dragscroll.touch.VelocityTracker;

import java.lang.reflect.Method;
import java.util.List;

/**
 * FancyMenu 3. Its editor UIs are PiP windows that ScreenOverlayHandler draws
 * on top of the current screen (usually the title screen), and their lists
 * are FancyMenu ScrollAreas, not AbstractScrollArea. FancyMenu takes overlay
 * clicks before vanilla sees them, and the host screen is vanilla-input-only,
 * so a finger drag never reached them.
 *
 * <p>A press over a FancyMenu ScrollArea arms the touch; a vertical drag then
 * moves the area's vertical ScrollBar 1:1 with the finger, and a fast lift
 * coasts it. The press is held back from FancyMenu (some list entries act on
 * press) and replayed on release only when the touch stayed a tap.
 * Everything is reflective: the mod does not depend on FancyMenu.
 */
public final class FancyMenuCompat {
    private FancyMenuCompat() {
    }

    private static final String OVERLAY_HANDLER = "de.keksuccino.fancymenu.util.rendering.ui.screen.ScreenOverlayHandler";
    private static final String PIP_HANDLER = "de.keksuccino.fancymenu.util.rendering.ui.pipwindow.PiPWindowHandler";
    private static final String SCROLL_AREA = "de.keksuccino.fancymenu.util.rendering.ui.scroll.v2.scrollarea.ScrollArea";
    private static final String CUSTOMIZATION_OVERLAY = "de.keksuccino.fancymenu.customization.overlay.CustomizationOverlay";

    /** Finger travel must be this vertical before the touch scrolls (|dy| >= |dx| * 0.6). */
    private static final double VERTICAL_DOMINANCE = 0.6;

    private static Boolean loaded;
    private static Object overlayHandler;

    // --- The current touch ---

    /** Vertical ScrollBar of the area under the current touch, or null. */
    private static Object targetBar;
    private static Object targetArea;
    /** GUI px -> window body px (PiP windows can be scaled). */
    private static double targetScale = 1.0;
    private static double pressX = Double.NaN;
    private static double pressY = Double.NaN;
    private static double lastY = Double.NaN;
    private static boolean dragging;
    /** The press FancyMenu has not seen yet; replayed on a tap release. */
    private static MouseButtonInfo deferredButton;
    private static final VelocityTracker VELOCITY = new VelocityTracker();

    // --- Inertia (GUI px/s; thresholds and decay as the list inertia) ---

    private static Screen coastScreen;
    private static Object coastBar;
    private static Object coastArea;
    private static double coastScale = 1.0;
    private static double coastVelocity;
    private static long coastLastNs;

    public static boolean isLoaded() {
        if (loaded == null) {
            boolean l;
            try {
                l = FabricLoader.getInstance().isModLoaded("fancymenu");
            } catch (Throwable t) {
                l = false;
            }
            loaded = l;
        }
        return loaded;
    }

    /** A touch that started on a FancyMenu list is held. */
    public static boolean isArmed() {
        return targetBar != null;
    }

    // =====================================================================
    // Touch
    // =====================================================================

    /**
     * Finger down. Arms the touch when it lands inside a FancyMenu ScrollArea,
     * but not on its grabber, which FancyMenu drags itself.
     */
    public static boolean onPress(Screen screen, double x, double y, MouseButtonInfo button) {
        stopCoast();
        clear();
        if (!isLoaded()) {
            return false;
        }
        try {
            if (!findTarget(screen, x, y)) {
                return false;
            }
            pressX = x;
            pressY = y;
            lastY = y;
            deferredButton = button;
            if (Debug.on()) Debug.log("FancyMenuCompat.onPress", "ARMED area=" + targetArea.getClass().getName()
                    + " scale=" + targetScale + " x=" + x + " y=" + y);
            return true;
        } catch (Throwable t) {
            clear();
            return false;
        }
    }

    /** Every frame while the finger is down. */
    public static void onFrame(double x, double y) {
        if (targetBar == null || !TouchState.leftButtonHeld) {
            return;
        }
        try {
            if (!dragging) {
                double fromX = x - pressX;
                double fromY = y - pressY;
                if (Math.abs(fromY) < DragScrollConfig.getDragThreshold()
                        || Math.abs(fromY) < Math.abs(fromX) * VERTICAL_DOMINANCE) {
                    return;
                }
                dragging = true;
                // Measure from the press point so the threshold travel is not lost.
                lastY = pressY;
            }
            double dy = y - lastY;
            lastY = y;
            VELOCITY.push(dy);
            if (dy == 0.0) {
                return;
            }
            float after = applyDy(targetArea, targetBar, dy, targetScale);
            if (Debug.on()) Debug.log("FancyMenuCompat.onFrame", "SCROLL dy=" + dy + " scroll=" + after);
        } catch (Throwable t) {
            clear();
        }
    }

    /**
     * Finger up. A tap gets its held-back press and a release replayed to
     * FancyMenu; a drag may start a coast.
     */
    public static void onRelease(Screen screen, double x, double y) {
        if (targetBar == null) {
            clear();
            return;
        }
        try {
            if (!dragging) {
                replayTap(screen, x, y);
            } else {
                startCoast(screen);
            }
        } catch (Throwable ignored) {
        }
        clear();
    }

    private static void replayTap(Screen screen, double x, double y) throws Exception {
        MouseButtonInfo info = deferredButton;
        if (info == null) {
            return;
        }
        // Replay where the finger went down; FancyMenu picks the target from it.
        double px = Double.isNaN(pressX) ? x : pressX;
        double py = Double.isNaN(pressY) ? y : pressY;
        Object handler = overlayHandler();
        boolean consumed = false;
        if (handler != null) {
            Method click = handler.getClass().getMethod("mouseClicked", double.class, double.class, int.class);
            consumed = Boolean.TRUE.equals(click.invoke(handler, px, py, info.button()));
            if (consumed) {
                Method release = handler.getClass().getMethod("mouseReleased", double.class, double.class, int.class);
                release.invoke(handler, px, py, info.button());
            }
        }
        if (!consumed && screen != null) {
            MouseButtonEvent event = new MouseButtonEvent(px, py, info);
            screen.mouseClicked(event, false);
            screen.mouseReleased(event);
        }
        if (Debug.on()) Debug.log("FancyMenuCompat.onRelease", "TAP_REPLAY x=" + px + " y=" + py
                + " overlay=" + consumed);
    }

    private static void clear() {
        targetBar = null;
        targetArea = null;
        targetScale = 1.0;
        pressX = Double.NaN;
        pressY = Double.NaN;
        lastY = Double.NaN;
        dragging = false;
        deferredButton = null;
        VELOCITY.clear();
    }

    // =====================================================================
    // Inertia
    // =====================================================================

    private static void startCoast(Screen screen) {
        double v = VELOCITY.pixelsPerSecond();
        if (Math.abs(v) < Inertia.MIN_FLICK * Inertia.TICKS_PER_SECOND) {
            return;
        }
        v *= DragScrollConfig.getInertiaStrengthMultiplier();
        double max = Inertia.MAX_VELOCITY * Inertia.TICKS_PER_SECOND;
        v = Math.max(-max, Math.min(max, v));
        if (v == 0.0) {
            return;
        }
        coastVelocity = v;
        coastLastNs = 0L;
        coastScreen = screen;
        coastBar = targetBar;
        coastArea = targetArea;
        coastScale = targetScale;
        if (Debug.on()) Debug.log("FancyMenuCompat.onRelease", "COAST_START velocity=" + v);
    }

    private static void stopCoast() {
        coastVelocity = 0.0;
        coastLastNs = 0L;
        coastScreen = null;
        coastBar = null;
        coastArea = null;
        coastScale = 1.0;
    }

    /** Every frame: advances a running coast. */
    public static void tickCoast() {
        if (coastBar == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null || mc.screen != coastScreen || mc.getOverlay() != null
                    || TouchState.leftButtonHeld) {
                stopCoast();
                return;
            }
            long now = System.nanoTime();
            if (coastLastNs == 0L) {
                coastLastNs = now;
                return;
            }
            double dt = (now - coastLastNs) / 1_000_000_000.0;
            coastLastNs = now;
            if (dt <= 0.0) {
                return;
            }
            dt = Math.min(dt, 0.1);
            double decayed = Inertia.decay(coastVelocity, dt);
            double dy = 0.5 * (coastVelocity + decayed) * dt;
            coastVelocity = decayed;
            float before = scrollOf(coastBar);
            float after = applyDy(coastArea, coastBar, dy, coastScale);
            boolean stuck = before == after || after <= 0.0F || after >= 1.0F;
            if (stuck || Math.abs(coastVelocity) < Inertia.STOP_VELOCITY * Inertia.TICKS_PER_SECOND) {
                stopCoast();
            }
        } catch (Throwable t) {
            stopCoast();
        }
    }

    private static float scrollOf(Object bar) {
        return ((Number) Reflect.invokePublic(bar, "getScroll")).floatValue();
    }

    /** Moves the area so its content follows a finger step of dy GUI px; returns the new scroll. */
    private static float applyDy(Object area, Object bar, double dy, double scale) throws Exception {
        float scroll = scrollOf(bar);
        float total = ((Number) Reflect.invokePublic(area, "getTotalScrollHeight")).floatValue();
        if (total <= 0.0F) {
            return scroll;
        }
        // Finger down -> content follows the finger down -> earlier content.
        float next = (float) (scroll - dy * scale / total);
        next = Math.max(0.0F, Math.min(1.0F, next));
        bar.getClass().getMethod("setScroll", float.class).invoke(bar, next);
        return next;
    }

    // =====================================================================
    // Mouse-wheel emulator and in-game clicks
    // =====================================================================

    /** Sends one wheel step to FancyMenu's overlays; true when one consumed it. */
    public static boolean dispatchWheel(double x, double y, double scrollY) {
        if (!isLoaded()) {
            return false;
        }
        try {
            Object handler = overlayHandler();
            if (handler == null) {
                return false;
            }
            Method m = handler.getClass().getMethod("mouseScrolled", double.class, double.class, double.class, double.class);
            return Boolean.TRUE.equals(m.invoke(handler, x, y, 0.0, scrollY));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * In-game press (no screen open). FancyMenu's customization menu bar only
     * refreshes its hover flags while it is drawn, but it takes clicks while no
     * screen is open. Leaving the pause menu with the cursor on the bar left it
     * "hovered", and the next in-game click hit a bar entry instead of the
     * world. The bar is never drawn in game: its stale hover is cleared and its
     * menus closed before FancyMenu sees the press.
     */
    public static void clearStaleMenuBarHover() {
        if (!isLoaded()) {
            return;
        }
        try {
            Object bar = Class.forName(CUSTOMIZATION_OVERLAY).getMethod("getCurrentMenuBarInstance").invoke(null);
            if (bar == null) {
                return;
            }
            setHovered(bar);
            Reflect.invokePublic(bar, "closeAllContextMenus");
            Object toggle = Reflect.read(bar, "collapseOrExpandEntry");
            if (toggle != null) {
                setHovered(toggle);
            }
            for (String listName : new String[] {"leftEntries", "rightEntries"}) {
                if (Reflect.read(bar, listName) instanceof List<?> entries) {
                    for (Object entry : entries) {
                        if (entry != null) {
                            setHovered(entry);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setHovered(Object target) {
        java.lang.reflect.Field f = Reflect.field(target.getClass(), "hovered");
        if (f != null && f.getType() == boolean.class) {
            try {
                f.setBoolean(target, false);
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // Finding the list under the finger
    // =====================================================================

    private static boolean findTarget(Screen screen, double x, double y) throws Exception {
        Object handler = overlayHandler();
        if (handler != null) {
            List<?> overlays = (List<?>) Reflect.invokePublic(handler, "getOverlays");
            for (int i = overlays.size() - 1; i >= 0; i--) {
                Object overlay = overlays.get(i);
                if (!Reflect.isInstance(overlay, PIP_HANDLER)) {
                    continue;
                }
                List<?> windows = (List<?>) Reflect.invokePublic(overlay, "getOpenWindows");
                // Same order as PiPWindowHandler.mouseScrolled: last = topmost.
                for (int w = windows.size() - 1; w >= 0; w--) {
                    Object window = windows.get(w);
                    if (!Boolean.TRUE.equals(Reflect.invokePublic(window, "isVisible"))
                            || !(window instanceof GuiEventListener listener) || !listener.isMouseOver(x, y)) {
                        continue;
                    }
                    if (!(Reflect.invokePublic(window, "getScreen") instanceof GuiEventListener body)) {
                        return false;
                    }
                    double sx = toWindow(window, "toScreenMouseX", x);
                    double sy = toWindow(window, "toScreenMouseY", y);
                    double scale = toWindow(window, "toScreenMouseY", y + 1.0) - sy;
                    // The topmost window under the finger owns the touch, even
                    // when nothing in it scrolls.
                    return pickArea(body, sx, sy, scale > 0.0 ? scale : 1.0);
                }
            }
        }
        // A FancyMenu screen shown directly, not in a window.
        return screen != null && pickArea(screen, x, y, 1.0);
    }

    /** PiPWindow's private GUI -> window body coordinate transform. */
    private static double toWindow(Object window, String method, double value) throws Exception {
        Method m = Reflect.declaredMethod(window.getClass(), method, double.class);
        if (m == null) {
            throw new NoSuchMethodException(method);
        }
        return ((Number) m.invoke(window, value)).doubleValue();
    }

    private static boolean pickArea(GuiEventListener root, double x, double y, double scale) {
        Object area = findArea(root, x, y);
        if (area == null) {
            return false;
        }
        try {
            Object bar = area.getClass().getField("verticalScrollBar").get(area);
            if (bar == null || !Boolean.TRUE.equals(Reflect.invokePublic(area, "isVerticalScrollBarVisible"))) {
                return false;
            }
            if (Boolean.TRUE.equals(bar.getClass().getMethod("isMouseOverGrabber", double.class, double.class)
                    .invoke(bar, x, y))) {
                return false;
            }
            targetArea = area;
            targetBar = bar;
            targetScale = scale;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object findArea(GuiEventListener node, double x, double y) {
        if (node == null) {
            return null;
        }
        if (Reflect.isInstance(node, SCROLL_AREA)) {
            return node.isMouseOver(x, y) ? node : null;
        }
        if (node instanceof ContainerEventHandler container) {
            try {
                List<? extends GuiEventListener> children = container.children();
                // Later children are drawn on top.
                for (int i = children.size() - 1; i >= 0; i--) {
                    Object found = findArea(children.get(i), x, y);
                    if (found != null) {
                        return found;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object overlayHandler() {
        if (overlayHandler == null) {
            try {
                overlayHandler = Class.forName(OVERLAY_HANDLER).getField("INSTANCE").get(null);
            } catch (Throwable t) {
                return null;
            }
        }
        return overlayHandler;
    }
}
