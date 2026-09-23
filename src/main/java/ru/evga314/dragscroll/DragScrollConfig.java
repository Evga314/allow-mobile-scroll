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

    private static final Pattern THRESHOLD_PATTERN = Pattern.compile("\\\"drag_threshold\\\"\\s*:\\s*(\\d+)");
    private static final Pattern INERTIA_PATTERN = Pattern.compile("\\\"inertia_strength\\\"\\s*:\\s*(\\d+)");
    private static final Pattern DEBUG_PATTERN = Pattern.compile("\"debug\"\\s*:\\s*(true|false)");
    private static final Pattern XAERO_PATTERN = Pattern.compile("\"xaero_world_map_slider\"\\s*:\\s*(true|false)");

    private static int dragThreshold = DEFAULT_DRAG_THRESHOLD;
    private static int inertiaStrength = DEFAULT_INERTIA_STRENGTH;
    private static boolean debug = false;
    private static boolean xaeroSlider = DEFAULT_XAERO_SLIDER;

    private DragScrollConfig() {
    }

    public static void load() {
        Path path = getPath();
        try {
            if (!Files.exists(path)) {
                save();
                return;
            }

            String json = Files.readString(path, StandardCharsets.UTF_8);
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
                debug = false;
            }

            Matcher xaeroMatcher = XAERO_PATTERN.matcher(json);
            if (xaeroMatcher.find()) {
                xaeroSlider = Boolean.parseBoolean(xaeroMatcher.group(1));
            } else {
                xaeroSlider = DEFAULT_XAERO_SLIDER;
            }

            // Rewrite so new keys appear in the file.
            save();
        } catch (Throwable ignored) {
            dragThreshold = DEFAULT_DRAG_THRESHOLD;
            inertiaStrength = DEFAULT_INERTIA_STRENGTH;
            xaeroSlider = DEFAULT_XAERO_SLIDER;
        }
    }

    public static void save() {
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    "{\n"
                            + "  \"drag_threshold\": " + dragThreshold + ",\n"
                            + "  \"inertia_strength\": " + inertiaStrength + ",\n"
                            + "  \"xaero_world_map_slider\": " + xaeroSlider + ",\n"
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

    public static boolean isXaeroSliderEnabled() {
        return xaeroSlider;
    }

    public static void setXaeroSliderEnabled(boolean value) {
        xaeroSlider = value;
    }

    private static Path getPath() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path neu = dir.resolve("allowmobilescroll.json");
        Path old = dir.resolve("dragscroll.json");
        if (!Files.exists(neu) && Files.exists(old)) {
            return old;
        }
        return neu;
    }
}
