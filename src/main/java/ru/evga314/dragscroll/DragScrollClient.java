package ru.evga314.dragscroll;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry. Inertia is applied on every GUI render frame with real wall-clock
 * dt, so it automatically tracks the menu FPS rather than a fixed tick rate.
 *
 * During a coast the scroll position is owned exclusively by lockedScroll /
 * our own integrator. We never re-read the widget mid-coast — that was the
 * source of the stepwise tail (fight with SmoothScroll / list re-layout).
 */
public class DragScrollClient implements ClientModInitializer {
	public static final String MOD_ID = "dragscroll";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final double SCROLL_PIXELS_PER_NOTCH = 12.0;

	/** Continuous decay rate (1/s): e^(-DECAY/20) == INERTIA_FRICTION per tick. */
	private static final double INERTIA_DECAY_PER_SEC =
			-20.0 * Math.log(DragScrollState.INERTIA_FRICTION);

	private static Screen lastScreen = null;
	private static long lastInertiaNs = 0L;
	private static long lastApplyNs = 0L;

	@Override
	public void onInitializeClient() {
		DragScrollConfig.load();
		LOGGER.info("Allow mobile scroll initialized. Drag threshold: {} px",
				DragScrollConfig.getDragThreshold());
		ClientTickEvents.END_CLIENT_TICK.register(DragScrollClient::onClientTick);
		ru.evga314.dragscroll.XaeroMapZoomOverlay.register();
	}

	private static void onClientTick(Minecraft client) {
		Screen screen = client.gui != null ? client.gui.screen() : null;
		if (screen != lastScreen) {
			lastScreen = screen;
			DragScrollState.stopInertia();
			lastInertiaNs = 0L;
		}
	}

	public static void applyInertiaFrame(Screen renderedScreen) {
		if (!DragScrollState.inertiaActive) {
			lastInertiaNs = 0L;
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null) {
			stopAll();
			return;
		}
		Screen screen = client.gui.screen();
		if (screen == null || screen != renderedScreen || client.gui.overlay() != null) {
			stopAll();
			return;
		}
		// Coast continues while finger is down until a real drag starts.
		// Grabbing a native / custom scrollbar must freeze and kill inertia
		// immediately, even if the press was not classified as a content drag.
		if (DragScrollState.nativeScrollbarHeld || DragScrollState.nativeControlHeld) {
			DragScrollState.stopInertia();
			lastInertiaNs = 0L;
			return;
		}
		if (DragScrollState.currentTouchDragged || DragScrollState.leftButtonHeld) {
			lastInertiaNs = 0L;
			return;
		}

		long now = System.nanoTime();
		// Same-frame duplicate (render + mouse) — skip.
		if (lastApplyNs != 0L && (now - lastApplyNs) < 1_000_000L) {
			return;
		}
		lastApplyNs = now;

		if (lastInertiaNs == 0L) {
			lastInertiaNs = now;
			return;
		}
		double dt = (now - lastInertiaNs) / 1_000_000_000.0;
		lastInertiaNs = now;
		if (dt <= 0.0 || dt > 0.1) {
			return;
		}

		double vel = DragScrollState.scrollVelocity;
		if (vel > DragScrollState.INERTIA_MAX_VEL) {
			vel = DragScrollState.INERTIA_MAX_VEL;
		} else if (vel < -DragScrollState.INERTIA_MAX_VEL) {
			vel = -DragScrollState.INERTIA_MAX_VEL;
		}

		double decayed = vel * Math.exp(-INERTIA_DECAY_PER_SEC * dt);
		double avgVel = 0.5 * (vel + decayed);
		double frameDy = avgVel * 20.0 * dt;

		if (Math.abs(decayed) < DragScrollState.INERTIA_STOP
				&& Math.abs(frameDy) < DragScrollState.INERTIA_STOP_PIXEL) {
			stopAll();
			return;
		}

		try {
			if (DragScrollState.lockedArea != null) {
				AbstractScrollArea area = DragScrollState.lockedArea;
				double next = DragScrollState.lockedScroll - frameDy;
				area.setScrollAmount(next);
				DragScrollState.lockedScroll = next;
				DragScrollState.baseScroll = next;
				DragScrollState.remember(area, next);
			} else if (DragScrollState.fallbackScrollScreen == screen) {
				double units = frameDy / SCROLL_PIXELS_PER_NOTCH;
				double scrollDelta = DragScrollState.fallbackScrollInverted ? units : -units;
				String sn = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
				boolean trender = sn.contains("trender") || sn.contains("cottonclientscreen")
						|| sn.contains("entityculling") || sn.contains("libgui");
				if (trender) {
					scrollDelta = DragScrollState.consumeTrenderScrollDelta(scrollDelta);
				}
				if (scrollDelta != 0.0) {
					try {
						screen.mouseScrolled(
								DragScrollState.inertiaGuiX,
								DragScrollState.inertiaGuiY,
								0.0,
								scrollDelta);
					} catch (Throwable ignored) {
					}
				}
			} else {
				stopAll();
				return;
			}
		} catch (Throwable t) {
			stopAll();
			return;
		}

		DragScrollState.scrollVelocity = decayed;
		if (Math.abs(DragScrollState.scrollVelocity) < DragScrollState.INERTIA_STOP) {
			stopAll();
		}
	}

	private static void stopAll() {
		DragScrollState.stopInertia();
		lastInertiaNs = 0L;
	}

	public static void markInertiaStarted() {
		lastInertiaNs = 0L;
		lastApplyNs = 0L;
	}
}
