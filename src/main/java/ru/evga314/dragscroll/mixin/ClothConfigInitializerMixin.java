package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test mixin: Cloth animated scroll duration = 0 (instant jump).
 */
@Mixin(targets = "me.shedaniel.clothconfig2.ClothConfigInitializer", remap = false)
public abstract class ClothConfigInitializerMixin {

    @Inject(method = "getScrollDuration", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dragscroll$noClothDuration(CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(0L);
    }
}
