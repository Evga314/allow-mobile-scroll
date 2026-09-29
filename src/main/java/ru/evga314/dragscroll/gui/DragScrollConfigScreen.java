package ru.evga314.dragscroll.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.loader.SafeMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Settings screen (opened from Mod Menu). A hand-made scrolling page: the
 * rows move with the scroll offset, and Save / Reset / Cancel stay fixed in
 * the top-right corner. Toggles apply live; Cancel restores what was saved.
 */
public final class DragScrollConfigScreen extends Screen {
    private static final int RESET_W = 34;
    private static final int SCROLLBAR_W = 6;
    private static final int HEADER_H = 28;
    private static final int BUTTON_H = 20;
    private static final int ROW_BUTTON_GAP = 24;
    private static final int DESC_COLOR = 0xFFA0A0A0;

    /** An on/off setting: button, then its description. */
    private record Toggle(String key, BooleanSupplier get, Consumer<Boolean> set, boolean defaultValue) {
        Component label() {
            return Component.translatable("dragscroll.config." + key + (get.getAsBoolean() ? ".on" : ".off"));
        }

        String descriptionKey() {
            return "dragscroll.config." + key + ".description";
        }
    }

    /** A row placed on the page: the widget and where it sits, relative to the list top. */
    private static final class Row {
        final AbstractWidget widget;
        final int rel;

        Row(AbstractWidget widget, int rel) {
            this.widget = widget;
            this.rel = rel;
        }
    }

    /** A text block placed on the page. */
    private record Text(Component text, int rel, int color) {
    }

    private final Screen parent;

    private final Toggle enabled = new Toggle("enabled",
            DragScrollConfig::isEnabled, DragScrollConfig::setEnabled, DragScrollConfig.DEFAULT_ENABLED);
    /** Rows below the number fields, in page order (null = the wheel setup button). */
    private final List<Toggle> toggles = new ArrayList<>();

    private final boolean[] savedToggles;
    private int savedThreshold;
    private int savedInertia;

    private final List<Row> rows = new ArrayList<>();
    private final List<Text> texts = new ArrayList<>();
    private final List<Button> toggleButtons = new ArrayList<>();
    private EditBox thresholdBox;
    private EditBox inertiaBox;
    private Button thresholdReset;
    private Button inertiaReset;
    private Button saveButton;

    private int wrapWidth;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private double scrollAmount;
    private boolean draggingBar;
    /** Finger Y and scroll offset when the thumb was grabbed. */
    private double barGrabY;
    private double barGrabScroll;

    public DragScrollConfigScreen(Screen parent) {
        super(Component.translatable("dragscroll.config.title"));
        this.parent = parent;
        toggles.add(new Toggle("xaero_slider", DragScrollConfig::isXaeroSliderEnabled,
                DragScrollConfig::setXaeroSliderEnabled, DragScrollConfig.DEFAULT_XAERO_SLIDER));
        toggles.add(new Toggle("merchant_bar", DragScrollConfig::isMerchantWideBarEnabled,
                DragScrollConfig::setMerchantWideBarEnabled, DragScrollConfig.DEFAULT_MERCHANT_WIDE_BAR));
        toggles.add(null);
        toggles.add(new Toggle("wheel_block_creative", DragScrollConfig::isWheelBlockCreativeEnabled,
                DragScrollConfig::setWheelBlockCreativeEnabled, DragScrollConfig.DEFAULT_WHEEL_BLOCK_CREATIVE));
        toggles.add(new Toggle("wheel_invert", DragScrollConfig::isWheelInvertEnabled,
                DragScrollConfig::setWheelInvertEnabled, DragScrollConfig.DEFAULT_WHEEL_INVERT));
        toggles.add(new Toggle("wheel_reposition", DragScrollConfig::isWheelRepositionEnabled,
                DragScrollConfig::setWheelRepositionEnabled, DragScrollConfig.DEFAULT_WHEEL_REPOSITION));
        toggles.add(new Toggle("debug", DragScrollConfig::isDebugEnabled,
                DragScrollConfig::setDebugEnabled, DragScrollConfig.DEFAULT_DEBUG));
        // Captured once: init() runs again on every resize, and re-reading
        // there made Cancel keep changes made before the resize.
        this.savedToggles = new boolean[allToggles().size()];
        this.captureSaved();
    }

