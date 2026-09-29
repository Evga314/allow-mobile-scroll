package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Scroll offsets of AbstractScrollAreas across touches.
 *
 * <p>A press is dispatched to the screen before the mod knows whether it is a
 * tap or a drag, and a click on a row may scroll the list. The offsets are
 * snapshotted before that click and restored if the touch becomes a drag.
 * Offsets are also remembered after each touch, so a new touch does not snap
 * a list back to an outdated position.
 */
public final class ScrollMemory {
    private ScrollMemory() {
    }

    private static final Map<AbstractScrollArea, Double> PRESS_SNAPSHOT = new WeakHashMap<>();
    private static final Map<AbstractScrollArea, Double> REMEMBERED = new WeakHashMap<>();

    public static void snapshot(AbstractScrollArea area) {
        if (area != null) {
            PRESS_SNAPSHOT.put(area, area.scrollAmount());
        }
    }

    /** Snapshots every AbstractScrollArea under {@code node}. */
    public static void snapshotAll(GuiEventListener node) {
        Widgets.forEachScrollArea(node, ScrollMemory::snapshot);
    }

    public static Double pressSnapshot(AbstractScrollArea area) {
        return area == null ? null : PRESS_SNAPSHOT.get(area);
    }

    public static void clearPressSnapshots() {
        PRESS_SNAPSHOT.clear();
    }

    public static void remember(AbstractScrollArea area, double scroll) {
        if (area != null) {
            REMEMBERED.put(area, scroll);
        }
    }

    /** Remembers the current offset of every AbstractScrollArea under {@code node}. */
    public static void rememberAll(GuiEventListener node) {
        Widgets.forEachScrollArea(node, area -> remember(area, area.scrollAmount()));
    }

    public static Double recall(AbstractScrollArea area) {
        return area == null ? null : REMEMBERED.get(area);
    }
}
