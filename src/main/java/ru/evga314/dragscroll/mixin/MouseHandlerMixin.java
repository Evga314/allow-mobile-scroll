package ru.evga314.dragscroll.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.DragScrollState;
import ru.evga314.dragscroll.DragScrollConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts the mobile launcher's "LMB + mouse movement" gesture to scrolling.
 *
 * Important detail for mobile launchers:
 *
 *     touch #1 -> release -> touch #2 at another screen position
 *
 * is NOT the same as one continuous mouse drag. The launcher moves the mouse
 * cursor to touch #2 immediately. Minecraft also processes the LMB click at
 * that new position. Selection lists can consequently change their scroll
 * position before the first drag movement arrives.
 *
 * The fix differs from simple saving/restoring after the click: when LMB goes
 * down, a snapshot is taken BEFORE the click. If the touch becomes a drag, the
 * snapshot is restored immediately before the first real finger movement. A
 * simple click remains unchanged.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow
    public abstract boolean isLeftPressed();

    @Shadow
    public abstract double getScaledXPos(Window window);

    @Shadow
    public abstract double getScaledYPos(Window window);

    /**
     * Pending cursor movement accumulated by MouseHandler.onMove().
     *
     * The mobile launcher can move the cursor to the next touch point before
     * (or immediately after) sending the new LMB press. That teleport is
     * stored here as accumulated movement. If it reaches
     * handleAccumulatedMovement(), vanilla interprets the distance between
     * the old and new touch points as a real drag.
     */
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Unique
    private double dragscroll$lastGuiX = Double.NaN;

    @Unique
    private double dragscroll$lastGuiY = Double.NaN;

    @Unique
    private double dragscroll$scrollAccum = 0.0;

    @Unique
    private boolean dragscroll$wasLeft = false;

    @Unique
    private Screen dragscroll$lastScreen = null;

    @Unique
    private double dragscroll$pressGuiX = Double.NaN;

    @Unique
    private double dragscroll$pressGuiY = Double.NaN;

    @Unique
    private static final double SCROLL_PIXELS_PER_NOTCH = 12.0;
    /** Minimum vertical finger travel in GUI pixels before a touch becomes a drag. */

    /**
     * Snapshot scroll positions BEFORE Minecraft processes the new click.
     * This is the crucial difference from 1.0.13.
     */
    @Inject(method = "onButton", at = @At("HEAD"))
    private void dragscroll$beforeButton(long handle, MouseButtonInfo buttonInfo, int action,
                                         CallbackInfo ci) {
        DragScrollState.debugEvent("MouseHandler.onButton",
                "HEAD button=" + (buttonInfo == null ? "null" : buttonInfo.button())
                        + " action=" + action
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld);
        try {
            if (buttonInfo == null || !DragScrollState.isLeftButton(buttonInfo.button())) {
                return;
            }

            if (action == InputConstants.PRESS) {
                // Each launcher touch is a new physical LMB press. Preserve the
                // persistent scroll session, but start a fresh tap/drag decision.
                DragScrollState.leftButtonHeld = true;
                DragScrollState.currentTouchDragged = false;
            } else if (action == InputConstants.RELEASE) {
                // A custom scroll screen can deliver release handling before the
                // next movement callback has a chance to update transient state.
                // If the current touch was already a drag, discard any deferred
                // click before vanilla/Sodium processes the release.
                Screen currentScreen = this.minecraft.gui.screen();
                if (DragScrollState.currentTouchDragged
                        && DragScrollState.fallbackScrollScreen == currentScreen) {
                    DragScrollState.cancelPendingClick();
                    DragScrollState.debugEvent("MouseHandler.onButton",
                            "CUSTOM_DRAG_RELEASE_CANCEL screen="
                                    + (currentScreen == null ? "null" : currentScreen.getClass().getName()));
                }

                // Release ends only the current physical touch. The active scroll
                // baseline is deliberately preserved for the next touch.
                DragScrollState.leftButtonHeld = false;
            } else {
                return;
            }

            DragScrollState.debugEvent("MouseHandler.onButton",
                    "MOD_LEFT_HELD=" + DragScrollState.leftButtonHeld
                            + " rawButton=" + buttonInfo.button()
                            + " action=" + action
                            + " touchDragged=" + DragScrollState.currentTouchDragged);

            if (action != InputConstants.PRESS) {
                return;
            }

            Screen screen = this.minecraft.gui.screen();
            if (screen == null || this.minecraft.gui.overlay() != null) {
                return;
            }

            Window window = this.minecraft.getWindow();
            this.dragscroll$pressGuiX = this.getScaledXPos(window);
            this.dragscroll$pressGuiY = this.getScaledYPos(window);
            this.dragscroll$lastGuiX = Double.NaN;
            this.dragscroll$lastGuiY = Double.NaN;
            this.dragscroll$wasLeft = false;

            if (DragScrollState.active
                    && (DragScrollState.lockedArea != null || DragScrollState.fallbackScrollScreen == screen)) {
                // Continuation touch: preserve the active scroll session and
                // restart only the virtual movement origin.
                DragScrollState.debugEvent("MouseHandler.onButton",
                        "CONTINUE_ACTIVE area="
                                + (DragScrollState.lockedArea == null ? "custom-screen" : DragScrollState.lockedArea.getClass().getName())
                                + " screen=" + screen.getClass().getName());
            } else {
                // This snapshot is intentionally taken before mouseClicked is run.
                DragScrollState.clearPressSnapshot();
                dragscroll$snapshotAll(screen);
            }

            // The launcher may have teleported the cursor to this touch
            // point before the press callback. Do not let that pending cursor
            // delta become the first drag/scroll delta. We take the snapshot
            // first, so the pre-teleport scroll position is still preserved.
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
        } catch (Throwable ignored) {
            // Input compatibility code must never be allowed to crash the game.
        }
    }

    /**
     * Intercepts the screen-level mouse click before it reaches a button.
     *
     * The mobile launcher can deliver the first touch as an ordinary LMB press.
     * A button therefore may be activated before the following mouse movement
     * reveals that the gesture is actually a drag. The click is deferred at the
     * Screen dispatch point, where every screen-specific mouseClicked override
     * is reached through the same call from MouseHandler.onButton().
     */
    @Redirect(
            method = "onButton",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"
            ),
            require = 0
    )
    private boolean dragscroll$deferScrollAreaClick(
            Screen screen, MouseButtonEvent event, boolean doubleClick) {
        try {
            if (screen != null
                    && event != null
                    && DragScrollState.isLeftButton(event.button())
                    && !DragScrollState.isReplaying()) {
                // The initial touch can begin on a button or another widget
                // outside the scroll area and enter the scrollable content only
                // after the finger starts moving. Therefore the initial click
                // cannot be filtered by the starting widget. Defer every LMB
                // screen click while a GUI is open, then decide on release or
                // real vertical movement.
                DragScrollState.deferScreenClick(screen, event, doubleClick);
                return true;
            }
        } catch (Throwable ignored) {
            // Input compatibility code must never be allowed to crash the game.
        }

        return screen != null && screen.mouseClicked(event, doubleClick);
    }

    /**
     * A tap is replayed immediately after the corresponding LMB release.
     * A real drag has already cancelled the pending click before this point.
     */
    @Inject(method = "onButton", at = @At("TAIL"))
    private void dragscroll$afterButton(long handle, MouseButtonInfo buttonInfo, int action,
                                         CallbackInfo ci) {
        DragScrollState.debugEvent("MouseHandler.onButton",
                "TAIL button=" + (buttonInfo == null ? "null" : buttonInfo.button())
                        + " action=" + action
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld
                        + " pending=" + DragScrollState.isPendingScreenClick()
                        + " active=" + DragScrollState.active);
        try {
            if (buttonInfo != null
                    && DragScrollState.isLeftButton(buttonInfo.button())
                    && action == InputConstants.RELEASE) {
                DragScrollState.finishPendingClick();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void dragscroll$beforeVanillaMove(long handle, double xpos, double ypos,
                                               double xrel, double yrel, CallbackInfo ci) {
        DragScrollState.debugEvent("MouseHandler.onMove",
                "HEAD x=" + xpos + " y=" + ypos
                        + " xrel=" + xrel + " yrel=" + yrel
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld
                        + " accumulatedDX=" + this.accumulatedDX
                        + " accumulatedDY=" + this.accumulatedDY
                        + " active=" + DragScrollState.active);
        try {
            Screen screen = this.minecraft.gui.screen();
            if (screen == null || this.minecraft.gui.overlay() != null) return;
            if (!DragScrollState.leftButtonHeld || DragScrollState.active) return;

            Window window = this.minecraft.getWindow();
            double guiX = MouseHandler.getScaledXPos(window, xpos);
            double guiY = MouseHandler.getScaledYPos(window, ypos);

            // If the launcher sends an onMove at exactly the press position,
            // that event is only the cursor teleport to the new touch.
            if (!Double.isNaN(this.dragscroll$pressGuiX)
                    && Math.abs(guiX - this.dragscroll$pressGuiX) < 0.01
                    && Math.abs(guiY - this.dragscroll$pressGuiY) < 0.01) {
                // If the cursor teleport is delivered after onButton(), the
                // teleport has already been added to accumulatedDX/DY. Clear
                // it here as well. This makes both callback orderings safe:
                //   onMove(teleport) -> onButton(press)
                //   onButton(press) -> onMove(teleport)
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                return;
            }

            // Do not classify tiny touch jitter as a drag. The mobile launcher
            // commonly emits sub-pixel mouse movement while a finger is resting
            // on the screen; cancelling the pending click for that movement makes
            // ordinary LMB taps unreliable.
            double fromPressX = guiX - this.dragscroll$pressGuiX;
            double fromPressY = guiY - this.dragscroll$pressGuiY;
            if (Double.isNaN(this.dragscroll$pressGuiX)
                    || Double.isNaN(this.dragscroll$pressGuiY)
                    || Math.abs(fromPressY) < DragScrollConfig.getDragThreshold()
                    || Math.abs(fromPressY) < Math.abs(fromPressX) * 0.35) return;

            AbstractScrollArea area = dragscroll$findScrollArea(screen, guiX, guiY);
            if (area != null) {
                // This runs before vanilla handles this exact mouse move.
                // Restore the pre-click scroll value now, not one frame later.
                dragscroll$beginRealDrag(area);
                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();

                // handleAccumulatedMovement runs later in the same frame.
                // Make it treat the press position as the virtual origin so
                // this first real finger movement is not lost.
                this.dragscroll$lastGuiX = this.dragscroll$pressGuiX;
                this.dragscroll$lastGuiY = this.dragscroll$pressGuiY;
                this.dragscroll$wasLeft = true;
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void dragscroll$beforeVanillaMovement(CallbackInfo ci) {
        Screen screen = this.minecraft.gui.screen();
        DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                "HEAD screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld
                        + " accumulatedDX=" + this.accumulatedDX
                        + " accumulatedDY=" + this.accumulatedDY
                        + " active=" + DragScrollState.active
                        + " pending=" + DragScrollState.isPendingScreenClick());

        if (screen == null || this.minecraft.gui.overlay() != null) {
            dragscroll$resetAll();
            dragscroll$wasLeft = false;
            dragscroll$lastScreen = null;
            return;
        }

        boolean left = DragScrollState.leftButtonHeld;

        // Never carry a gesture or a press snapshot into another screen.
        if (screen != dragscroll$lastScreen) {
            dragscroll$lastScreen = screen;
            DragScrollState.cancelPendingClick();
            dragscroll$resetAll();
            dragscroll$wasLeft = left;
            return;
        }

        // Finger released: preserve an active drag session for the next touch.
        if (!left) {
            // If the current touch never became a drag, replay its deferred click.
            // A real drag has already discarded the pending click. Persistent
            // scroll state is intentionally kept for the next touch.
            DragScrollState.finishPendingClick();

            AbstractScrollArea locked = DragScrollState.lockedArea;
            if (DragScrollState.active && locked != null) {
                try {
                    if (DragScrollState.moved) {
                        locked.setScrollAmount(DragScrollState.lockedScroll);
                    }
                    DragScrollState.remember(locked, locked.scrollAmount());
                } catch (Throwable ignored) {
                }
            }

            // For a plain click there may be no active drag. Remembering all
            // areas here gives the next touch a clean baseline too.
            dragscroll$rememberAll(screen);
            dragscroll$resetTrackingOnly();

            if (DragScrollState.active
                    && (DragScrollState.lockedArea != null || DragScrollState.fallbackScrollScreen == screen)) {
                // A real drag survives the gap between touches. The launcher
                // creates a fresh LMB press for every finger contact.
                if (DragScrollState.lockedArea != null) {
                    try {
                        DragScrollState.baseScroll = DragScrollState.lockedArea.scrollAmount();
                        DragScrollState.lockedScroll = DragScrollState.baseScroll;
                    } catch (Throwable ignored) {
                    }
                }
                DragScrollState.moved = false;
                DragScrollState.totalDy = 0.0;
            } else {
                DragScrollState.active = false;
                DragScrollState.moved = false;
                DragScrollState.lockedArea = null;
                DragScrollState.baseScroll = 0.0;
                DragScrollState.totalDy = 0.0;
                DragScrollState.lockedScroll = 0.0;
            }
            DragScrollState.currentTouchDragged = false;
            DragScrollState.clearPressSnapshot();
            this.dragscroll$pressGuiX = Double.NaN;
            this.dragscroll$pressGuiY = Double.NaN;
            dragscroll$wasLeft = false;
            return;
        }

        Window window = this.minecraft.getWindow();
        double guiX = this.getScaledXPos(window);
        double guiY = this.getScaledYPos(window);

        boolean fingerDown = !dragscroll$wasLeft;
        dragscroll$wasLeft = true;

        // The launcher has just teleported the mouse to this touch point.
        // Establish a new virtual drag origin and NEVER use the teleport as dx/dy.
        if (fingerDown || Double.isNaN(dragscroll$lastGuiX)) {
            dragscroll$lastGuiX = guiX;
            dragscroll$lastGuiY = guiY;
            return;
        }

        double dx = guiX - dragscroll$lastGuiX;
        double dy = guiY - dragscroll$lastGuiY;
        double stepDy = dy;

        // Until the finger has travelled far enough from the touch origin, keep
        // the gesture classified as a tap. This preserves normal LMB presses
        // despite the small cursor jitter produced by touch input.
        double fromPressX = guiX - this.dragscroll$pressGuiX;
        double fromPressY = guiY - this.dragscroll$pressGuiY;
        if (!DragScrollState.active
                && (Double.isNaN(this.dragscroll$pressGuiX)
                || Double.isNaN(this.dragscroll$pressGuiY)
                || Math.abs(fromPressY) < DragScrollConfig.getDragThreshold()
                || Math.abs(fromPressY) < Math.abs(fromPressX) * 0.35)) {
            dragscroll$lastGuiX = guiX;
            dragscroll$lastGuiY = guiY;
            return;
        }

        // A persistent scroll session still needs the same touch slop. A tiny
        // movement after a previous scroll must not cancel an ordinary button tap.
        if (DragScrollState.active
                && (Double.isNaN(this.dragscroll$pressGuiX)
                || Double.isNaN(this.dragscroll$pressGuiY)
                || Math.abs(fromPressY) < DragScrollConfig.getDragThreshold()
                || Math.abs(fromPressY) < Math.abs(fromPressX) * 0.35)) {
            dragscroll$lastGuiX = guiX;
            dragscroll$lastGuiY = guiY;
            return;
        }

        dragscroll$lastGuiX = guiX;
        dragscroll$lastGuiY = guiY;

        AbstractScrollArea area;
        if (DragScrollState.active && DragScrollState.lockedArea != null) {
            area = DragScrollState.lockedArea;
            // A persistent scroll session continues across launcher touch releases.
            // Movement beyond the touch threshold means that the current LMB
            // gesture is a drag, so any deferred click stays cancelled.
            DragScrollState.currentTouchDragged = true;
            DragScrollState.cancelPendingClick();
        } else if (DragScrollState.active && DragScrollState.fallbackScrollScreen == screen) {
            area = null;
            DragScrollState.currentTouchDragged = true;
            DragScrollState.cancelPendingClick();
        } else {
            area = dragscroll$findScrollArea(screen, guiX, guiY);
            if (area != null) {
                dragscroll$beginRealDrag(area);
                DragScrollState.currentTouchDragged = true;
            } else {
                dragscroll$beginCustomScreenDrag(screen);
                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();
            }
        }

        // Once the threshold is crossed, use the complete displacement from the
        // touch origin for AbstractScrollArea implementations so the first few
        // pixels are not lost. Custom screens use the incremental movement.
        dy = guiY - this.dragscroll$pressGuiY;

        if (area == null) {
            DragScrollState.moved = true;
            dragscroll$scrollAccum += stepDy;
            while (Math.abs(dragscroll$scrollAccum) >= SCROLL_PIXELS_PER_NOTCH) {
                double notch = dragscroll$scrollAccum > 0 ? 1.0 : -1.0;
                dragscroll$scrollAccum -= notch * SCROLL_PIXELS_PER_NOTCH;
                double scrollDelta = DragScrollState.fallbackScrollInverted ? notch : -notch;
                try {
                    screen.mouseScrolled(guiX, guiY, 0.0, scrollDelta);
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        DragScrollState.moved = true;
        DragScrollState.totalDy = dy;
        DragScrollState.lockedScroll = DragScrollState.baseScroll - DragScrollState.totalDy;
        area.setScrollAmount(DragScrollState.lockedScroll);
        DragScrollState.remember(area, DragScrollState.lockedScroll);
    }

    /**
     * Vanilla then processes the same movement. Re-apply the locked value afterwards
     * so a selection/click/ensureVisible cannot overwrite the drag position.
     */
    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void dragscroll$afterVanillaMovement(CallbackInfo ci) {
        DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                "RETURN active=" + DragScrollState.active
                        + " moved=" + DragScrollState.moved
                        + " totalDy=" + DragScrollState.totalDy
                        + " lockedScroll=" + DragScrollState.lockedScroll
                        + " pending=" + DragScrollState.isPendingScreenClick());
        if (DragScrollState.active && DragScrollState.lockedArea != null) {
            try {
                DragScrollState.lockedArea.setScrollAmount(DragScrollState.lockedScroll);
                DragScrollState.remember(DragScrollState.lockedArea, DragScrollState.lockedScroll);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Convert a possible new touch into a real drag.
     *
     * This is the important operation: the press snapshot was taken BEFORE
     * mouseClicked. If that click selected another row and Minecraft moved
     * the list, the old position is restored here, immediately before the
     * first real finger movement is applied.
     */
    @Unique
    private static void dragscroll$beginCustomScreenDrag(Screen screen) {
        DragScrollState.active = true;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = null;
        DragScrollState.fallbackScrollScreen = screen;
        DragScrollState.fallbackScrollInverted = dragscroll$isInvertedCustomScrollScreen(screen);
        DragScrollState.totalDy = 0.0;
        DragScrollState.debugEvent("MouseHandler.beginCustomScreenDrag",
                "screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " inverted=" + DragScrollState.fallbackScrollInverted);
    }

    @Unique
    private static boolean dragscroll$isInvertedCustomScrollScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("sodium");
    }

    @Unique
    private static void dragscroll$beginRealDrag(AbstractScrollArea area) {
        DragScrollState.debugEvent("MouseHandler.beginRealDrag",
                "area=" + (area == null ? "null" : area.getClass().getName())
                        + " activeBefore=" + DragScrollState.active
                        + " leftHeld=" + DragScrollState.leftButtonHeld);
        double start = area.scrollAmount();

        try {
            Double press = DragScrollState.pressSnapshot(area);
            if (press != null) {
                start = press;
                area.setScrollAmount(press);
            } else {
                Double remembered = DragScrollState.recall(area);
                if (remembered != null) {
                    start = remembered;
                    area.setScrollAmount(remembered);
                }
            }
        } catch (Throwable ignored) {
        }

        DragScrollState.active = true;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = area;
        DragScrollState.baseScroll = start;
        DragScrollState.lockedScroll = start;
        DragScrollState.totalDy = 0.0;
        DragScrollState.debugEvent("MouseHandler.beginRealDrag",
                "ACTIVE_SET start=" + start
                        + " locked=" + (DragScrollState.lockedArea == area));
    }

    @Unique
    private void dragscroll$resetTrackingOnly() {
        dragscroll$lastGuiX = Double.NaN;
        dragscroll$lastGuiY = Double.NaN;
        dragscroll$scrollAccum = 0.0;
    }

    @Unique
    private void dragscroll$resetAll() {
        DragScrollState.cancelPendingClick();
        dragscroll$resetTrackingOnly();
        this.dragscroll$pressGuiX = Double.NaN;
        this.dragscroll$pressGuiY = Double.NaN;
        DragScrollState.clear();
    }

    @Unique
    private static void dragscroll$snapshotAll(GuiEventListener node) {
        if (node instanceof AbstractScrollArea area) {
            DragScrollState.snapshot(area);
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                dragscroll$snapshotAll(child);
            }
        }
    }

    @Unique
    private static void dragscroll$rememberAll(GuiEventListener node) {
        if (node instanceof AbstractScrollArea area) {
            try {
                DragScrollState.remember(area, area.scrollAmount());
            } catch (Throwable ignored) {
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                dragscroll$rememberAll(child);
            }
        }
    }

    @Unique
    private static AbstractScrollArea dragscroll$findScrollArea(GuiEventListener root, double x, double y) {
        List<AbstractScrollArea> found = new ArrayList<>();
        dragscroll$collect(root, x, y, found);
        AbstractScrollArea result = found.isEmpty() ? null : found.get(found.size() - 1);
        DragScrollState.debugEvent("MouseHandler.findScrollArea",
                "x=" + x + " y=" + y
                        + " found=" + found.size()
                        + " result=" + (result == null ? "null" : result.getClass().getName())
                        + " leftHeld=" + DragScrollState.leftButtonHeld);
        return result;
    }

    @Unique
    private static void dragscroll$collect(GuiEventListener node, double x, double y,
                                           List<AbstractScrollArea> out) {
        if (node instanceof AbstractScrollArea area) {
            try {
                if (area.isMouseOver(x, y)) {
                    out.add(area);
                }
            } catch (Throwable ignored) {
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                dragscroll$collect(child, x, y, out);
            }
        }
    }
}