    private List<Toggle> allToggles() {
        List<Toggle> all = new ArrayList<>();
        all.add(enabled);
        for (Toggle t : toggles) {
            if (t != null) {
                all.add(t);
            }
        }
        return all;
    }

    private void captureSaved() {
        List<Toggle> all = allToggles();
        for (int i = 0; i < all.size(); i++) {
            savedToggles[i] = all.get(i).get.getAsBoolean();
        }
        savedThreshold = DragScrollConfig.getDragThreshold();
        savedInertia = DragScrollConfig.getInertiaStrength();
    }

    // =====================================================================
    // Layout
    // =====================================================================

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldWidth = Math.min(280, Math.max(180, this.width - 100));
        int fieldLeft = centerX - fieldWidth / 2;
        int fieldInnerW = fieldWidth - RESET_W - 6;
        this.wrapWidth = Math.min(this.width - 48, 400);
        this.listTop = HEADER_H;
        this.listBottom = this.height;
        this.scrollAmount = 0;
        this.draggingBar = false;
        rows.clear();
        texts.clear();
        toggleButtons.clear();

        int y = 4;
        y = addToggle(enabled, fieldLeft, fieldWidth, y);
        Component enabledDesc = Component.translatable(enabled.descriptionKey());
        texts.add(new Text(enabledDesc, y, DESC_COLOR));
        y += wrappedHeight(enabledDesc) + 4;
        if (SafeMode.isFallbackActive()) {
            Component status = Component.translatable("dragscroll.config.enabled.safemode", SafeMode.reason());
            texts.add(new Text(status, y, 0xFFFFD060));
            y += wrappedHeight(status) + 4;
        }
        y += 8;

        this.thresholdBox = numberField("dragscroll.config.threshold", DragScrollConfig.getDragThreshold(), 2,
                fieldLeft, fieldInnerW);
        y = addNumberRow("threshold", thresholdBox, fieldLeft + fieldInnerW + 6, y,
                DragScrollConfig.DEFAULT_DRAG_THRESHOLD);
        this.thresholdReset = (Button) rows.get(rows.size() - 1).widget;

        this.inertiaBox = numberField("dragscroll.config.inertia", DragScrollConfig.getInertiaStrength(), 3,
                fieldLeft, fieldInnerW);
        y = addNumberRow("inertia", inertiaBox, fieldLeft + fieldInnerW + 6, y,
                DragScrollConfig.DEFAULT_INERTIA_STRENGTH);
        this.inertiaReset = (Button) rows.get(rows.size() - 1).widget;
        this.setInitialFocus(this.thresholdBox);

        for (Toggle toggle : toggles) {
            String descKey;
            if (toggle == null) {
                Button setup = Button.builder(Component.translatable("dragscroll.config.wheel_setup"),
                        b -> this.minecraft.gui.setScreen(new WheelSetupScreen(this)))
                        .pos(fieldLeft, 0).size(fieldWidth, BUTTON_H).build();
                rows.add(new Row(this.addRenderableWidget(setup), y));
                y += ROW_BUTTON_GAP;
                descKey = "dragscroll.config.wheel_setup.description";
            } else {
                y = addToggle(toggle, fieldLeft, fieldWidth, y);
                descKey = toggle.descriptionKey();
            }
            Component desc = Component.translatable(descKey);
            texts.add(new Text(desc, y, DESC_COLOR));
            y += wrappedHeight(desc) + 8;
        }
        this.contentHeight = y;

