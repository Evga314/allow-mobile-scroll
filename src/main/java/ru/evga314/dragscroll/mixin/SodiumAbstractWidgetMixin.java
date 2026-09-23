package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget", remap = false)
public abstract class SodiumAbstractWidgetMixin {
    @Inject(method = "isMouseOver", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$widerScrollbar(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        String n = this.getClass().getName();
        if (!n.contains("Scrollbar")) {
            return;
        }
        try {
            int x = (Integer) this.getClass().getMethod("getX").invoke(this);
            int y = (Integer) this.getClass().getMethod("getY").invoke(this);
            int w = (Integer) this.getClass().getMethod("getWidth").invoke(this);
            int h = (Integer) this.getClass().getMethod("getHeight").invoke(this);
            if (w <= 0) {
                w = 5;
            }
            double padL = x > 140 ? w * 0.40 : 0;
            double padR = x > 140 ? w * 1.40 : 0;
            cir.setReturnValue(mouseX >= x - padL && mouseX < x + w + padR && mouseY >= y && mouseY < y + h);
        } catch (Throwable ignored) {
        }
    }
}
