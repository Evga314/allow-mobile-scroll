package ru.evga314.dragscroll;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;

public final class DragScrollConfig {
    public static final int DEFAULT_DRAG_THRESHOLD = 4;
    public static final int MIN_DRAG_THRESHOLD = 1;
    public static final int MAX_DRAG_THRESHOLD = 50;

    /**
     * Inertia strength as a percentage of the measured fling velocity.
     * 100 = previous full strength; default 70 is 30% softer.
     */
    public static final int DEFAULT_INERTIA_STRENGTH = 70;
    public static final int MIN_INERTIA_STRENGTH = 0;
    public static final int MAX_INERTIA_STRENGTH = 200;

    public static final boolean DEFAULT_XAERO_SLIDER = true;
    public static final boolean DEFAULT_ENABLED = true;

    /** Debug logging. Off by default — generates huge logs. */
    public static final boolean DEFAULT_DEBUG = false;

    /** Wider (2x, to the right) scrollbar in the villager trading screen. */
    public static final boolean DEFAULT_MERCHANT_WIDE_BAR = true;

    /**
     * Disable the mouse-wheel emulator inside the creative inventory. The vanilla
     * creative screen scrolls its item grid on mouseScrolled regardless of cursor
     * position, so the wheel emulator would scroll the grid from anywhere. When on
     * (default), the emulator does nothing in the creative inventory; the item list
     * is still scrollable with the scrollbar.
     */
    public static final boolean DEFAULT_WHEEL_BLOCK_CREATIVE = true;

    /** Invert vertical direction of the on-screen mouse-wheel emulator. */
    public static final boolean DEFAULT_WHEEL_INVERT = false;

    /**
     * Mouse-wheel emulator: a small on-screen wheel that turns a finger drag
     * into wheel scrolling. Position is the CENTER in GUI-scaled pixels; -1 on
     * an axis means "use the default for the current screen" (right side,
     * vertically centered).
     */
    public static final int WHEEL_POS_UNSET = -1;
    public static final int DEFAULT_WHEEL_SIZE = 100;
    public static final int MIN_WHEEL_SIZE = 50;
    public static final int MAX_WHEEL_SIZE = 300;
    public static final int DEFAULT_WHEEL_OPACITY = 70;
    public static final int MIN_WHEEL_OPACITY = 10;
    public static final int MAX_WHEEL_OPACITY = 100;

    /**
     * Wheel scroll speed as a percentage of the base speed: 100 = 1.0×,
     * range 10 (0.1×) … 1000 (10×).
     */
    public static final int DEFAULT_WHEEL_SPEED = 100;
    public static final int MIN_WHEEL_SPEED = 10;
    public static final int MAX_WHEEL_SPEED = 1000;

    private static final Pattern THRESHOLD_PATTERN = Pattern.compile("\\\"drag_threshold\\\"\\s*:\\s*(\\d+)");
    private static final Pattern INERTIA_PATTERN = Pattern.compile("\\\"inertia_strength\\\"\\s*:\\s*(\\d+)");
    private static final Pattern DEBUG_PATTERN = Pattern.compile("\"debug\"\\s*:\\s*(true|false)");
    private static final Pattern XAERO_PATTERN = Pattern.compile("\"xaero_world_map_slider\"\\s*:\\s*(true|false)");
    private static final Pattern ENABLED_PATTERN = Pattern.compile("\"enabled\"\\s*:\\s*(true|false)");
    private static final Pattern MERCHANT_BAR_PATTERN = Pattern.compile("\"merchant_wide_bar\"\\s*:\\s*(true|false)");
    private static final Pattern WHEEL_POS_X_PATTERN = Pattern.compile("\"wheel_pos_x\"\\s*:\\s*(-?\\d+)");
    private static final Pattern WHEEL_POS_Y_PATTERN = Pattern.compile("\"wheel_pos_y\"\\s*:\\s*(-?\\d+)");
    private static final Pattern WHEEL_SIZE_PATTERN = Pattern.compile("\"wheel_size\"\\s*:\\s*(\\d+)");
    private static final Pattern WHEEL_OPACITY_PATTERN = Pattern.compile("\"wheel_opacity\"\\s*:\\s*(\\d+)");
    private static final Pattern WHEEL_SPEED_PATTERN = Pattern.compile("\"wheel_speed\"\\s*:\\s*(\\d+)");
    private static final Pattern WHEEL_BLOCK_CREATIVE_PATTERN = Pattern.compile("\"wheel_block_creative\"\\s*:\\s*(true|false)");
    private static final Pattern WHEEL_INVERT_PATTERN = Pattern.compile("\"wheel_invert\"\\s*:\\s*(true|false)");

