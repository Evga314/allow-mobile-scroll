package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.compat.ChatCompat;
import ru.evga314.dragscroll.compat.ClothCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;

/**
 * Screen-level scrolling: a finger drag turned into Screen.mouseScrolled,
 * for lists that are not an AbstractScrollArea (Cloth Config, Sodium, YACL,
 * Easy Install, TRender, chat, ...).
 */
public final class ScreenScroll {
    private ScreenScroll() {
    }

    /** Content pixels one mouseScrolled unit moves on most screens. */
    private static final double DEFAULT_PIXELS_PER_UNIT = 12.0;

    /** Content pixels one mouseScrolled unit moves on this screen. */
    private static double pixelsPerUnit(Screen screen) {
        ScreenKind kind = ScreenKind.of(screen);
        if (kind.cloth) {
            double step = ClothCompat.scrollStep(screen);
            if (step > 0.0) {
                return step;
            }
        }
        return kind.chat ? ChatCompat.PIXELS_PER_SCROLL_UNIT : DEFAULT_PIXELS_PER_UNIT;
    }

    /**
     * mouseScrolled units that move the content with a finger step of
     * {@code dy} GUI px. A vanilla list takes positive units as "towards the
     * start"; {@code inverted} screens take the opposite sign.
     */
    public static double unitsFor(Screen screen, double dy, boolean inverted) {
        double units = dy / pixelsPerUnit(screen);
        return inverted ? units : -units;
    }

    /**
     * Sends {@code units} to the screen at (x, y). TRender's WScrollBar casts
     * the delta to int, so it only gets whole units (the rest is carried).
     */
    public static void dispatch(Screen screen, double x, double y, double units) {
        if (screen == null || units == 0.0) {
            return;
        }
        double emit = ScreenKind.of(screen).trender ? TrenderCompat.consumeScrollDelta(units) : units;
        if (emit == 0.0) {
            return;
        }
        try {
            screen.mouseScrolled(x, y, 0.0, emit);
        } catch (Throwable ignored) {
        }
    }
}
