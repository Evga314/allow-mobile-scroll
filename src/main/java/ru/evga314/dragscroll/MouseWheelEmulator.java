package ru.evga314.dragscroll;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import ru.evga314.dragscroll.compat.SafeMode;

/**
 * On-screen mouse-wheel emulator.
 *
 * <p>A small widget that looks like a mouse wheel seen from above, drawn in a
 * vertical rectangle. Holding LMB on it and moving the finger up/down rotates
 * the wheel and emits real {@code mouseScrolled} notches into the list under
 * the cursor (or the last list the user scrolled). Direction is inverted from a
 * desktop wheel on purpose: finger up scrolls the list down, finger down scrolls
 * the list up — the same way dragging the list content behaves on touch.
 *
 * <p>Toggled with a key mapping (default {@code U}), re-bindable in the vanilla
 * controls screen. Position, size and opacity are configured in a setup screen
 * opened from the mod settings.
 */
public final class MouseWheelEmulator {
    public static final String TOGGLE_KEY_ID = "key.dragscroll.wheel_emulator";

    /** Finger travel (GUI px) that equals one wheel notch. */
    private static final double PIXELS_PER_NOTCH = 16.0;

    /** Base widget footprint at 100% size, in GUI pixels. (Width +50% for easier grabbing.) */
    private static final int BASE_WIDTH = 39;
    private static final int BASE_HEIGHT = 96;
    /** Default distance of the wheel centre from the right screen edge. */
    private static final int DEFAULT_RIGHT_MARGIN = 26;

    private static KeyMapping toggleKey;
    private static boolean visible;

    private static boolean grabbed;
    private static Screen grabScreen;
    private static double lastY;
    private static double accum;
    private static double phase;

    private MouseWheelEmulator() {
    }

    /**
     * SDL3 key code for Numpad Divide in MC 26.3. Not a printable character, so
     * it never types into a focused text field. MC 26.3 uses SDL3 scancodes, not
     * GLFW codes — the vanilla registry maps "key.keyboard.keypad.divide" to 84.
     */
    private static final int KEY_KP_DIVIDE = 84;

