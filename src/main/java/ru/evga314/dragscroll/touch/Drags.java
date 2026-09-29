package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.compat.SodiumCompat;

/** Starting a scroll session once a touch turned out to be a drag. */
public final class Drags {
    private Drags() {
    }

    /**
     * Starts dragging {@code area}. The offset is restored first: the press
     * snapshot was taken before mouseClicked, and a click that selected a row
     * may have scrolled the list since. When the same list is still coasting,
     * the coast position is kept instead.
     */
    public static void beginList(AbstractScrollArea area) {
        if (Debug.on()) Debug.log("Drags.beginList", "area=" + area.getClass().getName()
                + " activeBefore=" + TouchState.active + " leftHeld=" + TouchState.leftButtonHeld);
        double start = area.scrollAmount();
        try {
            if (TouchState.lockedArea == area && (Inertia.active || Math.abs(Inertia.residual) > 0.01)) {
                start = TouchState.lockedScroll;
                area.setScrollAmount(start);
            } else {
                Double press = ScrollMemory.pressSnapshot(area);
                Double restored = press != null ? press : ScrollMemory.recall(area);
                if (restored != null) {
                    start = restored;
                    area.setScrollAmount(restored);
                }
            }
        } catch (Throwable ignored) {
        }
        TouchState.active = true;
        TouchState.moved = false;
        TouchState.lockedArea = area;
        TouchState.fallbackScreen = null;
        TouchState.lockedScroll = start;
        Inertia.catchForDrag();
        if (Debug.on()) Debug.log("Drags.beginList", "ACTIVE_SET start=" + start + " residual=" + Inertia.residual);
    }

    /** Starts a screen-level drag (the screen scrolls through mouseScrolled). */
    public static void beginScreen(Screen screen) {
        TouchState.active = true;
        TouchState.moved = false;
        TouchState.lockedArea = null;
        TouchState.fallbackScreen = screen;
        TouchState.fallbackInverted = ScreenScroll.isInverted(screen, Double.NaN);
        if (ScreenKind.of(screen).sodium) {
            SodiumCompat.contentLocked = true;
        }
        Inertia.catchForDrag();
        if (Debug.on()) Debug.log("Drags.beginScreen", "screen=" + screen.getClass().getName()
                + " inverted=" + TouchState.fallbackInverted);
    }
}
