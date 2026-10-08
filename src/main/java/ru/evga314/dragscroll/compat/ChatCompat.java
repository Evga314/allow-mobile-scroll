package ru.evga314.dragscroll.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import ru.evga314.dragscroll.touch.ScreenKind;

/**
 * In-game chat. It has no AbstractScrollArea: history scrolls only through
 * ChatScreen.mouseScrolled, which ignores the pointer position. The finger
 * drag is therefore limited to the chat box here.
 */
public final class ChatCompat {
    private ChatCompat() {
    }

    /**
     * Content pixels of one mouseScrolled unit: ChatScreen scrolls about 7
     * lines of ~9 px per unit, so this keeps the history under the finger.
     */
    public static final double PIXELS_PER_SCROLL_UNIT = 63.0;

    /** Chat lines one mouseScrolled unit scrolls (ChatScreen: amount * 7, cast to int). */
    private static final double LINES_PER_UNIT = 7.0;

    /** Fraction of a line not sent yet. */
    private static double lineCarry;

    /**
     * Units to send now. ChatScreen.mouseScrolled scrolls (int) (amount * 7)
     * lines, so the small per-frame steps of a finger were cut to 0; only
     * whole lines are sent and the rest is carried to the next step.
     */
    public static double consumeScrollUnits(double units) {
        lineCarry += units * LINES_PER_UNIT;
        int lines = (int) lineCarry;
        if (lines == 0) {
            return 0.0;
        }
        lineCarry -= lines;
        return lines / LINES_PER_UNIT;
    }

    /** Slack around the chat box so a finger on its edge still counts. */
    private static final double MARGIN = 8.0;

    /**
     * True when the touch starts on a chat screen but away from the history
     * box. The box is computed like ChatComponent lays it out: bottom left,
     * 40 px above the screen bottom, focused page height, times the chat scale.
     */
    public static boolean isOutsideChatArea(Screen screen, double x, double y) {
        if (!ScreenKind.of(screen).chat || Double.isNaN(x) || Double.isNaN(y)) {
            return false;
        }
        try {
            Options options = Minecraft.getInstance().options;
            double scale = options.chatScale().get();
            int width = ChatComponent.getWidth(options.chatWidth().get());
            int lineHeight = Math.max(1, (int) (9.0 * (options.chatLineSpacing().get() + 1.0)));
            int perPage = ChatComponent.getHeight(options.chatHeightFocused().get()) / lineHeight;
            double right = width + 12.0 * scale + MARGIN;
            double bottom = screen.height - 40.0 + MARGIN;
            double top = screen.height - 40.0 - perPage * lineHeight * scale - MARGIN;
            return x > right || y < top || y > bottom;
        } catch (Throwable t) {
            return false;
        }
    }
}
