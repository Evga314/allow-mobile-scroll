package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Releases sliders that missed their mouseReleased.
 *
 * <p>Vanilla, Sodium and YACL sliders keep an internal "dragging" flag. When
 * the release never reaches them (deferred click, cancelled press, cursor
 * teleport), the next tap anywhere still moves the last slider, usually to
 * its maximum.
 */
public final class Sliders {
    private Sliders() {
    }

    /** Boolean fields that hold a drag state in the slider implementations seen so far. */
    private static final String[] DRAG_FLAGS = {
            "dragging", "canChangeValue", "sliding", "selected",
            "isDragging", "held", "activeDrag", "mouseDown"
    };

    /** Drag-state fields of a slider class, looked up once. */
    private static final ClassValue<Field[]> FLAG_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (String name : DRAG_FLAGS) {
                    try {
                        Field f = c.getDeclaredField(name);
                        if (f.getType() == boolean.class) {
                            f.setAccessible(true);
                            fields.add(f);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return fields.toArray(new Field[0]);
        }
    };

    /**
     * Clears the drag state of every slider under {@code root} and ends the
     * slider part of the current gesture. Never dispatches a synthetic
     * {@code screen.mouseReleased}: on inventories that drops the held stack,
     * on vanilla menus it can replay as a second click.
     */
    public static void forceReleaseAll(GuiEventListener root) {
        try {
            release(root);
        } catch (Throwable ignored) {
        } finally {
            TouchState.nativeSliderHeld = false;
            TouchState.sliderGestureLocked = false;
        }
    }

    private static void release(GuiEventListener node) {
        if (node == null) {
            return;
        }
        if (isSliderLike(node)) {
            MouseButtonEvent pointer = DeferredClick.lastPointerEvent();
            if (pointer != null) {
                try {
                    node.mouseReleased(pointer);
                } catch (Throwable ignored) {
                }
            }
            for (Field f : FLAG_FIELDS.get(node.getClass())) {
                try {
                    f.setBoolean(node, false);
                } catch (Throwable ignored) {
                }
            }
        }
        if (node instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                release(child);
            }
        }
    }

    private static boolean isSliderLike(GuiEventListener node) {
        String name = Widgets.lowerName(node);
        return name.contains("slider")
                || (name.contains("yacl") && name.contains("controller"))
                || node instanceof AbstractSliderButton;
    }
}
