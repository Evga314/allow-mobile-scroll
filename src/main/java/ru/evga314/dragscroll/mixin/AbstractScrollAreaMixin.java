package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.evga314.dragscroll.DragScrollState;
import ru.evga314.dragscroll.compat.SafeMode;
import ru.evga314.dragscroll.access.NativeScrollbarAccess;

/**
 * Restores content-area LMB + drag scrolling for every AbstractScrollArea
 * (vanilla lists, options, creative inventory, and anything that extends it).
 * Does not interfere with native scrollbar dragging (the scrolling flag).
 *
 * Minecraft 26.3 uses SDL: left mouse button may be reported as 0 or 1.
 */
@Mixin(AbstractScrollArea.class)
public abstract class AbstractScrollAreaMixin implements NativeScrollbarAccess {

	@Shadow
	private boolean scrolling;

	@Shadow
	public abstract double scrollAmount();

	@Shadow
	protected abstract boolean isOverScrollbar(double x, double y);

	@Shadow
	public abstract void setScrollAmount(double amount);

	@Shadow
	protected abstract double scrollRate();

	@Override
	public boolean dragscroll$isOverScrollbar(double x, double y) {
		try {
			return this.isOverScrollbar(x, y);
		} catch (Throwable ignored) {
			return false;
		}
	}

	@Override
	public double dragscroll$scrollRate() {
		try {
			return this.scrollRate();
		} catch (Throwable ignored) {
			return 10.0;
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void dragscroll$onMouseDragged(MouseButtonEvent event, double dx, double dy,
			CallbackInfoReturnable<Boolean> cir) {
		if (SafeMode.bypass()) {
			return;
		}
        if (DragScrollState.debugOn()) DragScrollState.debugEvent("AbstractScrollArea.mouseDragged",
                "class=" + ((Object) this).getClass().getName()
                        + " button=" + (event == null ? "null" : dragscroll$button(event))
                        + " dx=" + dx + " dy=" + dy
                        + " scrolling=" + this.scrolling
                        + " active=" + DragScrollState.active
                        + " locked=" + (DragScrollState.lockedArea == (Object) this)
                        + " nativeSlider=" + DragScrollState.nativeSliderHeld
                        + " nativeControl=" + DragScrollState.nativeControlHeld);

		// Leave native scrollbar behaviour alone
		if (this.scrolling) {
			return;
		}

		// A deferred slider that has been engaged (or any native control) owns
		// the entire touch. Do not let the parent OptionsList / scroll area
		// scroll or fight the slider value while the finger is still down.
		if (DragScrollState.nativeControlHeld || DragScrollState.nativeSliderHeld) {
			cir.setReturnValue(true);
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

		// If the MouseHandlerMixin gesture owns ANY list on this screen,
		// only that list may move. Vanilla still delivers mouseDragged to
		// every AbstractScrollArea under the cursor (Mod Menu list +
		// description). Applying setScrollAmount here on the *other* list
		// is what scrolled both columns at once.
		if (DragScrollState.active && DragScrollState.lockedArea != null) {
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
