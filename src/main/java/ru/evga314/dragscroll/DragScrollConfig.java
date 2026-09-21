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

    private static final Pattern THRESHOLD_PATTERN = Pattern.compile("\\\"drag_threshold\\\"\\s*:\\s*(\\d+)");
    private static int dragThreshold = DEFAULT_DRAG_THRESHOLD;

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
                save();
            }
        } catch (Throwable ignored) {
            dragThreshold = DEFAULT_DRAG_THRESHOLD;
        }
    }

    public static void save() {
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    "{\n  \"drag_threshold\": " + dragThreshold + "\n}\n",
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

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("dragscroll.json");
    }
}
