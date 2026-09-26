package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.compat.SafeMode;

/**
 * Cloth animated scroll duration = 0 (instant jump). Applied only when the
 * Cloth Config version has getScrollDuration()J (see ClothMixinPlugin).
 */
@Mixin(targets = "me.shedaniel.clothconfig2.ClothConfigInitializer", remap = false)
public abstract class ClothConfigInitializerMixin {

    @Inject(method = "getScrollDuration", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dragscroll$noClothDuration(CallbackInfoReturnable<Long> cir) {
        if (SafeMode.bypass()) {
            return;
        }
        cir.setReturnValue(0L);
    }
}
