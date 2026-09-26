package ru.evga314.dragscroll.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;

/**
 * Capture the exact WScrollBar instance that received the press.
 * Tab panels keep every list's bar in memory; walking the tree often
 * picks the first tab's bar (max=8) instead of the visible one.
 *
 * <p>Also: once the user is already list-dragging this touch, refuse to
 * let the bar steal the gesture when the finger drifts over the thin
 * scrollbar track (EntityCulling / TRender).
 *
 * <p>Targets both the TRender fork and the original LibGui/Cotton widget.
 * Applied only when either class is present ({@code required: false}).
 */
@Mixin(targets = {
        "dev.tr7zw.trender.gui.widget.WScrollBar",
        "io.github.cottonmc.cotton.gui.widget.WScrollBar"
}, remap = false)
public abstract class TrenderScrollBarMixin {

    /** True when this physical touch already owns list scrolling, not the bar. */
    private static boolean listDragOwnsTouch() {
        return (DragScrollState.active || DragScrollState.currentTouchDragged)
                && !DragScrollState.trenderBarLocked;
    }

    private static Object inputResult(String name) {
        for (String pkg : new String[]{
                "dev.tr7zw.trender.gui.widget.data.InputResult",
                "io.github.cottonmc.cotton.gui.widget.data.InputResult"
        }) {
            try {
                Class<?> c = Class.forName(pkg);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object v = Enum.valueOf((Class) c.asSubclass(Enum.class), name);
                return v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Inject(method = "onMouseDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$captureOrBlockBar(int x, int y, int button, CallbackInfoReturnable<?> cir) {
        if (listDragOwnsTouch()) {
            DragScrollState.debugEvent("WScrollBar.onMouseDown",
                    "BLOCK_DURING_LIST_DRAG active=" + DragScrollState.active
                            + " dragged=" + DragScrollState.currentTouchDragged);
            Object processed = inputResult("PROCESSED");
            if (processed != null) {
                @SuppressWarnings("unchecked")
                CallbackInfoReturnable<Object> raw = (CallbackInfoReturnable<Object>) cir;
                raw.setReturnValue(processed);
            } else {
                cir.cancel();
            }
            return;
        }
        DragScrollState.trenderGrabbedBar = this;
        DragScrollState.debugEvent("WScrollBar.onMouseDown",
                "CAPTURE " + this.getClass().getName());
    }

    @Inject(method = "onMouseDrag", at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$blockBarDragDuringList(int x, int y, int button,
                                                   double deltaX, double deltaY,
                                                   CallbackInfoReturnable<?> cir) {
        if (!listDragOwnsTouch()) {
            return;
        }
        DragScrollState.debugEvent("WScrollBar.onMouseDrag", "BLOCK_DURING_LIST_DRAG");
        Object processed = inputResult("PROCESSED");
        if (processed != null) {
            @SuppressWarnings("unchecked")
            CallbackInfoReturnable<Object> raw = (CallbackInfoReturnable<Object>) cir;
            raw.setReturnValue(processed);
        } else {
            cir.cancel();
        }
    }
}
