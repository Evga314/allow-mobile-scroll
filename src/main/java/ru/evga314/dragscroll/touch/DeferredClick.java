package ru.evga314.dragscroll.touch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The screen click of a press that may still become a drag.
 *
 * <p>The whole {@code Screen.mouseClicked} dispatch is held back, which also
 * covers custom buttons that do not extend AbstractButton. A drag cancels it;
 * a tap replays the original event unchanged when the finger lifts.
 */
public final class DeferredClick {
    private DeferredClick() {
    }

    private static Screen screen;
    private static MouseButtonEvent event;
    private static boolean doubleClick;
    private static boolean replaying;
    /** Last pointer event dispatched by the mod (reused for synthetic slider releases). */
    private static MouseButtonEvent lastPointerEvent;

    public static void defer(Screen target, MouseButtonEvent click, boolean isDoubleClick) {
        screen = target;
        event = click;
        doubleClick = isDoubleClick;
        if (click != null) {
            lastPointerEvent = click;
        }
    }

    public static boolean isPending() {
        return screen != null && event != null;
    }

    /** True while the held-back click is being replayed (it must not be deferred again). */
    public static boolean isReplaying() {
        return replaying;
    }

    public static void cancel() {
        screen = null;
        event = null;
        doubleClick = false;
    }

    public static MouseButtonEvent lastPointerEvent() {
        return lastPointerEvent;
    }

    public static void setLastPointerEvent(MouseButtonEvent pointer) {
        lastPointerEvent = pointer;
    }

    /**
     * Replays a click that is still pending. A drag cancels the click the
     * moment it starts, so a click still pending here is a genuine tap.
     */
    public static void finish() {
        if (!isPending()) {
            cancel();
            return;
        }
        Screen target = screen;
        MouseButtonEvent click = event;
        boolean isDoubleClick = doubleClick;
        lastPointerEvent = click;
        cancel();

        // The screen may have closed meanwhile (ESC, a server screen change);
        // a replayed tap would press a button of a screen that is gone.
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null || mc.gui.screen() != target) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        try {
            replaying = true;
            target.mouseClicked(click, isDoubleClick);
        } catch (Throwable ignored) {
        } finally {
            replaying = false;
        }
        // YACL sliders are not force-released here: a horizontal slider
        // engage replays through this method while the finger is still down.
        // The release path clears them after the finger is up.
    }
}
