package ru.evga314.dragscroll.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
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
import ru.evga314.dragscroll.access.NativeScrollbarAccess;

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
     * Snapshot scroll positions before Minecraft processes the new click.
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
                DragScrollState.nativeSliderHeld = false;
                DragScrollState.nativeScrollbarHeld = false;
                DragScrollState.sliderGestureLocked = false;
                // Keep coasting until a real drag starts (browser-like).
                if (DragScrollState.inertiaActive) {
                    // Snapshot residual so a later flick can stack on top.
                    DragScrollState.residualVelocity = DragScrollState.scrollVelocity;
                    DragScrollState.debugEvent("MouseHandler.onButton",
                            "INERTIA_TOUCH velocity=" + DragScrollState.scrollVelocity
                                    + " residual=" + DragScrollState.residualVelocity
                                    + " pos=" + DragScrollState.lockedScroll);
                }
                DragScrollState.leftButtonHeld = true;
                DragScrollState.currentTouchDragged = false;
            } else if (action == InputConstants.RELEASE) {
                DragScrollState.nativeControlHeld = false;
                DragScrollState.sliderGestureLocked = false;
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

                // Start momentum coasting after any real drag.
                // No minimum-speed gate: even a slow flick may carry a little
                // residual velocity. Averaged samples keep the value stable.
                // If this was only a tap during a paused coast, resume residual.
                if (DragScrollState.currentTouchDragged && DragScrollState.active) {
                    // Stack residual + new flick velocity.
                    double releaseVel = DragScrollState.computeReleaseVelocity();
                    DragScrollState.scrollVelocity = releaseVel;
                    if (Math.abs(releaseVel) > 0.01 && currentScreen != null) {
                        if (DragScrollState.lockedArea != null) {
                            DragScrollState.baseScroll = DragScrollState.lockedScroll;
                        }
                        DragScrollState.inertiaActive = true;
                        ru.evga314.dragscroll.DragScrollClient.markInertiaStarted();
                        DragScrollState.debugEvent("MouseHandler.onButton",
                                "INERTIA_START velocity=" + releaseVel
                                        + " residual=" + DragScrollState.residualVelocity
                                        + " pos=" + DragScrollState.lockedScroll);
                    } else {
                        DragScrollState.stopInertia();
                    }
                } else if (DragScrollState.inertiaActive) {
                    // Plain tap during coast: keep residual.
                    DragScrollState.debugEvent("MouseHandler.onButton",
                            "INERTIA_KEEP velocity=" + DragScrollState.scrollVelocity
                                    + " pos=" + DragScrollState.lockedScroll);
                } else {
                    // No drag, no coast.
                    DragScrollState.stopInertia();
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

            // Container/inventory screens and any GUI that has no scrollable
            // content must stay fully vanilla. Deferral + custom drag break
            // slot dragging, free-move icons (e.g. Blood Vignette position
            // editor), and other non-list interactions.
            if (dragscroll$isContainerScreen(screen)
                    || !dragscroll$screenSupportsScroll(screen)) {
                DragScrollState.cancelPendingClick();
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.nativeControlHeld = false;
                DragScrollState.nativeSliderHeld = false;
                DragScrollState.nativeScrollbarHeld = false;
                DragScrollState.sliderGestureLocked = false;
                DragScrollState.lockedArea = null;
                DragScrollState.fallbackScrollScreen = null;
                DragScrollState.clearPressSnapshot();
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                DragScrollState.debugEvent("MouseHandler.onButton",
                        "VANILLA_INPUT_ONLY screen="
                                + screen.getClass().getName()
                                + " container=" + dragscroll$isContainerScreen(screen));
                return;
            }

            Window window = this.minecraft.getWindow();
            this.dragscroll$pressGuiX = this.getScaledXPos(window);
            this.dragscroll$pressGuiY = this.getScaledYPos(window);
            this.dragscroll$lastGuiX = Double.NaN;
            this.dragscroll$lastGuiY = Double.NaN;
            this.dragscroll$wasLeft = false;

            // Do not require the touch point to be inside the scroll area's
            // content hitbox before testing its native scrollbar. In Minecraft
            // 26.3 the scrollbar can occupy an edge region that is handled by
            // the area itself but is not reported by isMouseOver(). Requiring
            // both tests made identical physical touches intermittently miss
            // native scrollbar acquisition on mobile launchers.
            AbstractScrollArea pressArea = dragscroll$findScrollArea(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);
            AbstractScrollArea nativeScrollbarArea = dragscroll$findNativeScrollbar(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);
            boolean overNativeScrollbar = nativeScrollbarArea != null;
            if (overNativeScrollbar) {
                pressArea = nativeScrollbarArea;
            }
            boolean overCustomScrollBar = !overNativeScrollbar
                    && dragscroll$isScrollBarTarget(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);
            boolean overSlider = !overNativeScrollbar && !overCustomScrollBar
                    && dragscroll$isSliderTarget(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);
            // Sliders inside a still-scrollable list must not claim native ownership
            // on press: that would fire mouseClicked (sound + brief highlight) before
            // the gesture can become a vertical scroll. They stay deferred until a
            // clearly horizontal move re-engages them, or a vertical move scrolls.
            boolean sliderClaimsNative = overSlider
                    && !dragscroll$isSliderInsideScrollable(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);

            if (overNativeScrollbar || overCustomScrollBar || sliderClaimsNative) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = overNativeScrollbar || overCustomScrollBar;
                DragScrollState.nativeSliderHeld = sliderClaimsNative
                        && !overNativeScrollbar && !overCustomScrollBar;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.moved = false;
                DragScrollState.lockedArea = null;
                DragScrollState.fallbackScrollScreen = null;
                DragScrollState.clearPressSnapshot();
                // The mobile launcher can move the cursor from the previous
                // touch position to the new touch position before this press.
                // That teleport is stored in MouseHandler.accumulatedDX/DY.
                // Native control handling must start from zero movement here.
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;

                DragScrollState.debugEvent("MouseHandler.onButton",
                        "NATIVE_CONTROL_PRESS type="
                                + (overNativeScrollbar || overCustomScrollBar ? "scrollbar" : "slider")
                                + " area=" + (pressArea == null ? "none" : pressArea.getClass().getName())
                                + " TELEPORT_DELTA_CLEARED");
                return;
            }

            if (DragScrollState.active) {
                // A persistent scroll session may survive a release, but the next
                // touch can start inside a different scroll area on the same screen.
                // Rebind the session to the area under the new touch instead of
                // continuing to drive the area from the previous touch.
                AbstractScrollArea currentArea = dragscroll$findScrollArea(screen,
                        this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);

                if (currentArea != null) {
                    if (DragScrollState.lockedArea != currentArea) {
                        DragScrollState.lockedArea = currentArea;
                        DragScrollState.fallbackScrollScreen = null;
                        DragScrollState.clearPressSnapshot();
                        DragScrollState.snapshot(currentArea);
                        Double remembered = DragScrollState.recall(currentArea);
                        double start = remembered != null ? remembered : currentArea.scrollAmount();
                        DragScrollState.baseScroll = start;
                        DragScrollState.lockedScroll = start;
                        DragScrollState.moved = false;
                        DragScrollState.debugEvent("MouseHandler.onButton",
                                "SWITCH_ACTIVE_AREA area=" + currentArea.getClass().getName()
                                        + " screen=" + screen.getClass().getName());
                    } else {
                        DragScrollState.debugEvent("MouseHandler.onButton",
                                "CONTINUE_ACTIVE area=" + currentArea.getClass().getName()
                                        + " screen=" + screen.getClass().getName());
                    }
                } else if (DragScrollState.fallbackScrollScreen == screen) {
                    // Continue a custom screen-level scroll when the touch is not
                    // inside an AbstractScrollArea.
                    DragScrollState.debugEvent("MouseHandler.onButton",
                            "CONTINUE_ACTIVE area=custom-screen screen=" + screen.getClass().getName());
                } else {
                    // Keep the persistent session, but allow the next movement to
                    // discover a new scroll area if the touch starts outside one.
                    DragScrollState.lockedArea = null;
                    DragScrollState.fallbackScrollScreen = null;
                    DragScrollState.clearPressSnapshot();
                    DragScrollState.debugEvent("MouseHandler.onButton",
                            "ACTIVE_WAIT_FOR_AREA screen=" + screen.getClass().getName());
                }
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
                // Container screens and screens with no scrollable content must
                // receive the original press immediately (slots, free-drag icons…).
                if (dragscroll$isContainerScreen(screen)
                        || !dragscroll$screenSupportsScroll(screen)) {
                    DragScrollState.cancelPendingClick();
                    DragScrollState.debugEvent("MouseHandler.deferClick",
                            "VANILLA_DIRECT_DISPATCH screen="
                                    + screen.getClass().getName());
                    return screen.mouseClicked(event, doubleClick);
                }

                // Native scrollbars and slider-like controls must receive the
                // original press event immediately. Deferring a scrollbar press
                // leaves AbstractScrollArea without the native drag state for the
                // first touch, which makes the first drag appear to do nothing.
                AbstractScrollArea nativeScrollbar = dragscroll$findNativeScrollbar(
                        screen, event.x(), event.y());
                if (nativeScrollbar != null) {
                    DragScrollState.cancelPendingClick();
                    DragScrollState.debugEvent("MouseHandler.deferClick",
                            "NATIVE_SCROLLBAR_DIRECT_DISPATCH area="
                                    + nativeScrollbar.getClass().getName());
                    return screen.mouseClicked(event, doubleClick);
                }

                if (dragscroll$isScrollBarTarget(screen, event.x(), event.y())) {
                    DragScrollState.cancelPendingClick();
                    DragScrollState.debugEvent("MouseHandler.deferClick",
                            "CUSTOM_SCROLLBAR_DIRECT_DISPATCH");
                    return screen.mouseClicked(event, doubleClick);
                }

                if (dragscroll$isDirectManipulationTarget(screen, event.x(), event.y())) {
                    DragScrollState.debugEvent("MouseHandler.deferClick", "DIRECT_MANIPULATION_TARGET");
                    return screen.mouseClicked(event, doubleClick);
                }

                // The initial touch can begin on a button or another widget
                // outside the scroll area and enter the scrollable content only
                // after the finger starts moving. Therefore the initial click
                // cannot be filtered by the starting widget. Defer ordinary LMB
                // screen clicks while a GUI is open, then decide on release or
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
            if (dragscroll$isContainerScreen(screen)
                    || !dragscroll$screenSupportsScroll(screen)) {
                // No scrollable content (or a container): leave movement to vanilla.
                return;
            }
            // Native sliders and scrollbars must keep ownership of the entire
            // physical touch. Without this guard, the custom drag detector
            // takes over after the threshold and can overwrite the native
            // control position.
            if (DragScrollState.nativeControlHeld) {
                if (!DragScrollState.nativeSliderHeld) {
                    return;
                }

                // After the user has committed to horizontal slider dragging,
                // keep the slider for the rest of this touch. Vertical jitter
                // on mobile must not cancel the value adjustment mid-gesture.
                if (DragScrollState.sliderGestureLocked) {
                    return;
                }

                Window window = this.minecraft.getWindow();
                double guiX = MouseHandler.getScaledXPos(window, xpos);
                double guiY = MouseHandler.getScaledYPos(window, ypos);
                double fromPressX = guiX - this.dragscroll$pressGuiX;
                double fromPressY = guiY - this.dragscroll$pressGuiY;

                // Lock the gesture to the slider as soon as movement is clearly
                // horizontal. Further vertical noise cannot steal the touch.
                double threshold = DragScrollConfig.getDragThreshold();
                if (!Double.isNaN(this.dragscroll$pressGuiX)
                        && !Double.isNaN(this.dragscroll$pressGuiY)
                        && Math.abs(fromPressX) >= threshold
                        && Math.abs(fromPressX) > Math.abs(fromPressY) * 0.75) {
                    DragScrollState.sliderGestureLocked = true;
                    DragScrollState.debugEvent("MouseHandler.onMove",
                            "SLIDER_GESTURE_LOCKED dx=" + fromPressX
                                    + " dy=" + fromPressY);
                    return;
                }

                // Hand off to list scroll only for a strongly vertical gesture
                // that has not yet locked onto the slider. Require both a larger
                // absolute dy and clear vertical dominance so ordinary slider
                // drags with slight vertical noise stay on the slider.
                // Also only hand off when the list under the finger can actually
                // scroll (maxScrollAmount > 0); otherwise the gesture stays on
                // the slider and the value is not forced back by a useless drag.
                if (Double.isNaN(this.dragscroll$pressGuiX)
                        || Double.isNaN(this.dragscroll$pressGuiY)
                        || Math.abs(fromPressY) < threshold * 2.5
                        || Math.abs(fromPressY) <= Math.abs(fromPressX) * 1.15) {
                    return;
                }

                Screen transferScreen = this.minecraft.gui.screen();
                if (transferScreen == null || this.minecraft.gui.overlay() != null) {
                    return;
                }
                AbstractScrollArea transferArea = dragscroll$findScrollArea(
                        transferScreen, guiX, guiY);
                boolean canScroll = false;
                if (transferArea != null) {
                    try {
                        canScroll = transferArea.maxScrollAmount() > 0.0;
                    } catch (Throwable ignored) {
                        canScroll = true;
                    }
                }
                if (!canScroll && !dragscroll$isCustomScrollScreen(transferScreen)) {
                    return;
                }

                DragScrollState.nativeControlHeld = false;
                DragScrollState.nativeSliderHeld = false;
                DragScrollState.sliderGestureLocked = false;
                DragScrollState.currentTouchDragged = true;
                DragScrollState.active = false;
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;

                if (transferArea != null && canScroll) {
                    dragscroll$beginRealDrag(transferArea);
                } else if (dragscroll$isCustomScrollScreen(transferScreen)) {
                    dragscroll$beginCustomScreenDrag(transferScreen);
                } else {
                    return;
                }

                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();
                this.dragscroll$lastGuiX = this.dragscroll$pressGuiX;
                this.dragscroll$lastGuiY = this.dragscroll$pressGuiY;
                this.dragscroll$wasLeft = true;
                this.dragscroll$scrollAccum = 0.0;
                DragScrollState.debugEvent("MouseHandler.onMove",
                        "SLIDER_TO_SCROLL_HANDOFF dx=" + fromPressX
                                + " dy=" + fromPressY
                                + " screen=" + transferScreen.getClass().getName());
            }

            if (DragScrollState.nativeControlHeld) return;

            // Deferred press landed on a slider inside a scrollable list.
            // A clearly horizontal move means the user wants the slider, not scroll:
            // replay the pending click so the slider enters its native drag state.
            // Threshold is deliberately stricter than the ordinary drag threshold
            // so ordinary vertical-scroll jitter does not produce a click sound
            // and a one-frame value flash on every list swipe that starts on a slider.
            //
            // Important: this must also work while a previous scroll session is still
            // marked active. Otherwise the first list scroll permanently blocks all
            // subsequent slider adjustments on the same screen until the session ends.
            if (DragScrollState.isPendingScreenClick()
                    && DragScrollState.leftButtonHeld
                    && !Double.isNaN(this.dragscroll$pressGuiX)
                    && !Double.isNaN(this.dragscroll$pressGuiY)) {
                Window engageWindow = this.minecraft.getWindow();
                double engageX = MouseHandler.getScaledXPos(engageWindow, xpos);
                double engageY = MouseHandler.getScaledYPos(engageWindow, ypos);
                double engageDx = engageX - this.dragscroll$pressGuiX;
                double engageDy = engageY - this.dragscroll$pressGuiY;
                double horizThreshold = Math.max(DragScrollConfig.getDragThreshold() * 1.75, 7.0);
                if (Math.abs(engageDx) >= horizThreshold
                        && Math.abs(engageDx) > Math.abs(engageDy) * 1.15
                        && dragscroll$isSliderTarget(screen, this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
                    DragScrollState.finishPendingClick();
                    // Leave any previous list-scroll session so the slider owns the touch.
                    DragScrollState.active = false;
                    DragScrollState.moved = false;
                    DragScrollState.lockedArea = null;
                    DragScrollState.fallbackScrollScreen = null;
                    DragScrollState.lockedScroll = 0.0;
                    DragScrollState.nativeControlHeld = true;
                    DragScrollState.nativeSliderHeld = true;
                    DragScrollState.nativeScrollbarHeld = false;
                    // Commit to the slider for the rest of this touch so vertical
                    // noise cannot cancel value adjustment mid-drag.
                    DragScrollState.sliderGestureLocked = true;
                    DragScrollState.currentTouchDragged = false;
                    this.accumulatedDX = 0.0;
                    this.accumulatedDY = 0.0;
                    DragScrollState.debugEvent("MouseHandler.onMove",
                            "DEFERRED_SLIDER_HORIZONTAL_ENGAGE dx=" + engageDx
                                    + " dy=" + engageDy
                                    + " thresh=" + horizThreshold);
                    return;
                }
            }

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

            // When the deferred press landed on a slider inside a scrollable list,
            // require clear vertical dominance before turning the gesture into a
            // list scroll. Otherwise a mostly-horizontal slider adjustment with a
            // little vertical noise would start scrolling the list, move the row
            // out from under the finger, and make the slider value appear to snap
            // back. Horizontal engagement is handled above with a matching rule.
            if (DragScrollState.isPendingScreenClick()
                    && dragscroll$isSliderTarget(screen, this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
                double vertThreshold = Math.max(DragScrollConfig.getDragThreshold() * 1.5, 6.0);
                if (Math.abs(fromPressY) < vertThreshold
                        || Math.abs(fromPressY) <= Math.abs(fromPressX) * 1.15) {
                    return;
                }
            }

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
            } else if (dragscroll$isCustomScrollScreen(screen)) {
                // Screen-level scrollers (Easy Install, Sodium, Cloth, …)
                // without an AbstractScrollArea under the finger.
                dragscroll$beginCustomScreenDrag(screen);
                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();
                this.dragscroll$lastGuiX = this.dragscroll$pressGuiX;
                this.dragscroll$lastGuiY = this.dragscroll$pressGuiY;
                this.dragscroll$wasLeft = true;
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void dragscroll$beforeVanillaMovement(CallbackInfo ci) {
        // Reset each frame; set true only when this call actually scrolls.
        DragScrollState.moved = false;

        Screen screen = this.minecraft.gui.screen();
        DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                "HEAD screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld
                        + " accumulatedDX=" + this.accumulatedDX
                        + " accumulatedDY=" + this.accumulatedDY
                        + " active=" + DragScrollState.active
                        + " pending=" + DragScrollState.isPendingScreenClick());

        if (dragscroll$isContainerScreen(screen)
                || !dragscroll$screenSupportsScroll(screen)) {
            // Keep non-scroll / container screens fully vanilla.
            DragScrollState.cancelPendingClick();
            DragScrollState.active = false;
            DragScrollState.currentTouchDragged = false;
            DragScrollState.nativeControlHeld = false;
            DragScrollState.nativeSliderHeld = false;
            DragScrollState.nativeScrollbarHeld = false;
            DragScrollState.sliderGestureLocked = false;
            DragScrollState.lockedArea = null;
            DragScrollState.fallbackScrollScreen = null;
            DragScrollState.clearPressSnapshot();
            return;
        }

        // Fallback: keep coasting alive even if Screen.render inject misses this mapping.
        if (DragScrollState.inertiaActive && !DragScrollState.leftButtonHeld && screen != null) {
            ru.evga314.dragscroll.DragScrollClient.applyInertiaFrame(screen);
        }

        if (DragScrollState.nativeControlHeld) {
            // Let Minecraft deliver native slider/scrollbar dragging untouched.
            // The custom drag-scroll state must not overwrite the control value.
            return;
        }

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
            // Inertia owns lockedScroll — do not re-read the widget every frame.
            if (DragScrollState.inertiaActive) {
                DragScrollState.finishPendingClick();
                dragscroll$resetTrackingOnly();
                DragScrollState.currentTouchDragged = false;
                DragScrollState.clearPressSnapshot();
                this.dragscroll$pressGuiX = Double.NaN;
                this.dragscroll$pressGuiY = Double.NaN;
                dragscroll$wasLeft = false;
                return;
            }

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
                // Seed baseline once from the widget, then leave it alone.
                if (DragScrollState.lockedArea != null) {
                    try {
                        DragScrollState.baseScroll = DragScrollState.lockedArea.scrollAmount();
                        DragScrollState.lockedScroll = DragScrollState.baseScroll;
                    } catch (Throwable ignored) {
                    }
                }
                DragScrollState.moved = false;
            } else {
                DragScrollState.active = false;
                DragScrollState.moved = false;
                DragScrollState.lockedArea = null;
                DragScrollState.baseScroll = 0.0;
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
        if (Double.isNaN(this.dragscroll$pressGuiX)
                || Double.isNaN(this.dragscroll$pressGuiY)
                || Math.abs(fromPressY) < DragScrollConfig.getDragThreshold()
                || Math.abs(fromPressY) < Math.abs(fromPressX) * 0.35) {
            dragscroll$lastGuiX = guiX;
            dragscroll$lastGuiY = guiY;
            return;
        }

        // Press started on a slider inside a list: wait for clear vertical intent
        // before driving the list scroll (including a still-active previous session).
        // Otherwise horizontal slider adjustments keep fighting the list and the
        // value appears to snap back as rows move under the finger.
        if (DragScrollState.isPendingScreenClick()
                && dragscroll$isSliderTarget(this.minecraft.gui.screen(),
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
            double vertThreshold = Math.max(DragScrollConfig.getDragThreshold() * 1.5, 6.0);
            if (Math.abs(fromPressY) < vertThreshold
                    || Math.abs(fromPressY) <= Math.abs(fromPressX) * 1.15) {
                dragscroll$lastGuiX = guiX;
                dragscroll$lastGuiY = guiY;
                return;
            }
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
            // Catch coast; keep residual for stacking on release.
            if (DragScrollState.inertiaActive) {
                DragScrollState.catchInertiaForDrag();
            }
        } else if (DragScrollState.active && DragScrollState.fallbackScrollScreen == screen) {
            area = null;
            DragScrollState.currentTouchDragged = true;
            DragScrollState.cancelPendingClick();
        } else {
            area = dragscroll$findScrollArea(screen, guiX, guiY);
            if (area != null) {
                dragscroll$beginRealDrag(area);
                DragScrollState.currentTouchDragged = true;
            } else if (dragscroll$isCustomScrollScreen(screen)) {
                // Only known screen-level scrollers (Easy Install, Sodium,
                // Cloth Config, …) may use the custom path. Random GUIs without
                // AbstractScrollArea must stay vanilla.
                dragscroll$beginCustomScreenDrag(screen);
                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();
            } else {
                // No scroll target under the finger and not a custom scroller:
                // do not steal the gesture.
                return;
            }
        }

        // Delta-based: only stepDy is applied. Horizontal filter ignores sideways noise.
        double applyDy = stepDy;
        double applyDx = dx;
        if (Math.abs(applyDx) > Math.abs(applyDy) * 1.25 && Math.abs(applyDy) < 4.0) {
            applyDy = 0.0;
        }

        DragScrollState.pushVelocitySample(applyDy);
        DragScrollState.inertiaGuiX = guiX;
        DragScrollState.inertiaGuiY = guiY;

        if (area == null) {
            // Continuous fractional mouseScrolled — avoids the 12px notch jumps that
            // made both live drag and the right-hand scrollbar thumb look jerky
            // on Easy Install / Cloth / Sodium custom screens.
            DragScrollState.moved = true;
            double units = applyDy / SCROLL_PIXELS_PER_NOTCH;
            double scrollDelta = DragScrollState.fallbackScrollInverted ? units : -units;
            if (scrollDelta != 0.0) {
                try {
                    screen.mouseScrolled(guiX, guiY, 0.0, scrollDelta);
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        DragScrollState.moved = true;
        // 1:1 finger tracking.
        DragScrollState.lockedScroll = DragScrollState.lockedScroll - applyDy;
        try {
            double max = area.maxScrollAmount();
            if (DragScrollState.lockedScroll < 0.0) {
                DragScrollState.lockedScroll = 0.0;
            } else if (DragScrollState.lockedScroll > max) {
                DragScrollState.lockedScroll = max;
            }
        } catch (Throwable ignored) {
        }
        DragScrollState.baseScroll = DragScrollState.lockedScroll;
        area.setScrollAmount(DragScrollState.lockedScroll);
        DragScrollState.remember(area, DragScrollState.lockedScroll);
    }

    /**
     * Re-apply locked scroll after vanilla only when this call actually moved the list.
     */
    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void dragscroll$afterVanillaMovement(CallbackInfo ci) {
        DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                "RETURN active=" + DragScrollState.active
                        + " moved=" + DragScrollState.moved
                        + " lockedScroll=" + DragScrollState.lockedScroll
                        + " pending=" + DragScrollState.isPendingScreenClick());
        if (DragScrollState.active
                && DragScrollState.moved
                && DragScrollState.lockedArea != null
                && !DragScrollState.inertiaActive) {
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
    private static boolean dragscroll$isNativeScrollbar(AbstractScrollArea area, double x, double y) {
        if (area instanceof NativeScrollbarAccess access) {
            try {
                return access.dragscroll$isOverScrollbar(x, y);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * Finds a native scrollbar without first filtering the parent area through
     * isMouseOver(). This keeps native scrollbar acquisition reliable when the
     * scrollbar occupies an edge region outside the content hitbox.
     */
    @Unique
    private static AbstractScrollArea dragscroll$findNativeScrollbar(GuiEventListener node,
                                                                       double x, double y) {
        if (node == null) {
            return null;
        }
        if (node instanceof AbstractScrollArea area
                && dragscroll$isNativeScrollbar(area, x, y)) {
            return area;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                AbstractScrollArea found = dragscroll$findNativeScrollbar(child, x, y);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }


    /**
     * True when the deepest widget under (x,y) is a slider that lives inside a
     * scrollable context (AbstractScrollArea or a known custom-scroll screen).
     * In that case a vertical swipe should scroll the list, not grab the slider
     * on the initial press (which causes a click sound and value jumps while
     * scrolling — e.g. Sodium VideoSettingsScreen, Cloth Config, YACL).
     *
     * Robustness: on some mobile coordinate paths OptionsList.isMouseOver() can
     * return false even when a child slider is clearly under the finger. If the
     * primary find fails we still treat the slider as "inside a scrollable list"
     * whenever any AbstractScrollArea with maxScrollAmount > 0 exists on the
     * same screen. Custom scroll screens (Sodium, Cloth, Easy Install, …) have
     * no AbstractScrollArea but still scroll via mouseScrolled — treat their
     * sliders the same way so vertical drag never activates them first.
     */
    @Unique
    private static boolean dragscroll$isSliderInsideScrollable(GuiEventListener root, double x, double y) {
        if (!dragscroll$isSliderTarget(root, x, y)) {
            return false;
        }
        // Sodium / Cloth / YACL / Easy Install etc.: screen scrolls without an
        // AbstractScrollArea. Defer the slider press so vertical swipe scrolls
        // the page and only a clear horizontal move engages the control.
        if (root instanceof Screen screen && dragscroll$isCustomScrollScreen(screen)) {
            return true;
        }
        AbstractScrollArea area = dragscroll$findScrollArea(root, x, y);
        if (area != null) {
            try {
                return area.maxScrollAmount() > 0.0;
            } catch (Throwable ignored) {
                return true;
            }
        }
        // Fallback: any scrollable AbstractScrollArea present on this screen.
        return dragscroll$hasScrollableArea(root);
    }

    @Unique
    private static boolean dragscroll$hasScrollableArea(GuiEventListener node) {
        if (node instanceof AbstractScrollArea area) {
            try {
                if (area.maxScrollAmount() > 0.0) {
                    return true;
                }
            } catch (Throwable ignored) {
                return true;
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (dragscroll$hasScrollableArea(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static boolean dragscroll$isDirectManipulationTarget(GuiEventListener root, double x, double y) {
        // Native scrollbars must receive the original press immediately so
        // AbstractScrollArea can enter its native scrolling state.
        if (root instanceof AbstractScrollArea area && dragscroll$isNativeScrollbar(area, x, y)) {
            return true;
        }
        // Custom scrollbar thumbs (Cloth Config, Easy Install, YACL…) must also
        // receive the press immediately. Routing them through inverted content
        // drag makes the thumb scroll the list the wrong way.
        if (dragscroll$isScrollBarTarget(root, x, y)) {
            return true;
        }
        // Sliders outside a scrollable list still get the press immediately.
        // Sliders inside a scrollable AbstractScrollArea are deferred so a
        // vertical swipe can become a list scroll without a click sound.
        if (dragscroll$isSliderTarget(root, x, y) && !dragscroll$isSliderInsideScrollable(root, x, y)) {
            return true;
        }
        return false;
    }

    @Unique
    private static boolean dragscroll$isSliderTarget(GuiEventListener root, double x, double y) {
        if (root == null) {
            return false;
        }
        String name = root.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("slider")) {
            try {
                return root.isMouseOver(x, y);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (root instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (dragscroll$isSliderTarget(child, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static void dragscroll$beginCustomScreenDrag(Screen screen) {
        DragScrollState.active = true;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = null;
        DragScrollState.fallbackScrollScreen = screen;
        DragScrollState.fallbackScrollInverted = dragscroll$isInvertedCustomScrollScreen(screen);
        DragScrollState.catchInertiaForDrag();
        DragScrollState.debugEvent("MouseHandler.beginCustomScreenDrag",
                "screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " inverted=" + DragScrollState.fallbackScrollInverted);
    }

    @Unique
    private static boolean dragscroll$isEasyInstallScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("easy_install") || name.contains("easyinstall");
    }

    /**
     * Screens whose content drag must use inverted mouseScrolled so that
     * finger-down shows lower items (Sodium, Cloth Config, Easy Install, …).
     * Finger scrolling on these UIs is already correct with inverted=true.
     * Custom scrollBAR widgets on the same screens are handled separately
     * (see isScrollBarTarget) so dragging the thumb is not double-inverted.
     */
    private static boolean dragscroll$isInvertedCustomScrollScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("sodium")
                || name.contains("clothconfig")
                || name.contains("cloth_config")
                || name.contains("easy_install")
                || name.contains("easyinstall")
                || name.contains("lambdynlights")
                || name.contains("yacl")
                || name.contains("yetanotherconfig")
                || name.contains("yet_another_config")
                || name.contains("fzzy")
                || name.contains("midnightlib")
                || name.contains("resourcefulconfig")
                || name.contains("spruceui");
    }

    /**
     * Screens that scroll via screen.mouseScrolled without an AbstractScrollArea
     * under the finger (Easy Install, Sodium options, Cloth Config, YACL, …).
     */
    private static boolean dragscroll$isCustomScrollScreen(Screen screen) {
        return dragscroll$isEasyInstallScreen(screen)
                || dragscroll$isInvertedCustomScrollScreen(screen);
    }

    /**
     * Custom scrollbar / scroll-thumb widgets (Cloth Config, Easy Install, YACL…).
     * These are not AbstractScrollArea native bars. Dragging them must go through
     * vanilla — our content-drag inversion would otherwise make the thumb scroll
     * the list the wrong way.
     */
    private static boolean dragscroll$isScrollBarTarget(GuiEventListener root, double x, double y) {
        if (root == null) {
            return false;
        }
        // Full-width custom lists (Easy Install): thin strip at the absolute right edge.
        if (root instanceof Screen screen) {
            try {
                int w = screen.width;
                if (w > 0 && x >= w - 14) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        // Cloth Config / Mod Menu / panel lists: scrollbar is a thin tall widget
        // somewhere on the right of a panel, not necessarily at screen edge.
        // Treat any narrow vertical AbstractWidget under the cursor as a scrollbar.
        if (root instanceof AbstractWidget widget) {
            try {
                if (widget.getWidth() <= 18 && widget.getHeight() >= 32
                        && widget.isMouseOver(x, y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        String name = root.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("scrollbar") || name.contains("scroll_bar")
                || name.contains("scrollhandle") || name.contains("scroll_handle")
                || name.contains("scrollthumb") || name.contains("scroll_thumb")
                || name.contains("scroller") || name.contains("scrollpanel")
                || name.contains("scroll_panel") || name.contains("entrieslist")
                || name.contains("entrylist")) {
            try {
                return root.isMouseOver(x, y);
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (root instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (dragscroll$isScrollBarTarget(child, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when this GUI has something DragScroll is allowed to drive:
     * an AbstractScrollArea in the widget tree, or a known custom scroller.
     * Screens without either (icon editors, free-drag overlays, plain dialogs)
     * must stay fully vanilla.
     */
    private static boolean dragscroll$screenSupportsScroll(Screen screen) {
        if (screen == null) {
            return false;
        }
        if (dragscroll$isCustomScrollScreen(screen)) {
            return true;
        }
        return dragscroll$hasAnyScrollArea(screen);
    }

    private static boolean dragscroll$hasAnyScrollArea(GuiEventListener node) {
        if (node instanceof AbstractScrollArea) {
            return true;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (dragscroll$hasAnyScrollArea(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static void dragscroll$beginRealDrag(AbstractScrollArea area) {
        DragScrollState.debugEvent("MouseHandler.beginRealDrag",
                "area=" + (area == null ? "null" : area.getClass().getName())
                        + " activeBefore=" + DragScrollState.active
                        + " leftHeld=" + DragScrollState.leftButtonHeld);
        double start = area.scrollAmount();

        // Prefer lockedScroll when continuing the same list after coast.
        if (DragScrollState.lockedArea == area
                && (DragScrollState.inertiaActive || Math.abs(DragScrollState.residualVelocity) > 0.01)) {
            start = DragScrollState.lockedScroll;
            try {
                area.setScrollAmount(start);
            } catch (Throwable ignored) {
            }
        } else {
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
        }

        DragScrollState.active = true;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = area;
        DragScrollState.fallbackScrollScreen = null;
        DragScrollState.baseScroll = start;
        DragScrollState.lockedScroll = start;
        DragScrollState.catchInertiaForDrag();
        DragScrollState.debugEvent("MouseHandler.beginRealDrag",
                "ACTIVE_SET start=" + start
                        + " residual=" + DragScrollState.residualVelocity
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
    private static boolean dragscroll$isCreativeInventoryScreen(Screen screen) {
        return screen instanceof CreativeModeInventoryScreen;
    }

    /**
     * Survival inventory, creative inventory, chests, furnaces, hoppers,
     * and every other AbstractContainerScreen share the vanilla slot input
     * pipeline. DragScroll must not defer or reinterpret their clicks.
     */
    private static boolean dragscroll$isContainerScreen(Screen screen) {
        return screen instanceof AbstractContainerScreen
                || dragscroll$isCreativeInventoryScreen(screen);
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
