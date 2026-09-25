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
 * <p>Targets both the TRender fork (EntityCulling, Skin Layers 3D, …) and the
 * original LibGui/Cotton widget in one mixin. Applied only when either class is
 * present — the config is {@code required: false}, so a missing target class is
 * skipped rather than crashing.
 */
@Mixin(targets = {
        "dev.tr7zw.trender.gui.widget.WScrollBar",
        "io.github.cottonmc.cotton.gui.widget.WScrollBar"
}, remap = false)
public abstract class TrenderScrollBarMixin {

    @Inject(method = "onMouseDown", at = @At("HEAD"), require = 0)
    private void dragscroll$captureBar(int x, int y, int button, CallbackInfoReturnable<?> cir) {
        DragScrollState.trenderGrabbedBar = this;
        DragScrollState.debugEvent("WScrollBar.onMouseDown",
                "CAPTURE " + this.getClass().getName());
    }
}
