package ru.evga314.dragscroll;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Custom zoom slider for Xaero's World Map.
 * Thumb position is the source of truth: finger Y maps to a log zoom in
 * [0.0625x, 50x]. Incremental wheel ticks are not used while dragging.
 */
public final class XaeroMapZoomOverlay {
    private static final Identifier SCROLLER =
            Identifier.parse("minecraft:container/creative_inventory/scroller");
    private static final int TRACK_W = 16;
    private static final int THUMB_W = 12;
    private static final int THUMB_H = 16;
    private static final double MIN_ZOOM = 0.0625;
    private static final double MAX_ZOOM = 50.0;

    private static boolean dragging;
    private static float visualT = 0.5f;
    private static double lastAppliedZoom = Double.NaN;
    private static double grabOffsetY;

    private XaeroMapZoomOverlay() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!isMapScreen(screen)) {
                return;
            }
            try {
                ScreenEvents.afterForeground(screen).register(XaeroMapZoomOverlay::afterExtract);
            } catch (Throwable t) {
                DragScrollClient.LOGGER.warn("Xaero zoom overlay render hook failed", t);
            }
        });
    }

    public static boolean isMapScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String name = DragScrollState.lowerName(screen);
        if (!name.contains("xaero")) {
            return false;
        }
        // Settings / config / profile screens also contain "worldmap" in the
        // class name. The zoom overlay is only for the actual map view.
        if (name.contains("setting") || name.contains("config")
                || name.contains("option") || name.contains("profile")
                || name.contains("menu")) {
            return false;
        }
        return name.contains("guimap")
                || name.contains("guiworldmap")
                || name.contains("mapscreen")
                || name.contains("worldmap");
    }

    public static int[] sliderBounds(Screen screen) {
        if (screen == null) {
            return null;
        }
        int sw = screen.width;
        int sh = screen.height;
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        int mapSize = Math.min(sw, sh);
        int mapY = (sh - mapSize) / 2;
        int trackX = sw - 58;
        int fullH = mapSize - 36;
        if (fullH < 40) {
            fullH = sh - 36;
            mapY = 0;
        }
        int trackH = Math.max(40, (int) Math.round(fullH * 0.85));
        int trackY = mapY + 18 + Math.max(0, (fullH - trackH) / 2);
        return new int[] {trackX, trackY, TRACK_W, trackH};
    }

    public static boolean isEnabled() {
        return DragScrollConfig.isXaeroSliderEnabled() && !ru.evga314.dragscroll.compat.SafeMode.bypass();
    }

    public static boolean isOverSlider(Screen screen, double x, double y) {
        if (!isEnabled()) {
            return false;
        }
        int[] b = sliderBounds(screen);
        if (b == null) {
            return false;
        }
        return x >= b[0] - 10 && x <= b[0] + b[2] + 10
                && y >= b[1] - 18 && y <= b[1] + b[3] + 18;
    }

    public static void beginDrag(Screen screen, double y) {
        if (!isEnabled()) {
            return;
        }
        dragging = true;
        grabOffsetY = 0.0;
        int[] b = sliderBounds(screen);
        if (b != null) {
            float travel = Math.max(1, b[3] - THUMB_H);
            double thumbCenterY = b[1] + visualT * travel + THUMB_H / 2.0;
            grabOffsetY = y - thumbCenterY;
        }
        applyTargetZoom(screen, zoomFromT(visualT));
    }

    public static void endDrag() {
        dragging = false;
        grabOffsetY = 0.0;
    }

    public static boolean isDragging() {
        return dragging;
    }

    public static void applyDrag(Screen screen, double y) {
        if (!dragging || screen == null) {
            return;
        }
        snapToY(screen, y);
    }

    private static void snapToY(Screen screen, double y) {
        int[] b = sliderBounds(screen);
        if (b == null) {
            return;
        }
        float travel = Math.max(1, b[3] - THUMB_H);
        double mappedY = y - grabOffsetY;
        float t = (float) ((mappedY - (b[1] + THUMB_H / 2.0)) / travel);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        visualT = t;
        applyTargetZoom(screen, zoomFromT(t));
    }

    private static double zoomFromT(float t) {
        double logMin = Math.log(MIN_ZOOM);
        double logMax = Math.log(MAX_ZOOM);
        return Math.exp(logMax + (logMin - logMax) * t);
    }

    private static float tFromZoom(double z) {
        if (z <= 0) {
            return visualT;
        }
        double logMin = Math.log(MIN_ZOOM);
        double logMax = Math.log(MAX_ZOOM);
        float t = (float) ((Math.log(z) - logMax) / (logMin - logMax));
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t;
    }

    private static void applyTargetZoom(Screen screen, double target) {
        if (target < MIN_ZOOM) target = MIN_ZOOM;
        if (target > MAX_ZOOM) target = MAX_ZOOM;
        if (!Double.isNaN(lastAppliedZoom) && Math.abs(lastAppliedZoom - target) < 1e-6) {
            return;
        }

        boolean wrote = writeZoom(screen, target);
        if (!wrote) {
            double current = readZoomValue(screen);
            if (Double.isNaN(current) || current <= 0) {
                current = Double.isNaN(lastAppliedZoom) ? zoomFromT(visualT) : lastAppliedZoom;
            }
            double scrollY = Math.log(target / Math.max(current, MIN_ZOOM)) / Math.log(1.2);
            fireWheel(screen, scrollY);
        }
        lastAppliedZoom = target;
    }

    private static void fireWheel(Screen screen, double scrollY) {
        if (scrollY == 0.0 || Double.isNaN(scrollY)) {
            return;
        }
        try {
            double cx = screen.width / 2.0;
            double cy = screen.height / 2.0;
            screen.mouseScrolled(cx, cy, 0.0, scrollY);
        } catch (Throwable ignored) {
        }
    }

    private static void afterExtract(Screen screen, GuiGraphicsExtractor graphics,
                                     int mouseX, int mouseY, float tickProgress) {
        if (!isEnabled()) {
            return;
        }
        int[] b = sliderBounds(screen);
        if (b == null) {
            return;
        }
        int x = b[0];
        int y = b[1];
        int w = b[2];
        int h = b[3];

        // Even TRACK_W: geometric center is x + w/2. Draw a 2px line
        // at [centerX-1, centerX+1) so it is symmetric, no extra right pixel.
        int centerX = x + w / 2;
        // 30% opaque track (0x4C ≈ 76/255).
        graphics.fill(centerX - 1, y, centerX + 1, y + h, 0x4C8B8B8B);

        if (!dragging) {
            double z = readZoomValue(screen);
            if (!Double.isNaN(z) && z > 0) {
                visualT = tFromZoom(z);
                lastAppliedZoom = z;
            }
        }
        int thumbX = centerX - THUMB_W / 2;
        int thumbY = y + Math.round((h - THUMB_H) * visualT);
        // Thumb stays fully opaque. Prefer the vanilla creative scroller sprite.
        if (!blitScroller(graphics, thumbX, thumbY, THUMB_W, THUMB_H)) {
            graphics.fill(thumbX, thumbY, thumbX + THUMB_W, thumbY + THUMB_H, 0xFFC6C6C6);
            graphics.fill(thumbX, thumbY, thumbX + THUMB_W, thumbY + 1, 0xFFFFFFFF);
            graphics.fill(thumbX, thumbY, thumbX + 1, thumbY + THUMB_H, 0xFFFFFFFF);
            graphics.fill(thumbX, thumbY + THUMB_H - 1, thumbX + THUMB_W, thumbY + THUMB_H, 0xFF555555);
            graphics.fill(thumbX + THUMB_W - 1, thumbY, thumbX + THUMB_W, thumbY + THUMB_H, 0xFF555555);
        }

        var font = Minecraft.getInstance().font;
        graphics.centeredText(font, Component.literal("+"), x + w / 2, y - 12, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("-"), x + w / 2, y + h + 2, 0xFFFFFFFF);
    }

    private static boolean blitScroller(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        try {
            java.lang.reflect.Method m = graphics.getClass().getMethod(
                    "blitSprite", Identifier.class, int.class, int.class, int.class, int.class);
            m.invoke(graphics, SCROLLER, x, y, w, h);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static double readZoomValue(Object obj) {
        Object v = readZoom(obj);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static boolean writeZoom(Object obj, double target) {
        boolean any = false;
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            for (String name : new String[] {
                    "cameraZoom", "zoom", "scale", "mapScale", "cameraScale", "destScale", "userScale"}) {
                try {
                    java.lang.reflect.Field f = walk.getDeclaredField(name);
                    f.setAccessible(true);
                    Object cur = f.get(obj);
                    if (cur instanceof Double) {
                        f.set(obj, target);
                        any = true;
                    } else if (cur instanceof Float) {
                        f.set(obj, (float) target);
                        any = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (String name : new String[] {"changeZoom", "setZoom", "setScale", "setCameraZoom"}) {
                try {
                    java.lang.reflect.Method m = walk.getDeclaredMethod(name, double.class);
                    m.setAccessible(true);
                    m.invoke(obj, target);
                    any = true;
                } catch (Throwable ignored) {
                }
                try {
                    java.lang.reflect.Method m = walk.getDeclaredMethod(name, float.class);
                    m.setAccessible(true);
                    m.invoke(obj, (float) target);
                    any = true;
                } catch (Throwable ignored) {
                }
            }
        }
        return any;
    }

    private static Object readZoom(Object obj) {
        for (Class<?> walk = obj.getClass(); walk != null && walk != Object.class; walk = walk.getSuperclass()) {
            for (String name : new String[] {
                    "cameraZoom", "zoom", "scale", "mapScale", "cameraScale", "destScale", "userScale"}) {
                try {
                    java.lang.reflect.Field f = walk.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof Number n) {
                        double d = n.doubleValue();
                        if (d > 0 && d <= 64) {
                            return v;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
