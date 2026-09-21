package ru.evga314.dragscroll;

import net.minecraft.client.gui.components.AbstractScrollArea;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * State used by the mobile drag-scroll adapter.
 *
 * The launcher turns each finger touch into a fresh LMB press and also moves
 * the mouse cursor to the new finger position. That cursor teleport is not
 * part of the user's drag and must never become a scroll delta.
 */
public final class DragScrollState {
    private DragScrollState() {}

    public static boolean active = false;
    /** SDL3 reports the left mouse button as 1; 0 is retained for compatibility with layers that expose the legacy value. */
    public static boolean leftButtonHeld = false;
    /** True only while the current physical touch has actually become a drag.
     *  The persistent scroll session may remain active between separate touches. */
    public static boolean currentTouchDragged = false;

    public static boolean isLeftButton(int button) {
        return button == 0 || button == 1;
    }
    public static boolean moved = false;

    public static AbstractScrollArea lockedArea = null;
    public static double baseScroll = 0.0;
    public static double totalDy = 0.0;
    public static double lockedScroll = 0.0;

    /** Screen-level fallback used by custom scroll widgets which are not AbstractScrollArea. */
    public static net.minecraft.client.gui.screens.Screen fallbackScrollScreen = null;
    public static boolean fallbackScrollInverted = false;

    /**
     * Position at the instant a new LMB touch is received, before Minecraft
     * handles the click. The position is restored only if that touch turns into a drag.
     * A normal click therefore keeps its normal vanilla behaviour.
     */
    private static final Map<AbstractScrollArea, Double> PRESS_SNAPSHOT = new WeakHashMap<>();

    /** Last stable position of each scroll area. */
    private static final Map<AbstractScrollArea, Double> REMEMBERED = new WeakHashMap<>();

    public static void snapshot(AbstractScrollArea area) {
        if (area != null) {
            try {
                PRESS_SNAPSHOT.put(area, area.scrollAmount());
            } catch (Throwable ignored) {
            }
        }
    }

    public static Double pressSnapshot(AbstractScrollArea area) {
        return area == null ? null : PRESS_SNAPSHOT.get(area);
    }

    public static void clearPressSnapshot() {
        PRESS_SNAPSHOT.clear();
    }

    public static void remember(AbstractScrollArea area, double scroll) {
        if (area != null) {
            REMEMBERED.put(area, scroll);
        }
    }

    public static Double recall(AbstractScrollArea area) {
        return area == null ? null : REMEMBERED.get(area);
    }

    public static void clear() {
        active = false;
        leftButtonHeld = false;
        currentTouchDragged = false;
        moved = false;
        lockedArea = null;
        baseScroll = 0.0;
        totalDy = 0.0;
        lockedScroll = 0.0;
        fallbackScrollScreen = null;
        fallbackScrollInverted = false;
        clearPressSnapshot();
    }

    // The initial screen click is delayed while a possible drag is being determined.
    // Delaying the whole screen dispatch also covers custom buttons that do not extend
    // AbstractButton. A normal tap is replayed with the original event unchanged.
    private static net.minecraft.client.gui.screens.Screen pendingScreen;
    private static net.minecraft.client.input.MouseButtonEvent pendingEvent;
    private static boolean pendingDoubleClick;
    private static boolean replaying;

    private static long debugSequence = 0L;

    public static long debugEvent(String source, String message) {
        long id = ++debugSequence;
        ru.evga314.dragscroll.DragScrollClient.LOGGER.info("[DragScroll DEBUG #{}] {} {}", id, source, message);
        return id;
    }

    public static boolean isReplaying() {
        return replaying;
    }

    public static boolean isPendingScreenClick() {
        return pendingScreen != null && pendingEvent != null;
    }

    public static void deferScreenClick(
            net.minecraft.client.gui.screens.Screen screen,
            net.minecraft.client.input.MouseButtonEvent event,
            boolean doubleClick) {
        pendingScreen = screen;
        pendingEvent = event;
        pendingDoubleClick = doubleClick;
    }

    public static void cancelPendingClick() {
        pendingScreen = null;
        pendingEvent = null;
        pendingDoubleClick = false;
    }

    public static void finishPendingClick() {
        // A drag cancels the pending click at the exact moment the drag is
        // activated. Therefore a pending click still present at release is a
        // genuine tap and must be replayed even if the transient drag flag was
        // changed by a nearby mouse-movement callback.
        if (pendingScreen == null || pendingEvent == null) {
            cancelPendingClick();
            return;
        }

        net.minecraft.client.gui.screens.Screen screen = pendingScreen;
        net.minecraft.client.input.MouseButtonEvent event = pendingEvent;
        boolean doubleClick = pendingDoubleClick;
        cancelPendingClick();

        try {
            replaying = true;
            screen.mouseClicked(event, doubleClick);
        } catch (Throwable ignored) {
        } finally {
            replaying = false;
        }
    }

}
