package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test mixin: disable Cloth Config's own animated list scrolling.
 */
@Mixin(targets = {
        "me.shedaniel.clothconfig2.gui.widget.DynamicNewSmoothScrollingEntryListWidget"
}, remap = false)
public abstract class ClothSmoothScrollMixin {

    @Inject(method = "isSmoothScrolling", at = @At("HEAD"), cancellable = true, remap = false)
    private void dragscroll$disableClothSmooth(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
