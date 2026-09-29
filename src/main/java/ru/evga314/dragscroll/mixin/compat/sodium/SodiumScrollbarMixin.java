package ru.evga314.dragscroll.mixin.compat.sodium;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Sodium's ScrollbarWidget. While the mod drives a held bar, Sodium's own
 * click and drag on it are swallowed (they would fight the finger), and the
 * bar gets the wider hit box of {@link SodiumCompat#isOverWideBar}.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget", remap = false)
public abstract class SodiumScrollbarMixin {

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$blockNativeDrag(CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && TouchState.nativeScrollbarHeld) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$blockNativeClick(CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && TouchState.nativeScrollbarHeld) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMouseOver", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$widerHitBox(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass()) {
            cir.setReturnValue(SodiumCompat.isOverWideBar((GuiEventListener) this, mouseX, mouseY));
        }
    }
}