        // Save / Reset / Cancel: fixed in the top-right corner, outside the page.
        int buttonWidth = Math.max(56, (fieldWidth - 12) / 3);
        int buttonX = this.width - 12 - buttonWidth;
        this.saveButton = this.addRenderableWidget(Button.builder(Component.translatable("dragscroll.config.save"),
                b -> this.saveAndClose()).pos(buttonX, 4).size(buttonWidth, BUTTON_H).build());
        this.addRenderableWidget(Button.builder(Component.translatable("dragscroll.config.reset"),
                b -> this.resetToDefaults()).pos(buttonX, 28).size(buttonWidth, BUTTON_H).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
                b -> this.onClose()).pos(buttonX, 52).size(buttonWidth, BUTTON_H).build());

        this.repositionContent();
        this.updateButtons();
    }

    /** Adds a toggle button at {@code y}; returns the y after it. */
    private int addToggle(Toggle toggle, int left, int width, int y) {
        Button button = Button.builder(toggle.label(), b -> {
            toggle.set.accept(!toggle.get.getAsBoolean());
            b.setMessage(toggle.label());
        }).pos(left, 0).size(width, BUTTON_H).build();
        this.addRenderableWidget(button);
        toggleButtons.add(button);
        rows.add(new Row(button, y));
        return y + ROW_BUTTON_GAP;
    }

    private EditBox numberField(String key, int value, int maxLength, int left, int width) {
        EditBox box = new EditBox(this.font, left, 0, width, BUTTON_H, Component.translatable(key));
        box.setValue(Integer.toString(value));
        box.setMaxLength(maxLength);
        return box;
    }

    /** Description, label, then the field with its reset button; returns the y after it. */
    private int addNumberRow(String key, EditBox box, int resetX, int y, int defaultValue) {
        Component desc = Component.translatable("dragscroll.config." + key + ".description");
        texts.add(new Text(desc, y, DESC_COLOR));
        y += wrappedHeight(desc) + 4;
        texts.add(new Text(Component.translatable("dragscroll.config." + key + ".label"), y, 0xFFFFFFFF));
        y += 12;
        rows.add(new Row(this.addRenderableWidget(box), y));
        Button reset = Button.builder(Component.translatable("dragscroll.config." + key + ".reset"),
                b -> box.setValue(Integer.toString(defaultValue)))
                .pos(resetX, 0).size(RESET_W, BUTTON_H).build();
        rows.add(new Row(this.addRenderableWidget(reset), y));
        return y + 28;
    }

    private int wrappedHeight(Component text) {
        return Math.max(this.font.lineHeight, this.font.split(text, this.wrapWidth).size() * this.font.lineHeight);
    }

    private int contentY(int rel) {
        return this.listTop + rel - (int) Math.round(this.scrollAmount);
    }

    private int maxScroll() {
        int view = Math.max(1, this.listBottom - this.listTop);
        return Math.max(0, this.contentHeight - view);
    }

    private boolean inView(int rel, int h) {
        int y = contentY(rel);
        return y + h > this.listTop && y < this.listBottom;
    }

    /** Moves every row to the scroll offset and hides the ones out of view. */
    private void repositionContent() {
        this.scrollAmount = Math.max(0, Math.min(this.maxScroll(), this.scrollAmount));
        for (Row row : rows) {
            row.widget.setY(contentY(row.rel));
            row.widget.visible = inView(row.rel, BUTTON_H);
        }
    }

    // =====================================================================
    // State
    // =====================================================================

    @Override
    public void tick() {
        super.tick();
        this.updateButtons();
    }

    /** Reset buttons are active off their default; Save only with unsaved changes. */
    private void updateButtons() {
        if (thresholdReset != null) {
            thresholdReset.active = !thresholdBox.getValue().trim()
                    .equals(Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD));
            inertiaReset.active = !inertiaBox.getValue().trim()
                    .equals(Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH));
        }
        if (saveButton != null) {
            saveButton.active = isDirty();
        }
    }

    private static int parseOr(EditBox box, int fallback) {
        try {
            if (box != null && !box.getValue().isEmpty()) {
                return Integer.parseInt(box.getValue().trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private boolean isDirty() {
        if (parseOr(thresholdBox, savedThreshold) != savedThreshold || parseOr(inertiaBox, savedInertia) != savedInertia) {
            return true;
        }
        List<Toggle> all = allToggles();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).get.getAsBoolean() != savedToggles[i]) {
                return true;
            }
        }
        return false;
    }

    private void resetToDefaults() {
        thresholdBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD));
        inertiaBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH));
        for (Toggle toggle : allToggles()) {
            toggle.set.accept(toggle.defaultValue);
        }
        refreshToggleLabels();
    }

    private void refreshToggleLabels() {
        List<Toggle> all = allToggles();
        for (int i = 0; i < all.size(); i++) {
            toggleButtons.get(i).setMessage(all.get(i).label());
        }
    }

    private void saveAndClose() {
        DragScrollConfig.setDragThreshold(parseOr(thresholdBox, DragScrollConfig.getDragThreshold()));
        DragScrollConfig.setInertiaStrength(parseOr(inertiaBox, DragScrollConfig.getInertiaStrength()));
        DragScrollConfig.save();
        if (DragScrollConfig.isEnabled()) {
            // Switching the mod on also undoes an automatic safe mode
            // (from the next start, when the mixins are applied again).
            SafeMode.clearDisableFile();
        }
        captureSaved();
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Cancel / ESC: toggles apply live, so what was not saved is undone. */
    @Override
    public void onClose() {
        List<Toggle> all = allToggles();
        for (int i = 0; i < all.size(); i++) {
            all.get(i).set.accept(savedToggles[i]);
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    // =====================================================================
    // Scrolling
    // =====================================================================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Positive (wheel up) moves the content towards the start.
        this.scrollAmount -= scrollY * 12.0;
        this.repositionContent();
        return true;
    }

    private boolean overScrollbar(double x, double y) {
        int barX = this.width - 12;
        return x >= barX - 4 && x <= barX + SCROLLBAR_W + 4 && y >= this.listTop && y <= this.listBottom;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && isPrimary(event.button()) && this.maxScroll() > 0 && overScrollbar(event.x(), event.y())) {
            // Grab the thumb where the finger landed; the drag moves it by the
            // finger's travel instead of centring it under the finger.
            this.draggingBar = true;
            this.barGrabY = event.y();
            this.barGrabScroll = this.scrollAmount;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.draggingBar && event != null && isPrimary(event.button())) {
            int view = Math.max(1, this.listBottom - this.listTop);
            int max = this.maxScroll();
            if (max > 0) {
                int thumbH = Math.max(16, view * view / Math.max(view + max, 1));
                double perPixel = max / (double) Math.max(1, view - thumbH);
                this.scrollAmount = this.barGrabScroll + (event.y() - this.barGrabY) * perPixel;
                this.repositionContent();
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event != null && isPrimary(event.button())) {
            this.draggingBar = false;
        }
        return super.mouseReleased(event);
    }

    private static boolean isPrimary(int button) {
        return button == 0 || button == 1;
    }

    // =====================================================================
    // Rendering
    // =====================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, 8, 0xFFFFFFFF);
        for (Text text : texts) {
            int y = contentY(text.rel);
            for (FormattedCharSequence line : this.font.split(text.text, this.wrapWidth)) {
                if (y + this.font.lineHeight > this.listTop && y < this.listBottom) {
                    graphics.centeredText(this.font, line, centerX, y, text.color);
                }
                y += this.font.lineHeight;
            }
        }
        drawScrollbar(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int max = this.maxScroll();
        if (max <= 0) {
            return;
        }
        int view = Math.max(1, this.listBottom - this.listTop);
        int barX = this.width - 12;
        g.fill(barX, this.listTop, barX + SCROLLBAR_W, this.listBottom, 0x80000000);
        int thumbH = Math.max(16, view * view / Math.max(view + max, 1));
        int thumbY = this.listTop + (int) Math.round((view - thumbH) * (this.scrollAmount / max));
        g.fill(barX, thumbY, barX + SCROLLBAR_W, thumbY + thumbH, 0xFFA0A0A0);
        g.fill(barX, thumbY, barX + SCROLLBAR_W, thumbY + 1, 0xFFE0E0E0);
        g.fill(barX + SCROLLBAR_W - 1, thumbY, barX + SCROLLBAR_W, thumbY + thumbH, 0xFF555555);
    }
}
