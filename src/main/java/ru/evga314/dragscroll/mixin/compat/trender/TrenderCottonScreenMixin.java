package ru.evga314.dragscroll.mixin.compat.trender;

import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * TRender's CottonClientScreen: a launcher RELEASE in the middle of a hold
 * clears lastResponder and WScrollBar.sliding, so the thumb lights up but
 * never moves. That release is swallowed while the bar is locked and the
 * finger is still down. After the real lift the touch handler has already
 * cleared leftButtonHeld, and Cotton may drop lastResponder as usual.
 */
@Mixin(targets = "dev.tr7zw.trender.gui.client.CottonClientScreen", remap = true)
public abstract class TrenderCottonScreenMixin {

    @Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$keepBarGrab(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (SafeMode.bypass() || !TrenderCompat.barLocked || !TouchState.leftButtonHeld) {
            return;
        }
        if (Debug.on()) Debug.log("CottonClientScreen.mouseReleased", "TRENDER_BAR_RELEASE_SWALLOW");
        cir.setReturnValue(true);
    }
}
