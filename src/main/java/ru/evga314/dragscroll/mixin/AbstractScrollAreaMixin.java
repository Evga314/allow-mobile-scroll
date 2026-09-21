package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;

/**
 * Restores content-area LMB + drag scrolling for every AbstractScrollArea
 * (vanilla lists, options, creative inventory, and anything that extends it).
 * Does not interfere with native scrollbar dragging (the scrolling flag).
 *
 * Minecraft 26.3 uses SDL: left mouse button may be reported as 0 or 1.
 */
@Mixin(AbstractScrollArea.class)
public abstract class AbstractScrollAreaMixin {

	@Shadow
	private boolean scrolling;

	@Shadow
	public abstract double scrollAmount();

	@Shadow
	public abstract void setScrollAmount(double amount);

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void dragscroll$onMouseDragged(MouseButtonEvent event, double dx, double dy,
			CallbackInfoReturnable<Boolean> cir) {
        DragScrollState.debugEvent("AbstractScrollArea.mouseDragged",
                "class=" + ((Object) this).getClass().getName()
                        + " button=" + (event == null ? "null" : dragscroll$button(event))
                        + " dx=" + dx + " dy=" + dy
                        + " scrolling=" + this.scrolling
                        + " active=" + DragScrollState.active
                        + " locked=" + (DragScrollState.lockedArea == (Object) this));

		// Leave native scrollbar behaviour alone
		if (this.scrolling) {
			return;
		}

		int button = dragscroll$button(event);
		// SDL left = 1, GLFW-style left = 0; accept both
		if (button != 0 && button != 1) {
			return;
		}

		// Prefer vertical movement; ignore mostly-horizontal drags
		if (Math.abs(dy) < 0.5) {
			return;
		}
		if (Math.abs(dy) < Math.abs(dx) * 0.3) {
			return;
		}

		// If the MouseHandlerMixin gesture owns this area, swallow the vanilla
		// drag without scrolling. Otherwise the big "cursor jump" that comes with
		// the start of every new touch would be applied as a huge scroll.
		if (DragScrollState.active && DragScrollState.lockedArea == (Object) this) {
			cir.setReturnValue(true);
			return;
		}

		setScrollAmount(scrollAmount() - dy);
		cir.setReturnValue(true);
	}

	private static int dragscroll$button(MouseButtonEvent event) {
		try {
			return event.button();
		} catch (Throwable ignored) {
		}
		try {
			return event.buttonInfo().button();
		} catch (Throwable ignored) {
		}
		return -1;
	}
}
