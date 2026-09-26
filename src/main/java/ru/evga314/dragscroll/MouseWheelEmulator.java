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
 * the cursor (or the last list the user scrolled).
 *
 * <p>Long-press (&gt;300&nbsp;ms) without a swipe switches into reposition mode:
 * the wheel centre follows the finger until release, then the new position is
 * saved. Ordinary swipes still scroll and never enter reposition mode.
 *
 * <p>Toggled with a key mapping (default keypad divide), re-bindable in Controls.
 * Position is stored as float GUI coordinates.
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

    /** Fallback hold if config is unavailable (should not happen). */
    private static final long DEFAULT_REPOSITION_HOLD_NS = 300_000_000L;
    /** Movement (GUI px) that commits the touch to scrolling instead of long-press. */
    private static final double SWIPE_COMMIT_PX = 8.0;
    /**
     * Hit-test expansion so float centres still cover full integer pixels
     * the finger may land on (avoids missing the edge of a fractional box).
     */
    private static final double HIT_EXPAND = 0.75;

    private static KeyMapping toggleKey;
    private static boolean visible;

    private enum TouchMode {
        IDLE,
        /** Press landed on the wheel; waiting for swipe vs long-press. */
        PENDING,
        /** Vertical swipe — emit scroll notches. */
        SCROLL,
        /** Long-press — drag the widget centre. */
        REPOSITION
    }

    private static TouchMode touchMode = TouchMode.IDLE;
    private static Screen grabScreen;
    private static long pressNs;
    private static double pressX;
    private static double pressY;
    private static double lastY;
    private static double accum;
    private static double phase;
    /** Finger offset from wheel centre while repositioning. */
    private static double repositionGrabOffX;
    private static double repositionGrabOffY;

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey == null) {
                return;
            }
            boolean screenOpen = client.gui != null && client.gui.screen() != null;
            boolean toggled = false;
            while (toggleKey.consumeClick()) {
                toggled = true;
            }
            if (toggled && !screenOpen) {
                return;
            }

            // Promote PENDING → REPOSITION when the finger stays still long enough
            // (onMove may not fire if the launcher sends no movement).
            tryPromoteReposition();
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            try {
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> {
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
            releaseGrab(false);
        }
    }

    public static boolean isVisible() {
        return visible;
    }

    public static boolean isGrabbing() {
        return touchMode != TouchMode.IDLE;
    }

    public static boolean isRepositioning() {
        return touchMode == TouchMode.REPOSITION;
    }

    // ---------------------------------------------------------------- geometry

    /**
     * Wheel rectangle for a screen: {@code [x, y, width, height]} in GUI pixels
     * (float). The configured position is the widget centre; {@code -1} means
     * default (right side, vertically centred).
     */
    public static float[] bounds(Screen screen) {
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

        float x = (float) (cx - w / 2.0);
        float y = (float) (cy - h / 2.0);
        x = Math.max(0f, Math.min(x, screen.width - w));
        y = Math.max(0f, Math.min(y, screen.height - h));
        return new float[] {x, y, w, h};
    }

    /** Integer bounds for drawing (floor origin, ceil size so the box fully covers float edges). */
    public static int[] boundsInt(Screen screen) {
        float[] b = bounds(screen);
        if (b == null) {
            return null;
        }
        int x = (int) Math.floor(b[0]);
        int y = (int) Math.floor(b[1]);
        int right = (int) Math.ceil(b[0] + b[2]);
        int bottom = (int) Math.ceil(b[1] + b[3]);
        return new int[] {x, y, right - x, bottom - y};
    }

    public static int widthForSize(int sizePct) {
        return Math.max(12, BASE_WIDTH * sizePct / 100);
    }

    public static int heightForSize(int sizePct) {
        return Math.max(40, BASE_HEIGHT * sizePct / 100);
    }

    public static int defaultRightMargin() {
        return DEFAULT_RIGHT_MARGIN;
    }

    public static boolean isEnabled() {
        return visible && !SafeMode.bypass();
    }

    /**
     * Screens where the on-screen wheel emulator must emit the opposite
     * mouseScrolled sign. Finger-drag scrolling is not affected.
     *
     * <p>Libraries are detected by class hierarchy (e.g. any screen that
     * extends YACL), so host mods like Better Clouds are covered without
     * listing each mod package.
     */
    private static boolean shouldInvertWheelDirection(Screen screen) {
        if (screen == null) {
            return false;
        }
        Class<?> cls;
        String name;
        String simple;
        try {
            cls = screen.getClass();
            name = cls.getName().toLowerCase(java.util.Locale.ROOT);
            simple = cls.getSimpleName().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return false;
        }

        // --- hard exclusions ---
        if (name.contains("chatscreen")
                || name.contains("sleepingchatscreen")
                || simple.equals("chatscreen")
                || simple.equals("sleepingchatscreen")) {
            return false;
        }
        // Our own screens (package contains "modmenu" — must not match Mod Menu rule).
        if (name.startsWith("ru.evga314.dragscroll")) {
            return false;
        }
        // Entity Culling / TRender / Cotton — correct without invert.
        if (name.contains("entityculling")
                || name.contains("tr7zw.entityculling")
                || name.contains("trender")
                || name.contains("tr7zw.trender")
                || name.contains("cottonclientscreen")
                || name.contains("io.github.cottonmc")
                || name.contains("cotton.gui")
                || hierarchyContains(cls, "trender", "tr7zw.trender", "cotton.gui")) {
            return false;
        }

        // --- libraries (hierarchy so host-mod screens still match) ---
        // Yet Another Config Lib (Better Clouds, and any other YACL host).
        if (hierarchyContains(cls, "yacl", "yetanotherconfig", "yet_another_config", "dev.isxander.yacl")
                || name.contains("yacl")
                || name.contains("dev.isxander.yacl")) {
            return true;
        }

        // Craft Config Lib.
        if (hierarchyContains(cls, "craft_config", "craftconfig", "craft.config")
                || name.contains("craft_config")
                || name.contains("craftconfig")
                || name.contains("craft.config")) {
            return true;
        }

        // Mod Menu mod list (avoid bare "modmenu" — our package is *.dragscroll.modmenu).
        if (name.contains("terraformersmc.modmenu")
                || simple.equals("modsscreen")) {
            return true;
        }

        // Collective (Serilum) config / list screens.
        if (name.contains("collective")
                || name.contains("com.natamus.collective")
                || hierarchyContains(cls, "collective", "com.natamus.collective")) {
            return true;
        }

        // Creative inventory item list (wheel emulator only).
        if (name.contains("creativemodeinventoryscreen")
                || simple.equals("creativemodeinventoryscreen")
                || hierarchyContains(cls, "creativemodeinventoryscreen")) {
            return true;
        }

        // Vanilla Minecraft lists / option screens.
        if (name.startsWith("net.minecraft.")) {
            return true;
        }

        // EMF / ETF (Traben tconfig).
        if (name.contains("traben.tconfig")
                || name.contains("tconfig.gui")
                || name.contains("tconfigscreen")
                || name.contains("entitymodelfeatures")
                || name.contains("entity_texture_features")
                || name.contains("entitytexturefeatures")
                || name.contains("traben.entity_model_features")
                || name.contains("traben.entity_texture_features")
                || hierarchyContains(cls, "traben.tconfig")) {
            return true;
        }

        // Iris shader pack list.
        if (name.contains("net.irisshaders")
                || name.contains("net.coderbot.iris")
                || (name.contains("iris") && (name.contains("shader") || name.contains("pack")))) {
            return true;
        }

        // Shulker Box Tooltip config.
        if (name.contains("shulkerboxtooltip")) {
            return true;
        }

        // Smooth Scrolling (own config UI, not YACL).
        if (name.contains("smoothscroll")
                || name.contains("smajloslovakian.smoothscroll")) {
            return true;
        }

        return false;
    }

    /** True if this class or any superclass name contains one of the needles. */
    private static boolean hierarchyContains(Class<?> cls, String... needles) {
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                String n = c.getName().toLowerCase(java.util.Locale.ROOT);
                for (String needle : needles) {
                    if (n.contains(needle)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isOverWheel(Screen screen, double x, double y) {
        if (!isEnabled() || !shouldShow(screen)) {
            return false;
        }
        float[] b = bounds(screen);
        if (b == null) {
            return false;
        }
        // Expand slightly so fractional float centres still catch integer touch points.
        return x >= b[0] - HIT_EXPAND
                && x <= b[0] + b[2] + HIT_EXPAND
                && y >= b[1] - HIT_EXPAND
                && y <= b[1] + b[3] + HIT_EXPAND;
    }

    // ------------------------------------------------------------------- input

    /** Returns true when the press was on the wheel and the wheel took the touch. */
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
        repositionGrabOffX = 0.0;
        repositionGrabOffY = 0.0;
        return true;
    }

    public static void onDrag(Screen screen, double x, double y) {
        if (touchMode == TouchMode.IDLE || screen != grabScreen) {
            return;
        }

        if (touchMode == TouchMode.PENDING) {
            double dx = x - pressX;
            double dy = y - pressY;
            double dist = Math.hypot(dx, dy);
            if (dist >= SWIPE_COMMIT_PX) {
                // Clear swipe → scroll mode. Never treat this as a long-press.
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
            double signedDy = DragScrollConfig.isWheelInvertEnabled() ? -dy : dy;
            // Some UIs treat mouseScrolled with the opposite sign relative to the
            // wheel emulator's default. Invert only for those screens (not chat).
            if (shouldInvertWheelDirection(screen)) {
                signedDy = -signedDy;
            }
            accum += signedDy * DragScrollConfig.getWheelSpeedMultiplier();
            int notches = (int) (accum / PIXELS_PER_NOTCH);
            if (notches != 0) {
                accum -= notches * PIXELS_PER_NOTCH;
                dispatchScroll(screen, notches);
            }
            return;
        }

        if (touchMode == TouchMode.REPOSITION) {
            applyReposition(screen, x, y);
        }
    }

    public static void onRelease() {
        boolean save = touchMode == TouchMode.REPOSITION;
        releaseGrab(save);
    }

    private static void tryPromoteReposition() {
        if (touchMode != TouchMode.PENDING) {
            return;
        }
        // Feature is opt-in (off by default).
        if (!DragScrollConfig.isWheelRepositionEnabled()) {
            return;
        }
        long holdNs = DEFAULT_REPOSITION_HOLD_NS;
        try {
            holdNs = Math.max(50L, DragScrollConfig.getWheelRepositionHoldMs()) * 1_000_000L;
        } catch (Throwable ignored) {
        }
        if (System.nanoTime() - pressNs < holdNs) {
            return;
        }
        // Still essentially on the press point — commit to move mode.
        touchMode = TouchMode.REPOSITION;
        float[] b = bounds(grabScreen);
        if (b != null) {
            double cx = b[0] + b[2] / 2.0;
            double cy = b[1] + b[3] / 2.0;
            repositionGrabOffX = pressX - cx;
            repositionGrabOffY = pressY - cy;
        } else {
            repositionGrabOffX = 0.0;
            repositionGrabOffY = 0.0;
        }
        // Seed from current finger so the first frame does not jump.
        applyReposition(grabScreen, pressX, pressY);
    }

    private static void applyReposition(Screen screen, double fingerX, double fingerY) {
        if (screen == null) {
            return;
        }
        int size = DragScrollConfig.getWheelSize();
        float w = Math.max(12, BASE_WIDTH * size / 100.0f);
        float h = Math.max(40, BASE_HEIGHT * size / 100.0f);
        double cx = fingerX - repositionGrabOffX;
        double cy = fingerY - repositionGrabOffY;
        // Keep the whole widget on screen.
        cx = Math.max(w / 2.0, Math.min(cx, screen.width - w / 2.0));
        cy = Math.max(h / 2.0, Math.min(cy, screen.height - h / 2.0));
        // Live preview only — persist on release so cancel is possible via...
        // (we always save on release of reposition; no separate cancel.)
        DragScrollConfig.setWheelPos(cx, cy);
        // Do NOT touch lastCursorXBeforeTeleport — scroll target stays where it was.
    }

    private static void releaseGrab(boolean savePosition) {
        if (savePosition) {
            try {
                DragScrollConfig.save();
            } catch (Throwable ignored) {
            }
        }
        touchMode = TouchMode.IDLE;
        grabScreen = null;
        accum = 0.0;
        repositionGrabOffX = 0.0;
        repositionGrabOffY = 0.0;
    }

    private static void dispatchScroll(Screen screen, double scrollY) {
        if (screen == null || scrollY == 0.0) {
            return;
        }
        if (DragScrollConfig.isWheelBlockCreativeEnabled() && isCreativePlayerInventoryTab(screen)) {
            return;
        }
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
        float[] b = bounds(screen);
        if (b != null) {
            float wx = b[0];
            float wy = b[1];
            float ww = b[2];
            float wh = b[3];
            if (tx >= wx && tx <= wx + ww && ty >= wy && ty <= wy + wh) {
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

        AbstractScrollArea targetArea = findScrollAreaAt(screen, tx, ty);
        if (targetArea != null) {
            try {
                double current = targetArea.scrollAmount();
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
            try {
                screen.mouseScrolled(tx, ty, 0.0, scrollY);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isCreativeInventoryScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        return screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
    }

    /**
     * True only on the creative tab that shows the player model and personal
     * inventory slots (CreativeModeTab.Type.INVENTORY) — not on item-list tabs.
     */
    private static java.lang.reflect.Field creativeSelectedTabField;
    private static boolean creativeSelectedTabResolved;

    private static boolean isCreativePlayerInventoryTab(Screen screen) {
        if (!isCreativeInventoryScreen(screen)) {
            return false;
        }
        try {
            if (!creativeSelectedTabResolved) {
                creativeSelectedTabResolved = true;
                for (java.lang.reflect.Field f
                        : net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class
                        .getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (net.minecraft.world.item.CreativeModeTab.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        creativeSelectedTabField = f;
                        break;
                    }
                }
            }
            if (creativeSelectedTabField == null) {
                return false;
            }
            Object tab = creativeSelectedTabField.get(null);
            if (tab == null) {
                return false;
            }
            Object type = tab.getClass().getMethod("getType").invoke(tab);
            if (type instanceof Enum<?> e) {
                return "INVENTORY".equals(e.name());
            }
            return type != null && type.toString().toUpperCase(java.util.Locale.ROOT).contains("INVENTORY");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AbstractScrollArea findScrollAreaAt(GuiEventListener root, double x, double y) {
        if (root instanceof AbstractScrollArea area) {
            try {
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

    public static boolean shouldShow(Screen screen) {
        if (screen == null) {
            return false;
        }
        // Only our own wheel-setup screen: hide the live scroll emulator so its
        // preview is the only widget visible/movable. Other screens keep the overlay.
        if (screen instanceof ru.evga314.dragscroll.modmenu.WheelSetupScreen) {
            if (isGrabbing()) {
                releaseGrab(false);
            }
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- rendering

    private static void render(Screen screen, GuiGraphicsExtractor graphics,
                               int mouseX, int mouseY, float tickDelta) {
        if (!isEnabled() || !shouldShow(screen)) {
            return;
        }
        int[] b = boundsInt(screen);
        if (b == null) {
            return;
        }
        int opacity = DragScrollConfig.getWheelOpacity();
        // Slightly brighter while being repositioned so the user sees the grab.
        if (touchMode == TouchMode.REPOSITION) {
            opacity = Math.min(100, opacity + 15);
        }
        drawWheel(graphics, b[0], b[1], b[2], b[3], opacity, phase);
    }

    /**
     * Draws the wheel widget. Shared with the setup screen so the preview and
     * the live overlay look identical.
     */
    public static void drawWheel(GuiGraphicsExtractor g, int x, int y, int w, int h, int opacityPct, double phase) {
        int a = Math.max(0, Math.min(255, opacityPct * 255 / 100)) << 24;
        int right = x + w;
        int bottom = y + h;

        g.fill(x, y, right, bottom, a | 0x1C1C22);
        g.fill(x, y, right, y + 1, a | 0x000000);
        g.fill(x, bottom - 1, right, bottom, a | 0x000000);
        g.fill(x, y, x + 1, bottom, a | 0x000000);
        g.fill(right - 1, y, right, bottom, a | 0x000000);

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
        int off = (int) Math.floorMod((long) Math.round(phase), (long) spacing);
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
