package ru.evga314.dragscroll.mixin.compat.cloth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Cloth Config animates every scroll over a duration; with a finger that
 * makes the list lag behind it. The duration is set to 0 (instant). Applied
 * only when Cloth has getScrollDuration()J (see ClothMixinPlugin).
 */
@Mixin(targets = "me.shedaniel.clothconfig2.ClothConfigInitializer", remap = false)
public abstract class ClothConfigInitializerMixin {

    @Inject(method = "getScrollDuration", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dragscroll$instantScroll(CallbackInfoReturnable<Long> cir) {
        if (!SafeMode.bypass()) {
            cir.setReturnValue(0L);
        }
    }
}
