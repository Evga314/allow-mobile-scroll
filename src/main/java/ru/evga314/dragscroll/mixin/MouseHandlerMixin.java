package ru.evga314.dragscroll.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.access.MouseHandlerAccess;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.FrameHandler;
import ru.evga314.dragscroll.touch.GrabJumpGuard;
import ru.evga314.dragscroll.touch.MoveHandler;
import ru.evga314.dragscroll.touch.PressHandler;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Entry point of the touch handling: forwards MouseHandler's events to the
 * handlers in {@code ru.evga314.dragscroll.touch}. Every hook passes straight
 * to vanilla in safe mode, and an error inside a handler is reported to
 * {@link SafeMode} instead of crashing the game.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin implements MouseHandlerAccess {

    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;
    @Shadow
    private double xpos;
    @Shadow
    private double ypos;
    @Shadow
    private boolean mouseGrabbed;

    @Override
    public void dragscroll$clearAccumulatedMovement() {
        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void dragscroll$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            if (action == InputConstants.PRESS && info != null && TouchState.isLeftButton(info.button())) {
                GrabJumpGuard.onLeftPress();
            }
            if (PressHandler.onButton(this, info, action)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onButton", t);
        }
    }

    /**
     * The screen click of a press: dispatched, held back or dropped, as the
     * press classification decided. Decided first and dispatched once, so an
     * exception cannot make one tap press a button twice.
     */
    @Redirect(method = "onButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"),
            require = 0)
    private boolean dragscroll$screenClick(Screen screen, MouseButtonEvent event, boolean doubleClick) {
        if (SafeMode.bypass()) {
            return screen != null && screen.mouseClicked(event, doubleClick);
        }
        PressHandler.Click decision;
        try {
            decision = PressHandler.decideClick(screen, event);
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.mouseClicked", t);
            decision = PressHandler.Click.DISPATCH;
        }
        return PressHandler.dispatchClick(screen, event, doubleClick, decision);
    }

    @Inject(method = "onButton", at = @At("RETURN"))
    private void dragscroll$afterButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            PressHandler.afterButton(info, action);
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onButton", t);
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void dragscroll$onMove(long handle, double xpos, double ypos, CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            if (this.mouseGrabbed && GrabJumpGuard.isStaleJump(xrel, yrel)) {
                ci.cancel();
                return;
            }
            if (MoveHandler.onMove(this, xpos, ypos)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onMove", t);
        }
    }

    /** Before vanilla moves the cursor to the center: xpos/ypos are still the tap point. */
    @Inject(method = "grabMouse", at = @At("HEAD"))
    private void dragscroll$beforeGrab(CallbackInfo ci) {
        if (SafeMode.bypass() || this.mouseGrabbed) {
            return;
        }
        try {
            Window window = Minecraft.getInstance().getWindow();
            GrabJumpGuard.onGrab(this.xpos, this.ypos, window.getScreenWidth() / 2.0, window.getScreenHeight() / 2.0);
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.grabMouse", t);
        }
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"))
    private void dragscroll$beforeRelease(CallbackInfo ci) {
        GrabJumpGuard.onRelease();
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void dragscroll$beforeMovement(CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            FrameHandler.beforeMovement(this);
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.handleAccumulatedMovement", t);
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("RETURN"))
    private void dragscroll$afterMovement(CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            FrameHandler.afterMovement();
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.handleAccumulatedMovement", t);
        }
    }
}
