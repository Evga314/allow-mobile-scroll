package ru.evga314.dragscroll.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of the mod, stored in {@code config/allowmobilescroll.json}.
 *
 * <p>The file is a flat JSON object written by {@link #save()}; {@link #load()}
 * reads it key by key with regular expressions, so a missing or broken key
 * falls back to its default instead of discarding the whole file. Integer
 * settings are clamped to their range by their setters.
 *
 * <p>{@link ru.evga314.dragscroll.loader.SafeMode} reads the {@code enabled}
 * key before Minecraft starts, so this class must not reference Minecraft.
 */
public final class DragScrollConfig {
    private DragScrollConfig() {
    }

    // --- Touch ---

    /** Finger travel (GUI px) before a touch becomes a drag. */
    public static final int DEFAULT_DRAG_THRESHOLD = 4;
    public static final int MIN_DRAG_THRESHOLD = 1;
    public static final int MAX_DRAG_THRESHOLD = 50;

    /** Inertia strength, % of the measured fling speed. */
    public static final int DEFAULT_INERTIA_STRENGTH = 70;
    public static final int MIN_INERTIA_STRENGTH = 0;
    public static final int MAX_INERTIA_STRENGTH = 200;

    public static final boolean DEFAULT_ENABLED = true;
    /** Debug logging. Off by default: it writes several lines per frame. */
    public static final boolean DEFAULT_DEBUG = false;
    /** Zoom slider drawn over Xaero's World Map. */
    public static final boolean DEFAULT_XAERO_SLIDER = true;
    /** Twice as wide trade scroll bar in the villager screen. */
    public static final boolean DEFAULT_MERCHANT_WIDE_BAR = true;

    // --- Mouse-wheel emulator ---

    /**
     * No wheel emulator notches on the creative tab that shows the player
     * inventory: vanilla scrolls the item grid from anywhere on that screen.
     */
    public static final boolean DEFAULT_WHEEL_BLOCK_CREATIVE = true;
    public static final boolean DEFAULT_WHEEL_INVERT = false;
    /** Long press on the wheel drags it to a new position. */
    public static final boolean DEFAULT_WHEEL_REPOSITION = true;

    public static final int DEFAULT_WHEEL_REPOSITION_HOLD_MS = 425;
    public static final int MIN_WHEEL_REPOSITION_HOLD_MS = 50;
    public static final int MAX_WHEEL_REPOSITION_HOLD_MS = 1000;

    /** Wheel centre, GUI px. This value on an axis means "default placement". */
    public static final double WHEEL_POS_UNSET = -1.0;

    /** Wheel size, % of the base footprint. */
    public static final int DEFAULT_WHEEL_SIZE = 100;
    public static final int MIN_WHEEL_SIZE = 50;
    public static final int MAX_WHEEL_SIZE = 300;

    public static final int DEFAULT_WHEEL_OPACITY = 70;
    public static final int MIN_WHEEL_OPACITY = 10;
    public static final int MAX_WHEEL_OPACITY = 100;

    /** Wheel speed, % of the base speed (100 = 1.0x). */
    public static final int DEFAULT_WHEEL_SPEED = 100;
    public static final int MIN_WHEEL_SPEED = 10;
    public static final int MAX_WHEEL_SPEED = 1000;

    private static final String FILE_NAME = "allowmobilescroll.json";
    /** Name used before the mod was renamed; read once if the new file is missing. */
    private static final String LEGACY_FILE_NAME = "dragscroll.json";

    private static int dragThreshold = DEFAULT_DRAG_THRESHOLD;
    private static int inertiaStrength = DEFAULT_INERTIA_STRENGTH;
    private static boolean debug = DEFAULT_DEBUG;
    private static boolean xaeroSlider = DEFAULT_XAERO_SLIDER;
    private static boolean merchantWideBar = DEFAULT_MERCHANT_WIDE_BAR;
    private static double wheelPosX = WHEEL_POS_UNSET;
    private static double wheelPosY = WHEEL_POS_UNSET;
    private static int wheelSize = DEFAULT_WHEEL_SIZE;
    private static int wheelOpacity = DEFAULT_WHEEL_OPACITY;
    private static int wheelSpeed = DEFAULT_WHEEL_SPEED;
    private static boolean wheelBlockCreative = DEFAULT_WHEEL_BLOCK_CREATIVE;
    private static boolean wheelInvert = DEFAULT_WHEEL_INVERT;
    private static boolean wheelReposition = DEFAULT_WHEEL_REPOSITION;
    private static int wheelRepositionHoldMs = DEFAULT_WHEEL_REPOSITION_HOLD_MS;
    /** Read by the input hooks on every event, and from the config screen. */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    // =====================================================================
    // File
    // =====================================================================

    public static void load() {
        Path path = configDir().resolve(FILE_NAME);
        Path legacy = configDir().resolve(LEGACY_FILE_NAME);
        try {
            Path source = Files.exists(path) ? path : (Files.exists(legacy) ? legacy : null);
            if (source == null) {
                save();
                return;
            }
            String json = Files.readString(source, StandardCharsets.UTF_8);
            enabled = readBoolean(json, "enabled", DEFAULT_ENABLED);
            setDragThreshold(readInt(json, "drag_threshold", DEFAULT_DRAG_THRESHOLD));
            setInertiaStrength(readInt(json, "inertia_strength", DEFAULT_INERTIA_STRENGTH));
            xaeroSlider = readBoolean(json, "xaero_world_map_slider", DEFAULT_XAERO_SLIDER);
            merchantWideBar = readBoolean(json, "merchant_wide_bar", DEFAULT_MERCHANT_WIDE_BAR);
            wheelPosX = readDouble(json, "wheel_pos_x", WHEEL_POS_UNSET);
            wheelPosY = readDouble(json, "wheel_pos_y", WHEEL_POS_UNSET);
            setWheelSize(readInt(json, "wheel_size", DEFAULT_WHEEL_SIZE));
            setWheelOpacity(readInt(json, "wheel_opacity", DEFAULT_WHEEL_OPACITY));
            setWheelSpeed(readInt(json, "wheel_speed", DEFAULT_WHEEL_SPEED));
            wheelBlockCreative = readBoolean(json, "wheel_block_creative", DEFAULT_WHEEL_BLOCK_CREATIVE);
            wheelInvert = readBoolean(json, "wheel_invert", DEFAULT_WHEEL_INVERT);
            wheelReposition = readBoolean(json, "wheel_reposition", DEFAULT_WHEEL_REPOSITION);
            setWheelRepositionHoldMs(readInt(json, "wheel_reposition_hold_ms", DEFAULT_WHEEL_REPOSITION_HOLD_MS));
            debug = readBoolean(json, "debug", DEFAULT_DEBUG);
            // Rewrite so keys added by a newer version appear in the file, and
            // a legacy file is migrated to the new name.
            save();
        } catch (Throwable t) {
            resetToDefaults();
        }
    }

    /** Everything except "enabled", which a broken file must not switch. */
    private static void resetToDefaults() {
        dragThreshold = DEFAULT_DRAG_THRESHOLD;
        inertiaStrength = DEFAULT_INERTIA_STRENGTH;
        debug = DEFAULT_DEBUG;
        xaeroSlider = DEFAULT_XAERO_SLIDER;
        merchantWideBar = DEFAULT_MERCHANT_WIDE_BAR;
        wheelPosX = WHEEL_POS_UNSET;
        wheelPosY = WHEEL_POS_UNSET;
        wheelSize = DEFAULT_WHEEL_SIZE;
        wheelOpacity = DEFAULT_WHEEL_OPACITY;
        wheelSpeed = DEFAULT_WHEEL_SPEED;
        wheelBlockCreative = DEFAULT_WHEEL_BLOCK_CREATIVE;
        wheelInvert = DEFAULT_WHEEL_INVERT;
        wheelReposition = DEFAULT_WHEEL_REPOSITION;
        wheelRepositionHoldMs = DEFAULT_WHEEL_REPOSITION_HOLD_MS;
    }

    public static void save() {
        Path path = configDir().resolve(FILE_NAME);
        String json = "{\n"
                + entry("enabled", enabled)
                + entry("drag_threshold", dragThreshold)
                + entry("inertia_strength", inertiaStrength)
                + entry("xaero_world_map_slider", xaeroSlider)
                + entry("merchant_wide_bar", merchantWideBar)
                + entry("wheel_pos_x", formatWheelPos(wheelPosX))
                + entry("wheel_pos_y", formatWheelPos(wheelPosY))
                + entry("wheel_size", wheelSize)
                + entry("wheel_opacity", wheelOpacity)
                + entry("wheel_speed", wheelSpeed)
                + entry("wheel_block_creative", wheelBlockCreative)
                + entry("wheel_invert", wheelInvert)
                + entry("wheel_reposition", wheelReposition)
                + entry("wheel_reposition_hold_ms", wheelRepositionHoldMs)
                + "  \"debug\": " + debug + "\n"
                + "}\n";
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static String entry(String key, Object value) {
        return "  \"" + key + "\": " + value + ",\n";
    }

    private static Matcher find(String json, String key, String valueRegex) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(" + valueRegex + ")").matcher(json);
        return m.find() ? m : null;
    }

    private static int readInt(String json, String key, int def) {
        Matcher m = find(json, key, "\\d+");
        return m == null ? def : Integer.parseInt(m.group(1));
    }

    private static boolean readBoolean(String json, String key, boolean def) {
        Matcher m = find(json, key, "true|false");
        return m == null ? def : Boolean.parseBoolean(m.group(1));
    }

    private static double readDouble(String json, String key, double def) {
        Matcher m = find(json, key, "-?\\d+(?:\\.\\d+)?");
        return m == null ? def : Double.parseDouble(m.group(1));
    }

    /** Compact decimal without exponent; whole numbers are written as integers. */
    private static String formatWheelPos(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v == WHEEL_POS_UNSET) {
            return "-1";
        }
        long asLong = Math.round(v);
        if (Math.abs(v - asLong) < 1e-9) {
            return Long.toString(asLong);
        }
        // Four fractional digits are plenty for GUI pixels.
        return String.format(Locale.ROOT, "%.4f", v)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // =====================================================================
    // Settings
    // =====================================================================

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isDebugEnabled() {
        return debug;
    }

    public static void setDebugEnabled(boolean value) {
        debug = value;
    }

    public static int getDragThreshold() {
        return dragThreshold;
    }

    public static void setDragThreshold(int value) {
        dragThreshold = clamp(value, MIN_DRAG_THRESHOLD, MAX_DRAG_THRESHOLD);
    }

    public static int getInertiaStrength() {
        return inertiaStrength;
    }

    public static void setInertiaStrength(int value) {
        inertiaStrength = clamp(value, MIN_INERTIA_STRENGTH, MAX_INERTIA_STRENGTH);
    }

    /** Factor applied to the fling speed (0.0 to 2.0, default 0.7). */
    public static double getInertiaStrengthMultiplier() {
        return inertiaStrength / 100.0;
    }

    public static boolean isXaeroSliderEnabled() {
        return xaeroSlider;
    }

    public static void setXaeroSliderEnabled(boolean value) {
        xaeroSlider = value;
    }

    public static boolean isMerchantWideBarEnabled() {
        return merchantWideBar;
    }

    public static void setMerchantWideBarEnabled(boolean value) {
        merchantWideBar = value;
    }

    public static double getWheelPosX() {
        return wheelPosX;
    }

    public static double getWheelPosY() {
        return wheelPosY;
    }

    public static void setWheelPos(double x, double y) {
        wheelPosX = x;
        wheelPosY = y;
    }

    public static int getWheelSize() {
        return wheelSize;
    }

    public static void setWheelSize(int value) {
        wheelSize = clamp(value, MIN_WHEEL_SIZE, MAX_WHEEL_SIZE);
    }

    public static int getWheelOpacity() {
        return wheelOpacity;
    }

    public static void setWheelOpacity(int value) {
        wheelOpacity = clamp(value, MIN_WHEEL_OPACITY, MAX_WHEEL_OPACITY);
    }

    public static int getWheelSpeed() {
        return wheelSpeed;
    }

    public static void setWheelSpeed(int value) {
        wheelSpeed = clamp(value, MIN_WHEEL_SPEED, MAX_WHEEL_SPEED);
    }

    /** Wheel speed as a factor (150 % gives 1.5). */
    public static double getWheelSpeedMultiplier() {
        return wheelSpeed / 100.0;
    }

    public static boolean isWheelBlockCreativeEnabled() {
        return wheelBlockCreative;
    }

    public static void setWheelBlockCreativeEnabled(boolean value) {
        wheelBlockCreative = value;
    }

    public static boolean isWheelInvertEnabled() {
        return wheelInvert;
    }

    public static void setWheelInvertEnabled(boolean value) {
        wheelInvert = value;
    }

    public static boolean isWheelRepositionEnabled() {
        return wheelReposition;
    }

    public static void setWheelRepositionEnabled(boolean value) {
        wheelReposition = value;
    }

    public static int getWheelRepositionHoldMs() {
        return wheelRepositionHoldMs;
    }

    public static void setWheelRepositionHoldMs(int value) {
        wheelRepositionHoldMs = clamp(value, MIN_WHEEL_REPOSITION_HOLD_MS, MAX_WHEEL_REPOSITION_HOLD_MS);
    }
}
