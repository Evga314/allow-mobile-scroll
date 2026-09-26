package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.DragScrollState;
import ru.evga314.dragscroll.compat.SafeMode;

/**
 * Once a touch has actually become a drag, stop selection-list helpers from
 * fighting the drag by moving the selected entry into view.
 *
 * These methods are intentionally NOT blocked during the initial click. This
 * keeps ordinary button/list clicks unchanged; the pre-click scroll snapshot
 * is restored only if the user subsequently moves the finger.
 */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {

    @Inject(method = "ensureVisible", at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$blockEnsureVisible(CallbackInfo ci) {
        if (!SafeMode.bypass() && DragScrollState.active && DragScrollState.currentTouchDragged) {
            ci.cancel();
        }
    }

    @Inject(method = {"centerScrollOn", "scrollTo", "scrollToEntry"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$blockAutoScroll(CallbackInfo ci) {
        if (!SafeMode.bypass() && DragScrollState.active && DragScrollState.currentTouchDragged) {
            ci.cancel();
        }
    }
}
