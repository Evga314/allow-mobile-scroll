package ru.evga314.dragscroll.wheel;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.CreativeModeTab;
import ru.evga314.dragscroll.DragScrollClient;
import ru.evga314.dragscroll.access.ScrollAreaAccess;
import ru.evga314.dragscroll.compat.FancyMenuCompat;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.gui.WheelSetupScreen;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.mixin.screen.CreativeModeInventoryScreenAccessor;
import ru.evga314.dragscroll.touch.Debug;
import ru.evga314.dragscroll.touch.TouchState;

import java.util.Locale;

/**
 * On-screen mouse wheel.
 *
 * <p>A small widget that looks like a mouse wheel seen from above. A vertical
 * swipe on it turns the wheel and sends real wheel notches to the list the
 * finger last scrolled (or the one under the cursor before it jumped onto the
 * wheel). A long press without a swipe, when enabled, drags the wheel to a new
 * place; the position is saved on release.
 *
 * <p>Shown and hidden with a key mapping (keypad divide by default).
 */
public final class MouseWheelEmulator {
    private MouseWheelEmulator() {
    }

    public static final String TOGGLE_KEY_ID = "key.dragscroll.wheel_emulator";

    /** GLFW key code of keypad divide (not printable, so it never types into a text field). */
    private static final int KEY_KP_DIVIDE = 331;

    /** Finger travel (GUI px) of one wheel notch. */
    private static final double PIXELS_PER_NOTCH = 16.0;
    /** Widget size at 100 %, GUI px. */
    private static final int BASE_WIDTH = 39;
    private static final int BASE_HEIGHT = 96;
    /** Default distance of the wheel centre from the right screen edge. */
    private static final int DEFAULT_RIGHT_MARGIN = 26;
    /** Finger travel (GUI px) that commits a touch to scrolling instead of a long press. */
    private static final double SWIPE_COMMIT_PX = 8.0;
    /** Hit box margin, so a fractional box still catches the integer pixel under the finger. */
    private static final double HIT_EXPAND = 0.75;
    /** A key press seen by {@link #onKeyPressEarly} is not toggled again by the screen hook within this. */
    private static final long EARLY_TOGGLE_DEDUPE_NS = 250_000_000L;

    private enum TouchMode {
        IDLE,
        /** The press landed on the wheel; swipe or long press not known yet. */
        PENDING,
        /** Vertical swipe: send wheel notches. */
        SCROLL,
        /** Long press: the wheel follows the finger. */
        REPOSITION
    }

    private static KeyMapping toggleKey;
    private static boolean visible;
    private static long lastEarlyToggleNs;

    private static TouchMode touchMode = TouchMode.IDLE;
    private static Screen grabScreen;
    private static long pressNs;
    private static double pressX;
    private static double pressY;
    private static double lastY;
    /** Finger travel not yet sent as a notch. */
    private static double accum;
    /** Rotation of the drawn wheel ridges. */
    private static double phase;
    /** Finger offset from the wheel centre while repositioning. */
    private static double grabOffsetX;
    private static double grabOffsetY;

