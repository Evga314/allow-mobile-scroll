package ru.evga314.dragscroll.mixin;

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
import ru.evga314.dragscroll.touch.MoveHandler;
import ru.evga314.dragscroll.touch.PressHandler;

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
    private void dragscroll$onMove(long handle, double xpos, double ypos, double xrel, double yrel, CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            if (MoveHandler.onMove(this, xpos, ypos)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            SafeMode.reportFailure("MouseHandler.onMove", t);
        }
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