    private static int dragThreshold = DEFAULT_DRAG_THRESHOLD;
    private static int inertiaStrength = DEFAULT_INERTIA_STRENGTH;
    private static boolean debug = DEFAULT_DEBUG;
    private static boolean xaeroSlider = DEFAULT_XAERO_SLIDER;
    private static boolean merchantWideBar = DEFAULT_MERCHANT_WIDE_BAR;
    private static int wheelPosX = WHEEL_POS_UNSET;
    private static int wheelPosY = WHEEL_POS_UNSET;
    private static int wheelSize = DEFAULT_WHEEL_SIZE;
    private static int wheelOpacity = DEFAULT_WHEEL_OPACITY;
    private static int wheelSpeed = DEFAULT_WHEEL_SPEED;
    private static boolean wheelBlockCreative = DEFAULT_WHEEL_BLOCK_CREATIVE;
    private static boolean wheelInvert = DEFAULT_WHEEL_INVERT;
    /**
     * false = vanilla input. Read by SafeMode before the game starts, so a
     * disabled mod applies no mixins at all after the next restart.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    private DragScrollConfig() {
    }

    public static void load() {
        Path path = getPath();
        Path legacy = getLegacyPath();
        try {
            // Read the pre-rename file once if the new one does not exist yet;
            // the save() below then writes the new file. Previously the
            // legacy file stayed in use forever and the new name never appeared.
            Path source = Files.exists(path) ? path : (Files.exists(legacy) ? legacy : null);
            if (source == null) {
                save();
                return;
            }

            String json = Files.readString(source, StandardCharsets.UTF_8);
            Matcher matcher = THRESHOLD_PATTERN.matcher(json);
            if (matcher.find()) {
                setDragThreshold(Integer.parseInt(matcher.group(1)));
            } else {
                dragThreshold = DEFAULT_DRAG_THRESHOLD;
            }

            Matcher inertiaMatcher = INERTIA_PATTERN.matcher(json);
            if (inertiaMatcher.find()) {
                setInertiaStrength(Integer.parseInt(inertiaMatcher.group(1)));
            } else {
                inertiaStrength = DEFAULT_INERTIA_STRENGTH;
            }

            Matcher debugMatcher = DEBUG_PATTERN.matcher(json);
            if (debugMatcher.find()) {
                debug = Boolean.parseBoolean(debugMatcher.group(1));
            } else {
                debug = DEFAULT_DEBUG;
            }

            Matcher xaeroMatcher = XAERO_PATTERN.matcher(json);
            if (xaeroMatcher.find()) {
                xaeroSlider = Boolean.parseBoolean(xaeroMatcher.group(1));
            } else {
                xaeroSlider = DEFAULT_XAERO_SLIDER;
            }

            Matcher enabledMatcher = ENABLED_PATTERN.matcher(json);
            enabled = enabledMatcher.find() ? Boolean.parseBoolean(enabledMatcher.group(1)) : DEFAULT_ENABLED;

            Matcher merchantBarMatcher = MERCHANT_BAR_PATTERN.matcher(json);
            merchantWideBar = merchantBarMatcher.find()
                    ? Boolean.parseBoolean(merchantBarMatcher.group(1)) : DEFAULT_MERCHANT_WIDE_BAR;

            Matcher wheelPosXMatcher = WHEEL_POS_X_PATTERN.matcher(json);
            wheelPosX = wheelPosXMatcher.find() ? Integer.parseInt(wheelPosXMatcher.group(1)) : WHEEL_POS_UNSET;

            Matcher wheelPosYMatcher = WHEEL_POS_Y_PATTERN.matcher(json);
            wheelPosY = wheelPosYMatcher.find() ? Integer.parseInt(wheelPosYMatcher.group(1)) : WHEEL_POS_UNSET;

            Matcher wheelSizeMatcher = WHEEL_SIZE_PATTERN.matcher(json);
            if (wheelSizeMatcher.find()) {
                setWheelSize(Integer.parseInt(wheelSizeMatcher.group(1)));
            } else {
                wheelSize = DEFAULT_WHEEL_SIZE;
            }

            Matcher wheelOpacityMatcher = WHEEL_OPACITY_PATTERN.matcher(json);
            if (wheelOpacityMatcher.find()) {
                setWheelOpacity(Integer.parseInt(wheelOpacityMatcher.group(1)));
            } else {
                wheelOpacity = DEFAULT_WHEEL_OPACITY;
            }

            Matcher wheelSpeedMatcher = WHEEL_SPEED_PATTERN.matcher(json);
            if (wheelSpeedMatcher.find()) {
                setWheelSpeed(Integer.parseInt(wheelSpeedMatcher.group(1)));
            } else {
                wheelSpeed = DEFAULT_WHEEL_SPEED;
            }

            Matcher wheelBlockCreativeMatcher = WHEEL_BLOCK_CREATIVE_PATTERN.matcher(json);
            wheelBlockCreative = wheelBlockCreativeMatcher.find()
                    ? Boolean.parseBoolean(wheelBlockCreativeMatcher.group(1)) : DEFAULT_WHEEL_BLOCK_CREATIVE;

            Matcher wheelInvertMatcher = WHEEL_INVERT_PATTERN.matcher(json);
            wheelInvert = wheelInvertMatcher.find()
                    ? Boolean.parseBoolean(wheelInvertMatcher.group(1)) : DEFAULT_WHEEL_INVERT;

            // Rewrite so new keys appear in the file.
            save();
        } catch (Throwable ignored) {
            dragThreshold = DEFAULT_DRAG_THRESHOLD;
            inertiaStrength = DEFAULT_INERTIA_STRENGTH;
            xaeroSlider = DEFAULT_XAERO_SLIDER;
            debug = DEFAULT_DEBUG;
            merchantWideBar = DEFAULT_MERCHANT_WIDE_BAR;
            wheelPosX = WHEEL_POS_UNSET;
            wheelPosY = WHEEL_POS_UNSET;
            wheelSize = DEFAULT_WHEEL_SIZE;
            wheelOpacity = DEFAULT_WHEEL_OPACITY;
            wheelSpeed = DEFAULT_WHEEL_SPEED;
            wheelBlockCreative = DEFAULT_WHEEL_BLOCK_CREATIVE;
            wheelInvert = DEFAULT_WHEEL_INVERT;
        }
    }

    public static void save() {
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    "{\n"
                            + "  \"enabled\": " + enabled + ",\n"
                            + "  \"drag_threshold\": " + dragThreshold + ",\n"
                            + "  \"inertia_strength\": " + inertiaStrength + ",\n"
                            + "  \"xaero_world_map_slider\": " + xaeroSlider + ",\n"
                            + "  \"merchant_wide_bar\": " + merchantWideBar + ",\n"
                            + "  \"wheel_pos_x\": " + wheelPosX + ",\n"
                            + "  \"wheel_pos_y\": " + wheelPosY + ",\n"
                            + "  \"wheel_size\": " + wheelSize + ",\n"
                            + "  \"wheel_opacity\": " + wheelOpacity + ",\n"
                            + "  \"wheel_speed\": " + wheelSpeed + ",\n"
                            + "  \"wheel_block_creative\": " + wheelBlockCreative + ",\n"
                            + "  \"wheel_invert\": " + wheelInvert + ",\n"
                            + "  \"debug\": " + debug + "\n"
                            + "}\n",
                    StandardCharsets.UTF_8
            );
        } catch (IOException ignored) {
        }
    }

    public static int getDragThreshold() {
        return dragThreshold;
    }

    public static void setDragThreshold(int value) {
        dragThreshold = Math.max(MIN_DRAG_THRESHOLD, Math.min(MAX_DRAG_THRESHOLD, value));
    }

    public static int getInertiaStrength() {
        return inertiaStrength;
    }

    public static void setInertiaStrength(int value) {
        inertiaStrength = Math.max(MIN_INERTIA_STRENGTH, Math.min(MAX_INERTIA_STRENGTH, value));
    }

    /** Multiplier applied to fling velocity (0.0 … 2.0). Default 0.70. */
    public static double getInertiaStrengthMultiplier() {
        return inertiaStrength / 100.0;
    }

