package ru.evga314.dragscroll.compat;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.access.ScrollAreaAccess;
import ru.evga314.dragscroll.touch.Widgets;

/** Traben's tconfig (Entity Model Features, Entity Texture Features). */
public final class TConfigCompat {
    private TConfigCompat() {
    }

    /**
     * The finger is on a tconfig scroll bar: the right screen edge, a 28 px
     * strip at a list's right edge, or the list's own vanilla bar.
     */
    public static boolean isScrollbarHover(Screen screen, double x, double y) {
        if (screen == null || Double.isNaN(x)) {
            return false;
        }
        if (screen.width > 0 && x >= screen.width - 16) {
            return true;
        }
        for (AbstractScrollArea area : Widgets.allScrollLists(screen)) {
            int right = area.getX() + area.getWidth();
            int top = area.getY();
            int height = area.getHeight();
            if (x >= right - 16 && x <= right + 12
                    && (Double.isNaN(y) || (y >= top - 4 && y <= top + height + 4))) {
                return true;
            }
            try {
                if (area instanceof ScrollAreaAccess access
                        && access.dragscroll$isOverScrollbar(x, Double.isNaN(y) ? top + height * 0.5 : y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
