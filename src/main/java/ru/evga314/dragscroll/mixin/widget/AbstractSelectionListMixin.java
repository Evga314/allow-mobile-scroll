package ru.evga314.dragscroll.mixin.widget;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Once a touch is a drag, a selection list must not scroll the selected row
 * back into view and fight the finger. The click itself is not blocked: the
 * position from before the click is restored only if the finger moves.
 */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {

    @Inject(method = {"ensureVisible", "centerScrollOn", "scrollTo", "scrollToEntry"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$blockAutoScroll(CallbackInfo ci) {
        if (!SafeMode.bypass() && TouchState.active && TouchState.currentTouchDragged) {
            ci.cancel();
        }
    }
}
