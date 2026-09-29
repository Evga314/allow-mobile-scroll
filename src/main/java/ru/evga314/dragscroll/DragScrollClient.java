package ru.evga314.dragscroll;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.evga314.dragscroll.compat.MalilibCompat;
import ru.evga314.dragscroll.compat.SodiumCompat;
import ru.evga314.dragscroll.compat.TrenderCompat;
import ru.evga314.dragscroll.compat.XaeroMapZoomOverlay;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.DeferredClick;
import ru.evga314.dragscroll.touch.Inertia;
import ru.evga314.dragscroll.touch.ScrollMemory;
import ru.evga314.dragscroll.touch.TouchState;
import ru.evga314.dragscroll.wheel.MouseWheelEmulator;

/**
 * Client entry point.
 *
 * <p>Package layout: {@code touch} holds the touch handling (fed by
 * MouseHandlerMixin), {@code compat} the support for other mods' UIs,
 * {@code wheel} the on-screen mouse wheel, {@code gui} the settings screens,
 * {@code config} the settings file and {@code loader} what runs before
 * Minecraft starts (safe mode, mixin plugins).
 */
public final class DragScrollClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("dragscroll");

    private static final SystemToast.SystemToastId SAFE_MODE_TOAST = new SystemToast.SystemToastId(10_000L);

    private static Screen lastScreen;
    private static boolean safeModeNoticeShown;

    @Override
    public void onInitializeClient() {
        DragScrollConfig.load();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> showSafeModeNotice(client));
        if (SafeMode.isStartupSafe()) {
            // No mixin was applied; nothing is registered that could change the
            // game. The settings screen still works, to switch the mod back on.
            LOGGER.warn("Allow mobile scroll is in safe mode, the game uses vanilla input: {}", SafeMode.reason());
            return;
        }
        SafeMode.setRuntimeDisableHook(DragScrollClient::onRuntimeFallback);
        LOGGER.info("Allow mobile scroll initialized. Drag threshold: {} px", DragScrollConfig.getDragThreshold());
        ClientTickEvents.END_CLIENT_TICK.register(DragScrollClient::onClientTick);
        XaeroMapZoomOverlay.register();
        MouseWheelEmulator.init();
    }

    /** A new screen: nothing of the previous screen's gesture may carry over. */
    private static void onClientTick(Minecraft client) {
        Screen screen = client.gui != null ? client.gui.screen() : null;
        if (screen != lastScreen) {
            lastScreen = screen;
            Inertia.stop();
            MalilibCompat.reset();
            MouseWheelEmulator.onRelease();
        }
    }

    /** The hooks kept failing: drop this session's gesture and tell the user. */
    private static void onRuntimeFallback() {
        TouchState.clear();
        Inertia.stop();
        DeferredClick.cancel();
        ScrollMemory.clearPressSnapshots();
        MalilibCompat.reset();
        TrenderCompat.reset();
        SodiumCompat.reset();
        safeModeNoticeShown = false;
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> showSafeModeNotice(client));
        }
    }

    /**
     * One toast per fallback, on the first screen after it started; none when
     * the user switched the mod off on purpose.
     */
    private static void showSafeModeNotice(Minecraft client) {
        if (safeModeNoticeShown || !SafeMode.isFallbackActive() || !SafeMode.isAutomatic()
                || client == null || client.gui == null) {
            return;
        }
        try {
            safeModeNoticeShown = true;
            SystemToast.addOrUpdate(client.gui.toastManager(), SAFE_MODE_TOAST,
                    Component.translatable("dragscroll.safemode.toast.title"),
                    Component.translatable("dragscroll.safemode.toast.body"));
        } catch (Throwable ignored) {
        }
    }
}