    public static boolean isDebugEnabled() {
        return debug;
    }

    public static void setDebugEnabled(boolean value) {
        debug = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
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

    public static int getWheelPosX() {
        return wheelPosX;
    }

    public static int getWheelPosY() {
        return wheelPosY;
    }

    public static void setWheelPos(int x, int y) {
        wheelPosX = x;
        wheelPosY = y;
    }

    public static int getWheelSize() {
        return wheelSize;
    }

    public static void setWheelSize(int value) {
        wheelSize = Math.max(MIN_WHEEL_SIZE, Math.min(MAX_WHEEL_SIZE, value));
    }

    public static int getWheelOpacity() {
        return wheelOpacity;
    }

    public static void setWheelOpacity(int value) {
        wheelOpacity = Math.max(MIN_WHEEL_OPACITY, Math.min(MAX_WHEEL_OPACITY, value));
    }

    public static int getWheelSpeed() {
        return wheelSpeed;
    }

    public static void setWheelSpeed(int value) {
        wheelSpeed = Math.max(MIN_WHEEL_SPEED, Math.min(MAX_WHEEL_SPEED, value));
    }

    /**
     * Wheel speed as a multiplier (e.g. 1.5 for 150%). The config stores it as
     * an integer percentage (150), this returns the actual factor.
     */
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

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("allowmobilescroll.json");
    }

    private static Path getLegacyPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("dragscroll.json");
    }
}
