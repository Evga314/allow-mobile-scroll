package ru.evga314.dragscroll.compat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.evga314.dragscroll.DragScrollClient;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.loader.SafeMode;
import ru.evga314.dragscroll.touch.ScreenKind;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Zoom slider drawn over Xaero's World Map. A finger cannot use the mouse
 * wheel, and a pinch never reaches the game. The thumb position is the source
 * of truth: its place on the track maps to a logarithmic zoom in
 * [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}].
 */
public final class XaeroMapZoomOverlay {
    private XaeroMapZoomOverlay() {
    }

    private static final int TRACK_W = 16;
    private static final int THUMB_W = 12;
    private static final int THUMB_H = 16;
    private static final double MIN_ZOOM = 0.0625;
    private static final double MAX_ZOOM = 50.0;
    /** Zoom factor of one of Xaero's own wheel notches. */
    private static final double WHEEL_ZOOM_STEP = 1.2;

    /** Zoom fields of Xaero's map screen, in the order they are tried. */
    private static final String[] ZOOM_FIELDS = {
            "cameraZoom", "zoom", "scale", "mapScale", "cameraScale", "destScale", "userScale"};
    private static final String[] ZOOM_SETTERS = {"changeZoom", "setZoom", "setScale", "setCameraZoom"};

    private static boolean dragging;
    private static float visualT = 0.5f;
    private static double lastAppliedZoom = Double.NaN;
    private static double grabOffsetY;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!isMapScreen(screen)) {
                return;
            }
            try {
                ScreenEvents.afterForeground(screen).register(XaeroMapZoomOverlay::render);
            } catch (Throwable t) {
                DragScrollClient.LOGGER.warn("Xaero zoom overlay render hook failed", t);
            }
        });
    }

    public static boolean isMapScreen(Screen screen) {
        return ScreenKind.of(screen).xaeroMap;
    }

    public static boolean isEnabled() {
        return DragScrollConfig.isXaeroSliderEnabled() && !SafeMode.bypass();
    }

    /** {x, y, width, height} of the track, right of the map. */
    private static int[] sliderBounds(Screen screen) {
        if (screen == null || screen.width <= 0 || screen.height <= 0) {
            return null;
        }
        int mapSize = Math.min(screen.width, screen.height);
        int mapY = (screen.height - mapSize) / 2;
        int fullH = mapSize - 36;
        if (fullH < 40) {
            fullH = screen.height - 36;
            mapY = 0;
        }
        int trackH = Math.max(40, (int) Math.round(fullH * 0.85));
        int trackY = mapY + 18 + Math.max(0, (fullH - trackH) / 2);
        return new int[] {screen.width - 58, trackY, TRACK_W, trackH};
    }

    /** The finger is on the slider (with a margin for the "+" / "-" labels). */
    public static boolean isOverSlider(Screen screen, double x, double y) {
        if (!isEnabled()) {
            return false;
        }
        int[] b = sliderBounds(screen);
        return b != null && x >= b[0] - 10 && x <= b[0] + b[2] + 10
                && y >= b[1] - 18 && y <= b[1] + b[3] + 18;
    }

    public static boolean isDragging() {
        return dragging;
    }

    /** Grabs the thumb where the finger is, without moving it. */
    public static void beginDrag(Screen screen, double y) {
        if (!isEnabled()) {
            return;
        }
        dragging = true;
        grabOffsetY = 0.0;
        int[] b = sliderBounds(screen);
        if (b != null) {
            float travel = Math.max(1, b[3] - THUMB_H);
            grabOffsetY = y - (b[1] + visualT * travel + THUMB_H / 2.0);
        }
        applyZoom(screen, zoomFromT(visualT));
    }

    public static void endDrag() {
        dragging = false;
        grabOffsetY = 0.0;
    }

    public static void applyDrag(Screen screen, double y) {
        if (!dragging || screen == null) {
            return;
        }
        int[] b = sliderBounds(screen);
        if (b == null) {
            return;
        }
        float travel = Math.max(1, b[3] - THUMB_H);
        float t = (float) ((y - grabOffsetY - (b[1] + THUMB_H / 2.0)) / travel);
        visualT = Math.max(0f, Math.min(1f, t));
        applyZoom(screen, zoomFromT(visualT));
    }

    // =====================================================================
    // Zoom
    // =====================================================================

    /** t = 0 at the top (maximum zoom), 1 at the bottom (minimum zoom). */
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
        return Math.max(0f, Math.min(1f, t));
    }

    private static void applyZoom(Screen screen, double target) {
        target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, target));
        if (!Double.isNaN(lastAppliedZoom) && Math.abs(lastAppliedZoom - target) < 1e-6) {
            return;
        }
        if (!writeZoom(screen, target)) {
            // No zoom field or setter: approximate with Xaero's wheel steps.
            double current = readZoom(screen);
            if (Double.isNaN(current) || current <= 0) {
                current = Double.isNaN(lastAppliedZoom) ? zoomFromT(visualT) : lastAppliedZoom;
            }
            double notches = Math.log(target / Math.max(current, MIN_ZOOM)) / Math.log(WHEEL_ZOOM_STEP);
            if (notches != 0.0 && !Double.isNaN(notches)) {
                try {
                    screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0.0, notches);
                } catch (Throwable ignored) {
                }
            }
        }
        lastAppliedZoom = target;
    }

    /** First plausible zoom value (0 < z <= 64) among the known fields, or NaN. */
    private static double readZoom(Object map) {
        for (Class<?> c = map.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : ZOOM_FIELDS) {
                Field f = Reflect.declaredField(c, name);
                if (f == null) {
                    continue;
                }
                try {
                    if (f.get(map) instanceof Number n && n.doubleValue() > 0 && n.doubleValue() <= 64) {
                        return n.doubleValue();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return Double.NaN;
    }

    /** Writes every known zoom field and calls every known zoom setter; true when any existed. */
    private static boolean writeZoom(Object map, double target) {
        boolean any = false;
        for (Class<?> c = map.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : ZOOM_FIELDS) {
                Field f = Reflect.declaredField(c, name);
                if (f == null) {
                    continue;
                }
                try {
                    Object current = f.get(map);
                    if (current instanceof Double) {
                        f.set(map, target);
                        any = true;
                    } else if (current instanceof Float) {
                        f.set(map, (float) target);
                        any = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (String name : ZOOM_SETTERS) {
                Method d = Reflect.declaredMethodIn(c, name, double.class);
                if (d != null) {
                    try {
                        d.invoke(map, target);
                        any = true;
                    } catch (Throwable ignored) {
                    }
                }
                Method f = Reflect.declaredMethodIn(c, name, float.class);
                if (f != null) {
                    try {
                        f.invoke(map, (float) target);
                        any = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return any;
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    private static void render(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickProgress) {
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

        // 2 px line centred on the even-width track; 30 % opaque.
        int centerX = x + w / 2;
        graphics.fill(centerX - 1, y, centerX + 1, y + h, 0x4C8B8B8B);

        if (!dragging) {
            double z = readZoom(screen);
            if (!Double.isNaN(z)) {
                visualT = tFromZoom(z);
                lastAppliedZoom = z;
            }
        }
        int thumbX = centerX - THUMB_W / 2;
        int thumbY = y + Math.round((h - THUMB_H) * visualT);
        graphics.fill(thumbX, thumbY, thumbX + THUMB_W, thumbY + THUMB_H, 0xFFC6C6C6);
        graphics.fill(thumbX, thumbY, thumbX + THUMB_W, thumbY + 1, 0xFFFFFFFF);
        graphics.fill(thumbX, thumbY, thumbX + 1, thumbY + THUMB_H, 0xFFFFFFFF);
        graphics.fill(thumbX, thumbY + THUMB_H - 1, thumbX + THUMB_W, thumbY + THUMB_H, 0xFF555555);
        graphics.fill(thumbX + THUMB_W - 1, thumbY, thumbX + THUMB_W, thumbY + THUMB_H, 0xFF555555);

        var font = Minecraft.getInstance().font;
        graphics.centeredText(font, Component.literal("+"), centerX, y - 12, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("-"), centerX, y + h + 2, 0xFFFFFFFF);
    }
}
