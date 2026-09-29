package ru.evga314.dragscroll.mixin.widget;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.DeferredClick;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Buttons: no action while the screen click of the touch is held back, even
 * through an onClick override that bypasses AbstractWidget.mouseClicked.
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void dragscroll$deferOnClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
        if (SafeMode.bypass() || event == null || !TouchState.isLeftButton(event.button())
                || DeferredClick.isReplaying()) {
            return;
        }
        if (DeferredClick.isPending()) {
            ci.cancel();
            if (Debug.on()) Debug.log("AbstractButton.onClick", "HELD_BACK class=" + getClass().getName());
        }
    }
}
