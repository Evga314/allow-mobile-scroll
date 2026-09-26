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
import ru.evga314.dragscroll.compat.SafeMode;
import ru.evga314.dragscroll.DragScrollConfig;
import ru.evga314.dragscroll.MalilibCompat;
import ru.evga314.dragscroll.MouseWheelEmulator;
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

    /** Track previous GUI coords in onMove to detect teleports (large jumps). */
    @Unique
    private double dragscroll$prevMoveGuiX = Double.NaN;

    @Unique
    private double dragscroll$prevMoveGuiY = Double.NaN;

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
    private boolean dragscroll$clothTouchArmed = false;

    @Unique
    private Screen dragscroll$clothTouchScreen = null;

    @Unique
    private long dragscroll$clothPressNs = 0L;

    @Unique
    private static final double SCROLL_PIXELS_PER_NOTCH = 12.0;
    /** Minimum vertical finger travel in GUI pixels before a touch becomes a drag. */

    /**
     * Snapshot scroll positions before Minecraft processes the new click.
     */
    @Inject(method = "onButton", at = @At("HEAD"))
    private void dragscroll$beforeButton(long handle, MouseButtonInfo buttonInfo, int action,
                                         CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                "HEAD button=" + (buttonInfo == null ? "null" : buttonInfo.button())
                        + " action=" + action
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld);
        try {
            if (buttonInfo == null || !DragScrollState.isLeftButton(buttonInfo.button())) {
                return;
            }

            // Mouse-wheel emulator: a press on the on-screen wheel owns the whole
            // touch, on ANY screen (including container screens like the villager
            // trade menu that are otherwise vanilla-input-only). It must be tested
            // before the generic classification below claims the gesture.
            Screen wheelScreen = this.minecraft.gui.screen();
            if (this.minecraft.gui.overlay() == null && MouseWheelEmulator.isEnabled()) {
                if (action == InputConstants.PRESS) {
                    Window ww = this.minecraft.getWindow();
                    double wx = this.getScaledXPos(ww);
                    double wy = this.getScaledYPos(ww);
                    if (MouseWheelEmulator.onPress(wheelScreen, wx, wy)) {
                        DragScrollState.leftButtonHeld = true;
                        DragScrollState.nativeControlHeld = true;
                        DragScrollState.active = false;
                        DragScrollState.currentTouchDragged = false;
                        DragScrollState.cancelPendingClick();
                        this.accumulatedDX = 0.0;
                        this.accumulatedDY = 0.0;
                        return;
                    }
                } else if (action == InputConstants.RELEASE && MouseWheelEmulator.isGrabbing()) {
                    // Wheel released. Return early so no list inertia starts and no
                    // deferred click is replayed — this is what stops an accidental
                    // scroll of a list the wheel happened to end up over.
                    MouseWheelEmulator.onRelease();
                    DragScrollState.cancelPendingClick();
                    DragScrollState.stopInertia();
                    DragScrollState.nativeControlHeld = false;
                    DragScrollState.active = false;
                    DragScrollState.currentTouchDragged = false;
                    DragScrollState.leftButtonHeld = false;
                    return;
                }
            }

            // MaLiLib (Litematica, MiniHUD, Tweakeroo, ...): its lists are not
            // AbstractScrollArea and its scroll bar is moved from render(), so
            // the whole touch is handled by MalilibCompat instead of the
            // generic path below.
            Screen malilibScreen = this.minecraft.gui.screen();
            if (this.minecraft.gui.overlay() == null && MalilibCompat.isMalilibScreen(malilibScreen)) {
                if (action == InputConstants.PRESS) {
                    Window mw = this.minecraft.getWindow();
                    boolean alreadyHeld = DragScrollState.leftButtonHeld;
                    DragScrollState.leftButtonHeld = true;
                    // The cursor teleport to this finger is not a drag.
                    this.accumulatedDX = 0.0;
                    this.accumulatedDY = 0.0;
                    MalilibCompat.onPress(malilibScreen, this.getScaledXPos(mw), this.getScaledYPos(mw), alreadyHeld);
                } else if (action == InputConstants.RELEASE) {
                    ru.evga314.dragscroll.XaeroMapZoomOverlay.endDrag();
                    MalilibCompat.onRelease(malilibScreen);
                    DragScrollState.leftButtonHeld = false;
                }
                return;
            }

            if (action == InputConstants.PRESS) {
                Screen pressScreen = this.minecraft.gui.screen();
                // TRender WScrollBar: the launcher injects extra PRESS events
                // while the finger is still down and after it has left the thin
                // bar hitbox. Those must not drop an already-grabbed thumb.
                boolean keepTrenderBar = false;
                if (pressScreen != null
                        && dragscroll$isTrenderScreen(pressScreen)
                        && DragScrollState.trenderBarLocked) {
                    long nowNs = System.nanoTime();
                    long sinceRelease = DragScrollState.trenderBarReleaseNs == 0L
                            ? Long.MAX_VALUE
                            : nowNs - DragScrollState.trenderBarReleaseNs;
                    // Synthetic launcher RELEASE/PRESS arrives within ~40ms.
                    // A real finger-up then a new tap is much later — that
                    // must start a fresh gesture on the *current* tab's bar.
                    keepTrenderBar = sinceRelease < 40_000_000L
                            || (DragScrollState.trenderBarReleaseNs == 0L
                            && DragScrollState.trenderBarLastMoveNs != 0L
                            && (nowNs - DragScrollState.trenderBarLastMoveNs) < 40_000_000L);
                }
                if (pressScreen != null
                        && !(pressScreen instanceof AbstractContainerScreen)
                        && dragscroll$isSodiumScreen(pressScreen)) {
                    DragScrollState.forceReleaseAllSliders(pressScreen);
                }
                DragScrollState.nativeSliderHeld = false;
                if (!keepTrenderBar) {
                    DragScrollState.nativeScrollbarHeld = false;
                }
                DragScrollState.sliderGestureLocked = false;
                DragScrollState.sodiumGrabbedBar = null;
                // Keep the selected Sodium scroll window across the launcher's
                // repeated LMB presses while the same screen-level scroll session
                // remains active. A new session or a different screen clears it.
                boolean keepSodiumContentLock = DragScrollState.active
                        && DragScrollState.fallbackScrollScreen == pressScreen
                        && dragscroll$isSodiumScreen(pressScreen)
                        && DragScrollState.sodiumContentLocked;
                if (!keepSodiumContentLock) {
                    DragScrollState.sodiumContentLocked = false;
                }
                DragScrollState.sodiumThumbGrabArmed = false;
                DragScrollState.clothThumbGrabArmed = false;
                DragScrollState.clothThumbGrabOffset = 0.0;
                // Any touch stops the current coast immediately so a long hold
                // cannot resume it. Residual is kept only for a following swipe.
                if (DragScrollState.inertiaActive
                        || Math.abs(DragScrollState.scrollVelocity) > DragScrollState.INERTIA_STOP) {
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "INERTIA_TOUCH_STOP velocity=" + DragScrollState.scrollVelocity
                                    + " residual=" + DragScrollState.residualVelocity
                                    + " pos=" + DragScrollState.lockedScroll);
                    DragScrollState.stopCoastKeepResidual();
                }
                DragScrollState.leftButtonHeld = true;
                if (!keepTrenderBar) {
                    DragScrollState.currentTouchDragged = false;
                    DragScrollState.trenderBarLocked = false;
                    DragScrollState.trenderBarClickSent = false;
                    DragScrollState.trenderBarLockX = 0.0;
                    DragScrollState.trenderBarReleaseNs = 0L;
                    DragScrollState.trenderThumbGrabArmed = false;
                    DragScrollState.trenderGrabbedBar = null;
                } else {
                    DragScrollState.trenderBarLocked = true;
                    DragScrollState.nativeScrollbarHeld = true;
                    DragScrollState.nativeControlHeld = true;
                    DragScrollState.active = false;
                    DragScrollState.trenderBarReleaseNs = 0L;
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "TRENDER_BAR_KEEP");
                }
            } else if (action == InputConstants.RELEASE) {
                ru.evga314.dragscroll.XaeroMapZoomOverlay.endDrag();
                Screen releaseScreen = this.minecraft.gui.screen();
                // Clear native/Sodium slider capture on finger-up. YACL pure taps
                // are force-released later in onButton RETURN (after finishPendingClick
                // has replayed mouseClicked), so a deferred horizontal engage can
                // keep mouseDown for the rest of the drag.
                if (DragScrollState.nativeSliderHeld || DragScrollState.sliderGestureLocked) {
                    DragScrollState.releaseCapturedSlider(releaseScreen);
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "SLIDER_FORCE_RELEASE");
                }
                DragScrollState.nativeControlHeld = false;
                DragScrollState.nativeSliderHeld = false;
                DragScrollState.sliderGestureLocked = false;
                DragScrollState.clothThumbGrabArmed = false;
                // A custom scroll screen can deliver release handling before the
                // next movement callback has a chance to update transient state.
                // If the current touch was already a drag, discard any deferred
                // click before vanilla/Sodium processes the release.
                Screen currentScreen = this.minecraft.gui.screen();
                this.dragscroll$clothTouchArmed = false;
                this.dragscroll$clothTouchScreen = null;
                this.dragscroll$clothPressNs = 0L;
                if (DragScrollState.currentTouchDragged
                        && DragScrollState.fallbackScrollScreen == currentScreen) {
                    DragScrollState.cancelPendingClick();
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "CUSTOM_DRAG_RELEASE_CANCEL screen="
                                    + (currentScreen == null ? "null" : currentScreen.getClass().getName()));
                }

                // TRender bar swipe must not start list inertia — that is what
                // yanked the thumb back after a bar drag.
                if (DragScrollState.trenderBarLocked
                        || (DragScrollState.nativeScrollbarHeld
                                && currentScreen != null
                                && dragscroll$isTrenderScreen(currentScreen))) {
                    DragScrollState.stopInertia();
                    DragScrollState.active = false;
                    DragScrollState.trenderBarReleaseNs = System.nanoTime();
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "TRENDER_BAR_NO_INERTIA");
                } else if (DragScrollState.currentTouchDragged && DragScrollState.active) {
                    // Stack residual + new flick velocity.
                    double releaseVel = DragScrollState.computeReleaseVelocity();
                    DragScrollState.scrollVelocity = releaseVel;
                    if (Math.abs(releaseVel) > 0.01 && currentScreen != null) {
                        if (DragScrollState.lockedArea != null) {
                            DragScrollState.baseScroll = DragScrollState.lockedScroll;
                        }
                        DragScrollState.inertiaActive = true;
                        ru.evga314.dragscroll.DragScrollClient.markInertiaStarted();
                        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                                "INERTIA_START velocity=" + releaseVel
                                        + " residual=" + DragScrollState.residualVelocity
                                        + " pos=" + DragScrollState.lockedScroll);
                    } else {
                        DragScrollState.stopInertia();
                    }
                } else {
                    // Tap or long hold — not a swipe. Drop residual too.
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "INERTIA_STOP_NO_SWIPE dragged=" + DragScrollState.currentTouchDragged
                                    + " residual=" + DragScrollState.residualVelocity
                                    + " pos=" + DragScrollState.lockedScroll);
                    DragScrollState.stopInertia();
                }

                // The Sodium target belongs only to the current physical swipe.
                // Inertia already captured the exact coordinates above, so the
                // lock can be cleared immediately without changing the coast.
                if (currentScreen != null && dragscroll$isSodiumScreen(currentScreen)) {
                    DragScrollState.sodiumContentLocked = false;
                    DragScrollState.sodiumLockGuiX = 0.0;
                    DragScrollState.sodiumLockGuiY = 0.0;
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "SODIUM_CONTENT_LOCK_RESET_AFTER_SWIPE");
                }

                // Release ends only the current physical touch. The active scroll
                // baseline is deliberately preserved for the next touch.
                DragScrollState.leftButtonHeld = false;
            } else {
                return;
            }

            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
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

            // Other screens of the MaLiLib mods that are not GuiBase (e.g. the
            // inventory preview overlay) stay completely vanilla.
            if (dragscroll$isMalilibFamilyScreen(screen)) {
                return;
            }

            if (ru.evga314.dragscroll.XaeroMapZoomOverlay.isMapScreen(screen)
                    && ru.evga314.dragscroll.XaeroMapZoomOverlay.isOverSlider(
                            screen, this.getScaledXPos(this.minecraft.getWindow()),
                            this.getScaledYPos(this.minecraft.getWindow()))) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                ru.evga314.dragscroll.XaeroMapZoomOverlay.beginDrag(
                        screen, this.getScaledYPos(this.minecraft.getWindow()));
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "XAERO_ZOOM_SLIDER_PRESS");
                return;
            }

            if (dragscroll$isClothConfigScreen(screen)
                    && (dragscroll$isClothScrollbarHover(screen,
                            this.getScaledXPos(this.minecraft.getWindow()),
                            this.getScaledYPos(this.minecraft.getWindow()))
                    || this.getScaledXPos(this.minecraft.getWindow()) >= screen.width - 120)) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                dragscroll$applyClothThumbAbsolute(screen,
                        this.getScaledYPos(this.minecraft.getWindow()));
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "CLOTH_THUMB_GRAB");
                return;
            }

            if (dragscroll$isShulkerConfigScreen(screen)
                    && dragscroll$isShulkerScrollbarHover(screen,
                            this.getScaledXPos(this.minecraft.getWindow()),
                            this.getScaledYPos(this.minecraft.getWindow()))) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                DragScrollState.clothThumbGrabArmed = false;
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                dragscroll$applyShulkerThumbAbsolute(screen,
                        this.getScaledYPos(this.minecraft.getWindow()));
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "SHULKER_THUMB_GRAB");
                return;
            }

            // Container/inventory screens and any GUI that has no scrollable
            // content must stay fully vanilla. Deferral + custom drag break
            // slot dragging, free-move icons (e.g. Blood Vignette position
            // editor), and other non-list interactions.
            if (dragscroll$isVanillaInputOnlyScreen(screen)) {
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
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                        "VANILLA_INPUT_ONLY screen="
                                + screen.getClass().getName()
                                + " container=" + dragscroll$isContainerScreen(screen)
                                + " xaeroMap=" + ru.evga314.dragscroll.XaeroMapZoomOverlay.isMapScreen(screen));
                return;
            }

            Window window = this.minecraft.getWindow();
            this.dragscroll$pressGuiX = this.getScaledXPos(window);
            this.dragscroll$pressGuiY = this.getScaledYPos(window);
            this.dragscroll$clothTouchArmed = dragscroll$isClothConfigScreen(screen);
            this.dragscroll$clothTouchScreen = this.dragscroll$clothTouchArmed ? screen : null;
            this.dragscroll$clothPressNs = this.dragscroll$clothTouchArmed ? System.nanoTime() : 0L;
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
            if (dragscroll$isShulkerConfigScreen(screen)
                    && !dragscroll$isShulkerScrollbarHover(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
                overNativeScrollbar = false;
            }
            if (overNativeScrollbar) {
                pressArea = nativeScrollbarArea;
            }
            boolean overCustomScrollBar = !overNativeScrollbar
                    && dragscroll$isScrollBarTarget(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY);
            if (!overCustomScrollBar
                    && dragscroll$isTrenderScreen(screen)
                    && DragScrollState.trenderBarLocked) {
                // Same physical swipe: finger left the thin bar but LMB is
                // still down. Keep the vanilla bar gesture, block our list.
                overCustomScrollBar = true;
            }
            if (dragscroll$isTrenderScreen(screen)) {
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                        "TRENDER_CLASSIFY x=" + this.dragscroll$pressGuiX
                                + " y=" + this.dragscroll$pressGuiY
                                + " bar=" + overCustomScrollBar
                                + " locked=" + DragScrollState.trenderBarLocked
                                + " active=" + DragScrollState.active);
                if (overCustomScrollBar) {
                    DragScrollState.trenderBarLocked = true;
                    DragScrollState.active = false;
                    DragScrollState.fallbackScrollScreen = null;
                    DragScrollState.trenderBarLastMoveNs = System.nanoTime();
                    if (DragScrollState.trenderBarLockX == 0.0) {
                        DragScrollState.trenderBarLockX = dragscroll$trenderBarCenterX(screen,
                                this.dragscroll$pressGuiX);
                    }
                } else if (DragScrollState.trenderBarLocked) {
                    double releaseX = DragScrollState.trenderBarLockX;
                    if (releaseX == 0.0) {
                        releaseX = this.dragscroll$pressGuiX;
                    }
                    dragscroll$trenderBarPointer(screen, releaseX, this.dragscroll$pressGuiY, 0);
                    DragScrollState.trenderBarLocked = false;
                    DragScrollState.trenderBarLockX = 0.0;
                    DragScrollState.trenderBarClickSent = false;
                    DragScrollState.trenderBarReleaseNs = 0L;
                    DragScrollState.trenderThumbGrabArmed = false;
                    DragScrollState.trenderGrabbedBar = null;
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "TRENDER_BAR_CONTENT_RELEASE");
                } else {
                    DragScrollState.trenderBarLocked = false;
                    DragScrollState.trenderBarLockX = 0.0;
                    DragScrollState.trenderBarClickSent = false;
                }
            }
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
                if (dragscroll$isSodiumScreen(screen)) {
                    // A native Sodium control starts a separate native gesture;
                    // do not carry an older content-window lock into the next
                    // custom scroll.
                    DragScrollState.sodiumContentLocked = false;
                }
                // Grabbing a scrollbar must kill leftover list inertia.
                DragScrollState.stopInertia();
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton", "NATIVE_BAR_STOP_INERTIA");
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

                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                        "NATIVE_CONTROL_PRESS type="
                                + (overNativeScrollbar || overCustomScrollBar ? "scrollbar" : "slider")
                                + " area=" + (pressArea == null ? "none" : pressArea.getClass().getName())
                                + " TELEPORT_DELTA_CLEARED");
                return;
            }

            if (DragScrollState.active) {
                // A persistent scroll session may survive a release, but the next
                // touch can start inside a different scroll area on the same screen
                // (Mod Menu list vs description). Always rebind to the pane under
                // THIS press. Never keep driving the previous list, and never fall
                // back to screen-level mouseScrolled while multiple lists exist —
                // that is what scrolled both Mod Menu columns at once.
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
                        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                                "SWITCH_ACTIVE_AREA area=" + currentArea.getClass().getName()
                                        + " screen=" + screen.getClass().getName());
                    } else {
                        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                                "CONTINUE_ACTIVE area=" + currentArea.getClass().getName()
                                        + " screen=" + screen.getClass().getName());
                    }
                } else if (DragScrollState.fallbackScrollScreen == screen
                        && !dragscroll$hasMultipleScrollAreas(screen)) {
                    DragScrollState.lockedArea = null;
                    DragScrollState.fallbackScrollInverted = dragscroll$customScrollInverted(
                            screen, this.dragscroll$pressGuiX);
                    if (dragscroll$isSodiumScreen(screen)) {
                        DragScrollState.sodiumLockGuiX = this.dragscroll$pressGuiX;
                        DragScrollState.sodiumLockGuiY = this.dragscroll$pressGuiY;
                        DragScrollState.sodiumContentLocked = true;
                    }
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "CONTINUE_ACTIVE area=custom-screen screen=" + screen.getClass().getName()
                                    + " inverted=" + DragScrollState.fallbackScrollInverted
                                    + " pressX=" + this.dragscroll$pressGuiX
                                    + " sodiumLock=" + DragScrollState.sodiumContentLocked);
                } else {
                    DragScrollState.lockedArea = null;
                    DragScrollState.fallbackScrollScreen = null;
                    DragScrollState.clearPressSnapshot();
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
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
        } catch (Throwable t) {
            // Input compatibility code must never be allowed to crash the game.
            SafeMode.reportFailure("MouseHandler.onButton", t);
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
        if (SafeMode.bypass()) {
            return screen != null && screen.mouseClicked(event, doubleClick);
        }
        // Decide first, dispatch once. Previously an exception thrown after
        // (or inside) screen.mouseClicked fell through to a second
        // screen.mouseClicked call, so one tap could press a button twice.
        int decision;
        try {
            decision = dragscroll$decideClick(screen, event);
        } catch (Throwable t) {
            // Input compatibility code must never be allowed to crash the game.
            SafeMode.reportFailure("MouseHandler.mouseClicked", t);
            decision = DRAGSCROLL_CLICK_DISPATCH;
        }

        if (decision == DRAGSCROLL_CLICK_SWALLOW) {
            return true;
        }
        if (decision == DRAGSCROLL_CLICK_DEFER) {
            DragScrollState.deferScreenClick(screen, event, doubleClick);
            return true;
        }
        boolean clicked = screen != null && screen.mouseClicked(event, doubleClick);
        if (decision == DRAGSCROLL_CLICK_TRENDER_BAR) {
            try {
                dragscroll$applyTrenderThumbAbsolute(screen, event.y());
            } catch (Throwable ignored) {
            }
        }
        return clicked;
    }

    @Unique
    private static final int DRAGSCROLL_CLICK_DISPATCH = 0;
    @Unique
    private static final int DRAGSCROLL_CLICK_SWALLOW = 1;
    @Unique
    private static final int DRAGSCROLL_CLICK_DEFER = 2;
    @Unique
    private static final int DRAGSCROLL_CLICK_TRENDER_BAR = 3;

    @Unique
    private static int dragscroll$decideClick(Screen screen, MouseButtonEvent event) {
        if (screen == null
                || event == null
                || !DragScrollState.isLeftButton(event.button())
                || DragScrollState.isReplaying()) {
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        // A press captured by the mouse-wheel emulator must not click the
        // screen underneath it.
        if (MouseWheelEmulator.isGrabbing()
                || (MouseWheelEmulator.isEnabled()
                        && MouseWheelEmulator.isOverWheel(screen, event.x(), event.y()))) {
            DragScrollState.cancelPendingClick();
            return DRAGSCROLL_CLICK_SWALLOW;
        }

        // MaLiLib list screens: the press was classified in onButton HEAD.
        if (MalilibCompat.isMalilibScreen(screen)) {
            int malilib = MalilibCompat.clickDecision(screen);
            if (malilib == MalilibCompat.CLICK_SWALLOW) {
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick", "MALILIB_BAR_SWALLOW");
                return DRAGSCROLL_CLICK_SWALLOW;
            }
            if (malilib == MalilibCompat.CLICK_DEFER) {
                return DRAGSCROLL_CLICK_DEFER;
            }
            return DRAGSCROLL_CLICK_DISPATCH;
        }
        // Other MaLiLib-family screens receive the original click untouched.
        if (dragscroll$isMalilibFamilyScreen(screen)) {
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        // Container screens and screens with no scrollable content must
        // receive the original press immediately (slots, free-drag icons…).
        if (ru.evga314.dragscroll.XaeroMapZoomOverlay.isMapScreen(screen)
                && ru.evga314.dragscroll.XaeroMapZoomOverlay.isOverSlider(
                        screen, event.x(), event.y())) {
            DragScrollState.cancelPendingClick();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick", "XAERO_ZOOM_SLIDER_SWALLOW");
            return DRAGSCROLL_CLICK_SWALLOW;
        }
        if (dragscroll$isTrenderScreen(screen)
                && DragScrollState.trenderBarLocked
                && DragScrollState.trenderBarClickSent) {
            DragScrollState.cancelPendingClick();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick", "TRENDER_BAR_SWALLOW");
            return DRAGSCROLL_CLICK_SWALLOW;
        }

        if (dragscroll$isVanillaInputOnlyScreen(screen)) {
            DragScrollState.cancelPendingClick();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick",
                    "VANILLA_DIRECT_DISPATCH screen="
                            + screen.getClass().getName());
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        // Native scrollbars and slider-like controls must receive the
        // original press event immediately. Deferring a scrollbar press
        // leaves AbstractScrollArea without the native drag state for the
        // first touch, which makes the first drag appear to do nothing.
        AbstractScrollArea nativeScrollbar = dragscroll$findNativeScrollbar(
                screen, event.x(), event.y());
        if (nativeScrollbar != null) {
            DragScrollState.cancelPendingClick();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick",
                    "NATIVE_SCROLLBAR_DIRECT_DISPATCH area="
                            + nativeScrollbar.getClass().getName());
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        if (dragscroll$isScrollBarTarget(screen, event.x(), event.y())
                || (dragscroll$isTrenderScreen(screen) && DragScrollState.trenderBarLocked)) {
            DragScrollState.cancelPendingClick();
            if (dragscroll$isTrenderScreen(screen)) {
                DragScrollState.trenderBarLocked = true;
                if (DragScrollState.trenderBarClickSent) {
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick", "TRENDER_BAR_SWALLOW");
                    dragscroll$trenderBarPointer(screen, event.x(), event.y(), 2);
                    return DRAGSCROLL_CLICK_SWALLOW;
                }
                DragScrollState.trenderBarClickSent = true;
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick",
                        "TRENDER_BAR_VANILLA_CLICK x=" + event.x() + " y=" + event.y());
                return DRAGSCROLL_CLICK_TRENDER_BAR;
            }
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick",
                    "CUSTOM_SCROLLBAR_DIRECT_DISPATCH");
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        if (dragscroll$isDirectManipulationTarget(screen, event.x(), event.y())) {
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.deferClick", "DIRECT_MANIPULATION_TARGET");
            return DRAGSCROLL_CLICK_DISPATCH;
        }

        // The initial touch can begin on a button or another widget
        // outside the scroll area and enter the scrollable content only
        // after the finger starts moving. Therefore the initial click
        // cannot be filtered by the starting widget. Defer ordinary LMB
        // screen clicks while a GUI is open, then decide on release or
        // real vertical movement.
        return DRAGSCROLL_CLICK_DEFER;
    }

    /**
     * A tap is replayed immediately after the corresponding LMB release.
     * A real drag has already cancelled the pending click before this point.
     *
     * RETURN, not TAIL: onButton returns early whenever the screen reports
     * the release as handled (which ContainerEventHandler does after every
     * click), and TAIL only covers the final return. The tap then waited for
     * the next frame's fallback instead of being replayed here.
     */
    @Inject(method = "onButton", at = @At("RETURN"))
    private void dragscroll$afterButton(long handle, MouseButtonInfo buttonInfo, int action,
                                         CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        Screen tailScreen = this.minecraft.gui.screen();
        if (MalilibCompat.isMalilibScreen(tailScreen) || dragscroll$isMalilibFamilyScreen(tailScreen)) {
            // MaLiLib taps were already replayed before the release (onButton HEAD).
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
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
                // YACL SliderControllerElement: deferred-tap replay sets mouseDown
                // without a matching mouseReleased. Clear it now that the finger
                // is up so an empty-area tap cannot keep driving the last slider.
                // (During a drag, nativeSliderHeld path already released at HEAD.)
                Screen yaclTail = this.minecraft.gui.screen();
                if (dragscroll$isYaclScreen(yaclTail)) {
                    DragScrollState.releaseCapturedSlider(yaclTail);
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onButton",
                            "YACL_SLIDER_RELEASE_AFTER_TAP");
                }
            }
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onButton", t);
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void dragscroll$beforeVanillaMove(long handle, double xpos, double ypos,
                                               double xrel, double yrel, CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove",
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

            // Track the cursor position BEFORE a sharp teleport. The launcher
            // teleports the cursor to each new finger touch (e.g. onto the wheel
            // widget). That teleport is not a drag: the wheel emulator should send
            // its scroll to where the cursor WAS before the jump, not where it
            // landed. While the wheel is grabbed we freeze the pre-teleport point.
            if (!MouseWheelEmulator.isGrabbing()) {
                Window w = this.minecraft.getWindow();
                double guiX = MouseHandler.getScaledXPos(w, xpos);
                double guiY = MouseHandler.getScaledYPos(w, ypos);
                double prevX = this.dragscroll$prevMoveGuiX;
                double prevY = this.dragscroll$prevMoveGuiY;
                if (!Double.isNaN(prevX) && !Double.isNaN(prevY)) {
                    double jump = Math.abs(guiX - prevX) + Math.abs(guiY - prevY);
                    double teleportThreshold = Math.max(64.0, screen.width / 8.0);
                    // Never remember a point that lies on the wheel widget itself.
                    // Otherwise, after the cursor teleports onto the wheel, the small
                    // jitter moves there overwrite the real pre-teleport list position
                    // and the wheel ends up scrolling whatever is under the widget.
                    boolean onWheel = MouseWheelEmulator.isEnabled()
                            && MouseWheelEmulator.isOverWheel(screen, guiX, guiY);
                    if (jump < teleportThreshold && !onWheel) {
                        // Normal small move outside the wheel: a genuine cursor position.
                        DragScrollState.rememberCursorBeforeTeleport(guiX, guiY);
                        if (DragScrollState.debugOn()) {
                            DragScrollState.debugEvent("MouseHandler.onMove",
                                    "REMEMBER_CURSOR x=" + guiX + " y=" + guiY
                                            + " jump=" + jump + " threshold=" + teleportThreshold);
                        }
                    } else if (DragScrollState.debugOn()) {
                        DragScrollState.debugEvent("MouseHandler.onMove",
                                (onWheel ? "SKIP_ON_WHEEL" : "TELEPORT_DETECTED")
                                        + " jump=" + jump + " threshold=" + teleportThreshold
                                        + " from=" + prevX + "," + prevY + " to=" + guiX + "," + guiY
                                        + " kept=" + DragScrollState.lastCursorXBeforeTeleport
                                        + "," + DragScrollState.lastCursorYBeforeTeleport);
                    }
                    // Large jump = teleport, or a point on the wheel: keep the stored point.
                }
                // Always update prevMove for the next comparison, even on first move.
                this.dragscroll$prevMoveGuiX = guiX;
                this.dragscroll$prevMoveGuiY = guiY;
            }

            // Mouse-wheel emulator owns the touch while its wheel is grabbed.
            if (MouseWheelEmulator.isGrabbing() && DragScrollState.leftButtonHeld) {
                Window wheelW = this.minecraft.getWindow();
                MouseWheelEmulator.onDrag(screen,
                        MouseHandler.getScaledXPos(wheelW, xpos),
                        MouseHandler.getScaledYPos(wheelW, ypos));
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                ci.cancel();
                return;
            }
            if (MalilibCompat.isMalilibScreen(screen)) {
                Window mw = this.minecraft.getWindow();
                MalilibCompat.onMove(screen,
                        MouseHandler.getScaledXPos(mw, xpos), MouseHandler.getScaledYPos(mw, ypos));
                return;
            }
            if (dragscroll$isMalilibFamilyScreen(screen)) {
                // Non-GuiBase MaLiLib-family screens: no movement interception.
                return;
            }
            if (ru.evga314.dragscroll.XaeroMapZoomOverlay.isDragging()) {
                Window zw = this.minecraft.getWindow();
                double zy = MouseHandler.getScaledYPos(zw, ypos);
                ru.evga314.dragscroll.XaeroMapZoomOverlay.applyDrag(screen, zy);
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                ci.cancel();
                return;
            }
            if (dragscroll$isTrenderScreen(screen) && DragScrollState.trenderBarLocked) {
                Window tw = this.minecraft.getWindow();
                double ty = MouseHandler.getScaledYPos(tw, ypos);
                if (!dragscroll$applyTrenderThumbAbsolute(screen, ty)) {
                    double tx = DragScrollState.trenderBarLockX;
                    if (tx == 0.0) {
                        tx = MouseHandler.getScaledXPos(tw, xpos);
                    }
                    dragscroll$trenderBarPointer(screen, tx, ty, 2);
                }
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                DragScrollState.moved = true;
                DragScrollState.trenderBarLastMoveNs = System.nanoTime();
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove", "TRENDER_THUMB_NATIVE y=" + ty);
                ci.cancel();
                return;
            }
            if (dragscroll$isVanillaInputOnlyScreen(screen)) {
                // No scrollable content, container, or Xaero map: leave movement to vanilla.
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
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove",
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
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove",
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
                    if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove",
                            "DEFERRED_SLIDER_HORIZONTAL_ENGAGE dx=" + engageDx
                                    + " dy=" + engageDy
                                    + " thresh=" + horizThreshold);
                    return;
                }
            }

            boolean clothSyntheticTouch = this.dragscroll$clothTouchArmed
                    && this.dragscroll$clothTouchScreen == screen
                    && (System.nanoTime() - this.dragscroll$clothPressNs) < 1_000_000_000L;
            if ((!DragScrollState.leftButtonHeld && !clothSyntheticTouch) || DragScrollState.active) return;

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
            } else if (dragscroll$isClothConfigScreen(screen)
                    && (dragscroll$isClothScrollbarHover(screen, this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)
                    || this.dragscroll$pressGuiX >= screen.width - 120)) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                dragscroll$applyClothThumbAbsolute(screen, guiY);
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove", "CLOTH_THUMB_NATIVE");
            } else if (dragscroll$isShulkerConfigScreen(screen)
                    && dragscroll$isShulkerScrollbarHover(screen,
                            this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                dragscroll$applyShulkerThumbAbsolute(screen, guiY);
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove", "SHULKER_THUMB_NATIVE");
            } else if (dragscroll$isSodiumScreen(screen)
                    && dragscroll$isSodiumScrollbarHover(screen,
                    this.dragscroll$pressGuiX, this.dragscroll$pressGuiY)) {
                DragScrollState.nativeControlHeld = true;
                DragScrollState.nativeScrollbarHeld = true;
                DragScrollState.active = false;
                DragScrollState.currentTouchDragged = false;
                DragScrollState.cancelPendingClick();
                dragscroll$applySodiumThumbAbsolute(screen,
                        this.getScaledXPos(this.minecraft.getWindow()), guiY);
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.onMove", "SODIUM_THUMB_NATIVE");
            } else if (dragscroll$isCustomScrollScreen(screen)
                    && !dragscroll$hasMultipleScrollAreas(screen)) {
                if (dragscroll$isSodiumScreen(screen) && !DragScrollState.sodiumContentLocked) {
                    DragScrollState.sodiumLockGuiX = this.dragscroll$pressGuiX;
                    DragScrollState.sodiumLockGuiY = this.dragscroll$pressGuiY;
                    DragScrollState.sodiumContentLocked = true;
                }
                DragScrollState.currentTouchDragged = true;
                DragScrollState.cancelPendingClick();
                this.dragscroll$lastGuiX = this.dragscroll$pressGuiX;
                this.dragscroll$lastGuiY = this.dragscroll$pressGuiY;
                this.dragscroll$wasLeft = true;
            }
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onMove", t);
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void dragscroll$beforeVanillaMovement(CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        // This runs every frame and walks other mods' widget trees; a
        // throwing custom widget must not crash the game from inside a menu.
        try {
            this.dragscroll$beforeVanillaMovementImpl();
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.handleAccumulatedMovement", t);
        }
    }

    @Unique
    private void dragscroll$beforeVanillaMovementImpl() {
        // Reset each frame; set true only when this call actually scrolls.
        DragScrollState.moved = false;

        Screen screen = this.minecraft.gui.screen();
        if (MalilibCompat.isMalilibScreen(screen)) {
            if (screen != dragscroll$lastScreen) {
                // Leaving a generic screen for a MaLiLib one: drop its gesture.
                dragscroll$lastScreen = screen;
                DragScrollState.cancelPendingClick();
                DragScrollState.active = false;
                DragScrollState.lockedArea = null;
                DragScrollState.fallbackScrollScreen = null;
                DragScrollState.clearPressSnapshot();
            }
            if (this.minecraft.gui.overlay() == null) {
                Window mw = this.minecraft.getWindow();
                MalilibCompat.onFrame(screen, this.getScaledXPos(mw), this.getScaledYPos(mw));
            }
            return;
        }
        if (dragscroll$isMalilibFamilyScreen(screen)) {
            // Non-GuiBase MaLiLib-family screens stay vanilla.
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                "HEAD screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " leftPressed=" + this.isLeftPressed()
                        + " modLeftHeld=" + DragScrollState.leftButtonHeld
                        + " accumulatedDX=" + this.accumulatedDX
                        + " accumulatedDY=" + this.accumulatedDY
                        + " active=" + DragScrollState.active
                        + " pending=" + DragScrollState.isPendingScreenClick());

        if (dragscroll$isVanillaInputOnlyScreen(screen)) {
            // Keep non-scroll / container / Xaero map screens fully vanilla.
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

        // Thumb mapping only while the finger is down. After the lift the
        // cursor stays where the next touch teleports it, and a frame drawn
        // between that teleport and the press used to jump the list there.
        if (DragScrollState.nativeScrollbarHeld && DragScrollState.leftButtonHeld
                && dragscroll$isClothConfigScreen(screen)) {
            dragscroll$applyClothThumbAbsolute(screen, this.getScaledYPos(this.minecraft.getWindow()));
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            DragScrollState.moved = true;
            return;
        }
        if (DragScrollState.nativeScrollbarHeld && DragScrollState.leftButtonHeld
                && dragscroll$isShulkerConfigScreen(screen)) {
            dragscroll$applyShulkerThumbAbsolute(screen, this.getScaledYPos(this.minecraft.getWindow()));
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            DragScrollState.moved = true;
            return;
        }
        if (DragScrollState.nativeScrollbarHeld && DragScrollState.leftButtonHeld
                && dragscroll$isSodiumScreen(screen)) {
            dragscroll$applySodiumThumbAbsolute(screen,
                    this.getScaledXPos(this.minecraft.getWindow()),
                    this.getScaledYPos(this.minecraft.getWindow()));
            this.accumulatedDX = 0.0;
            this.accumulatedDY = 0.0;
            DragScrollState.moved = true;
            return;
        }
        if (dragscroll$isTrenderScreen(screen) && DragScrollState.trenderBarLocked) {
            if (!DragScrollState.leftButtonHeld
                    && DragScrollState.trenderBarReleaseNs != 0L
                    && (System.nanoTime() - DragScrollState.trenderBarReleaseNs) > 5_000_000L) {
                DragScrollState.trenderBarLocked = false;
                DragScrollState.trenderBarClickSent = false;
                DragScrollState.trenderBarLockX = 0.0;
                DragScrollState.trenderThumbGrabArmed = false;
                DragScrollState.trenderGrabbedBar = null;
                DragScrollState.nativeScrollbarHeld = false;
                DragScrollState.nativeControlHeld = false;
                if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
                        "TRENDER_BAR_IDLE_CLEAR");
            } else {
                DragScrollState.active = false;
                // Do NOT re-apply the thumb position here from getScaledYPos():
                // on mobile launchers the reported cursor Y is frozen at the touch
                // point (the launcher teleports the cursor), while onMove receives
                // the live finger Y. Re-applying the stale Y every frame fought the
                // live onMove updates and made the thumb jump back to the press
                // point. onMove owns the thumb movement; here we only keep the grab
                // alive and swallow the teleport delta.
                this.accumulatedDX = 0.0;
                this.accumulatedDY = 0.0;
                DragScrollState.moved = true;
                return;
            }
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
        boolean clothSyntheticTouch = this.dragscroll$clothTouchArmed
                && this.dragscroll$clothTouchScreen == screen
                && (System.nanoTime() - this.dragscroll$clothPressNs) < 1_000_000_000L;

        // Never carry a gesture or a press snapshot into another screen.
        if (screen != dragscroll$lastScreen) {
            dragscroll$lastScreen = screen;
            DragScrollState.cancelPendingClick();
            dragscroll$resetAll();
            dragscroll$wasLeft = left;
            return;
        }

        // Finger released: preserve an active drag session for the next touch.
        if (!left && !clothSyntheticTouch) {
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
            if (dragscroll$isSodiumScreen(screen) && !DragScrollState.sodiumContentLocked) {
                DragScrollState.sodiumLockGuiX = this.dragscroll$pressGuiX;
                DragScrollState.sodiumLockGuiY = this.dragscroll$pressGuiY;
                DragScrollState.sodiumContentLocked = true;
            }
        } else {
            area = dragscroll$findScrollArea(screen, guiX, guiY);
            if (area != null) {
                dragscroll$beginRealDrag(area);
                DragScrollState.currentTouchDragged = true;
            } else if (dragscroll$isCustomScrollScreen(screen)
                    && !dragscroll$hasMultipleScrollAreas(screen)) {
                // Only known screen-level scrollers (Easy Install, Sodium,
                // Cloth Config, …) may use the custom path. Multi-list screens
                // (Mod Menu) must never get a whole-screen mouseScrolled.
                if (dragscroll$isSodiumScreen(screen) && !DragScrollState.sodiumContentLocked) {
                    DragScrollState.sodiumLockGuiX = this.dragscroll$pressGuiX;
                    DragScrollState.sodiumLockGuiY = this.dragscroll$pressGuiY;
                    DragScrollState.sodiumContentLocked = true;
                }
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
        if (DragScrollState.sodiumContentLocked && dragscroll$isSodiumScreen(screen)) {
            DragScrollState.inertiaGuiX = DragScrollState.sodiumLockGuiX;
            DragScrollState.inertiaGuiY = DragScrollState.sodiumLockGuiY;
        } else {
            DragScrollState.inertiaGuiX = guiX;
            DragScrollState.inertiaGuiY = guiY;
        }
        // Let the mouse-wheel emulator target the list the finger is scrolling.
        DragScrollState.rememberScrollTarget(screen,
                DragScrollState.inertiaGuiX, DragScrollState.inertiaGuiY);

        if (area == null) {
            // Continuous fractional mouseScrolled — avoids the 12px notch jumps that
            // made both live drag and the right-hand scrollbar thumb look jerky
            // on Easy Install / Cloth / Sodium custom screens.
            DragScrollState.moved = true;
            double units = applyDy / DragScrollState.pixelsPerNotch(screen);
            boolean invert = dragscroll$customScrollInverted(screen, this.dragscroll$pressGuiX);
            DragScrollState.fallbackScrollInverted = invert;
            double scrollDelta = invert ? units : -units;
            if (scrollDelta != 0.0) {
                try {
                    double sx = guiX;
                    double sy = guiY;
                    if (DragScrollState.sodiumContentLocked && dragscroll$isSodiumScreen(screen)) {
                        sx = DragScrollState.sodiumLockGuiX;
                        sy = DragScrollState.sodiumLockGuiY;
                    }
                    dragscroll$dispatchCustomMouseScroll(screen, sx, sy, scrollDelta);
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        DragScrollState.moved = true;
        // 1:1 finger tracking. Same sign as inertia (lockedScroll - frameDy).
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
        if (SafeMode.bypass()) {
            return;
        }
        Screen current = this.minecraft.gui.screen();
        if (MalilibCompat.isMalilibScreen(current) || dragscroll$isMalilibFamilyScreen(current)) {
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.handleAccumulatedMovement",
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
                if (!access.dragscroll$isOverScrollbar(x, y)) {
                    return false;
                }
                // Shulker Box Tooltip ConfigEntryList reserves 80px on the
                // right (getRowWidth = width - 80). The bar lives in that
                // gutter, not at the widget's last 18px.
                String n = DragScrollState.lowerName(area);
                if (n.contains("shulkerboxtooltip") || n.contains("configentrylist")) {
                    try {
                        int ax = area.getX();
                        int aw = area.getWidth();
                        return x >= ax + aw - 80 && x <= ax + aw + 8;
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
                if (n.contains("tconfig")) {
                    try {
                        int ax = area.getX();
                        int aw = area.getWidth();
                        return x >= ax + aw - 16 && x <= ax + aw + 12;
                    } catch (Throwable ignored) {
                        return true;
                    }
                }
                return true;
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
        String name = DragScrollState.lowerName(root);
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
    /**
     * TRender WScrollBar casts scroll deltas to int, so we only emit whole
     * notches there. Other custom screens keep the fractional path.
     */
    private static void dragscroll$dispatchCustomMouseScroll(Screen screen, double x, double y, double scrollDelta) {
        if (screen == null || scrollDelta == 0.0) {
            return;
        }
        double emit = scrollDelta;
        if (dragscroll$isTrenderScreen(screen)) {
            emit = DragScrollState.consumeTrenderScrollDelta(scrollDelta);
            if (emit == 0.0) {
                return;
            }
        }
        screen.mouseScrolled(x, y, 0.0, emit);
    }

    private static void dragscroll$beginCustomScreenDrag(Screen screen) {
        DragScrollState.active = true;
        DragScrollState.moved = false;
        DragScrollState.lockedArea = null;
        DragScrollState.fallbackScrollScreen = screen;
        DragScrollState.fallbackScrollInverted = dragscroll$customScrollInverted(screen, Double.NaN);
        if (dragscroll$isSodiumScreen(screen) && !DragScrollState.sodiumContentLocked) {
            DragScrollState.sodiumContentLocked = true;
        }
        DragScrollState.catchInertiaForDrag();
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.beginCustomScreenDrag",
                "screen=" + (screen == null ? "null" : screen.getClass().getName())
                        + " inverted=" + DragScrollState.fallbackScrollInverted);
    }

    @Unique
    private static boolean dragscroll$isEasyInstallScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("easy_install") || name.contains("easyinstall");
    }

    /**
     * tr7zw TRender / TRansition config screens (EntityCulling, Skin Layers 3D,
     * Wavey Capes, …). They wrap LibGui CottonClientScreen + WListPanel/WScrollBar
     * and have no vanilla AbstractScrollArea, so without this they fall into
     * VANILLA_INPUT_ONLY and finger drag never becomes mouseScrolled.
     */
    @Unique
    private static int[] dragscroll$trenderPanel(Screen screen) {
        int w = screen == null ? 0 : screen.width;
        // TRender/LibGui config roots are a centered ~300–400px modal, not 72%
        // of the screen. The 72% heuristic put the bar ~60px too far right,
        // so EntityCulling presses at x≈460 never grabbed the thumb.
        int panelW = Math.min(Math.max(w - 48, 200), 400);
        if (w > 0 && panelW > w - 24) {
            panelW = Math.max(200, w - 24);
        }
        int panelX = w > panelW ? (w - panelW) / 2 : 0;
        return new int[] {panelX, panelW};
    }

    @Unique
    private static double dragscroll$trenderBarCenterX(Screen screen, double fallbackX) {
        if (screen == null || screen.width <= 0) {
            return fallbackX;
        }
        int[] p = dragscroll$trenderPanel(screen);
        return p[0] + p[1] - 4.0;
    }

    /**
     * action: 1 = press, 2 = drag, 0 = release. Always dispatched at the
     * locked bar X so a sideways finger still drives only WScrollBar.
     */
    @Unique
    private static void dragscroll$collectTrenderScrollBars(Object node,
            java.util.IdentityHashMap<Object, Boolean> seen,
            java.util.List<Object> out) {
        if (node == null || seen.containsKey(node)) {
            return;
        }
        seen.put(node, Boolean.TRUE);
        String n = node.getClass().getName();
        if (n.contains("WScrollBar") || n.endsWith("ScrollBar")) {
            out.add(node);
        }
        if (node instanceof Screen screen) {
            try {
                java.lang.reflect.Method gd = screen.getClass().getMethod("getDescription");
                dragscroll$collectTrenderScrollBars(gd.invoke(screen), seen, out);
            } catch (Throwable ignored) {
            }
        }
        // Only the currently attached widget tree. Hidden tab cards keep their
        // WScrollBars in fields; walking those picks the previous tab's bar.
        for (String m : new String[]{"getRootPanel", "getChildren", "getScrollBar"}) {
            try {
                java.lang.reflect.Method method = node.getClass().getMethod(m);
                Object child = method.invoke(node);
                if (child instanceof java.lang.Iterable<?> it) {
                    for (Object o : it) {
                        dragscroll$collectTrenderScrollBars(o, seen, out);
                    }
                } else {
                    dragscroll$collectTrenderScrollBars(child, seen, out);
                }
            } catch (Throwable ignored) {
            }
        }
        Class<?> fc = node.getClass();
        while (fc != null && fc != Object.class) {
            for (String fn : new String[]{"children", "widgets", "scrollBar", "scrollbar"}) {
                try {
                    java.lang.reflect.Field f = fc.getDeclaredField(fn);
                    f.setAccessible(true);
                    Object v = f.get(node);
                    if (v instanceof java.lang.Iterable<?> it) {
                        for (Object o : it) {
                            dragscroll$collectTrenderScrollBars(o, seen, out);
                        }
                    } else if (v != null) {
                        dragscroll$collectTrenderScrollBars(v, seen, out);
                    }
                } catch (Throwable ignored) {
                }
            }
            fc = fc.getSuperclass();
        }
    }

    private static Object dragscroll$pickTrenderScrollBar(Screen screen, double guiY) {
        if (DragScrollState.trenderGrabbedBar != null) {
            return DragScrollState.trenderGrabbedBar;
        }
        java.util.List<Object> bars = new java.util.ArrayList<>();
        dragscroll$collectTrenderScrollBars(screen, new java.util.IdentityHashMap<>(), bars);
        if (bars.isEmpty()) {
            return null;
        }
        double lockX = DragScrollState.trenderBarLockX;
        Object best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Object bar : bars) {
            double max = dragscroll$invokeDouble(bar, "getMaxValue", "getMax");
            double height = dragscroll$invokeDouble(bar, "getHeight");
            double absX = dragscroll$invokeDouble(bar, "getAbsoluteX", "getX");
            double absY = dragscroll$invokeDouble(bar, "getAbsoluteY", "getY");
            double width = dragscroll$invokeDouble(bar, "getWidth");
            if (Double.isNaN(max) || max <= 0) {
                continue;
            }
            try {
                java.lang.reflect.Method vis = bar.getClass().getMethod("isVisible");
                Object v = vis.invoke(bar);
                if (v instanceof Boolean b && !b) {
                    continue;
                }
            } catch (Throwable ignored) {
            }
            if (Double.isNaN(height) || height < 8) {
                continue;
            }
            if (Double.isNaN(width)) {
                width = 8;
            }
            double score = max;
            if (!Double.isNaN(absX) && lockX != 0.0
                    && lockX >= absX - 24 && lockX <= absX + width + 24) {
                score += 5000;
            }
            if (!Double.isNaN(absY) && guiY >= absY - 12 && guiY <= absY + height + 12) {
                score += 5000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = bar;
            }
        }
        return best;
    }

    private static boolean dragscroll$applyTrenderThumbAbsolute(Screen screen, double guiY) {
        Object bar = dragscroll$pickTrenderScrollBar(screen, guiY);
        if (bar == null) {
            return false;
        }
        double max = dragscroll$invokeDouble(bar, "getMaxValue", "getMax");
        double window = dragscroll$invokeDouble(bar, "getWindow");
        double value = dragscroll$invokeDouble(bar, "getValue");
        double height = dragscroll$invokeDouble(bar, "getHeight");
        double absY = dragscroll$invokeDouble(bar, "getAbsoluteY", "getY");
        if (Double.isNaN(max) || max <= 0 || Double.isNaN(height) || height < 8) {
            return false;
        }
        if (Double.isNaN(window) || window < 1) {
            window = 1;
        }
        if (Double.isNaN(value)) {
            value = 0;
        }
        if (Double.isNaN(absY)) {
            absY = 32;
        }
        double range = Math.max(1.0, max - window);
        double handle = Math.max(8.0, height * (window / Math.max(max, 1.0)));
        double travel = Math.max(1.0, height - handle);
        if (!DragScrollState.trenderThumbGrabArmed) {
            // Grab the thumb by the FULL offset between the finger and the thumb
            // centre and keep it for the whole drag. This is what makes the grab
            // teleport-free: pressing anywhere on the track (even far from the
            // thumb) leaves the thumb exactly where it is, and it then follows the
            // finger 1:1 by the movement delta — the way every other slider in this
            // mod behaves. Zeroing the offset (snapping the thumb under the finger)
            // was the jump the user reported.
            double thumbCenter = absY + (value / range) * travel + handle * 0.5;
            double offset = guiY - thumbCenter;
            DragScrollState.trenderThumbGrabOffset = offset;
            DragScrollState.trenderThumbGrabArmed = true;
            DragScrollState.trenderGrabbedBar = bar;
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.trenderThumb",
                    "ARM offset=" + offset + " thumbCenter=" + thumbCenter
                            + " value=" + value + " max=" + max
                            + " window=" + window
                            + " captured=" + (DragScrollState.trenderGrabbedBar == bar));
            // Do NOT apply on the arming event — applying with the initial finger Y
            // and the fresh offset is a no-op anyway (thumb stays put), and returning
            // here avoids a redundant setValue on the press frame.
            return true;
        }
        double mappedY = guiY - DragScrollState.trenderThumbGrabOffset;
        double t = ((mappedY - absY) - handle * 0.5) / travel;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int target = (int) Math.round(t * range);
        try {
            java.lang.reflect.Method set = bar.getClass().getMethod("setValue", int.class);
            set.invoke(bar, target);
            try {
                java.lang.reflect.Method parent = bar.getClass().getMethod("getParent");
                Object p = parent.invoke(bar);
                if (p != null) {
                    try {
                        java.lang.reflect.Method layout = p.getClass().getMethod("layout");
                        layout.invoke(p);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.trenderThumb",
                    "SET value=" + target + " max=" + max + " y=" + guiY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void dragscroll$trenderBarPointer(Screen screen, double x, double y, int action) {
        if (screen == null) {
            return;
        }
        try {
            int button = 1;
            if (DragScrollState.lastPointerEvent != null) {
                try {
                    button = DragScrollState.lastPointerEvent.button();
                } catch (Throwable ignored) {
                    button = 1;
                }
            }
            MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
            DragScrollState.lastPointerEvent = event;
            if (action == 1) {
                screen.mouseClicked(event, false);
            } else if (action == 2) {
                screen.mouseDragged(event, 0.0, 0.0);
            } else {
                screen.mouseReleased(event);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean dragscroll$isTrenderScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("trender")
                || name.contains("tr7zw.trender")
                || name.contains("cottonclientscreen")
                || name.contains("cotton.gui")
                || name.contains("libgui")
                || name.contains("entityculling")
                || name.contains("lightweightguidescription");
    }

    /**
     * Screens whose content drag must use inverted mouseScrolled so that
     * finger-down shows lower items (Sodium, Cloth Config, Easy Install, …).
     * Finger scrolling on these UIs is already correct with inverted=true.
     * Custom scrollBAR widgets on the same screens are handled separately
     * (see isScrollBarTarget) so dragging the thumb is not double-inverted.
     */
    private static boolean dragscroll$customScrollInverted(Screen screen, double pressX) {
        boolean invert = dragscroll$isInvertedCustomScrollScreen(screen);
        if (invert && dragscroll$isClothConfigScreen(screen)
                && !Double.isNaN(pressX) && screen.width > 0
                && pressX >= screen.width - 120) {
            return false;
        }
        return invert;
    }

    private static boolean dragscroll$isTConfigScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("traben.tconfig")
                || name.contains("tconfig.gui")
                || name.contains("tconfigscreenlist");
    }

    private static boolean dragscroll$isTConfigScrollbarHover(Screen screen, double x, double y) {
        if (screen == null || Double.isNaN(x)) {
            return false;
        }
        try {
            if (screen.width > 0 && x >= screen.width - 16) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        java.util.List<AbstractScrollArea> all = new java.util.ArrayList<>();
        dragscroll$collectAll(screen, all);
        for (AbstractScrollArea area : all) {
            try {
                int ax = area.getX();
                int aw = area.getWidth();
                int ay = area.getY();
                int ah = area.getHeight();
                if (x >= ax + aw - 16 && x <= ax + aw + 12) {
                    if (Double.isNaN(y) || (y >= ay - 4 && y <= ay + ah + 4)) {
                        return true;
                    }
                }
                if (area instanceof NativeScrollbarAccess access
                        && access.dragscroll$isOverScrollbar(x, Double.isNaN(y) ? ay + ah * 0.5 : y)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean dragscroll$isShulkerConfigScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("shulkerboxtooltip") && name.contains("config");
    }

    private static boolean dragscroll$isShulkerScrollbarHover(Screen screen, double x, double y) {
        if (screen == null || Double.isNaN(x)) {
            return false;
        }
        java.util.List<AbstractScrollArea> all = new java.util.ArrayList<>();
        dragscroll$collectAll(screen, all);
        AbstractScrollArea best = null;
        int bestScore = -1;
        for (AbstractScrollArea area : all) {
            try {
                String n = DragScrollState.lowerName(area);
                if (!n.contains("shulkerboxtooltip") && !n.contains("configentrylist")) {
                    continue;
                }
                int ax = area.getX();
                int aw = area.getWidth();
                int ay = area.getY();
                int ah = area.getHeight();
                if (ah < 24 || aw < 16) {
                    continue;
                }
                if (!Double.isNaN(y) && (y < ay - 4 || y > ay + ah + 4)) {
                    continue;
                }
                boolean overStrip = x >= ax + aw - 80 && x <= ax + aw + 8;
                if (!overStrip) {
                    continue;
                }
                int score = ah;
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
            } catch (Throwable ignored) {
            }
        }
        return best != null;
    }

    private static AbstractScrollArea dragscroll$findShulkerList(Screen screen) {
        if (screen == null) {
            return null;
        }
        java.util.List<AbstractScrollArea> all = new java.util.ArrayList<>();
        dragscroll$collectAll(screen, all);
        AbstractScrollArea best = null;
        int bestScore = -1;
        for (AbstractScrollArea area : all) {
            try {
                String n = DragScrollState.lowerName(area);
                if (!n.contains("shulkerboxtooltip") && !n.contains("configentrylist")) {
                    continue;
                }
                int ah = area.getHeight();
                int aw = area.getWidth();
                if (ah < 24 || aw < 16) {
                    continue;
                }
                int score = ah + aw;
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
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static boolean dragscroll$applyShulkerThumbAbsolute(Screen screen, double guiY) {
        if (screen == null || Double.isNaN(guiY)) {
            return false;
        }
        AbstractScrollArea list = dragscroll$findShulkerList(screen);
        if (list == null) {
            return false;
        }
        double top;
        double bottom;
        double max;
        try {
            top = list.getY();
            bottom = list.getY() + list.getHeight();
            max = list.maxScrollAmount();
        } catch (Throwable t) {
            return false;
        }
        if (max <= 0.0 || bottom - top < 8.0) {
            return false;
        }
        double current;
        try {
            current = list.scrollAmount();
        } catch (Throwable t) {
            current = 0.0;
        }
        double next = dragscroll$mapGrabbedThumb(guiY, top, bottom, current, max);
        if (!DragScrollState.clothThumbGrabArmed) {
            return false;
        }
        if (Math.abs(next - current) < 0.01) {
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler",
                    "SHULKER_THUMB_ARM current=" + current + " max=" + max);
            return true;
        }
        try {
            list.setScrollAmount(next);
        } catch (Throwable ignored) {
            return false;
        }
        DragScrollState.lockedScroll = next;
        DragScrollState.remember(list, next);
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler",
                "SHULKER_THUMB_SET next=" + next + " max=" + max);
        return true;
    }

    /**
     * Maps a grabbed scrollbar: attach to the visible thumb center (no teleport)
     * and drive scroll by the usable track, not 1:1 content pixels.
     */
    @Unique
    private static double dragscroll$mapGrabbedThumb(double guiY, double top, double bottom,
                                                     double current, double max) {
        double track = bottom - top;
        if (track < 8.0 || max <= 0.0) {
            return current;
        }
        double thumbH = Math.max(16.0, track * track / (track + max));
        if (thumbH > track * 0.85) {
            thumbH = track * 0.5;
        }
        double usable = Math.max(1.0, track - thumbH);
        if (!DragScrollState.clothThumbGrabArmed) {
            double visualT = current / max;
            if (visualT < 0.0) visualT = 0.0;
            if (visualT > 1.0) visualT = 1.0;
            double thumbCenter = top + thumbH * 0.5 + visualT * usable;
            DragScrollState.clothThumbGrabOffset = guiY - thumbCenter;
            DragScrollState.clothThumbGrabArmed = true;
            return current;
        }
        double targetCenter = guiY - DragScrollState.clothThumbGrabOffset;
        double t = (targetCenter - top - thumbH * 0.5) / usable;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return t * max;
    }

    private static boolean dragscroll$isInvertedCustomScrollScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        // Chat: finger up should scroll history the other way (toward newer /
        // older messages opposite of default mouseScrolled sign).
        if (dragscroll$isChatScreen(screen)) {
            return true;
        }
        String name = DragScrollState.lowerName(screen);
        // Cloth Config 26.3 mouseScrolled is opposite of vanilla lists.
        return name.contains("clothconfig")
                || name.contains("cloth_config")
                || name.contains("me.shedaniel.clothconfig")
                || name.contains("me.shedaniel.autoconfig")
                || name.contains("sodium")
                || name.contains("easy_install")
                || name.contains("easyinstall")
                || name.contains("lambdynlights")
                || name.contains("yacl")
                || name.contains("yetanotherconfig")
                || name.contains("yet_another_config")
                || dragscroll$isTrenderScreen(screen)
                || name.contains("fzzy")
                || name.contains("midnightlib")
                || name.contains("resourcefulconfig")
                || name.contains("spruceui")
                || name.contains("modmenu")
                || name.contains("shulkerboxtooltip");
    }

    /**
     * Screens owned by MaLiLib and its family of mods, matched by name.
     * Callers test {@link MalilibCompat#isMalilibScreen} first: GuiBase
     * screens get the MaLiLib touch handling, and whatever else these mods
     * show (e.g. the inventory preview overlay) keeps its input untouched.
     */
    private static boolean dragscroll$isMalilibFamilyScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("litematica")
                || name.contains("malilib")
                || name.contains("tweakeroo")
                || name.contains("minihud")
                || name.contains("itemscroller")
                || name.startsWith("fi.dy.masa.");
    }

    private static Integer dragscroll$intField(Object obj, String name) {
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            try {
                java.lang.reflect.Field field = walk.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Screens that scroll via screen.mouseScrolled without an AbstractScrollArea
     * under the finger (Easy Install, Sodium options, Cloth Config, YACL, …).
     */

    @Unique
    private static boolean dragscroll$isClothScrollbarHover(Screen screen, double x, double y) {
        return dragscroll$findClothScrollbar(screen, x, y, new java.util.IdentityHashMap<>());
    }

    @Unique
    private static Object dragscroll$findClothListWidget(Object node,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 24) {
            return null;
        }
        String n = DragScrollState.lowerName(node);
        if (n.contains("clothconfig") && (n.contains("listwidget") || n.contains("entrylist")
                || n.contains("dynamic"))) {
            return node;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                Object found = dragscroll$findClothListWidget(child, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Unique
    private static boolean dragscroll$applyClothThumbAbsolute(Screen screen, double guiY) {
        if (screen == null) {
            return false;
        }
        Object list = dragscroll$findClothListWidget(screen, new java.util.IdentityHashMap<>());
        if (list == null) {
            list = screen;
        }
        double top = Double.NaN;
        double bottom = Double.NaN;
        if (list instanceof AbstractWidget w) {
            top = w.getY();
            bottom = w.getY() + w.getHeight();
        } else {
            top = dragscroll$readDoubleField(list, "top", "y", "posY", "y0");
            bottom = dragscroll$readDoubleField(list, "bottom", "y1");
            double height = dragscroll$readDoubleField(list, "height");
            if (Double.isNaN(bottom) && !Double.isNaN(top) && !Double.isNaN(height)) {
                bottom = top + height;
            }
        }
        if (Double.isNaN(top) || Double.isNaN(bottom) || bottom - top < 8.0) {
            top = 32;
            bottom = Math.max(top + 8, screen.height - 32);
        }
        double max = dragscroll$invokeDouble(list, "getMaxScroll", "getMaxScrollPosition", "getMaxScrollAmount");
        if (Double.isNaN(max) || max <= 0) {
            max = dragscroll$readDoubleField(list, "maxScroll", "maxScrollPosition");
        }
        if (Double.isNaN(max) || max <= 0) {
            return false;
        }
        double current = dragscroll$invokeDouble(list, "getScroll", "getScrollAmount",
                "getScrollPosition", "getMaxScrollPosition");
        if (Double.isNaN(current)) {
            current = dragscroll$readDoubleField(list, "scroll", "scrollAmount", "target", "scrollTarget");
        }
        if (Double.isNaN(current)) {
            current = 0;
        }
        double target = dragscroll$mapGrabbedThumb(guiY, top, bottom, current, max);
        dragscroll$invokeScrollTo(list, target);
        dragscroll$writeDoubleField(list, target,
                "scroll", "scrollAmount", "target", "scrollTarget", "pendingScroll");
        return true;
    }

    @Unique
    private static double dragscroll$invokeDouble(Object node, String... methods) {
        Class<?> c = node.getClass();
        while (c != null && c != Object.class) {
            for (String name : methods) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object v = m.invoke(node);
                    if (v instanceof Number n) {
                        return n.doubleValue();
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return Double.NaN;
    }

    @Unique
    private static boolean dragscroll$invokeScrollTo(Object node, double target) {
        Class<?> c = node.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("scrollTo") || name.equals("setScrollAmount")
                        || name.equals("capYPosition") || name.equals("setScroll"))) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                try {
                    m.setAccessible(true);
                    if (p.length == 1 && (p[0] == double.class || p[0] == Double.class
                            || p[0] == float.class || p[0] == int.class)) {
                        if (p[0] == int.class) {
                            m.invoke(node, (int) Math.round(target));
                        } else if (p[0] == float.class) {
                            m.invoke(node, (float) target);
                        } else {
                            m.invoke(node, target);
                        }
                        return true;
                    }
                    if (p.length == 2 && (p[0] == double.class || p[0] == Double.class)
                            && (p[1] == boolean.class || p[1] == Boolean.class)) {
                        m.invoke(node, target, Boolean.FALSE);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    @Unique
    private static void dragscroll$writeDoubleField(Object node, double value, String... names) {
        Class<?> c = node.getClass();
        while (c != null && c != Object.class) {
            for (String name : names) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.getType() == double.class) {
                        f.setDouble(node, value);
                    } else if (f.getType() == float.class) {
                        f.setFloat(node, (float) value);
                    } else if (f.getType() == int.class) {
                        f.setInt(node, (int) Math.round(value));
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
    }

    @Unique
    private static boolean dragscroll$findClothScrollbar(Object node, double x, double y,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 24) {
            return false;
        }
        String n = DragScrollState.lowerName(node);
        if (n.contains("clothconfig") && (n.contains("listwidget") || n.contains("entrylist")
                || n.contains("dynamic"))) {
            double left = Double.NaN, top = Double.NaN, right = Double.NaN, bottom = Double.NaN;
            if (node instanceof AbstractWidget w) {
                left = w.getX();
                top = w.getY();
                right = w.getX() + w.getWidth();
                bottom = w.getY() + w.getHeight();
            } else {
                left = dragscroll$readDoubleField(node, "left", "x", "posX", "x0");
                top = dragscroll$readDoubleField(node, "top", "y", "posY", "y0");
                right = dragscroll$readDoubleField(node, "right", "x1");
                bottom = dragscroll$readDoubleField(node, "bottom", "y1");
                double width = dragscroll$readDoubleField(node, "width");
                double height = dragscroll$readDoubleField(node, "height");
                if (Double.isNaN(right) && !Double.isNaN(left) && !Double.isNaN(width)) {
                    right = left + width;
                }
                if (Double.isNaN(bottom) && !Double.isNaN(top) && !Double.isNaN(height)) {
                    bottom = top + height;
                }
            }
            if (!Double.isNaN(left) && !Double.isNaN(right) && !Double.isNaN(top) && !Double.isNaN(bottom)) {
                double barLeft = right - 20.0;
                if (x >= barLeft && x <= right + 4.0 && y >= top && y <= bottom) {
                    return true;
                }
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                if (dragscroll$findClothScrollbar(child, x, y, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private static double dragscroll$readDoubleField(Object node, String... names) {
        Class<?> c = node.getClass();
        while (c != null && c != Object.class) {
            for (String name : names) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.getType() == int.class) {
                        return f.getInt(node);
                    }
                    if (f.getType() == double.class) {
                        return f.getDouble(node);
                    }
                    if (f.getType() == float.class) {
                        return f.getFloat(node);
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return Double.NaN;
    }

    private static boolean dragscroll$isClothConfigScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("clothconfig")
                || name.contains("cloth_config")
                || name.contains("me.shedaniel.clothconfig")
                || name.contains("me.shedaniel.autoconfig");
    }

    /**
     * In-game chat (ChatScreen / SleepingChatScreen). Has no AbstractScrollArea;
     * history is driven only via ChatComponent.scrollChat through mouseScrolled.
     * Vanilla chat scrollbar is visual-only (not LMB-draggable), so we only add
     * finger-drag → mouseScrolled support here.
     */
    private static boolean dragscroll$isChatScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("chatscreen")
                || name.contains("sleepingchatscreen")
                || screen.getClass().getSimpleName().equals("ChatScreen")
                || screen.getClass().getSimpleName().equals("SleepingChatScreen");
    }

    private static boolean dragscroll$isCustomScrollScreen(Screen screen) {
        return dragscroll$isEasyInstallScreen(screen)
                || dragscroll$isInvertedCustomScrollScreen(screen)
                || dragscroll$isClothConfigScreen(screen)
                || dragscroll$isTrenderScreen(screen)
                || dragscroll$isTConfigScreen(screen)
                || dragscroll$isShulkerConfigScreen(screen)
                || dragscroll$isChatScreen(screen)
                || dragscroll$isYaclScreen(screen)
                || (screen instanceof ru.evga314.dragscroll.modmenu.DragScrollConfigScreen);
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
        if (root instanceof Screen screen && dragscroll$isClothConfigScreen(screen)
                && dragscroll$isClothScrollbarHover(screen, x, y)) {
            return true;
        }
        if (root instanceof Screen screen && dragscroll$isSodiumScreen(screen)
                && dragscroll$isSodiumScrollbarHover(screen, x, y)) {
            return true;
        }
        if (root instanceof Screen screen && dragscroll$isTConfigScreen(screen)
                && dragscroll$isTConfigScrollbarHover(screen, x, y)) {
            return true;
        }
        if (root instanceof Screen screen && dragscroll$isShulkerConfigScreen(screen)
                && dragscroll$isShulkerScrollbarHover(screen, x, y)) {
            return true;
        }
        // TRender / LibGui WScrollBar is not in Screen.children().
        // Only the thin strip at the right edge of the centered modal is
        // the bar. A wide band (64–84% of screen) previously stole list
        // drags and turned them into checkbox clicks.
        if (root instanceof Screen screen && dragscroll$isTrenderScreen(screen)) {
            try {
                int w = screen.width;
                if (w > 0) {
                    int[] p = dragscroll$trenderPanel(screen);
                    // 8px WScrollBar plus a fat-finger strip to its left, and
                    // the empty margin to the screen edge. Checkboxes stay left.
                    double barLeft = p[0] + p[1] - 48.0;
                    double barRight = p[0] + p[1] + 12.0;
                    if (x >= barLeft && x <= barRight) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Full-width custom lists (Easy Install): thin strip at the absolute right edge.
        // Do not apply this heuristic on Sodium — option buttons sit in that band.
        if (root instanceof Screen screen
                && !dragscroll$isSodiumScreen(screen)
                && !dragscroll$isTrenderScreen(screen)) {
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
        String name = DragScrollState.lowerName(root);
        // Only real scrollbar widgets. Do NOT match *EntryList / *EntriesList —
        // those are the scrollable lists themselves (e.g. Shulker Box Tooltip
        // ConfigEntryList). Treating the list as a bar swallows content drag.
        if (name.contains("scrollbar") || name.contains("scroll_bar")
                || name.contains("scrollhandle") || name.contains("scroll_handle")
                || name.contains("scrollthumb") || name.contains("scroll_thumb")
                || name.contains("scroller") || name.contains("scrollpanel")
                || name.contains("scroll_panel")) {
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
    /**
     * Screens that must receive raw mouse events: inventories, GUIs with no
     * list to scroll, and Xaero's World Map (wheel = zoom, LMB = pan / native
     * zoom slider). Converting drag to mouseScrolled there fights the slider.
     */
    private static boolean dragscroll$isVanillaInputOnlyScreen(Screen screen) {
        if (screen == null) {
            return true;
        }
        if (ru.evga314.dragscroll.XaeroMapZoomOverlay.isMapScreen(screen)) {
            return true;
        }
        return dragscroll$isContainerScreen(screen)
                || !dragscroll$screenSupportsScroll(screen);
    }

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
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.beginRealDrag",
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
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.beginRealDrag",
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
        this.dragscroll$clothTouchArmed = false;
        this.dragscroll$clothTouchScreen = null;
        this.dragscroll$clothPressNs = 0L;
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
        List<AbstractScrollArea> over = new ArrayList<>();
        dragscroll$collectOver(root, x, y, over);
        if (!over.isEmpty()) {
            AbstractScrollArea result = over.get(over.size() - 1);
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.findScrollArea",
                    "x=" + x + " y=" + y
                            + " found=" + over.size()
                            + " result=" + result.getClass().getName()
                            + " leftHeld=" + DragScrollState.leftButtonHeld);
            return result;
        }
        // isMouseOver can miss a pane (Mod Menu description after a list scroll).
        // Fall back to the list whose rectangle / column contains the point.
        List<AbstractScrollArea> all = new ArrayList<>();
        dragscroll$collectAll(root, all);
        AbstractScrollArea result = dragscroll$pickAreaByBounds(all, x, y);
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("MouseHandler.findScrollArea",
                "x=" + x + " y=" + y
                        + " found=0 all=" + all.size()
                        + " result=" + (result == null ? "null" : result.getClass().getName())
                        + " leftHeld=" + DragScrollState.leftButtonHeld);
        return result;
    }

    @Unique
    private static boolean dragscroll$hasMultipleScrollAreas(GuiEventListener root) {
        List<AbstractScrollArea> all = new ArrayList<>();
        dragscroll$collectAll(root, all);
        return all.size() >= 2;
    }

    @Unique
    private static AbstractScrollArea dragscroll$pickAreaByBounds(List<AbstractScrollArea> all,
                                                                 double x, double y) {
        AbstractScrollArea best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (AbstractScrollArea area : all) {
            double[] box = dragscroll$areaBox(area);
            if (box == null) {
                continue;
            }
            double ax = box[0], ay = box[1], aw = box[2], ah = box[3];
            boolean inside = x >= ax && x <= ax + aw && y >= ay && y <= ay + ah;
            boolean inColumn = x >= ax && x <= ax + aw;
            if (!inside && !inColumn) {
                continue;
            }
            double cx = ax + aw * 0.5;
            double cy = ay + ah * 0.5;
            double score = Math.abs(x - cx) + (inside ? 0.0 : 1000.0 + Math.abs(y - cy));
            if (score < bestScore) {
                bestScore = score;
                best = area;
            }
        }
        return best;
    }

    @Unique
    private static double[] dragscroll$areaBox(AbstractScrollArea area) {
        try {
            if (area instanceof AbstractWidget w) {
                return new double[] { w.getX(), w.getY(), w.getWidth(), w.getHeight() };
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method gx = area.getClass().getMethod("getX");
            java.lang.reflect.Method gy = area.getClass().getMethod("getY");
            java.lang.reflect.Method gw = area.getClass().getMethod("getWidth");
            java.lang.reflect.Method gh = area.getClass().getMethod("getHeight");
            return new double[] {
                    ((Number) gx.invoke(area)).doubleValue(),
                    ((Number) gy.invoke(area)).doubleValue(),
                    ((Number) gw.invoke(area)).doubleValue(),
                    ((Number) gh.invoke(area)).doubleValue()
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static void dragscroll$collectOver(GuiEventListener node, double x, double y,
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
                dragscroll$collectOver(child, x, y, out);
            }
        }
    }

    @Unique
    private static void dragscroll$collectAll(GuiEventListener node, List<AbstractScrollArea> out) {
        if (node instanceof AbstractScrollArea area) {
            out.add(area);
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                dragscroll$collectAll(child, out);
            }
        }
    }

    @Unique
    private static void dragscroll$collect(GuiEventListener node, double x, double y,
                                           List<AbstractScrollArea> out) {
        dragscroll$collectOver(node, x, y, out);
    }

    @Unique
    private static boolean dragscroll$isYaclScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("yacl")
                || name.contains("yetanotherconfig")
                || name.contains("yet_another_config")
                || name.contains("dev.isxander.yacl");
    }

    private static boolean dragscroll$isSodiumScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        return name.contains("sodium") && (name.contains("videosettings")
                || name.contains("option") || name.contains("gui"));
    }

    @Unique
    private static boolean dragscroll$isSodiumScrollbarHover(Screen screen, double x, double y) {
        if (screen == null) {
            return false;
        }
        Object bar = dragscroll$resolveSodiumBarByColumn(screen, x);
        if (bar != null && dragscroll$pointOnSodiumBar(bar, x, y)) {
            return true;
        }
        Object found = dragscroll$findSodiumScrollbarWidget(screen, x, y, new java.util.IdentityHashMap<>());
        return found != null && dragscroll$pointOnSodiumBar(found, x, y);
    }

    @Unique
    private static boolean dragscroll$pointOnSodiumBar(Object bar, double x, double y) {
        double[] d = dragscroll$sodiumWidgetDim(bar);
        if (d == null) {
            return false;
        }
        double bx = d[0], by = d[1], bw = Math.max(1, d[2]), bh = d[3];
        double padL = bx > 140 ? bw * 0.40 : 6;
        double padR = bx > 140 ? bw * 1.40 : 6;
        return x >= bx - padL && x <= bx + bw + padR && y >= by && y <= by + bh;
    }

    @Unique
    private static double[] dragscroll$sodiumWidgetDim(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof AbstractWidget w) {
            return new double[] { w.getX(), w.getY(), w.getWidth(), w.getHeight() };
        }
        double x = dragscroll$invokeDouble(node, "getX");
        double y = dragscroll$invokeDouble(node, "getY");
        double w = dragscroll$invokeDouble(node, "getWidth");
        double h = dragscroll$invokeDouble(node, "getHeight");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(w) || Double.isNaN(h) || h < 4) {
            return null;
        }
        return new double[] { x, y, w, h };
    }

    @Unique
    private static Object dragscroll$resolveSodiumBarByColumn(Screen screen, double guiX) {
        Object pageList = dragscroll$findNamedSodiumWidget(screen, "pagelistwidget", new java.util.IdentityHashMap<>());
        Object optionList = dragscroll$findNamedSodiumWidget(screen, "optionlistwidget", new java.util.IdentityHashMap<>());
        Object pageBar = dragscroll$scrollbarOf(pageList);
        Object optionBar = dragscroll$scrollbarOf(optionList);
        if (pageBar == null && optionBar == null) {
            return null;
        }
        int splitX = 160;
        double[] optionDim = dragscroll$sodiumWidgetDim(optionList);
        double[] pageDim = dragscroll$sodiumWidgetDim(pageList);
        if (optionDim != null) {
            splitX = (int) optionDim[0];
        } else if (pageDim != null) {
            splitX = (int) (pageDim[0] + pageDim[2]);
        }
        if (guiX < splitX) {
            return pageBar != null ? pageBar : optionBar;
        }
        return optionBar != null ? optionBar : pageBar;
    }

    @Unique
    private static Object dragscroll$scrollbarOf(Object list) {
        if (list == null) {
            return null;
        }
        try {
            for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!f.getName().equals("scrollbar")) {
                        continue;
                    }
                    f.setAccessible(true);
                    return f.get(list);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private static Object dragscroll$findNamedSodiumWidget(Object node, String classNeedle,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 64) {
            return null;
        }
        if (DragScrollState.lowerName(node).contains(classNeedle)) {
            return node;
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                Object found = dragscroll$findNamedSodiumWidget(child, classNeedle, seen);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Unique
    private static Object dragscroll$findSodiumScrollbarWidget(Object node, double x, double y,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        java.util.ArrayList<Object> bars = new java.util.ArrayList<>();
        dragscroll$collectSodiumScrollbars(node, seen, bars);
        Object best = null;
        double bestDist = Double.MAX_VALUE;
        for (Object bar : bars) {
            double[] d = dragscroll$sodiumWidgetDim(bar);
            if (d == null) continue;
            double padL = d[0] > 140 ? d[2] * 0.40 : 4;
            double padR = d[0] > 140 ? d[2] * 1.40 : 6;
            if (x < d[0] - padL || x > d[0] + d[2] + padR || y < d[1] || y > d[1] + d[3]) {
                continue;
            }
            double distX = Math.abs(x - (d[0] + d[2] * 0.5));
            if (distX < bestDist) {
                bestDist = distX;
                best = bar;
            }
        }
        return best;
    }

    @Unique
    private static void dragscroll$collectSodiumScrollbars(Object node,
            java.util.IdentityHashMap<Object, Boolean> seen, java.util.List<Object> out) {
        if (node == null || seen.put(node, Boolean.TRUE) != null || seen.size() > 256) {
            return;
        }
        String n = DragScrollState.lowerName(node);
        if (n.contains("scrollbarwidget") || (n.contains("sodium") && n.contains("scrollbar"))) {
            out.add(node);
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                dragscroll$collectSodiumScrollbars(child, seen, out);
            }
        }
    }

    @Unique
    private static boolean dragscroll$applySodiumThumbAbsolute(Screen screen, double guiX, double guiY) {
        if (screen == null) {
            return false;
        }
        Object bar = DragScrollState.sodiumGrabbedBar;
        if (bar == null) {
            bar = dragscroll$resolveSodiumBarByColumn(screen, guiX);
        }
        if (bar == null) {
            bar = dragscroll$findSodiumScrollbarWidget(screen, guiX, guiY, new java.util.IdentityHashMap<>());
        }
        if (bar == null) {
            return false;
        }
        DragScrollState.sodiumGrabbedBar = bar;
        double[] dim = dragscroll$sodiumWidgetDim(bar);
        double top = dim != null ? dim[1] : 32;
        double height = dim != null ? dim[3] : Math.max(8, screen.height - 64);
        if (height < 8) {
            height = Math.max(8, screen.height - 64);
        }
        double total = dragscroll$readDoubleField(bar, "total");
        double visible = dragscroll$readDoubleField(bar, "visible");
        if (Double.isNaN(total) || total <= 1) {
            return false;
        }
        if (Double.isNaN(visible) || visible <= 0) {
            visible = height;
        }
        double max = Math.max(1.0, total - visible);
        if (!DragScrollState.sodiumThumbGrabArmed) {
            double current = dragscroll$readDoubleField(bar, "scrollAmount");
            if (Double.isNaN(current)) {
                current = dragscroll$invokeDouble(bar, "getScrollAmount");
            }
            if (Double.isNaN(current)) {
                current = 0;
            }
            double visualT = current / max;
            if (visualT < 0) visualT = 0;
            if (visualT > 1) visualT = 1;
            double thumbCenter = top + visualT * height;
            DragScrollState.sodiumThumbGrabOffset = guiY - thumbCenter;
            DragScrollState.sodiumThumbGrabArmed = true;
            return true;
        }
        double mappedY = guiY - DragScrollState.sodiumThumbGrabOffset;
        double tt = (mappedY - top) / height;
        if (tt < 0) tt = 0;
        if (tt > 1) tt = 1;
        int target = (int) Math.round(tt * max);
        boolean ok = dragscroll$invokeScrollTo(bar, target);
        if (!ok) {
            dragscroll$writeDoubleField(bar, target, "scrollAmount");
        }
        return true;
    }
}
