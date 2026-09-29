package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.ScrollMemory;
import ru.evga314.dragscroll.touch.TouchState;
import ru.evga314.dragscroll.touch.Widgets;

/**
 * Shulker Box Tooltip config. Its ConfigEntryList keeps the scroll bar in an
 * 80 px gutter on the right, outside the vanilla bar hit box, so the mod
 * drives the thumb itself.
 */
public final class ShulkerCompat {
    private ShulkerCompat() {
    }

    private static boolean isShulkerList(AbstractScrollArea area) {
        String name = Widgets.lowerName(area);
        return name.contains("shulkerboxtooltip") || name.contains("configentrylist");
    }

    /** The finger is in the scroll bar gutter of a Shulker Box Tooltip list. */
    public static boolean isScrollbarHover(Screen screen, double x, double y) {
        if (screen == null || Double.isNaN(x)) {
            return false;
        }
        for (AbstractScrollArea area : Widgets.allScrollLists(screen)) {
            if (!isShulkerList(area) || area.getHeight() < 24 || area.getWidth() < 16) {
                continue;
            }
            if (!Double.isNaN(y) && (y < area.getY() - 4 || y > area.getY() + area.getHeight() + 4)) {
                continue;
            }
            int right = area.getX() + area.getWidth();
            if (x >= right - 80 && x <= right + 8) {
                return true;
            }
        }
        return false;
    }

    /** The main (tallest, preferably scrollable) Shulker Box Tooltip list. */
    private static AbstractScrollArea findList(Screen screen) {
        AbstractScrollArea best = null;
        int bestScore = -1;
        for (AbstractScrollArea area : Widgets.allScrollLists(screen)) {
            if (!isShulkerList(area) || area.getHeight() < 24 || area.getWidth() < 16) {
                continue;
            }
            int score = area.getHeight() + area.getWidth();
            try {
                if (area.maxScrollAmount() > 0.0) {
                    score += 10000;
                }
            } catch (Throwable ignored) {
            }
            if (score > bestScore) {
                bestScore = score;
                best = area;
            }
        }
        return best;
    }

    /** Moves the list so the grabbed thumb follows the finger at {@code guiY}. */
    public static boolean applyThumb(Screen screen, double guiY) {
        if (screen == null || Double.isNaN(guiY)) {
            return false;
        }
        AbstractScrollArea list = findList(screen);
        if (list == null) {
            return false;
        }
        double top = list.getY();
        double bottom = list.getY() + list.getHeight();
        double max;
        double current;
        try {
            max = list.maxScrollAmount();
            current = list.scrollAmount();
        } catch (Throwable t) {
            return false;
        }
        if (max <= 0.0 || bottom - top < 8.0) {
            return false;
        }
        double next = ClothCompat.THUMB.map(guiY, top, bottom, current, max);
        if (Math.abs(next - current) < 0.01) {
            return true;
        }
        try {
            list.setScrollAmount(next);
        } catch (Throwable ignored) {
            return false;
        }
        TouchState.lockedScroll = next;
        ScrollMemory.remember(list, next);
        if (Debug.on()) Debug.log("ShulkerCompat", "THUMB_SET next=" + next + " max=" + max);
        return true;
    }
}
