package ru.evga314.dragscroll.touch;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import ru.evga314.dragscroll.access.MouseHandlerAccess;
import ru.evga314.dragscroll.compat.ChatCompat;
import ru.evga314.dragscroll.compat.ClothCompat;
import ru.evga314.dragscroll.compat.FancyMenuCompat;
import ru.evga314.dragscroll.compat.MalilibCompat;
import ru.evga314.dragscroll.compat.ReesesCompat;
import ru.evga314.dragscroll.compat.ReiCompat;
import ru.evga314.dragscroll.compat.ShulkerCompat;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.compat.XaeroMapZoomOverlay;
import ru.evga314.dragscroll.gui.WheelSetupScreen;
import ru.evga314.dragscroll.wheel.MouseWheelEmulator;

/**
 * LMB press and release (MouseHandler.onButton) and the screen click they
 * cause.
 *
 * <p>A press first decides who owns the touch: the wheel emulator, a
 * FancyMenu or MaLiLib list, a scroll bar or slider (native control), or
 * the generic list drag. For the generic drag the screen click is held back
 * ({@link DeferredClick}) until the finger either moves (drag) or lifts
 * (tap, the click is replayed).
 */
public final class PressHandler {
    private PressHandler() {
    }

    /** How the Screen.mouseClicked of a press is dispatched. */
    public enum Click {
        /** Deliver now. */
        DISPATCH,
        /** Drop; the mod handles the press. */
        SWALLOW,
        /** Hold back until tap or drag is known. */
        DEFER,
        /** Deliver now, then grab the TRender thumb. */
        TRENDER_BAR
    }