    public static void init() {
        toggleKey = new KeyMapping(
                TOGGLE_KEY_ID,
                InputConstants.Type.KEYBOARD,
                KEY_KP_DIVIDE,
                KeyMapping.Category.MISC);
        KeyMappingHelper.registerKeyMapping(toggleKey);

        // The wheel only makes sense while a GUI is open (a cursor is present).
        // In-world (no screen), the key must do nothing — and it must NOT queue a
        // click that would fire the next time a screen opens. Drain any pending
        // click each tick while no screen is open so it can never leak into a menu.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey == null) {
                return;
            }
            boolean screenOpen = client.gui != null && client.gui.screen() != null;
            boolean toggled = false;
            while (toggleKey.consumeClick()) {
                toggled = true;
            }
            // Only toggle here for non-text screens without keyboard-event hooks is
            // unnecessary — screen key presses are handled by the hook below. In-world
            // presses are consumed above and deliberately ignored.
            if (toggled && !screenOpen) {
                // In-world: ignore. (Consumed so it won't fire later.)
                return;
            }
        });

        // Toggle while any screen is open, and draw the overlay on scrollable ones.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            try {
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> {
                    // Any screen: the default key (Numpad Divide) is not a text
                    // character, so it never types into a focused input field. No
                    // text-field guard needed — toggle works everywhere a GUI is open.
                    if (isToggleKey(keyEvent) && scr != null) {
                        toggle();
                    }
                });
                ScreenEvents.afterForeground(screen).register(MouseWheelEmulator::render);
            } catch (Throwable t) {
                DragScrollClient.LOGGER.warn("Mouse-wheel emulator hook failed", t);
            }
        });
    }

    public static KeyMapping toggleKey() {
        return toggleKey;
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

    public static void toggle() {
        setVisible(!visible);
    }

    public static void setVisible(boolean value) {
        visible = value;
        if (!visible) {
            releaseGrab();
        }
    }

    public static boolean isVisible() {
        return visible;
    }

    public static boolean isGrabbing() {
        return grabbed;
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Wheel rectangle for a screen: {@code [x, y, width, height]} in GUI pixels.
     * The configured position is the widget centre; {@code -1} means "default"
     * (right side, vertically centred).
     */
    public static int[] bounds(Screen screen) {
        if (screen == null || screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        int size = DragScrollConfig.getWheelSize();
        int w = Math.max(12, BASE_WIDTH * size / 100);
        int h = Math.max(40, BASE_HEIGHT * size / 100);

        int cx = DragScrollConfig.getWheelPosX();
        int cy = DragScrollConfig.getWheelPosY();
        if (cx == DragScrollConfig.WHEEL_POS_UNSET) {
            cx = screen.width - DEFAULT_RIGHT_MARGIN - w / 2;
        }
        if (cy == DragScrollConfig.WHEEL_POS_UNSET) {
            cy = screen.height / 2;
        }

        int x = cx - w / 2;
        int y = cy - h / 2;
        // Keep the whole widget on screen.
        x = Math.max(0, Math.min(x, screen.width - w));
        y = Math.max(0, Math.min(y, screen.height - h));
        return new int[] {x, y, w, h};
    }

    /** Wheel width in GUI px for a given size percentage. Shared with the setup preview. */
    public static int widthForSize(int sizePct) {
        return Math.max(12, BASE_WIDTH * sizePct / 100);
    }

    /** Wheel height in GUI px for a given size percentage. Shared with the setup preview. */
    public static int heightForSize(int sizePct) {
        return Math.max(40, BASE_HEIGHT * sizePct / 100);
    }

    /** Default distance of the wheel centre from the right screen edge. */
    public static int defaultRightMargin() {
        return DEFAULT_RIGHT_MARGIN;
    }

    public static boolean isEnabled() {
        return visible && !SafeMode.bypass();
    }

    public static boolean isOverWheel(Screen screen, double x, double y) {
        if (!isEnabled() || !shouldShow(screen)) {
            return false;
        }
        int[] b = bounds(screen);
        if (b == null) {
            return false;
        }
        return x >= b[0] && x <= b[0] + b[2] && y >= b[1] && y <= b[1] + b[3];
    }

    // ------------------------------------------------------------------- input

    /** Returns true when the press was on the wheel and the wheel took the touch. */
    public static boolean onPress(Screen screen, double x, double y) {
        if (!isOverWheel(screen, x, y)) {
            return false;
        }
        grabbed = true;
        grabScreen = screen;
        lastY = y;
        accum = 0.0;
        return true;
    }

    public static void onDrag(Screen screen, double x, double y) {
        if (!grabbed || screen != grabScreen) {
            return;
        }
        double dy = y - lastY;
        lastY = y;
        phase += dy;
        // Scroll speed multiplier scales how much list movement each finger pixel
        // produces (0.1× … 10×).
        double signedDy = DragScrollConfig.isWheelInvertEnabled() ? -dy : dy;
        accum += signedDy * DragScrollConfig.getWheelSpeedMultiplier();
        int notches = (int) (accum / PIXELS_PER_NOTCH);
        if (notches != 0) {
            accum -= notches * PIXELS_PER_NOTCH;
            // Finger down (dy > 0, notches > 0) scrolls the list up (towards the
            // start): a positive scrollY, exactly like a physical wheel does.
            // When wheel_invert is on, the sign is flipped above.
            dispatchScroll(screen, notches);
        }
    }

    public static void onRelease() {
        releaseGrab();
    }

    private static void releaseGrab() {
        grabbed = false;
        grabScreen = null;
        accum = 0.0;
    }

    private static void dispatchScroll(Screen screen, double scrollY) {
        if (screen == null || scrollY == 0.0) {
            return;
        }
        // Block the scroll if this is the creative inventory and the setting is on.
        if (DragScrollConfig.isWheelBlockCreativeEnabled() && isCreativeInventoryScreen(screen)) {
            return;
        }
        // Target priority:
        // 1. Where the cursor was before it teleported onto the wheel widget.
        // 2. Where the user last scrolled/dragged (if on the same screen).
        // 3. Screen center (fallback).
        double tx = DragScrollState.lastCursorXBeforeTeleport;
        double ty = DragScrollState.lastCursorYBeforeTeleport;
        if (Double.isNaN(tx) || Double.isNaN(ty)) {
            if (DragScrollState.lastScrollTargetScreen == screen
                    && !Double.isNaN(DragScrollState.lastScrollTargetX)
                    && !Double.isNaN(DragScrollState.lastScrollTargetY)) {
                tx = DragScrollState.lastScrollTargetX;
                ty = DragScrollState.lastScrollTargetY;
            } else {
                tx = screen.width / 2.0;
                ty = screen.height / 2.0;
            }
        }
        // If the target point is inside the wheel widget itself, shift it outside
        // so the scroll doesn't hit a list that happens to be under the widget.
        int[] b = bounds(screen);
        if (b != null) {
            int wx = b[0];
            int wy = b[1];
            int ww = b[2];
            int wh = b[3];
            if (tx >= wx && tx <= wx + ww && ty >= wy && ty <= wy + wh) {
                // Move target left of the widget, or to screen center if too close to edge.
                tx = wx > 80 ? wx - 40 : screen.width / 2.0;
                ty = wy + wh / 2.0;
            }
        }
        if (DragScrollState.debugOn()) {
            DragScrollState.debugEvent("MouseWheelEmulator.dispatchScroll",
                    "screen=" + (screen == null ? "null" : screen.getClass().getSimpleName())
                            + " scrollY=" + scrollY
                            + " targetX=" + tx + " targetY=" + ty
                            + " beforeTeleportX=" + DragScrollState.lastCursorXBeforeTeleport
                            + " beforeTeleportY=" + DragScrollState.lastCursorYBeforeTeleport
                            + " lastScrollX=" + DragScrollState.lastScrollTargetX
                            + " lastScrollY=" + DragScrollState.lastScrollTargetY);
        }

        // Find the specific AbstractScrollArea under the target point and scroll
        // it directly, rather than dispatching screen.mouseScrolled, which may
        // route the event to the wrong list when multiple scrollable areas exist
        // (e.g. Mod Menu: left mod list + right description pane).
        AbstractScrollArea targetArea = findScrollAreaAt(screen, tx, ty);
        if (targetArea != null) {
            try {
                double current = targetArea.scrollAmount();
                // Apply scrollRate to match the speed when scrolling via mouseScrolled.
                // AbstractScrollArea.mouseScrolled internally multiplies notches by
                // scrollRate(), so direct setScrollAmount must do the same.
                double rate = 10.0;
                if (targetArea instanceof ru.evga314.dragscroll.access.NativeScrollbarAccess access) {
                    rate = access.dragscroll$scrollRate();
                }
                targetArea.setScrollAmount(current + scrollY * rate);
                if (DragScrollState.debugOn()) {
                    DragScrollState.debugEvent("MouseWheelEmulator.dispatchScroll",
                            "DIRECT_SCROLL area=" + targetArea.getClass().getSimpleName()
                                    + " before=" + current + " delta=" + scrollY
                                    + " rate=" + rate);
                }
            } catch (Throwable ignored) {
            }
        } else {
            // Fallback: send to the screen's mouseScrolled dispatcher.
            try {
                screen.mouseScrolled(tx, ty, 0.0, scrollY);
            } catch (Throwable ignored) {
            }
        }
    }

    /** True for the creative inventory / item picker screen. */
    private static boolean isCreativeInventoryScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        return screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
    }

    /**
     * Find the AbstractScrollArea under the given point. Screens with multiple
     * scrollable lists (Mod Menu, config screens) need this so the wheel targets
     * the correct list rather than letting the screen dispatcher pick one.
     *
     * <p>Skips invisible areas (hidden recipe book, tabs) so they don't steal
     * scroll in the inventory screen.
     */
    private static AbstractScrollArea findScrollAreaAt(GuiEventListener root, double x, double y) {
        if (root instanceof AbstractScrollArea area) {
            try {
                // Skip invisible areas: the recipe book's scroll panel stays in the
                // widget tree even when the book is closed, and would otherwise
                // steal wheel events in the inventory screen.
                if (root instanceof net.minecraft.client.gui.components.AbstractWidget widget
                        && !widget.visible) {
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

    // ----------------------------------------------------------- target lookup

    /**
     * Shown on all GUI screens (any Screen open), and hidden in-world when no
     * GUI is up (no cursor visible). Allows scrolling config screens, chat, and
     * anything the mod's drag handlers might miss.
     */
    public static boolean shouldShow(Screen screen) {
        // Show on every screen, hide only in-world (screen == null).
        return screen != null;
    }

    // ---------------------------------------------------------------- rendering

    private static void render(Screen screen, GuiGraphicsExtractor graphics,
                               int mouseX, int mouseY, float tickDelta) {
        if (!isEnabled() || !shouldShow(screen)) {
            return;
        }
        int[] b = bounds(screen);
        if (b == null) {
            return;
        }
        drawWheel(graphics, b[0], b[1], b[2], b[3], DragScrollConfig.getWheelOpacity(), phase);
    }

    /**
     * Draws the wheel widget. Shared with the setup screen so the preview and
     * the live overlay look identical.
     */
    public static void drawWheel(GuiGraphicsExtractor g, int x, int y, int w, int h, int opacityPct, double phase) {
        int a = Math.max(0, Math.min(255, opacityPct * 255 / 100)) << 24;
        int right = x + w;
        int bottom = y + h;

        // Body + border.
        g.fill(x, y, right, bottom, a | 0x1C1C22);
        g.fill(x, y, right, y + 1, a | 0x000000);
        g.fill(x, bottom - 1, right, bottom, a | 0x000000);
        g.fill(x, y, x + 1, bottom, a | 0x000000);
        g.fill(right - 1, y, right, bottom, a | 0x000000);

        // Wheel drum: an inset vertical capsule.
        int inset = Math.max(2, w / 5);
        int dx0 = x + inset;
        int dx1 = right - inset;
        int dy0 = y + 3;
        int dy1 = bottom - 3;
        if (dx1 <= dx0 || dy1 <= dy0) {
            return;
        }
        g.fill(dx0, dy0, dx1, dy1, a | 0x3A3A44);
        // Side shading for a rounded look.
        g.fill(dx0, dy0, dx0 + 1, dy1, a | 0x101014);
        g.fill(dx1 - 1, dy0, dx1, dy1, a | 0x101014);
        g.fill(dx0 + 1, dy0, dx0 + 2, dy1, a | 0x5A5A66);

        // Ridges that shift with the rotation phase, so scrolling looks alive.
        int spacing = 6;
        int drumH = dy1 - dy0;
        int off = (int) Math.floorMod((long) Math.round(phase), (long) spacing);
        for (int ry = dy0 + off; ry < dy1; ry += spacing) {
            g.fill(dx0 + 1, ry, dx1 - 1, ry + 1, a | 0x1A1A20);
            if (ry + 1 < dy1) {
                g.fill(dx0 + 1, ry + 1, dx1 - 1, ry + 2, a | 0x6C6C7A);
            }
        }
        // Centre catch-light.
        int midY = (dy0 + dy1) / 2;
        g.fill(dx0 + 1, midY - 1, dx1 - 1, midY + 1, a | 0x8A8A98);
    }
}
