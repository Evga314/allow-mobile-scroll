package ru.evga314.dragscroll.touch;

import ru.evga314.dragscroll.DragScrollClient;
import ru.evga314.dragscroll.config.DragScrollConfig;

/**
 * Debug log, switched in the mod's settings. Every call site guards the
 * message with {@link #on()} so nothing is concatenated when it is off.
 */
public final class Debug {
    private Debug() {
    }

    private static long sequence;

    public static boolean on() {
        return DragScrollConfig.isDebugEnabled();
    }

    public static void log(String source, String message) {
        if (!on()) {
            return;
        }
        DragScrollClient.LOGGER.info("[DragScroll DEBUG #{}] {} {}", ++sequence, source, message);
    }
}