    private static double guiX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledXPos(mc.getWindow());
    }

    private static double guiY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledYPos(mc.getWindow());
    }

    // =====================================================================
    // onButton HEAD
    // =====================================================================

    /**
     * Before Minecraft handles the button. Returns true when the whole
     * onButton must be cancelled (the mod replays it later).
     */
    public static boolean onButton(MouseHandlerAccess mouse, MouseButtonInfo info, int action) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        boolean noOverlay = mc.gui.overlay() == null;

        // In game, FancyMenu's menu bar may still be "hovered" from the pause
        // menu and would eat this click.
        if (action == InputConstants.PRESS && screen == null) {
            FancyMenuCompat.clearStaleMenuBarHover();
        }
        if (info == null || !TouchState.isLeftButton(info.button())) {
            return false;
        }
        if (noOverlay && handleWheel(mouse, screen, action)) {
            return false;
        }
        if (noOverlay && FancyMenuCompat.isLoaded()) {
            if (action == InputConstants.PRESS && FancyMenuCompat.onPress(screen, guiX(), guiY(), info)) {
                Inertia.stop();
                takeTouchForControl(mouse);
                return true;
            }
            if (action == InputConstants.RELEASE && FancyMenuCompat.isArmed()) {
                releaseControl();
                FancyMenuCompat.onRelease(screen, guiX(), guiY());
                return true;
            }
        }
        // MaLiLib lists are not AbstractScrollArea and its bar moves from
        // render(); MalilibCompat owns the whole touch on those screens.
        if (noOverlay && MalilibCompat.isMalilibScreen(screen)) {
            if (action == InputConstants.PRESS) {
                boolean alreadyHeld = TouchState.leftButtonHeld;
                TouchState.leftButtonHeld = true;
                mouse.dragscroll$clearAccumulatedMovement();
                MalilibCompat.onPress(screen, guiX(), guiY(), alreadyHeld);
            } else if (action == InputConstants.RELEASE) {
                XaeroMapZoomOverlay.endDrag();
                MalilibCompat.onRelease(screen);
                TouchState.leftButtonHeld = false;
            }
            return false;
        }

        if (action == InputConstants.PRESS) {
            beginTouch(screen);
        } else if (action == InputConstants.RELEASE) {
            endTouch();
            return false;
        } else {
            return false;
        }
        if (Debug.on()) Debug.log("PressHandler.onButton", "PRESS touchDragged=" + TouchState.currentTouchDragged);

        if (screen != null && noOverlay && !ScreenKind.of(screen).malilibFamily) {
            classifyPress(mouse, screen);
        }
        return false;
    }

    /**
     * The on-screen wheel owns a touch that starts on it, on any screen
     * (containers included). The wheel setup screen's preview is excluded:
     * that editor must get the press itself. True when the wheel took it.
     */
    private static boolean handleWheel(MouseHandlerAccess mouse, Screen screen, int action) {
        if (!MouseWheelEmulator.isEnabled()
                || screen instanceof WheelSetupScreen setup && setup.isOverPreview(guiX(), guiY())) {
            return false;
        }
        if (action == InputConstants.PRESS) {
            if (!MouseWheelEmulator.onPress(screen, guiX(), guiY())) {
                return false;
            }
            takeTouchForControl(mouse);
            return true;
        }
        if (action == InputConstants.RELEASE && MouseWheelEmulator.isGrabbing()) {
            // No list inertia and no replayed click: the wheel may have ended
            // up over a list.
            MouseWheelEmulator.onRelease();
            Inertia.stop();
            releaseControl();
            return true;
        }
        return false;
    }

    /** A control the mod drives itself takes the whole touch. */
    private static void takeTouchForControl(MouseHandlerAccess mouse) {
        TouchState.leftButtonHeld = true;
        TouchState.nativeControlHeld = true;
        TouchState.active = false;
        TouchState.currentTouchDragged = false;
        DeferredClick.cancel();
        mouse.dragscroll$clearAccumulatedMovement();
    }

    private static void releaseControl() {
        DeferredClick.cancel();
        TouchState.nativeControlHeld = false;
        TouchState.active = false;
        TouchState.currentTouchDragged = false;
        TouchState.leftButtonHeld = false;
    }

    /** Finger down on any screen (or none): reset the per-touch state. */
    private static void beginTouch(Screen screen) {
        ScreenKind kind = ScreenKind.of(screen);
        // The launcher sends extra PRESS events while the finger still holds
        // the thin TRender bar; they must not drop the grabbed thumb.
        boolean keepTrenderBar = screen != null && kind.trender && TrenderCompat.isSyntheticRepress(System.nanoTime());
        if (screen != null && kind.sodium && !Widgets.isContainerScreen(screen)) {
            Sliders.forceReleaseAll(screen);
        }
        TouchState.nativeSliderHeld = false;
        if (!keepTrenderBar) {
            TouchState.nativeScrollbarHeld = false;
        }
        TouchState.sliderGestureLocked = false;
        // Sodium: keep the locked column across the launcher's repeated
        // presses of one screen-level session; anything else clears it.
        boolean keepSodiumLock = TouchState.active && TouchState.fallbackScreen == screen
                && kind.sodium && SodiumCompat.contentLocked;
        SodiumCompat.resetGrab();
        ReesesCompat.resetGrab();
        ReiCompat.resetGrab();
        if (!keepSodiumLock) {
            SodiumCompat.contentLocked = false;
        }
        ClothCompat.THUMB.reset();
        // Any touch stops a coast at once, so a long hold cannot resume it.
        // The speed is kept only for a following swipe.
        if (Inertia.isMoving()) {
            if (Debug.on()) Debug.log("PressHandler.onButton", "INERTIA_TOUCH_STOP velocity=" + Inertia.velocity
                    + " residual=" + Inertia.residual + " pos=" + TouchState.lockedScroll);
            Inertia.stopCoastKeepResidual();
        }
        TouchState.leftButtonHeld = true;
        if (keepTrenderBar) {
            TrenderCompat.barLocked = true;
            TrenderCompat.barReleaseNs = 0L;
            TouchState.nativeScrollbarHeld = true;
            TouchState.nativeControlHeld = true;
            TouchState.active = false;
            if (Debug.on()) Debug.log("PressHandler.onButton", "TRENDER_BAR_KEEP");
        } else {
            TouchState.currentTouchDragged = false;
            TrenderCompat.releaseBar();
        }
    }

    /** Finger up: end the touch, start inertia after a swipe. */
    private static void endTouch() {
        XaeroMapZoomOverlay.endDrag();
        Screen screen = Minecraft.getInstance().gui.screen();
        // Sliders held by the touch are released now. YACL taps are released
        // in onButton RETURN, after the replayed click.
        if (TouchState.nativeSliderHeld || TouchState.sliderGestureLocked) {
            Sliders.forceReleaseAll(screen);
            if (Debug.on()) Debug.log("PressHandler.onButton", "SLIDER_FORCE_RELEASE");
        }
        TouchState.nativeControlHeld = false;
        TouchState.nativeSliderHeld = false;
        TouchState.sliderGestureLocked = false;
        ClothCompat.THUMB.reset();
        ClothCompat.disarmTouch();
        // A screen-level drag: drop the held click before the screen sees the
        // release (it may arrive before the next movement frame).
        if (TouchState.currentTouchDragged && TouchState.fallbackScreen == screen) {
            DeferredClick.cancel();
            if (Debug.on()) Debug.log("PressHandler.onButton", "CUSTOM_DRAG_RELEASE_CANCEL");
        }

        ScreenKind kind = ScreenKind.of(screen);
        if (TrenderCompat.barLocked || TouchState.nativeScrollbarHeld && screen != null && kind.trender) {
            // A bar swipe must not start list inertia: it yanked the thumb back.
            Inertia.stop();
            TouchState.active = false;
            TrenderCompat.barReleaseNs = System.nanoTime();
            if (Debug.on()) Debug.log("PressHandler.onButton", "TRENDER_BAR_NO_INERTIA");
        } else if (TouchState.currentTouchDragged && TouchState.active) {
            double v = Inertia.computeReleaseVelocity();
            if (Math.abs(v) > 0.01 && screen != null) {
                Inertia.start(v);
                if (Debug.on()) Debug.log("PressHandler.onButton", "INERTIA_START velocity=" + v
                        + " residual=" + Inertia.residual + " pos=" + TouchState.lockedScroll);
            } else {
                Inertia.stop();
            }
        } else {
            // A tap or a long hold, not a swipe: drop the leftover speed too.
            if (Debug.on()) Debug.log("PressHandler.onButton", "INERTIA_STOP_NO_SWIPE dragged="
                    + TouchState.currentTouchDragged + " residual=" + Inertia.residual);
            Inertia.stop();
        }
        // The Sodium column lock belongs to one swipe; the coast already has
        // its coordinates.
        if (screen != null && kind.sodium) {
            SodiumCompat.unlockContent();
        }
        // Only the touch ends here; the scroll session stays for the next one.
        TouchState.leftButtonHeld = false;
    }

    /** Press on an open screen: find out who owns this touch. */
    private static void classifyPress(MouseHandlerAccess mouse, Screen screen) {
        ScreenKind kind = ScreenKind.of(screen);
        double x = guiX();
        double y = guiY();

        if (kind.xaeroMap && XaeroMapZoomOverlay.isOverSlider(screen, x, y)) {
            TouchState.nativeControlHeld = true;
            TouchState.active = false;
            TouchState.currentTouchDragged = false;
            DeferredClick.cancel();
            XaeroMapZoomOverlay.beginDrag(screen, y);
            mouse.dragscroll$clearAccumulatedMovement();
            if (Debug.on()) Debug.log("PressHandler.onButton", "XAERO_ZOOM_SLIDER_PRESS");
            return;
        }
        if (kind.cloth && ClothCompat.isBarBand(screen, x, y)) {
            grabCustomThumb(mouse);
            ClothCompat.applyThumb(screen, y);
            if (Debug.on()) Debug.log("PressHandler.onButton", "CLOTH_THUMB_GRAB x=" + x + " y=" + y);
            return;
        }
        if (kind.reeses && ReesesCompat.isBarHover(screen, x, y)) {
            grabCustomThumb(mouse);
            ReesesCompat.applyThumb(screen, x, y);
            if (Debug.on()) Debug.log("PressHandler.onButton", "REESES_THUMB_GRAB x=" + x + " y=" + y);
            return;
        }
        if (kind.rei && ReiCompat.isBarHover(screen, x, y)) {
            grabCustomThumb(mouse);
            ReiCompat.applyThumb(screen, x, y);
            if (Debug.on()) Debug.log("PressHandler.onButton", "REI_THUMB_GRAB x=" + x + " y=" + y);
            return;
        }
        if (kind.shulkerConfig && ShulkerCompat.isScrollbarHover(screen, x, y)) {
            grabCustomThumb(mouse);
            ClothCompat.THUMB.reset();
            ShulkerCompat.applyThumb(screen, y);
            if (Debug.on()) Debug.log("PressHandler.onButton", "SHULKER_THUMB_GRAB");
            return;
        }
        // Inventories and screens without anything to scroll stay vanilla:
        // deferring their clicks breaks slot drags and free-move editors.
        if (Widgets.isVanillaInputOnly(screen)) {
            DeferredClick.cancel();
            TouchState.endSession();
            TouchState.currentTouchDragged = false;
            TouchState.nativeControlHeld = false;
            TouchState.nativeSliderHeld = false;
            TouchState.nativeScrollbarHeld = false;
            TouchState.sliderGestureLocked = false;
            ScrollMemory.clearPressSnapshots();
            mouse.dragscroll$clearAccumulatedMovement();
            if (Debug.on()) Debug.log("PressHandler.onButton", "VANILLA_INPUT_ONLY screen=" + screen.getClass().getName());
            return;
        }

        TouchState.pressX = x;
        TouchState.pressY = y;
        if (kind.cloth) {
            ClothCompat.armTouch(screen);
        } else {
            ClothCompat.disarmTouch();
        }
        TouchState.clearTracking();
        TouchState.wasHeld = false;

        // The native bar is tested without requiring isMouseOver on its area:
        // in 26.3 the bar can sit in an edge region outside it.
        AbstractScrollArea pressArea = Widgets.findScrollArea(screen, x, y);
        AbstractScrollArea nativeBarArea = Widgets.findNativeScrollbar(screen, x, y);
        boolean overNativeBar = nativeBarArea != null
                && (!kind.shulkerConfig || ShulkerCompat.isScrollbarHover(screen, x, y));
        boolean overCustomBar = !overNativeBar && Widgets.isCustomScrollbar(screen, x, y);
        if (kind.trender) {
            overCustomBar = classifyTrenderBar(screen, x, y, overCustomBar);
        }
        boolean overSlider = !overNativeBar && !overCustomBar && Widgets.isSliderTarget(screen, x, y);
        // A slider in a scrollable list stays deferred: claiming it now would
        // click it (sound, value jump) before a vertical swipe can scroll.
        boolean sliderClaimsNative = overSlider && !Widgets.inScrollableContext(screen, x, y);

        if (overNativeBar || overCustomBar || sliderClaimsNative) {
            TouchState.nativeControlHeld = true;
            TouchState.nativeScrollbarHeld = overNativeBar || overCustomBar;
            TouchState.nativeSliderHeld = !TouchState.nativeScrollbarHeld;
            if (kind.sodium) {
                // A Sodium control is its own gesture; no stale column lock.
                SodiumCompat.contentLocked = false;
            }
            // Grabbing a scroll bar kills leftover list inertia.
            Inertia.stop();
            TouchState.endSession();
            TouchState.currentTouchDragged = false;
            TouchState.moved = false;
            ScrollMemory.clearPressSnapshots();
            // The launcher's teleport to this touch must not reach the control.
            mouse.dragscroll$clearAccumulatedMovement();
            if (Debug.on()) Debug.log("PressHandler.onButton", "NATIVE_CONTROL_PRESS type="
                    + (TouchState.nativeScrollbarHeld ? "scrollbar" : "slider")
                    + " area=" + (overNativeBar ? nativeBarArea.getClass().getName() : "none"));
            return;
        }

        if (TouchState.active) {
            continueSession(screen, pressArea, x, y);
        } else {
            // Before mouseClicked runs, so a click that scrolls can be undone.
            ScrollMemory.clearPressSnapshots();
            ScrollMemory.snapshotAll(screen);
        }
        // The launcher may have teleported the cursor to this touch before
        // the press; that jump must not become the first drag step.
        mouse.dragscroll$clearAccumulatedMovement();
    }

    /** Cloth / Shulker thumb: the mod drives it for the whole touch. */
    private static void grabCustomThumb(MouseHandlerAccess mouse) {
        TouchState.nativeControlHeld = true;
        TouchState.nativeScrollbarHeld = true;
        TouchState.active = false;
        TouchState.currentTouchDragged = false;
        DeferredClick.cancel();
        mouse.dragscroll$clearAccumulatedMovement();
    }

    /**
     * TRender: whether this press belongs to the bar, and the bar lock that
     * keeps one physical swipe on it.
     */
    private static boolean classifyTrenderBar(Screen screen, double x, double y, boolean overBar) {
        // A list drag owns the touch: the thin bar must not steal it when the
        // finger drifts over it.
        if (overBar && (TouchState.active || TouchState.currentTouchDragged) && !TrenderCompat.barLocked) {
            if (Debug.on()) Debug.log("PressHandler.onButton", "TRENDER_BAR_IGNORE_LIST_DRAG");
            overBar = false;
        }
        // Same swipe: the finger left the thin bar but is still down.
        if (!overBar && TrenderCompat.barLocked) {
            overBar = true;
        }
        if (Debug.on()) Debug.log("PressHandler.onButton", "TRENDER_CLASSIFY x=" + x + " y=" + y
                + " bar=" + overBar + " locked=" + TrenderCompat.barLocked + " active=" + TouchState.active);
        if (overBar) {
            TrenderCompat.barLocked = true;
            TouchState.active = false;
            TouchState.fallbackScreen = null;
            TrenderCompat.barLastMoveNs = System.nanoTime();
            if (TrenderCompat.barLockX == 0.0) {
                TrenderCompat.barLockX = TrenderCompat.barCenterX(screen, x);
            }
        } else {
            TrenderCompat.barLocked = false;
            TrenderCompat.barLockX = 0.0;
            TrenderCompat.barClickSent = false;
        }
        return overBar;
    }

    /**
     * A new touch while a scroll session runs. It may start on another list
     * of the same screen (Mod Menu list vs description): always rebind to the
     * list under this press, and never scroll the whole screen while it has
     * several lists (that scrolled both Mod Menu columns at once).
     */
    private static void continueSession(Screen screen, AbstractScrollArea area, double x, double y) {
        if (area != null) {
            if (TouchState.lockedArea != area) {
                TouchState.lockedArea = area;
                TouchState.fallbackScreen = null;
                ScrollMemory.clearPressSnapshots();
                ScrollMemory.snapshot(area);
                Double remembered = ScrollMemory.recall(area);
                TouchState.lockedScroll = remembered != null ? remembered : area.scrollAmount();
                TouchState.moved = false;
                if (Debug.on()) Debug.log("PressHandler.onButton", "SWITCH_ACTIVE_AREA area=" + area.getClass().getName());
            } else if (Debug.on()) {
                Debug.log("PressHandler.onButton", "CONTINUE_ACTIVE area=" + area.getClass().getName());
            }
        } else if (TouchState.fallbackScreen == screen
                && !Widgets.hasMultipleScrollLists(screen)
                && !ChatCompat.isOutsideChatArea(screen, x, y)) {
            TouchState.lockedArea = null;
            TouchState.fallbackInverted = ScreenKind.of(screen).invertedScroll;
            if (ScreenKind.of(screen).sodium) {
                SodiumCompat.lockX = x;
                SodiumCompat.lockY = y;
                SodiumCompat.contentLocked = true;
            }
            if (Debug.on()) Debug.log("PressHandler.onButton", "CONTINUE_ACTIVE area=custom-screen inverted="
                    + TouchState.fallbackInverted + " sodiumLock=" + SodiumCompat.contentLocked);
        } else {
            TouchState.lockedArea = null;
            TouchState.fallbackScreen = null;
            ScrollMemory.clearPressSnapshots();
            if (Debug.on()) Debug.log("PressHandler.onButton", "ACTIVE_WAIT_FOR_AREA");
        }
    }

    // =====================================================================
    // Screen.mouseClicked inside onButton
    // =====================================================================

    /**
     * How the screen click of this press is dispatched. The press was already
     * classified in {@link #onButton}; this only turns the result into a
     * dispatch decision.
     */
    public static Click decideClick(Screen screen, MouseButtonEvent event) {
        if (screen == null || event == null || !TouchState.isLeftButton(event.button()) || DeferredClick.isReplaying()) {
            return Click.DISPATCH;
        }
        double x = event.x();
        double y = event.y();
        ScreenKind kind = ScreenKind.of(screen);

        // The wheel setup preview is dragged by the setup screen itself and
        // needs its press at once.
        if (screen instanceof WheelSetupScreen setup && setup.isOverPreview(x, y)) {
            DeferredClick.cancel();
            return Click.DISPATCH;
        }
        // A press taken by the wheel emulator must not click the screen below.
        if (MouseWheelEmulator.isGrabbing() || MouseWheelEmulator.isOverWheel(screen, x, y)) {
            DeferredClick.cancel();
            return Click.SWALLOW;
        }
        if (MalilibCompat.isMalilibScreen(screen)) {
            return switch (MalilibCompat.clickDecision(screen)) {
                case SWALLOW -> Click.SWALLOW;
                case DEFER -> Click.DEFER;
                case VANILLA -> Click.DISPATCH;
            };
        }
        if (kind.malilibFamily) {
            return Click.DISPATCH;
        }
        if (kind.xaeroMap && XaeroMapZoomOverlay.isOverSlider(screen, x, y)) {
            DeferredClick.cancel();
            return Click.SWALLOW;
        }
        if (kind.trender && TrenderCompat.barLocked && TrenderCompat.barClickSent) {
            DeferredClick.cancel();
            if (Debug.on()) Debug.log("PressHandler.decideClick", "TRENDER_BAR_SWALLOW");
            return Click.SWALLOW;
        }
        // A thumb the mod drives: the bar's own click would jump it to the finger.
        if ((kind.reeses || kind.rei) && TouchState.nativeScrollbarHeld) {
            DeferredClick.cancel();
            return Click.SWALLOW;
        }
        if (Widgets.isVanillaInputOnly(screen)) {
            DeferredClick.cancel();
            return Click.DISPATCH;
        }
        // Scroll bars need their press at once: a held-back press leaves
        // AbstractScrollArea without its drag state, and the first drag does nothing.
        AbstractScrollArea nativeBar = Widgets.findNativeScrollbar(screen, x, y);
        if (nativeBar != null) {
            DeferredClick.cancel();
            if (Debug.on()) Debug.log("PressHandler.decideClick", "NATIVE_SCROLLBAR_DIRECT_DISPATCH area="
                    + nativeBar.getClass().getName());
            return Click.DISPATCH;
        }
        if (Widgets.isCustomScrollbar(screen, x, y) || kind.trender && TrenderCompat.barLocked) {
            DeferredClick.cancel();
            if (!kind.trender) {
                return Click.DISPATCH;
            }
            TrenderCompat.barLocked = true;
            if (TrenderCompat.barClickSent) {
                TrenderCompat.barPointer(screen, x, y, TrenderCompat.Pointer.DRAG);
                return Click.SWALLOW;
            }
            TrenderCompat.barClickSent = true;
            if (Debug.on()) Debug.log("PressHandler.decideClick", "TRENDER_BAR_VANILLA_CLICK x=" + x + " y=" + y);
            return Click.TRENDER_BAR;
        }
        // Sliders outside a scrollable list are adjusted directly.
        if (Widgets.isSliderTarget(screen, x, y) && !Widgets.inScrollableContext(screen, x, y)) {
            return Click.DISPATCH;
        }
        // The touch may start on a button and only then move into a list:
        // every other click waits until tap or drag is known.
        return Click.DEFER;
    }

    /** Runs the decided dispatch; returns what Screen.mouseClicked returned. */
    public static boolean dispatchClick(Screen screen, MouseButtonEvent event, boolean doubleClick, Click decision) {
        switch (decision) {
            case SWALLOW:
                return true;
            case DEFER:
                DeferredClick.defer(screen, event, doubleClick);
                return true;
            default:
                boolean clicked = screen != null && screen.mouseClicked(event, doubleClick);
                if (decision == Click.TRENDER_BAR) {
                    try {
                        TrenderCompat.applyThumb(screen, event.y());
                    } catch (Throwable ignored) {
                    }
                }
                return clicked;
        }
    }

    // =====================================================================
    // onButton RETURN
    // =====================================================================

    /**
     * After Minecraft handled the button: a tap's held-back click is replayed
     * right after its release. (RETURN, not TAIL: onButton returns early
     * when the screen reports the release as handled.)
     */
    public static void afterButton(MouseButtonInfo info, int action) {
        Screen screen = Minecraft.getInstance().gui.screen();
        // MaLiLib taps were already replayed before the release.
        if (MalilibCompat.isMalilibScreen(screen) || ScreenKind.of(screen).malilibFamily) {
            return;
        }
        if (info == null || !TouchState.isLeftButton(info.button()) || action != InputConstants.RELEASE) {
            return;
        }
        DeferredClick.finish();
        // YACL sliders: the replayed tap set mouseDown without a matching
        // release; clear it so the next tap elsewhere does not move the slider.
        Screen afterReplay = Minecraft.getInstance().gui.screen();
        if (ScreenKind.of(afterReplay).yacl) {
            Sliders.forceReleaseAll(afterReplay);
            if (Debug.on()) Debug.log("PressHandler.afterButton", "YACL_SLIDER_RELEASE_AFTER_TAP");
        }
    }
}