    public static void init() {
        toggleKey = new KeyMapping(TOGGLE_KEY_ID, InputConstants.Type.KEYSYM, KEY_KP_DIVIDE, KeyMapping.Category.MISC);
        KeyBindingHelper.registerKeyBinding(toggleKey);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // The key only toggles the wheel on screens (handled by the screen
            // hooks); in game its clicks are drained so they do not pile up.
            while (toggleKey.consumeClick()) {
                // drained
            }
            // A finger that rests without moving sends no movement events;
            // the long press must still become a reposition.
            tryPromoteReposition();
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            try {
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> {
                    if (isToggleKey(keyEvent) && System.nanoTime() - lastEarlyToggleNs > EARLY_TOGGLE_DEDUPE_NS) {
                        toggle();
                    }
                });
                ScreenEvents.afterRender(screen).register(MouseWheelEmulator::render);
            } catch (Throwable t) {
                DragScrollClient.LOGGER.warn("Mouse-wheel emulator hook failed", t);
            }
        });
    }

    private static boolean isToggleKey(KeyEvent event) {
        if (toggleKey == null || toggleKey.isUnbound() || event == null) {
            return false;
        }
        try {
            return toggleKey.matches(event);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * KeyboardHandler.keyPress HEAD while a screen is open. FancyMenu cancels
     * keyPress for keys its windows consume, so the screen hook never saw the
     * toggle key there.
     */
    public static void onKeyPressEarly(KeyEvent event) {
        if (isToggleKey(event)) {
            lastEarlyToggleNs = System.nanoTime();
            toggle();
        }
    }

    public static void toggle() {
        visible = !visible;
        if (!visible) {
            releaseGrab(false);
        }
    }

    public static boolean isEnabled() {
        return visible && !SafeMode.bypass();
    }

    public static boolean isGrabbing() {
        return touchMode != TouchMode.IDLE;
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    public static int widthForSize(int sizePct) {
        return Math.max(12, BASE_WIDTH * sizePct / 100);
    }

    public static int heightForSize(int sizePct) {
        return Math.max(40, BASE_HEIGHT * sizePct / 100);
    }

    public static int defaultRightMargin() {
        return DEFAULT_RIGHT_MARGIN;
    }

    /** {x, y, width, height} of the wheel on this screen, GUI px. */
    private static float[] bounds(Screen screen) {
        if (screen == null || screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        int size = DragScrollConfig.getWheelSize();
        float w = Math.max(12, BASE_WIDTH * size / 100.0f);
        float h = Math.max(40, BASE_HEIGHT * size / 100.0f);
        double cx = DragScrollConfig.getWheelPosX();
        double cy = DragScrollConfig.getWheelPosY();
        if (cx == DragScrollConfig.WHEEL_POS_UNSET) {
            cx = screen.width - DEFAULT_RIGHT_MARGIN - w / 2.0;
        }
        if (cy == DragScrollConfig.WHEEL_POS_UNSET) {
            cy = screen.height / 2.0;
        }
        float x = Math.max(0f, Math.min((float) (cx - w / 2.0), screen.width - w));
        float y = Math.max(0f, Math.min((float) (cy - h / 2.0), screen.height - h));
        return new float[] {x, y, w, h};
    }

    public static boolean isOverWheel(Screen screen, double x, double y) {
        if (!isEnabled() || !shouldShow(screen)) {
            return false;
        }
        float[] b = bounds(screen);
        return b != null
                && x >= b[0] - HIT_EXPAND && x <= b[0] + b[2] + HIT_EXPAND
                && y >= b[1] - HIT_EXPAND && y <= b[1] + b[3] + HIT_EXPAND;
    }

    /** Hidden on the wheel setup screen, where its preview is the only wheel. */
    private static boolean shouldShow(Screen screen) {
        if (screen == null) {
            return false;
        }
        if (screen instanceof WheelSetupScreen) {
            if (isGrabbing()) {
                releaseGrab(false);
            }
            return false;
        }
        return true;
    }

    // =====================================================================
    // Direction
    // =====================================================================

    /**
     * Screens whose mouseScrolled runs the other way round for the wheel
     * notches. Decided once per class; libraries are matched through the
     * class hierarchy so the screens of their host mods are covered too.
     */
    private static final ClassValue<Boolean> INVERTED_WHEEL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return isInvertedWheelScreen(type);
        }
    };

    private static boolean isInvertedWheelScreen(Class<?> cls) {
        String name = cls.getName().toLowerCase(Locale.ROOT);
        String simple = cls.getSimpleName().toLowerCase(Locale.ROOT);
        // Correct without inverting: chat, this mod's own screens, TRender/Cotton.
        if (ChatScreen.class.isAssignableFrom(cls) || name.startsWith("ru.evga314.dragscroll")
                || name.contains("entityculling") || name.contains("trender")
                || name.contains("cottonclientscreen") || name.contains("io.github.cottonmc")
                || name.contains("cotton.gui")
                || hierarchyContains(cls, "trender", "cotton.gui")) {
            return false;
        }
        return hierarchyContains(cls, "yacl", "yetanotherconfig", "yet_another_config")
                || hierarchyContains(cls, "craft_config", "craftconfig", "craft.config")
                || name.contains("terraformersmc.modmenu") || simple.equals("modsscreen")
                || hierarchyContains(cls, "collective")
                || CreativeModeInventoryScreen.class.isAssignableFrom(cls)
                || name.startsWith("net.minecraft.")
                || name.contains("tconfig.gui") || name.contains("tconfigscreen")
                || name.contains("entitymodelfeatures") || name.contains("entity_texture_features")
                || name.contains("entitytexturefeatures") || name.contains("traben.entity_model_features")
                || hierarchyContains(cls, "traben.tconfig")
                || name.contains("net.irisshaders") || name.contains("net.coderbot.iris")
                || name.contains("iris") && (name.contains("shader") || name.contains("pack"))
                || name.contains("shulkerboxtooltip")
                || name.contains("smoothscroll");
    }

    /** True if this class or a superclass has a name containing one of the needles. */
    private static boolean hierarchyContains(Class<?> cls, String... needles) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName().toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (n.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    // =====================================================================
    // Touch
    // =====================================================================

    /** True when the press was on the wheel and the wheel took the touch. */
    public static boolean onPress(Screen screen, double x, double y) {
        if (!isOverWheel(screen, x, y)) {
            return false;
        }
        touchMode = TouchMode.PENDING;
        grabScreen = screen;
        pressNs = System.nanoTime();
        pressX = x;
        pressY = y;
        lastY = y;
        accum = 0.0;
        grabOffsetX = 0.0;
        grabOffsetY = 0.0;
        return true;
    }

    public static void onDrag(Screen screen, double x, double y) {
        if (touchMode == TouchMode.IDLE || screen != grabScreen) {
            return;
        }
        if (touchMode == TouchMode.PENDING) {
            if (Math.hypot(x - pressX, y - pressY) >= SWIPE_COMMIT_PX) {
                // A clear swipe scrolls; it never turns into a long press.
                touchMode = TouchMode.SCROLL;
                lastY = y;
                accum = 0.0;
            } else {
                tryPromoteReposition();
            }
        }
        if (touchMode == TouchMode.SCROLL) {
            double dy = y - lastY;
            lastY = y;
            phase += dy;
            boolean inverted = DragScrollConfig.isWheelInvertEnabled() != INVERTED_WHEEL.get(screen.getClass());
            accum += (inverted ? -dy : dy) * DragScrollConfig.getWheelSpeedMultiplier();
            int notches = (int) (accum / PIXELS_PER_NOTCH);
            if (notches != 0) {
                accum -= notches * PIXELS_PER_NOTCH;
                dispatchScroll(screen, notches);
            }
        } else if (touchMode == TouchMode.REPOSITION) {
            applyReposition(screen, x, y);
        }
    }

    public static void onRelease() {
        releaseGrab(touchMode == TouchMode.REPOSITION);
    }

    /** A pending press held still long enough becomes a reposition (when enabled). */
    private static void tryPromoteReposition() {
        if (touchMode != TouchMode.PENDING || !DragScrollConfig.isWheelRepositionEnabled()) {
            return;
        }
        long holdNs = Math.max(50L, DragScrollConfig.getWheelRepositionHoldMs()) * 1_000_000L;
        if (System.nanoTime() - pressNs < holdNs) {
            return;
        }
        touchMode = TouchMode.REPOSITION;
        float[] b = bounds(grabScreen);
        grabOffsetX = b != null ? pressX - (b[0] + b[2] / 2.0) : 0.0;
        grabOffsetY = b != null ? pressY - (b[1] + b[3] / 2.0) : 0.0;
        applyReposition(grabScreen, pressX, pressY);
    }

    /** Moves the wheel (live, saved on release) so it stays under the finger. */
    private static void applyReposition(Screen screen, double fingerX, double fingerY) {
        if (screen == null) {
            return;
        }
        int size = DragScrollConfig.getWheelSize();
        float w = Math.max(12, BASE_WIDTH * size / 100.0f);
        float h = Math.max(40, BASE_HEIGHT * size / 100.0f);
        double cx = Math.max(w / 2.0, Math.min(fingerX - grabOffsetX, screen.width - w / 2.0));
        double cy = Math.max(h / 2.0, Math.min(fingerY - grabOffsetY, screen.height - h / 2.0));
        DragScrollConfig.setWheelPos(cx, cy);
    }

    private static void releaseGrab(boolean savePosition) {
        if (savePosition) {
            DragScrollConfig.save();
        }
        touchMode = TouchMode.IDLE;
        grabScreen = null;
        accum = 0.0;
        grabOffsetX = 0.0;
        grabOffsetY = 0.0;
    }

    // =====================================================================
    // Scrolling
    // =====================================================================

    /**
     * Sends {@code notches} (positive = towards the start, like the vanilla
     * wheel) to the point the finger last scrolled, or where the cursor was
     * before it jumped onto the wheel.
     */
    private static void dispatchScroll(Screen screen, double notches) {
        if (DragScrollConfig.isWheelBlockCreativeEnabled() && isCreativeInventoryTab(screen)) {
            return;
        }
        double tx = TouchState.cursorBeforeTeleportX;
        double ty = TouchState.cursorBeforeTeleportY;
        if (Double.isNaN(tx) || Double.isNaN(ty)) {
            if (TouchState.lastScrollTargetScreen == screen && !Double.isNaN(TouchState.lastScrollTargetX)
                    && !Double.isNaN(TouchState.lastScrollTargetY)) {
                tx = TouchState.lastScrollTargetX;
                ty = TouchState.lastScrollTargetY;
            } else {
                tx = screen.width / 2.0;
                ty = screen.height / 2.0;
            }
        }
        // A target on the wheel itself moves left of it.
        float[] b = bounds(screen);
        if (b != null && tx >= b[0] && tx <= b[0] + b[2] && ty >= b[1] && ty <= b[1] + b[3]) {
            tx = b[0] > 80 ? b[0] - 40 : screen.width / 2.0;
            ty = b[1] + b[3] / 2.0;
        }
        if (Debug.on()) Debug.log("MouseWheelEmulator.dispatchScroll", "screen=" + screen.getClass().getSimpleName()
                + " notches=" + notches + " x=" + tx + " y=" + ty);

        // FancyMenu windows lie on top of the screen and take the wheel first.
        if (FancyMenuCompat.dispatchWheel(tx, ty, notches)) {
            return;
        }
        AbstractScrollArea area = findScrollAreaAt(screen, tx, ty);
        try {
            if (area != null) {
                double rate = area instanceof ScrollAreaAccess access ? access.dragscroll$scrollRate() : 10.0;
                area.setScrollAmount(area.scrollAmount() + notches * rate);
            } else {
                screen.mouseScrolled(tx, ty, 0.0, notches);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The creative tab that shows the player's inventory slots (not an item
     * list). Vanilla scrolls the item grid from anywhere on that screen.
     */
    private static boolean isCreativeInventoryTab(Screen screen) {
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            return false;
        }
        CreativeModeTab tab = CreativeModeInventoryScreenAccessor.dragscroll$getSelectedTab();
        return tab != null && tab.getType() == CreativeModeTab.Type.INVENTORY;
    }

    /** Innermost visible scrollable AbstractScrollArea under the point. */
    private static AbstractScrollArea findScrollAreaAt(GuiEventListener root, double x, double y) {
        if (root instanceof AbstractScrollArea area) {
            try {
                if (!area.visible) {
                    return null;
                }
                if (area.isMouseOver(x, y) && area.maxScrollAmount() > 0.0) {
                    return area;
                }
            } catch (Throwable ignored) {
            }
        }
        if (root instanceof ContainerEventHandler container) {
            try {
                for (GuiEventListener child : container.children()) {
                    AbstractScrollArea found = findScrollAreaAt(child, x, y);
                    if (found != null) {
                        return found;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        if (!isEnabled() || !shouldShow(screen)) {
            return;
        }
        float[] b = bounds(screen);
        if (b == null) {
            return;
        }
        // Integer box covering the fractional one.
        int x = (int) Math.floor(b[0]);
        int y = (int) Math.floor(b[1]);
        int w = (int) Math.ceil(b[0] + b[2]) - x;
        int h = (int) Math.ceil(b[1] + b[3]) - y;
        int opacity = DragScrollConfig.getWheelOpacity();
        if (touchMode == TouchMode.REPOSITION) {
            // Brighter while being moved, so the grab is visible.
            opacity = Math.min(100, opacity + 15);
        }
        drawWheel(graphics, x, y, w, h, opacity, phase);
    }

    /** Draws the wheel; shared with the setup screen's preview. */
    public static void drawWheel(GuiGraphics g, int x, int y, int w, int h, int opacityPct, double phase) {
        int a = Math.max(0, Math.min(255, opacityPct * 255 / 100)) << 24;
        int right = x + w;
        int bottom = y + h;

        g.fill(x, y, right, bottom, a | 0x1C1C22);
        g.fill(x, y, right, y + 1, a);
        g.fill(x, bottom - 1, right, bottom, a);
        g.fill(x, y, x + 1, bottom, a);
        g.fill(right - 1, y, right, bottom, a);

        int inset = Math.max(2, w / 5);
        int dx0 = x + inset;
        int dx1 = right - inset;
        int dy0 = y + 3;
        int dy1 = bottom - 3;
        if (dx1 <= dx0 || dy1 <= dy0) {
            return;
        }
        g.fill(dx0, dy0, dx1, dy1, a | 0x3A3A44);
        g.fill(dx0, dy0, dx0 + 1, dy1, a | 0x101014);
        g.fill(dx1 - 1, dy0, dx1, dy1, a | 0x101014);
        g.fill(dx0 + 1, dy0, dx0 + 2, dy1, a | 0x5A5A66);

        int spacing = 6;
        int off = (int) Math.floorMod(Math.round(phase), (long) spacing);
        for (int ry = dy0 + off; ry < dy1; ry += spacing) {
            g.fill(dx0 + 1, ry, dx1 - 1, ry + 1, a | 0x1A1A20);
            if (ry + 1 < dy1) {
                g.fill(dx0 + 1, ry + 1, dx1 - 1, ry + 2, a | 0x6C6C7A);
            }
        }
        int midY = (dy0 + dy1) / 2;
        g.fill(dx0 + 1, midY - 1, dx1 - 1, midY + 1, a | 0x8A8A98);
    }
}
