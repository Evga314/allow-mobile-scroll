package ru.evga314.dragscroll.modmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import ru.evga314.dragscroll.DragScrollConfig;
import ru.evga314.dragscroll.MouseWheelEmulator;

/**
 * Setup screen for the mouse-wheel emulator.
 *
 * <p>Top-left: three sliders — position (X and Y of the wheel centre), size and
 * opacity. Top-right: Save, Reset and Exit buttons. The wheel itself is drawn
 * live at the values being edited, so the user sees exactly where it will land.
 */
public final class WheelSetupScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int SLIDER_W = 150;
    private static final int SLIDER_H = 20;
    private static final int ROW_GAP = 24;
    private static final int BTN_W = 80;
    private static final int BTN_H = 20;

    private final Screen parent;

    // Edited values (the wheel is previewed from these, not from the config).
    private int posX;
    private int posY;
    private int size;
    private int opacity;
    private int speed;

    private PosSlider posXSlider;
    private PosSlider posYSlider;

    public WheelSetupScreen(Screen parent) {
        super(Component.translatable("dragscroll.wheel.setup.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Seed from config, resolving "unset" to the actual default position.
        this.size = DragScrollConfig.getWheelSize();
        this.opacity = DragScrollConfig.getWheelOpacity();
        this.speed = DragScrollConfig.getWheelSpeed();
        int[] def = defaultCenter(this.size);
        int cx = DragScrollConfig.getWheelPosX();
        int cy = DragScrollConfig.getWheelPosY();
        this.posX = cx == DragScrollConfig.WHEEL_POS_UNSET ? def[0] : cx;
        this.posY = cy == DragScrollConfig.WHEEL_POS_UNSET ? def[1] : cy;
        clampPos();

        int x = MARGIN;
        int y = MARGIN + 12;

        this.posXSlider = new PosSlider(x, y, Axis.X);
        this.addRenderableWidget(this.posXSlider);
        y += ROW_GAP;

        this.posYSlider = new PosSlider(x, y, Axis.Y);
        this.addRenderableWidget(this.posYSlider);
        y += ROW_GAP;

        this.addRenderableWidget(new SizeSlider(x, y));
        y += ROW_GAP;

        this.addRenderableWidget(new OpacitySlider(x, y));
        y += ROW_GAP;

        this.addRenderableWidget(new SpeedSlider(x, y));

        // Top-right buttons.
        int bx = this.width - MARGIN - BTN_W;
        int by = MARGIN;
        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.wheel.setup.save"),
                b -> this.saveAndClose()).pos(bx, by).size(BTN_W, BTN_H).build());
        by += BTN_H + 4;
        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.wheel.setup.reset"),
                b -> this.resetToDefaults()).pos(bx, by).size(BTN_W, BTN_H).build());
        by += BTN_H + 4;
        this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_CANCEL,
                b -> this.onClose()).pos(bx, by).size(BTN_W, BTN_H).build());
    }

    private int[] defaultCenter(int sizePct) {
        // Mirror MouseWheelEmulator.bounds() default: right side, vertically centered.
        int w = MouseWheelEmulator.widthForSize(sizePct);
        int cx = this.width - MouseWheelEmulator.defaultRightMargin() - w / 2;
        int cy = this.height / 2;
        return new int[] {cx, cy};
    }

    private void clampPos() {
        int[] b = previewBounds();
        int w = b[2];
        int h = b[3];
        this.posX = Math.max(w / 2, Math.min(this.posX, this.width - w / 2));
        this.posY = Math.max(h / 2, Math.min(this.posY, this.height - h / 2));
    }

    /** Wheel rectangle from the values being edited: {@code [x, y, w, h]}. */
    private int[] previewBounds() {
        int w = MouseWheelEmulator.widthForSize(this.size);
        int h = MouseWheelEmulator.heightForSize(this.size);
        int x = this.posX - w / 2;
        int y = this.posY - h / 2;
        x = Math.max(0, Math.min(x, this.width - w));
        y = Math.max(0, Math.min(y, this.height - h));
        return new int[] {x, y, w, h};
    }

    private void resetToDefaults() {
        this.size = DragScrollConfig.DEFAULT_WHEEL_SIZE;
        this.opacity = DragScrollConfig.DEFAULT_WHEEL_OPACITY;
        this.speed = DragScrollConfig.DEFAULT_WHEEL_SPEED;
        int[] def = defaultCenter(this.size);
        this.posX = def[0];
        this.posY = def[1];
        clampPos();
        this.clearWidgets();
        this.init();
    }

    private void saveAndClose() {
        DragScrollConfig.setWheelPos(this.posX, this.posY);
        DragScrollConfig.setWheelSize(this.size);
        DragScrollConfig.setWheelOpacity(this.opacity);
        DragScrollConfig.setWheelSpeed(this.speed);
        DragScrollConfig.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(this.font, this.title, MARGIN, MARGIN, 0xFFFFFFFF, false);
        // Live preview of the wheel at the edited values.
        int[] b = previewBounds();
        MouseWheelEmulator.drawWheel(graphics, b[0], b[1], b[2], b[3], this.opacity, 0.0);
    }

    // --------------------------------------------------------------- sliders

    private enum Axis { X, Y }

    private final class PosSlider extends AbstractSliderButton {
        private final Axis axis;

        private PosSlider(int x, int y, Axis axis) {
            super(x, y, SLIDER_W, SLIDER_H, CommonComponents.EMPTY, 0.0);
            this.axis = axis;
            this.value = toValue();
            this.updateMessage();
        }

        private double toValue() {
            int max = this.axis == Axis.X ? WheelSetupScreen.this.width : WheelSetupScreen.this.height;
            int cur = this.axis == Axis.X ? WheelSetupScreen.this.posX : WheelSetupScreen.this.posY;
            return max <= 0 ? 0.0 : (double) cur / max;
        }

        @Override
        protected void updateMessage() {
            String key = this.axis == Axis.X
                    ? "dragscroll.wheel.setup.pos_x" : "dragscroll.wheel.setup.pos_y";
            int cur = this.axis == Axis.X ? WheelSetupScreen.this.posX : WheelSetupScreen.this.posY;
            this.setMessage(Component.translatable(key, cur));
        }

        @Override
        protected void applyValue() {
            int max = this.axis == Axis.X ? WheelSetupScreen.this.width : WheelSetupScreen.this.height;
            int mapped = (int) Math.round(this.value * max);
            if (this.axis == Axis.X) {
                WheelSetupScreen.this.posX = mapped;
            } else {
                WheelSetupScreen.this.posY = mapped;
            }
            WheelSetupScreen.this.clampPos();
        }
    }

    private final class SizeSlider extends AbstractSliderButton {
        private SizeSlider(int x, int y) {
            super(x, y, SLIDER_W, SLIDER_H, CommonComponents.EMPTY, 0.0);
            this.value = toValue();
            this.updateMessage();
        }

        private double toValue() {
            int range = DragScrollConfig.MAX_WHEEL_SIZE - DragScrollConfig.MIN_WHEEL_SIZE;
            return range <= 0 ? 0.0
                    : (double) (WheelSetupScreen.this.size - DragScrollConfig.MIN_WHEEL_SIZE) / range;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("dragscroll.wheel.setup.size", WheelSetupScreen.this.size));
        }

        @Override
        protected void applyValue() {
            int range = DragScrollConfig.MAX_WHEEL_SIZE - DragScrollConfig.MIN_WHEEL_SIZE;
            WheelSetupScreen.this.size = DragScrollConfig.MIN_WHEEL_SIZE + (int) Math.round(this.value * range);
            WheelSetupScreen.this.clampPos();
        }
    }

    private final class OpacitySlider extends AbstractSliderButton {
        private OpacitySlider(int x, int y) {
            super(x, y, SLIDER_W, SLIDER_H, CommonComponents.EMPTY, 0.0);
            this.value = toValue();
            this.updateMessage();
        }

        private double toValue() {
            int range = DragScrollConfig.MAX_WHEEL_OPACITY - DragScrollConfig.MIN_WHEEL_OPACITY;
            return range <= 0 ? 0.0
                    : (double) (WheelSetupScreen.this.opacity - DragScrollConfig.MIN_WHEEL_OPACITY) / range;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable("dragscroll.wheel.setup.opacity", WheelSetupScreen.this.opacity));
        }

        @Override
        protected void applyValue() {
            int range = DragScrollConfig.MAX_WHEEL_OPACITY - DragScrollConfig.MIN_WHEEL_OPACITY;
            WheelSetupScreen.this.opacity =
                    DragScrollConfig.MIN_WHEEL_OPACITY + (int) Math.round(this.value * range);
        }
    }

    private final class SpeedSlider extends AbstractSliderButton {
        private SpeedSlider(int x, int y) {
            super(x, y, SLIDER_W, SLIDER_H, CommonComponents.EMPTY, 0.0);
            this.value = toValue();
            this.updateMessage();
        }

        private double toValue() {
            int range = DragScrollConfig.MAX_WHEEL_SPEED - DragScrollConfig.MIN_WHEEL_SPEED;
            return range <= 0 ? 0.0
                    : (double) (WheelSetupScreen.this.speed - DragScrollConfig.MIN_WHEEL_SPEED) / range;
        }

        @Override
        protected void updateMessage() {
            // Show as a multiplier, e.g. "1.5×".
            double mult = WheelSetupScreen.this.speed / 100.0;
            this.setMessage(Component.translatable("dragscroll.wheel.setup.speed",
                    String.format(java.util.Locale.ROOT, "%.1f", mult)));
        }

        @Override
        protected void applyValue() {
            int range = DragScrollConfig.MAX_WHEEL_SPEED - DragScrollConfig.MIN_WHEEL_SPEED;
            WheelSetupScreen.this.speed =
                    DragScrollConfig.MIN_WHEEL_SPEED + (int) Math.round(this.value * range);
        }
    }
}
