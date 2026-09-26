package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.DragScrollState;
import ru.evga314.dragscroll.compat.SafeMode;

/**
 * Prevents a button action from escaping through an implementation-specific
 * onClick override while a touch is waiting to be classified as a tap or drag.
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {

    @Inject(method = "onClick", at = @At("HEAD"), cancellable = true)
    private void dragscroll$deferButtonOnClick(MouseButtonEvent event, boolean doubleClick,
                                                 CallbackInfo ci) {
        if (SafeMode.bypass()) {
            return;
        }
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("AbstractButton.onClick",
                "class=" + ((Object) this).getClass().getName()
                        + " button=" + (event == null ? "null" : event.button())
                        + " doubleClick=" + doubleClick
                        + " replaying=" + DragScrollState.isReplaying()
                        + " pending=" + DragScrollState.isPendingScreenClick());

        if (event == null || !DragScrollState.isLeftButton(event.button()) || DragScrollState.isReplaying()) {
            return;
        }

        if (DragScrollState.isPendingScreenClick()) {
            ci.cancel();
            if (DragScrollState.debugOn()) DragScrollState.debugEvent("AbstractButton.onClick", "CANCELLED_BY_DRAGSCROLL");
        }
    }
}
