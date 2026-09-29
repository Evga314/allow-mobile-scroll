package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import ru.evga314.dragscroll.access.ScrollAreaAccess;
import ru.evga314.dragscroll.compat.ClothCompat;
import ru.evga314.dragscroll.compat.ShulkerCompat;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.compat.TConfigCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.compat.XaeroMapZoomOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Queries on a screen's widget tree: which list is under the finger, whether
 * the finger is on a scroll bar or a slider, and so on.
 */
public final class Widgets {
    private Widgets() {
    }

    /** Lower-cased class name, cached per class (asked on every event). */
    private static final ClassValue<String> LOWER_NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return type.getName().toLowerCase(Locale.ROOT);
        }
    };

    public static String lowerName(Object obj) {
        return obj == null ? "" : LOWER_NAMES.get(obj.getClass());
    }

    // =====================================================================
    // Screen classification
    // =====================================================================

    /**
     * Inventories and every other AbstractContainerScreen share the vanilla
     * slot input; the mod must not defer or reinterpret their clicks.
     */
    public static boolean isContainerScreen(Screen screen) {
        return screen instanceof AbstractContainerScreen;
    }

    /**
     * Screens that keep raw mouse input: no screen, inventories, screens with
     * nothing to scroll, and Xaero's World Map (wheel = zoom, LMB = pan).
     */
    public static boolean isVanillaInputOnly(Screen screen) {
        if (screen == null || XaeroMapZoomOverlay.isMapScreen(screen) || isContainerScreen(screen)) {
            return true;
        }
        return !ScreenKind.of(screen).customScroll && !hasAnyScrollArea(screen);
    }

    // =====================================================================
    // Scroll areas
    // =====================================================================

    /**
     * Tab bars (TabNavigationBar, MenuTabBar) are AbstractScrollArea
     * containers but never scroll vertically. Easy Install's project page has
     * one across the top; picking it swallowed the drags meant for the page.
     */
    private static boolean isScrollList(AbstractScrollArea area) {
        return !(area instanceof TabNavigationBar);
    }

    /** Visits every AbstractScrollArea under {@code node}, tab bars included. */
    public static void forEachScrollArea(GuiEventListener node, Consumer<AbstractScrollArea> action) {
        if (node instanceof AbstractScrollArea area) {
            try {
                action.accept(area);
            } catch (Throwable ignored) {
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                forEachScrollArea(child, action);
            }
        }
    }

    public static boolean hasAnyScrollArea(GuiEventListener node) {
        if (node instanceof AbstractScrollArea) {
            return true;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (hasAnyScrollArea(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when some AbstractScrollArea under {@code node} can scroll. */
    private static boolean hasScrollableArea(GuiEventListener node) {
        if (node instanceof AbstractScrollArea area && canScroll(area)) {
            return true;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (hasScrollableArea(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** maxScrollAmount() > 0; true when the area throws (assume it scrolls). */
    public static boolean canScroll(AbstractScrollArea area) {
        try {
            return area.maxScrollAmount() > 0.0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Every scroll list (no tab bars) under {@code node}, in tree order. */
    public static List<AbstractScrollArea> allScrollLists(GuiEventListener node) {
        List<AbstractScrollArea> out = new ArrayList<>();
        collectAll(node, out);
        return out;
    }

    private static void collectAll(GuiEventListener node, List<AbstractScrollArea> out) {
        if (node instanceof AbstractScrollArea area && isScrollList(area)) {
            out.add(area);
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                collectAll(child, out);
            }
        }
    }

    /** Two or more scroll lists: never scroll such a screen as a whole. */
    public static boolean hasMultipleScrollLists(GuiEventListener root) {
        return countScrollLists(root, 2) >= 2;
    }

    private static int countScrollLists(GuiEventListener node, int limit) {
        int count = node instanceof AbstractScrollArea area && isScrollList(area) ? 1 : 0;
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (count >= limit) {
                    break;
                }
                count += countScrollLists(child, limit - count);
            }
        }
        return count;
    }

    /**
     * The scroll list under the point: the innermost one that reports the
     * point over it, else the list whose rectangle or column contains it
     * (isMouseOver misses some panes, e.g. Mod Menu's description).
     */
    public static AbstractScrollArea findScrollArea(GuiEventListener root, double x, double y) {
        List<AbstractScrollArea> over = new ArrayList<>();
        collectOver(root, x, y, over);
        if (!over.isEmpty()) {
            AbstractScrollArea result = over.get(over.size() - 1);
            if (Debug.on()) Debug.log("Widgets.findScrollArea", "x=" + x + " y=" + y
                    + " found=" + over.size() + " result=" + result.getClass().getName());
            return result;
        }
        List<AbstractScrollArea> all = allScrollLists(root);
        AbstractScrollArea result = pickAreaByBounds(all, x, y);
        if (Debug.on()) Debug.log("Widgets.findScrollArea", "x=" + x + " y=" + y
                + " found=0 all=" + all.size()
                + " result=" + (result == null ? "null" : result.getClass().getName()));
        return result;
    }

    private static void collectOver(GuiEventListener node, double x, double y, List<AbstractScrollArea> out) {
        if (node instanceof AbstractScrollArea area && isScrollList(area)) {
            try {
                if (area.isMouseOver(x, y)) {
                    out.add(area);
                }
            } catch (Throwable ignored) {
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                collectOver(child, x, y, out);
            }
        }
    }

    /**
     * Closest list that contains the point, or whose column does. Lists that
     * cannot scroll are skipped: a short list whose column covers the touch
     * would swallow drags meant for the screen's own scroller.
     */
    private static AbstractScrollArea pickAreaByBounds(List<AbstractScrollArea> all, double x, double y) {
        AbstractScrollArea best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (AbstractScrollArea area : all) {
            try {
                if (area.maxScrollAmount() <= 0) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            double ax = area.getX();
            double ay = area.getY();
            double aw = area.getWidth();
            double ah = area.getHeight();
            if (x < ax || x > ax + aw) {
                continue;
            }
            boolean inside = y >= ay && y <= ay + ah;
            double score = Math.abs(x - (ax + aw * 0.5))
                    + (inside ? 0.0 : 1000.0 + Math.abs(y - (ay + ah * 0.5)));
            if (score < bestScore) {
                bestScore = score;
                best = area;
            }
        }
        return best;
    }

    // =====================================================================
    // Native scroll bars
    // =====================================================================

    /** The finger is on the vanilla scroll bar of {@code area}. */
    public static boolean isNativeScrollbar(AbstractScrollArea area, double x, double y) {
        if (!(area instanceof ScrollAreaAccess access)) {
            return false;
        }
        try {
            if (!access.dragscroll$isOverScrollbar(x, y)) {
                return false;
            }
            String name = lowerName(area);
            // Shulker Box Tooltip's ConfigEntryList keeps its bar in an 80 px
            // gutter on the right (getRowWidth = width - 80).
            if (name.contains("shulkerboxtooltip") || name.contains("configentrylist")) {
                return x >= area.getX() + area.getWidth() - 80 && x <= area.getX() + area.getWidth() + 8;
            }
            if (name.contains("tconfig")) {
                return x >= area.getX() + area.getWidth() - 16 && x <= area.getX() + area.getWidth() + 12;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The area whose native scroll bar is under the finger. The area itself is
     * not required to report the point over it: in 26.3 the bar can sit in an
     * edge region outside isMouseOver().
     */
    public static AbstractScrollArea findNativeScrollbar(GuiEventListener node, double x, double y) {
        if (node == null) {
            return null;
        }
        if (node instanceof AbstractScrollArea area && isNativeScrollbar(area, x, y)) {
            return area;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                AbstractScrollArea found = findNativeScrollbar(child, x, y);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Custom scroll bars (Cloth Config, Sodium, tconfig, Shulker Box Tooltip,
     * TRender, thin bars of other mods). A press there goes to the mod's own
     * bar handling: routed through the content drag, the thumb would move
     * the list the wrong way.
     */
    public static boolean isCustomScrollbar(GuiEventListener root, double x, double y) {
        if (root == null) {
            return false;
        }
        if (root instanceof Screen screen) {
            ScreenKind kind = ScreenKind.of(screen);
            if (kind.cloth && ClothCompat.isScrollbarHover(screen, x, y)
                    || kind.sodium && SodiumCompat.isScrollbarHover(screen, x, y)
                    || kind.tconfig && TConfigCompat.isScrollbarHover(screen, x, y)
                    || kind.shulkerConfig && ShulkerCompat.isScrollbarHover(screen, x, y)
                    || kind.trender && TrenderCompat.isBarStrip(screen, x)) {
                return true;
            }
            // Full-width custom lists (Easy Install): thin strip at the right
            // screen edge. Not on Sodium (option buttons live there) or TRender.
            if (!kind.sodium && !kind.trender && screen.width > 0 && x >= screen.width - 14) {
                return true;
            }
        }
        // A narrow, tall widget under the finger is a scroll bar (Mod Menu, panels).
        if (root instanceof AbstractWidget widget) {
            try {
                if (widget.getWidth() <= 18 && widget.getHeight() >= 32 && widget.isMouseOver(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        // Real scroll bar widgets only. Not *EntryList: that is the list
        // itself (Shulker Box Tooltip), and matching it swallowed content drags.
        String name = lowerName(root);
        if (name.contains("scrollbar") || name.contains("scroll_bar")
                || name.contains("scrollhandle") || name.contains("scroll_handle")
                || name.contains("scrollthumb") || name.contains("scroll_thumb")
                || name.contains("scroller") || name.contains("scrollpanel")
                || name.contains("scroll_panel")) {
            try {
                return root.isMouseOver(x, y);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (root instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (isCustomScrollbar(child, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    // =====================================================================
    // Sliders
    // =====================================================================

    public static boolean isSliderTarget(GuiEventListener root, double x, double y) {
        if (root == null) {
            return false;
        }
        if (lowerName(root).contains("slider")) {
            try {
                return root.isMouseOver(x, y);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (root instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (isSliderTarget(child, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A slider at the point lives in something that scrolls. Its press is
     * then deferred: a vertical swipe must scroll the list without the
     * slider's click sound and value jump; only a clear horizontal move
     * engages the slider.
     *
     * <p>On some coordinate paths OptionsList.isMouseOver() misses the list
     * under a slider; any scrollable list on the screen then counts.
     */
    public static boolean inScrollableContext(GuiEventListener root, double x, double y) {
        if (root instanceof Screen screen && ScreenKind.of(screen).customScroll) {
            return true;
        }
        AbstractScrollArea area = findScrollArea(root, x, y);
        if (area != null) {
            return canScroll(area);
        }
        return hasScrollableArea(root);
    }
}
