package ru.evga314.dragscroll.modmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import ru.evga314.dragscroll.DragScrollConfig;
import ru.evga314.dragscroll.MouseWheelEmulator;

/**
 * Setup screen for the mouse-wheel emulator.
 *
 * <p>Coordinate fields accept any number while typing; values outside the
 * on-screen range are clamped only when saving. A bottom toggle hides/shows
 * all other controls so the preview can be dragged without obstruction.
 */
public final class WheelSetupScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int FIELD_W = 90;
    private static final int FIELD_H = 20;
    private static final int SLIDER_W = 150;
    private static final int SLIDER_H = 20;
    private static final int ROW_GAP = 26;
    private static final int BTN_W = 80;
    private static final int BTN_H = 20;
    private static final int RESET_W = 40;
    private static final int TOGGLE_H = 18;
    private static final int TOGGLE_W = 160;

    private final Screen parent;

    /** When true, init() keeps the current local values instead of reloading config. */
    private boolean keepLocalValues;

    /** When true, all setup controls except the toggle button are hidden. */
    private boolean uiHidden;

    private double posX;
    private double posY;
    private int size;
    private int opacity;
    private int speed;
    private int holdMs;

    private EditBox posXField;
    private EditBox posYField;
    private EditBox holdField;
    private Button resetXBtn;
    private Button resetYBtn;
    private Button resetHoldBtn;
    private Button saveBtn;
    private Button resetAllBtn;
    private Button cancelBtn;
    private Button toggleUiBtn;
    private SizeSlider sizeSlider;
    private OpacitySlider opacitySlider;
    private SpeedSlider speedSlider;

    private boolean draggingWheel;
    private double dragOffX;
    private double dragOffY;

    public WheelSetupScreen(Screen parent) {
        super(Component.translatable("dragscroll.wheel.setup.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!this.keepLocalValues) {
            this.size = DragScrollConfig.getWheelSize();
            this.opacity = DragScrollConfig.getWheelOpacity();
            this.speed = DragScrollConfig.getWheelSpeed();
            this.holdMs = DragScrollConfig.getWheelRepositionHoldMs();
            double[] def = defaultCenter(this.size);
            double cx = DragScrollConfig.getWheelPosX();
            double cy = DragScrollConfig.getWheelPosY();
            this.posX = cx == DragScrollConfig.WHEEL_POS_UNSET ? def[0] : cx;
            this.posY = cy == DragScrollConfig.WHEEL_POS_UNSET ? def[1] : cy;
        }
        this.keepLocalValues = false;

        int x = MARGIN;
        int y = MARGIN + 14;

        this.posXField = new EditBox(this.font, x, y, FIELD_W, FIELD_H,
                Component.translatable("dragscroll.wheel.setup.pos_x", 0));
        this.posXField.setMaxLength(16);
        this.posXField.setValue(formatCoord(this.posX));
        this.posXField.setResponder(s -> onCoordTyped(true, s));
        this.addRenderableWidget(this.posXField);
        this.resetXBtn = Button.builder(
                Component.translatable("dragscroll.wheel.setup.reset_field"),
                b -> resetAxis(true))
                .pos(x + FIELD_W + 4, y)
                .size(RESET_W, FIELD_H)
                .build();
        this.addRenderableWidget(this.resetXBtn);
        y += ROW_GAP;

        this.posYField = new EditBox(this.font, x, y, FIELD_W, FIELD_H,
                Component.translatable("dragscroll.wheel.setup.pos_y", 0));
        this.posYField.setMaxLength(16);
        this.posYField.setValue(formatCoord(this.posY));
        this.posYField.setResponder(s -> onCoordTyped(false, s));
        this.addRenderableWidget(this.posYField);
        this.resetYBtn = Button.builder(
                Component.translatable("dragscroll.wheel.setup.reset_field"),
                b -> resetAxis(false))
                .pos(x + FIELD_W + 4, y)
                .size(RESET_W, FIELD_H)
                .build();
        this.addRenderableWidget(this.resetYBtn);
        y += ROW_GAP;

        this.holdField = new EditBox(this.font, x, y, FIELD_W, FIELD_H,
                Component.translatable("dragscroll.wheel.setup.hold_ms", 0));
        this.holdField.setMaxLength(6);
        this.holdField.setValue(Integer.toString(this.holdMs));
        this.holdField.setResponder(this::onHoldTyped);
        this.addRenderableWidget(this.holdField);
        this.resetHoldBtn = Button.builder(
                Component.translatable("dragscroll.wheel.setup.reset_field"),
                b -> {
                    this.holdMs = DragScrollConfig.DEFAULT_WHEEL_REPOSITION_HOLD_MS;
                    if (this.holdField != null) {
                        this.holdField.setValue(Integer.toString(this.holdMs));
                    }
                    updateResetButtons();
                })
                .pos(x + FIELD_W + 4, y)
                .size(RESET_W, FIELD_H)
                .build();
        this.addRenderableWidget(this.resetHoldBtn);
        y += ROW_GAP;

        this.sizeSlider = new SizeSlider(x, y);
        this.addRenderableWidget(this.sizeSlider);
        y += ROW_GAP;
        this.opacitySlider = new OpacitySlider(x, y);
        this.addRenderableWidget(this.opacitySlider);
        y += ROW_GAP;
        this.speedSlider = new SpeedSlider(x, y);
        this.addRenderableWidget(this.speedSlider);

        int bx = this.width - MARGIN - BTN_W;
        int by = MARGIN;
        this.saveBtn = Button.builder(
                Component.translatable("dragscroll.wheel.setup.save"),
                b -> this.saveAndClose()).pos(bx, by).size(BTN_W, BTN_H).build();
        this.addRenderableWidget(this.saveBtn);
        by += BTN_H + 4;
        this.resetAllBtn = Button.builder(
                Component.translatable("dragscroll.wheel.setup.reset"),
                b -> this.resetToDefaults()).pos(bx, by).size(BTN_W, BTN_H).build();
        this.addRenderableWidget(this.resetAllBtn);
        by += BTN_H + 4;
        this.cancelBtn = Button.builder(
                CommonComponents.GUI_CANCEL,
                b -> this.onClose()).pos(bx, by).size(BTN_W, BTN_H).build();
        this.addRenderableWidget(this.cancelBtn);

        int toggleX = (this.width - TOGGLE_W) / 2;
        int toggleY = this.height - MARGIN - TOGGLE_H;
        this.toggleUiBtn = Button.builder(
                this.toggleLabel(),
                b -> {
                    this.uiHidden = !this.uiHidden;
                    applyUiVisibility();
                })
                .pos(toggleX, toggleY)
                .size(TOGGLE_W, TOGGLE_H)
                .build();
        this.addRenderableWidget(this.toggleUiBtn);

        applyUiVisibility();
        updateResetButtons();
    }

    private Component toggleLabel() {
        return Component.translatable(this.uiHidden
                ? "dragscroll.wheel.setup.show_buttons"
                : "dragscroll.wheel.setup.hide_buttons");
    }

    private void applyUiVisibility() {
        boolean show = !this.uiHidden;
        setWidgetVisible(this.posXField, show);
        setWidgetVisible(this.posYField, show);
        setWidgetVisible(this.holdField, show);
        setWidgetVisible(this.sizeSlider, show);
        setWidgetVisible(this.opacitySlider, show);
        setWidgetVisible(this.speedSlider, show);
        setWidgetVisible(this.saveBtn, show);
        setWidgetVisible(this.resetAllBtn, show);
        setWidgetVisible(this.cancelBtn, show);
        // Reset buttons: only toggle visibility here; active state is driven by
        // updateResetButtons() so "already default" correctly greys them out.
        if (this.resetXBtn != null) {
            this.resetXBtn.visible = show;
        }
        if (this.resetYBtn != null) {
            this.resetYBtn.visible = show;
        }
        if (this.resetHoldBtn != null) {
            this.resetHoldBtn.visible = show;
        }
        if (this.toggleUiBtn != null) {
            this.toggleUiBtn.visible = true;
            this.toggleUiBtn.active = true;
            this.toggleUiBtn.setMessage(toggleLabel());
        }
        updateResetButtons();
    }

    private static void setWidgetVisible(AbstractWidget w, boolean visible) {
        if (w != null) {
            w.visible = visible;
            w.active = visible;
        }
    }

    private boolean isNumericInput(String s) {
        if (s == null || s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) {
            return true;
        }
        return s.matches("-?\\d*\\.?\\d*");
    }

    private static String formatCoord(double v) {
        long asLong = Math.round(v);
        if (Math.abs(v - asLong) < 1e-6) {
            return Long.toString(asLong);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private void onCoordTyped(boolean isX, String s) {
        if (s == null) {
            return;
        }
        if (!isNumericInput(s)) {
            String cleaned = s.replaceAll("[^0-9.\\-]", "");
            StringBuilder sb = new StringBuilder();
            boolean seenDot = false;
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '-' && sb.length() == 0) {
                    sb.append(c);
                } else if (c == '.' && !seenDot) {
                    sb.append(c);
                    seenDot = true;
                } else if (c >= '0' && c <= '9') {
                    sb.append(c);
                }
            }
            cleaned = sb.toString();
            EditBox field = isX ? this.posXField : this.posYField;
            if (field != null && !cleaned.equals(s)) {
                field.setValue(cleaned);
            }
            s = cleaned;
        }
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) {
            return;
        }
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return;
            }
            if (isX) {
                this.posX = v;
            } else {
                this.posY = v;
            }
            updateResetButtons();
        } catch (NumberFormatException ignored) {
        }
    }

    private void onHoldTyped(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        String cleaned = s.replaceAll("[^0-9]", "");
        if (!cleaned.equals(s) && this.holdField != null) {
            this.holdField.setValue(cleaned);
            s = cleaned;
        }
        if (s.isEmpty()) {
            return;
        }
        try {
            this.holdMs = Integer.parseInt(s);
            updateResetButtons();
        } catch (NumberFormatException ignored) {
        }
    }

    private void resetAxis(boolean isX) {
        double[] def = defaultCenter(this.size);
        if (isX) {
            this.posX = def[0];
            if (this.posXField != null) {
                this.posXField.setValue(formatCoord(this.posX));
            }
        } else {
            this.posY = def[1];
            if (this.posYField != null) {
                this.posYField.setValue(formatCoord(this.posY));
            }
        }
        updateResetButtons();
    }

    private void updateResetButtons() {
        double[] def = defaultCenter(this.size);
        // Wider epsilon: formatCoord rounds, and defaultCentre can drift a fraction of a pixel.
        final double eps = 0.5;
        if (this.resetXBtn != null) {
            this.resetXBtn.active = !this.uiHidden && Math.abs(this.posX - def[0]) > eps;
        }
        if (this.resetYBtn != null) {
            this.resetYBtn.active = !this.uiHidden && Math.abs(this.posY - def[1]) > eps;
        }
        if (this.resetHoldBtn != null) {
            this.resetHoldBtn.active = !this.uiHidden
                    && this.holdMs != DragScrollConfig.DEFAULT_WHEEL_REPOSITION_HOLD_MS;
        }
    }

    private double[] defaultCenter(int sizePct) {
        int w = MouseWheelEmulator.widthForSize(sizePct);
        double cx = this.width - MouseWheelEmulator.defaultRightMargin() - w / 2.0;
        double cy = this.height / 2.0;
        return new double[] {cx, cy};
    }

    private void clampPos() {
        int w = MouseWheelEmulator.widthForSize(this.size);
        int h = MouseWheelEmulator.heightForSize(this.size);
        this.posX = Math.max(w / 2.0, Math.min(this.posX, this.width - w / 2.0));
        this.posY = Math.max(h / 2.0, Math.min(this.posY, this.height - h / 2.0));
    }

    private int[] previewBoundsInt() {
        int w = MouseWheelEmulator.widthForSize(this.size);
        int h = MouseWheelEmulator.heightForSize(this.size);
        double cx = Math.max(w / 2.0, Math.min(this.posX, this.width - w / 2.0));
        double cy = Math.max(h / 2.0, Math.min(this.posY, this.height - h / 2.0));
        int x = (int) Math.floor(cx - w / 2.0);
        int y = (int) Math.floor(cy - h / 2.0);
        x = Math.max(0, Math.min(x, this.width - w));
        y = Math.max(0, Math.min(y, this.height - h));
        return new int[] {x, y, w, h};
    }

    private boolean isOverPreview(double mx, double my) {
        int[] b = previewBoundsInt();
        return mx >= b[0] - 0.75 && mx <= b[0] + b[2] + 0.75
                && my >= b[1] - 0.75 && my <= b[1] + b[3] + 0.75;
    }

    /**
     * Input hit-test used by MouseHandlerMixin. The setup preview is deliberately
     * kept separate from MouseWheelEmulator's in-game wheel hit-test because a
     * touch on this preview must move the editor preview immediately, not enter
     * the emulator's pending/scroll/reposition state machine.
     */
    public boolean isOverPreviewForInput(double mx, double my) {
        return isOverPreview(mx, my);
    }

    private void resetToDefaults() {
        this.size = DragScrollConfig.DEFAULT_WHEEL_SIZE;
        this.opacity = DragScrollConfig.DEFAULT_WHEEL_OPACITY;
        this.speed = DragScrollConfig.DEFAULT_WHEEL_SPEED;
        this.holdMs = DragScrollConfig.DEFAULT_WHEEL_REPOSITION_HOLD_MS;
        double[] def = defaultCenter(this.size);
        this.posX = def[0];
        this.posY = def[1];
        boolean wasHidden = this.uiHidden;
        this.keepLocalValues = true;
        this.clearWidgets();
        this.init();
        this.uiHidden = wasHidden;
        applyUiVisibility();
    }

    private void saveAndClose() {
        if (this.posXField != null) {
            onCoordTyped(true, this.posXField.getValue());
        }
        if (this.posYField != null) {
            onCoordTyped(false, this.posYField.getValue());
        }
        if (this.holdField != null) {
            onHoldTyped(this.holdField.getValue());
        }
        clampPos();
        this.holdMs = Math.max(DragScrollConfig.MIN_WHEEL_REPOSITION_HOLD_MS,
                Math.min(DragScrollConfig.MAX_WHEEL_REPOSITION_HOLD_MS, this.holdMs));
        DragScrollConfig.setWheelPos(this.posX, this.posY);
        DragScrollConfig.setWheelSize(this.size);
        DragScrollConfig.setWheelOpacity(this.opacity);
        DragScrollConfig.setWheelSpeed(this.speed);
        DragScrollConfig.setWheelRepositionHoldMs(this.holdMs);
        DragScrollConfig.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int btn = event.button();
        if ((btn == 0 || btn == 1) && isOverPreview(event.x(), event.y())) {
            // The preview itself owns the press. Do not pass this click through
            // to the setup widgets: a mobile launcher reports a finger press
            // as LMB down, followed by mouse-drag events while the finger moves.
            // Capture starts immediately on that press and is released only
            // when the button is released.
            this.draggingWheel = true;
            this.dragOffX = event.x() - this.posX;
            this.dragOffY = event.y() - this.posY;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.draggingWheel) {
            this.posX = event.x() - this.dragOffX;
            this.posY = event.y() - this.dragOffY;
            clampPos();
            if (this.posXField != null && !this.uiHidden) {
                this.posXField.setValue(formatCoord(this.posX));
            }
            if (this.posYField != null && !this.uiHidden) {
                this.posYField.setValue(formatCoord(this.posY));
            }
            updateResetButtons();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingWheel) {
            this.draggingWheel = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (!this.uiHidden) {
            graphics.text(this.font, this.title, MARGIN, MARGIN, 0xFFFFFFFF, false);
            graphics.text(this.font,
                    Component.translatable("dragscroll.wheel.setup.pos_x_range", 0, Math.max(0, this.width)).getString(),
                    MARGIN + FIELD_W + RESET_W + 12, MARGIN + 14 + 6, 0xFFAAAAAA, false);
            graphics.text(this.font,
                    Component.translatable("dragscroll.wheel.setup.pos_y_range", 0, Math.max(0, this.height)).getString(),
                    MARGIN + FIELD_W + RESET_W + 12, MARGIN + 14 + ROW_GAP + 6, 0xFFAAAAAA, false);
            graphics.text(this.font,
                    Component.translatable("dragscroll.wheel.setup.hold_ms_range",
                            DragScrollConfig.MIN_WHEEL_REPOSITION_HOLD_MS,
                            DragScrollConfig.MAX_WHEEL_REPOSITION_HOLD_MS).getString(),
                    MARGIN + FIELD_W + RESET_W + 12, MARGIN + 14 + ROW_GAP * 2 + 6, 0xFFAAAAAA, false);
            graphics.text(this.font, "X", MARGIN - 1, MARGIN + 4, 0xFFCCCCCC, false);
            graphics.text(this.font, "Y", MARGIN - 1, MARGIN + 4 + ROW_GAP, 0xFFCCCCCC, false);
            graphics.text(this.font, "ms", MARGIN - 1, MARGIN + 4 + ROW_GAP * 2, 0xFFCCCCCC, false);
        }

        int[] b = previewBoundsInt();
        MouseWheelEmulator.drawWheel(graphics, b[0], b[1], b[2], b[3], this.opacity, 0.0);

        if (this.draggingWheel) {
            int x0 = b[0] - 2;
            int y0 = b[1] - 2;
            int x1 = b[0] + b[2] + 2;
            int y1 = b[1] + b[3] + 2;
            int red = 0x88FF2020;
            graphics.fill(x0, y0, x1, y0 + 2, red);
            graphics.fill(x0, y1 - 2, x1, y1, red);
            graphics.fill(x0, y0, x0 + 2, y1, red);
            graphics.fill(x1 - 2, y0, x1, y1, red);
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
            WheelSetupScreen.this.updateResetButtons();
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
