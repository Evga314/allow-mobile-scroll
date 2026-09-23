package ru.evga314.dragscroll.mixin;

import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;

/**
 * TRender / Cotton: a launcher RELEASE mid-hold clears lastResponder and
 * WScrollBar.sliding, so the thumb lights up but never moves. Swallow that
 * release while our bar lock is active. Applied only if TRender is present.
 */
@Mixin(targets = "dev.tr7zw.trender.gui.client.CottonClientScreen", remap = true)
public abstract class TrenderCottonScreenMixin {

    @Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$keepTrenderBar(MouseButtonEvent event,
                                           CallbackInfoReturnable<Boolean> cir) {
        // Swallow only a synthetic mid-hold RELEASE. After the real finger-up
        // our onButton HEAD already cleared leftButtonHeld — let Cotton drop
        // lastResponder so the next tab's bar can receive the next press.
        if (!DragScrollState.trenderBarLocked || !DragScrollState.leftButtonHeld) {
            return;
        }
        DragScrollState.debugEvent("CottonClientScreen.mouseReleased",
                "TRENDER_BAR_RELEASE_SWALLOW");
        cir.setReturnValue(true);
    }
}
