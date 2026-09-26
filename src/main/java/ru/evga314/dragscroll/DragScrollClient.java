package ru.evga314.dragscroll;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.evga314.dragscroll.compat.SafeMode;
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

	public static final double SCROLL_PIXELS_PER_NOTCH = DragScrollState.DEFAULT_PIXELS_PER_NOTCH;

	/** Continuous decay rate (1/s): e^(-DECAY/20) == INERTIA_FRICTION per tick. */
	private static final double INERTIA_DECAY_PER_SEC =
			-20.0 * Math.log(DragScrollState.INERTIA_FRICTION);

	private static Screen lastScreen = null;
	private static long lastInertiaNs = 0L;
	private static long lastApplyNs = 0L;
	private static boolean safeModeNoticeShown = false;
	private static final SystemToast.SystemToastId SAFE_MODE_TOAST = new SystemToast.SystemToastId(10_000L);

	@Override
	public void onInitializeClient() {
		DragScrollConfig.load();
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> showSafeModeNotice(client));
		if (SafeMode.isStartupSafe()) {
			// No mixin was applied; register nothing that could change the game.
			// The config screen (Mod Menu) still works, to switch the mod back on.
			LOGGER.warn("Allow mobile scroll is in safe mode, the game uses vanilla input: {}", SafeMode.reason());
			return;
		}
		SafeMode.setRuntimeDisableHook(DragScrollClient::onRuntimeFallback);
		LOGGER.info("Allow mobile scroll initialized. Drag threshold: {} px",
				DragScrollConfig.getDragThreshold());
		ClientTickEvents.END_CLIENT_TICK.register(DragScrollClient::onClientTick);
		ru.evga314.dragscroll.XaeroMapZoomOverlay.register();
		ru.evga314.dragscroll.MouseWheelEmulator.init();
	}

	/** Hooks kept failing: drop every gesture of this session and tell the user. */
	private static void onRuntimeFallback() {
		DragScrollState.clear();
		MalilibCompat.reset();
		lastInertiaNs = 0L;
		safeModeNoticeShown = false;
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.execute(() -> showSafeModeNotice(client));
		}
	}

	/**
	 * One toast per fallback, on the first screen after it started. Not shown
	 * when the user switched the mod off on purpose.
	 */
	private static void showSafeModeNotice(Minecraft client) {
		if (safeModeNoticeShown || !SafeMode.isFallbackActive() || !SafeMode.isAutomatic()) {
			return;
		}
		try {
			if (client == null || client.gui == null) {
				return;
			}
			safeModeNoticeShown = true;
			SystemToast.addOrUpdate(client.gui.toastManager(), SAFE_MODE_TOAST,
					Component.translatable("dragscroll.safemode.toast.title"),
					Component.translatable("dragscroll.safemode.toast.body"));
		} catch (Throwable ignored) {
		}
	}

	private static void onClientTick(Minecraft client) {
		Screen screen = client.gui != null ? client.gui.screen() : null;
		if (screen != lastScreen) {
			lastScreen = screen;
			DragScrollState.stopInertia();
			MalilibCompat.reset();
			ru.evga314.dragscroll.MouseWheelEmulator.onRelease();
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
			} else if (MalilibCompat.hasCoast(screen)) {
				if (!MalilibCompat.applyCoast(screen, frameDy)) {
					stopAll();
					return;
				}
			} else if (DragScrollState.fallbackScrollScreen == screen) {
				double units = frameDy / DragScrollState.pixelsPerNotch(screen);
				double scrollDelta = DragScrollState.fallbackScrollInverted ? units : -units;
				String sn = DragScrollState.lowerName(screen);
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
