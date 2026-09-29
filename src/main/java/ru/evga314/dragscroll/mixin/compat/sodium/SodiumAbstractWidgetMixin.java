package ru.evga314.dragscroll.mixin.compat.sodium;

import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Sodium's AbstractWidget: scroll bar widgets of the option list get a hit
 * box that is wider than the thin drawn bar, mostly to the right, so a finger
 * can grab them. Sodium widgets report their box through the vanilla
 * GuiEventListener.getRectangle().
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget", remap = false)
public abstract class SodiumAbstractWidgetMixin {

    @Inject(method = "isMouseOver", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dragscroll$widerScrollbar(double mouseX, double mouseY, CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && getClass().getName().contains("Scrollbar")) {
            cir.setReturnValue(SodiumCompat.isOverWideBar((GuiEventListener) this, mouseX, mouseY));
        }
    }
}
