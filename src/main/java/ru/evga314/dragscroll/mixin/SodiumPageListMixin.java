package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.DragScrollState;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.PageListWidget", remap = false)
public abstract class SodiumPageListMixin {
    @Inject(method = "switchSelected", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$holdPageListDuringOptionThumb(@Coerce Object page, CallbackInfo ci) {
        if (!DragScrollState.nativeScrollbarHeld || DragScrollState.sodiumGrabbedBar == null) {
            return;
        }
        try {
            Object bar = DragScrollState.sodiumGrabbedBar;
            if (bar instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getX() > 140) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
