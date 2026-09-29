package ru.evga314.dragscroll.mixin.widget;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.access.ScrollAreaAccess;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.TouchState;

/**
 * Every AbstractScrollArea (vanilla lists, option lists and everything built
 * on them): an LMB drag on the content scrolls it, and the protected members
 * the touch handler needs are exposed through {@link ScrollAreaAccess}. The
 * native scroll bar drag is left alone.
 */
@Mixin(AbstractScrollArea.class)
public abstract class AbstractScrollAreaMixin implements ScrollAreaAccess {

    @Shadow
    private boolean scrolling;

    @Shadow
    public abstract double scrollAmount();

    @Shadow
    public abstract void setScrollAmount(double amount);

    @Shadow
    protected abstract boolean isOverScrollbar(double x, double y);

    @Shadow
    protected abstract double scrollRate();

    @Shadow
    protected abstract int scrollerHeight();

    @Override
    public boolean dragscroll$isOverScrollbar(double x, double y) {
        return this.isOverScrollbar(x, y);
    }

    @Override
    public double dragscroll$scrollRate() {
        return this.scrollRate();
    }

    @Override
    public boolean dragscroll$isScrolling() {
        return this.scrolling;
    }

    @Override
    public int dragscroll$scrollerHeight() {
        return this.scrollerHeight();
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void dragscroll$contentDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (SafeMode.bypass()) {
            return;
        }
        if (Debug.on()) Debug.log("AbstractScrollArea.mouseDragged", "class=" + getClass().getName()
                + " dx=" + dx + " dy=" + dy + " scrolling=" + this.scrolling + " active=" + TouchState.active
                + " nativeControl=" + TouchState.nativeControlHeld);
        if (this.scrolling) {
            return;
        }
        // A native control (an engaged slider in a row) owns the touch: the
        // list must not scroll under it.
        if (TouchState.nativeControlHeld || TouchState.nativeSliderHeld) {
            cir.setReturnValue(true);
            return;
        }
        if (event == null || !TouchState.isLeftButton(event.button())) {
            return;
        }
        // Mostly vertical movement only.
        if (Math.abs(dy) < 0.5 || Math.abs(dy) < Math.abs(dx) * 0.3) {
            return;
        }
        // Vanilla sends mouseDragged to every list under the cursor (the list
        // and the description of Mod Menu). While the touch handler drags one
        // list, the others must not move.
        if (TouchState.active && TouchState.lockedArea != null) {
            cir.setReturnValue(true);
            return;
        }
        this.setScrollAmount(this.scrollAmount() - dy);
        cir.setReturnValue(true);
    }
}
