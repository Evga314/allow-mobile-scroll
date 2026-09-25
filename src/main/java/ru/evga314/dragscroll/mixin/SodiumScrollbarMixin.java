package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;
import ru.evga314.dragscroll.compat.SafeMode;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget", remap = false)
public abstract class SodiumScrollbarMixin {
        @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$blockNativeDrag(CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && DragScrollState.nativeScrollbarHeld) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$blockNativeClick(CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && DragScrollState.nativeScrollbarHeld) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMouseOver", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$widerRightHit(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (SafeMode.bypass()) {
            return;
        }
        try {
            int x = (Integer) this.getClass().getMethod("getX").invoke(this);
            int y = (Integer) this.getClass().getMethod("getY").invoke(this);
            int w = (Integer) this.getClass().getMethod("getWidth").invoke(this);
            int h = (Integer) this.getClass().getMethod("getHeight").invoke(this);
            if (w <= 0) { w = 5; }
            double padL = x > 140 ? w * 0.40 : 0;
            double padR = x > 140 ? w * 1.40 : 0;
            boolean over = mouseX >= x - padL && mouseX < x + w + padR && mouseY >= y && mouseY < y + h;
            cir.setReturnValue(over);
        } catch (Throwable ignored) {
        }
    }
}
