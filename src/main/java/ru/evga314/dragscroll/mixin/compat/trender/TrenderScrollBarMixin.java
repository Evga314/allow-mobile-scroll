package ru.evga314.dragscroll.mixin.compat.trender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * TRender / LibGui WScrollBar.
 *
 * <p>The bar that receives the press is recorded: tab panels keep every
 * list's bar in memory, and walking the tree often found the first tab's
 * bar instead of the visible one. And when the touch already drags the list,
 * the thin bar must not steal it as the finger drifts over it.
 *
 * <p>Targets both the TRender fork and the original LibGui (Cotton).
 */
@Mixin(targets = {
        "dev.tr7zw.trender.gui.widget.WScrollBar",
        "io.github.cottonmc.cotton.gui.widget.WScrollBar"
}, remap = false)
public abstract class TrenderScrollBarMixin {

    /** The touch already scrolls the list, not the bar. */
    @Unique
    private static boolean dragscroll$listOwnsTouch() {
        return (TouchState.active || TouchState.currentTouchDragged) && !TrenderCompat.barLocked;
    }

    /** InputResult.PROCESSED of whichever library is present, or null. */
    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object dragscroll$processed() {
        for (String name : new String[] {
                "dev.tr7zw.trender.gui.widget.data.InputResult",
                "io.github.cottonmc.cotton.gui.widget.data.InputResult"}) {
            try {
                return Enum.valueOf((Class) Class.forName(name).asSubclass(Enum.class), "PROCESSED");
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static void dragscroll$consume(CallbackInfoReturnable<?> cir) {
        Object processed = dragscroll$processed();
        if (processed != null) {
            ((CallbackInfoReturnable<Object>) cir).setReturnValue(processed);
        } else {
            cir.cancel();
        }
    }

    @Inject(method = "onMouseDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$captureOrBlockBar(int x, int y, int button, CallbackInfoReturnable<?> cir) {
        if (SafeMode.bypass()) {
            return;
        }
        if (dragscroll$listOwnsTouch()) {
            if (Debug.on()) Debug.log("WScrollBar.onMouseDown", "BLOCK_DURING_LIST_DRAG");
            dragscroll$consume(cir);
            return;
        }
        TrenderCompat.grabbedBar = this;
        if (Debug.on()) Debug.log("WScrollBar.onMouseDown", "CAPTURE " + getClass().getName());
    }

    @Inject(method = "onMouseDrag", at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$blockBarDragDuringList(int x, int y, int button, double deltaX, double deltaY,
                                                   CallbackInfoReturnable<?> cir) {
        if (!SafeMode.bypass() && dragscroll$listOwnsTouch()) {
            if (Debug.on()) Debug.log("WScrollBar.onMouseDrag", "BLOCK_DURING_LIST_DRAG");
            dragscroll$consume(cir);
        }
    }
}
