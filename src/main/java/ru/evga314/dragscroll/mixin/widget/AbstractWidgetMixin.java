package ru.evga314.dragscroll.mixin.widget;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.DeferredClick;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Widgets: no click while the screen click of the touch is held back (a
 * screen may call a widget directly), and no widget drag while the touch
 * scrolls a list.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void dragscroll$deferClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (SafeMode.bypass() || event == null || !TouchState.isLeftButton(event.button())
                || DeferredClick.isReplaying()) {
            return;
        }
        if (DeferredClick.isPending()) {
            cir.setReturnValue(true);
            if (Debug.on()) Debug.log("AbstractWidget.mouseClicked", "HELD_BACK class=" + getClass().getName());
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void dragscroll$noDragWhileScrolling(MouseButtonEvent event, double dx, double dy,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!SafeMode.bypass() && TouchState.active && TouchState.currentTouchDragged && !TouchState.nativeControlHeld) {
            cir.setReturnValue(true);
        }
    }
}
