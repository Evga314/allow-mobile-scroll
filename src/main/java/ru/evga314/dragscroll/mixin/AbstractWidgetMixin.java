package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;

/**
 * Delays widget click handling until the touch is known to be a tap.
 *
 * A mobile touch is delivered as an immediate LMB press. The following mouse
 * movement may later reveal that the same touch is actually a drag. Delaying
 * the widget dispatch prevents the initial press from activating a control
 * before the drag decision is made.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void dragscroll$deferWidgetClick(MouseButtonEvent event, boolean doubleClick,
                                               CallbackInfoReturnable<Boolean> cir) {
        DragScrollState.debugEvent("AbstractWidget.mouseClicked",
                "class=" + ((Object) this).getClass().getName()
                        + " button=" + (event == null ? "null" : event.button())
                        + " doubleClick=" + doubleClick
                        + " replaying=" + DragScrollState.isReplaying()
                        + " pending=" + DragScrollState.isPendingScreenClick());

        if (event == null || !DragScrollState.isLeftButton(event.button()) || DragScrollState.isReplaying()) {
            return;
        }

        if (DragScrollState.isPendingScreenClick()) {
            cir.setReturnValue(true);
            DragScrollState.debugEvent("AbstractWidget.mouseClicked", "CANCELLED_BY_DRAGSCROLL");
        }
    }
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void dragscroll$cancelWidgetDragDuringScroll(MouseButtonEvent event, double dx, double dy,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (DragScrollState.active
                && DragScrollState.currentTouchDragged
                && !DragScrollState.nativeControlHeld) {
            cir.setReturnValue(true);
        }
    }

}
