package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollClient;
import ru.evga314.dragscroll.DragScrollState;

/**
 * Diagnostic logging for the screen-level mouse click dispatch, plus
 * per-frame inertia application so coasting is as smooth as live drag.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void dragscroll$debugMouseClicked(MouseButtonEvent event, boolean doubleClick,
                                               CallbackInfoReturnable<Boolean> cir) {
        DragScrollState.debugEvent("Screen.mouseClicked",
                "class=" + ((Object) this).getClass().getName()
                        + " button=" + (event == null ? "null" : event.button())
                        + " x=" + (event == null ? "null" : event.x())
                        + " y=" + (event == null ? "null" : event.y())
                        + " doubleClick=" + doubleClick
                        + " replaying=" + DragScrollState.isReplaying()
                        + " pending=" + DragScrollState.isPendingScreenClick());
    }

    @Inject(method = "mouseClicked", at = @At("RETURN"))
    private void dragscroll$debugMouseClickedReturn(MouseButtonEvent event, boolean doubleClick,
                                                     CallbackInfoReturnable<Boolean> cir) {
        DragScrollState.debugEvent("Screen.mouseClicked",
                "RETURN result=" + cir.getReturnValue()
                        + " replaying=" + DragScrollState.isReplaying()
                        + " pending=" + DragScrollState.isPendingScreenClick());
    }

    /**
     * TRender WScrollBar drops {@code sliding} on mouseReleased. The mobile
     * launcher injects RELEASE in the middle of a finger-hold, which is why
     * the thumb highlights but never moves. Swallow those releases until our
     * lock is actually cleared.
     */
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void dragscroll$swallowTrenderBarRelease(MouseButtonEvent event,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!DragScrollState.trenderBarLocked) {
            return;
        }
        String name = ((Object) this).getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.contains("trender")
                || name.contains("cotton")
                || name.contains("libgui")
                || name.contains("entityculling"))) {
            return;
        }
        DragScrollState.debugEvent("Screen.mouseReleased", "TRENDER_BAR_RELEASE_SWALLOW");
        cir.setReturnValue(true);
    }

    /**
     * Apply residual scroll velocity once per rendered frame.
     * First argument is typed as Object so we do not depend on GuiGraphics /
     * DrawContext class names that differ across Minecraft mappings (26.3 has
     * no net.minecraft.client.gui.GuiGraphics).
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void dragscroll$applyInertiaOnRender(Object graphics, int mouseX, int mouseY,
                                                  float partialTick, CallbackInfo ci) {
        DragScrollClient.applyInertiaFrame((Screen) (Object) this);
    }
}
