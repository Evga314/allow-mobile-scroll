package ru.evga314.dragscroll.mixin.compat.jade;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.access.ScrollAreaAccess;
import ru.evga314.dragscroll.compat.Reflect;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.Debug;

/**
 * Jade config lists (SmoothScrollableList). Their mouseDragged first snaps
 * the list back to Jade's smooth-scroll target and only then runs the vanilla
 * thumb drag. That target is only updated by Jade's own content drags, and
 * never when a smooth-scroll mod is installed, so every thumb grab jumped the
 * list back to the top. While the native scroll bar is held, the list is moved
 * here from its real offset and Jade's smoothing state is synced with
 * forceSetScrollAmount; Jade's own body is skipped.
 *
 * <p>Jade 26.3.1 keeps the class in gui.config; later builds moved it to gui.
 */
@Mixin(targets = {
        "snownee.jade.gui.config.SmoothScrollableList",
        "snownee.jade.gui.SmoothScrollableList"
}, remap = true)
public abstract class JadeSmoothScrollableListMixin {

    /** A gap longer than this between drag events means a new thumb grab. */
    @Unique
    private static final long DRAGSCROLL_GRAB_GAP_NS = 150_000_000L;

    @Unique
    private double dragscroll$thumbScroll = Double.NaN;

    @Unique
    private long dragscroll$lastThumbDragNs = 0L;

    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void dragscroll$thumbDrag(MouseButtonEvent event, double dx, double dy,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (SafeMode.bypass() || event == null) {
            return;
        }
        Object self = this;
        if (!(self instanceof AbstractScrollArea area) || !(self instanceof ScrollAreaAccess access)) {
            return;
        }
        if (!access.dragscroll$isScrolling()) {
            this.dragscroll$thumbScroll = Double.NaN;
            return;
        }
        try {
            long now = System.nanoTime();
            if (Double.isNaN(this.dragscroll$thumbScroll)
                    || now - this.dragscroll$lastThumbDragNs > DRAGSCROLL_GRAB_GAP_NS) {
                // New grab: start from where the list actually is, not from
                // Jade's stale smooth target.
                this.dragscroll$thumbScroll = area.scrollAmount();
            }
            this.dragscroll$lastThumbDragNs = now;

            double max = area.maxScrollAmount();
            double next;
            if (event.y() < area.getY()) {
                next = 0.0;
            } else if (event.y() > area.getBottom()) {
                next = max;
            } else {
                // Same thumb-to-content ratio as AbstractScrollArea.mouseDragged.
                double track = area.getHeight() - access.dragscroll$scrollerHeight();
                double scale = Math.max(1.0, Math.max(1.0, max) / Math.max(1.0, track));
                next = this.dragscroll$thumbScroll + dy * scale;
            }
            next = Math.max(0.0, Math.min(max, next));
            this.dragscroll$thumbScroll = next;

            java.lang.reflect.Method force = Reflect.publicMethod(self.getClass(), "forceSetScrollAmount", double.class);
            try {
                force.invoke(self, next);
            } catch (Throwable ignored) {
                area.setScrollAmount(next);
            }
            if (Debug.on()) Debug.log("JadeList.mouseDragged",
                    "THUMB dy=" + dy + " scroll=" + next + " max=" + max);
            cir.setReturnValue(true);
        } catch (Throwable t) {
            SafeMode.reportFailure("JadeList.mouseDragged", t);
        }
    }
}
