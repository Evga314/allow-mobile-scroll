package ru.evga314.dragscroll.modmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import ru.evga314.dragscroll.DragScrollConfig;
import ru.evga314.dragscroll.compat.SafeMode;

public final class DragScrollConfigScreen extends Screen {
    private static final int RESET_W = 34;
    private static final int SCROLLBAR_W = 6;
    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 32;

    private final Screen parent;
    private EditBox thresholdBox;
    private EditBox inertiaBox;
    private Button thresholdReset;
    private Button inertiaReset;
    private Button debugButton;
    private Button enabledButton;
    private Button xaeroButton;
    private Button merchantBarButton;
    private Button wheelSetupButton;
    private Button wheelBlockCreativeButton;
    private Button wheelInvertButton;
    private Button saveButton;

    private int fieldLeft;
    private int fieldWidth;
    private int wrapWidth;
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private double scrollAmount;
    private boolean draggingBar;
    private int savedThreshold;
    private int savedInertia;
    private boolean savedDebug;
    private boolean savedXaero;
    private boolean savedEnabled;

    private int enabledRel;
    private int enabledDescRel;
    private int thresholdDescRel;
    private int thresholdLabelRel;
    private int thresholdFieldRel;
    private int inertiaDescRel;
    private int inertiaLabelRel;
    private int inertiaFieldRel;
    private int xaeroRel;
    private int xaeroDescRel;
    private int merchantBarRel;
    private int merchantBarDescRel;
    private int wheelSetupRel;
    private int wheelSetupDescRel;
    private int wheelBlockCreativeRel;
    private int wheelBlockCreativeDescRel;
    private int wheelInvertRel;
    private int wheelInvertDescRel;
    private int debugRel;
    private int debugDescRel;

    private boolean savedMerchantBar;
    private boolean savedWheelBlockCreative;
    private boolean savedWheelInvert;

    public DragScrollConfigScreen(Screen parent) {
        super(Component.translatable("dragscroll.config.title"));
        this.parent = parent;
        // Captured once: init() runs again on every resize / rotation, and
        // re-reading here made Cancel keep the toggles changed before it.
        this.savedThreshold = DragScrollConfig.getDragThreshold();
        this.savedInertia = DragScrollConfig.getInertiaStrength();
        this.savedDebug = DragScrollConfig.isDebugEnabled();
        this.savedXaero = DragScrollConfig.isXaeroSliderEnabled();
        this.savedEnabled = DragScrollConfig.isEnabled();
        this.savedMerchantBar = DragScrollConfig.isMerchantWideBarEnabled();
        this.savedWheelBlockCreative = DragScrollConfig.isWheelBlockCreativeEnabled();
        this.savedWheelInvert = DragScrollConfig.isWheelInvertEnabled();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.fieldWidth = Math.min(280, Math.max(180, this.width - 100));
        this.wrapWidth = Math.min(this.width - 48, 400);
        this.fieldLeft = centerX - this.fieldWidth / 2;
        this.listTop = HEADER_H;
        this.listBottom = this.height - FOOTER_H;
        this.scrollAmount = 0;
        this.draggingBar = false;

        int y = 4;
        this.enabledRel = y;
        y += 24;
        this.enabledDescRel = y;
        y += this.wrappedHeight(Component.translatable("dragscroll.config.enabled.description")) + 4;
        if (SafeMode.isFallbackActive()) {
            y += this.wrappedHeight(this.safeModeStatus()) + 4;
        }
        y += 8;

        this.thresholdDescRel = y;
        y += this.wrappedHeight("dragscroll.config.threshold.description") + 4;
        this.thresholdLabelRel = y;
        y += 12;
        this.thresholdFieldRel = y;
        y += 28;

        this.inertiaDescRel = y;
        y += this.wrappedHeight("dragscroll.config.inertia.description") + 4;
        this.inertiaLabelRel = y;
        y += 12;
        this.inertiaFieldRel = y;
        y += 28;

        this.xaeroRel = y;
        y += 24;
        this.xaeroDescRel = y;
        y += this.wrappedHeight("dragscroll.config.xaero_slider.description") + 8;

        this.merchantBarRel = y;
        y += 24;
        this.merchantBarDescRel = y;
        y += this.wrappedHeight("dragscroll.config.merchant_bar.description") + 8;

        this.wheelSetupRel = y;
        y += 24;
        this.wheelSetupDescRel = y;
        y += this.wrappedHeight("dragscroll.config.wheel_setup.description") + 8;

        this.wheelBlockCreativeRel = y;
        y += 24;
        this.wheelBlockCreativeDescRel = y;
        y += this.wrappedHeight("dragscroll.config.wheel_block_creative.description") + 8;

        this.wheelInvertRel = y;
        y += 24;
        this.wheelInvertDescRel = y;
        y += this.wrappedHeight("dragscroll.config.wheel_invert.description") + 8;

        this.debugRel = y;
        y += 24;
        this.debugDescRel = y;
        y += this.wrappedHeight("dragscroll.config.debug.description") + 8;
        this.contentHeight = y;

        int fieldInnerW = this.fieldWidth - RESET_W - 6;

        this.enabledButton = this.addRenderableWidget(Button.builder(
                this.getEnabledButtonText(),
                button -> {
                    DragScrollConfig.setEnabled(!DragScrollConfig.isEnabled());
                    this.updateEnabledButton();
                }
        ).pos(this.fieldLeft, contentY(this.enabledRel)).size(this.fieldWidth, 20).build());

        this.thresholdBox = new EditBox(
                this.font,
                this.fieldLeft,
                contentY(this.thresholdFieldRel),
                fieldInnerW,
                20,
                Component.translatable("dragscroll.config.threshold")
        );
        this.thresholdBox.setValue(Integer.toString(DragScrollConfig.getDragThreshold()));
        this.thresholdBox.setMaxLength(2);
        this.addRenderableWidget(this.thresholdBox);
        this.setInitialFocus(this.thresholdBox);

        this.thresholdReset = this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.threshold.reset"),
                button -> this.thresholdBox.setValue(
                        Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD))
        ).pos(this.fieldLeft + fieldInnerW + 6, contentY(this.thresholdFieldRel)).size(RESET_W, 20).build());

        this.inertiaBox = new EditBox(
                this.font,
                this.fieldLeft,
                contentY(this.inertiaFieldRel),
                fieldInnerW,
                20,
                Component.translatable("dragscroll.config.inertia")
        );
        this.inertiaBox.setValue(Integer.toString(DragScrollConfig.getInertiaStrength()));
        this.inertiaBox.setMaxLength(3);
        this.addRenderableWidget(this.inertiaBox);

        this.inertiaReset = this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.inertia.reset"),
                button -> this.inertiaBox.setValue(
                        Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH))
        ).pos(this.fieldLeft + fieldInnerW + 6, contentY(this.inertiaFieldRel)).size(RESET_W, 20).build());

        this.xaeroButton = this.addRenderableWidget(Button.builder(
                this.getXaeroButtonText(),
                button -> {
                    DragScrollConfig.setXaeroSliderEnabled(!DragScrollConfig.isXaeroSliderEnabled());
                    this.updateXaeroButton();
                }
        ).pos(this.fieldLeft, contentY(this.xaeroRel)).size(this.fieldWidth, 20).build());

        this.merchantBarButton = this.addRenderableWidget(Button.builder(
                this.getMerchantBarButtonText(),
                button -> {
                    DragScrollConfig.setMerchantWideBarEnabled(!DragScrollConfig.isMerchantWideBarEnabled());
                    this.updateMerchantBarButton();
                }
        ).pos(this.fieldLeft, contentY(this.merchantBarRel)).size(this.fieldWidth, 20).build());

        this.wheelSetupButton = this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.wheel_setup"),
                button -> this.minecraft.gui.setScreen(new WheelSetupScreen(this))
        ).pos(this.fieldLeft, contentY(this.wheelSetupRel)).size(this.fieldWidth, 20).build());

        this.wheelBlockCreativeButton = this.addRenderableWidget(Button.builder(
                this.getWheelBlockCreativeButtonText(),
                button -> {
                    DragScrollConfig.setWheelBlockCreativeEnabled(!DragScrollConfig.isWheelBlockCreativeEnabled());
                    this.updateWheelBlockCreativeButton();
                }
        ).pos(this.fieldLeft, contentY(this.wheelBlockCreativeRel)).size(this.fieldWidth, 20).build());

        this.wheelInvertButton = this.addRenderableWidget(Button.builder(
                this.getWheelInvertButtonText(),
                button -> {
                    DragScrollConfig.setWheelInvertEnabled(!DragScrollConfig.isWheelInvertEnabled());
                    this.updateWheelInvertButton();
                }
        ).pos(this.fieldLeft, contentY(this.wheelInvertRel)).size(this.fieldWidth, 20).build());

        this.debugButton = this.addRenderableWidget(Button.builder(
                this.getDebugButtonText(),
                button -> {
                    DragScrollConfig.setDebugEnabled(!DragScrollConfig.isDebugEnabled());
                    this.updateDebugButton();
                }
        ).pos(this.fieldLeft, contentY(this.debugRel)).size(this.fieldWidth, 20).build());

        int buttonWidth = Math.max(56, (this.fieldWidth - 12) / 3);
        int rowWidth = buttonWidth * 3 + 12;
        int rowLeft = centerX - rowWidth / 2;
        int footerY = this.height - 26;

        this.saveButton = this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.save"),
                button -> this.saveAndClose()
        ).pos(rowLeft, footerY).size(buttonWidth, 20).build());
        this.saveButton.active = false;

        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.reset"),
                button -> {
                    this.thresholdBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD));
                    this.inertiaBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH));
                    DragScrollConfig.setDebugEnabled(false);
                    DragScrollConfig.setXaeroSliderEnabled(DragScrollConfig.DEFAULT_XAERO_SLIDER);
                    DragScrollConfig.setMerchantWideBarEnabled(DragScrollConfig.DEFAULT_MERCHANT_WIDE_BAR);
                    DragScrollConfig.setWheelBlockCreativeEnabled(DragScrollConfig.DEFAULT_WHEEL_BLOCK_CREATIVE);
                    DragScrollConfig.setWheelInvertEnabled(DragScrollConfig.DEFAULT_WHEEL_INVERT);
                    DragScrollConfig.setEnabled(DragScrollConfig.DEFAULT_ENABLED);
                    this.updateDebugButton();
                    this.updateXaeroButton();
                    this.updateMerchantBarButton();
                    this.updateWheelBlockCreativeButton();
                    this.updateWheelInvertButton();
                    this.updateEnabledButton();
                }
        ).pos(rowLeft + buttonWidth + 6, footerY).size(buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_CANCEL,
                button -> this.onClose()
        ).pos(rowLeft + (buttonWidth + 6) * 2, footerY).size(buttonWidth, 20).build());

        this.repositionContent();
        this.updateResetButtons();
        this.updateSaveButton();
    }

    private int wrappedHeight(String key) {
        return this.wrappedHeight(Component.translatable(key));
    }

    private int wrappedHeight(Component text) {
        return Math.max(this.font.lineHeight,
                this.font.split(text, this.wrapWidth).size() * this.font.lineHeight);
    }

    private Component safeModeStatus() {
        return Component.translatable("dragscroll.config.enabled.safemode", SafeMode.reason());
    }

    private int contentY(int rel) {
        return this.listTop + rel - (int) Math.round(this.scrollAmount);
    }

    private int maxScroll() {
        int view = Math.max(1, this.listBottom - this.listTop);
        return Math.max(0, this.contentHeight - view);
    }

    private void clampScroll() {
        double max = this.maxScroll();
        if (this.scrollAmount < 0) {
            this.scrollAmount = 0;
        }
        if (this.scrollAmount > max) {
            this.scrollAmount = max;
        }
    }

    private void repositionContent() {
        this.clampScroll();
        if (this.enabledButton != null) {
            this.enabledButton.setY(contentY(this.enabledRel));
        }
        if (this.thresholdBox != null) {
            this.thresholdBox.setY(contentY(this.thresholdFieldRel));
        }
        if (this.thresholdReset != null) {
            this.thresholdReset.setY(contentY(this.thresholdFieldRel));
        }
        if (this.inertiaBox != null) {
            this.inertiaBox.setY(contentY(this.inertiaFieldRel));
        }
        if (this.inertiaReset != null) {
            this.inertiaReset.setY(contentY(this.inertiaFieldRel));
        }
        if (this.xaeroButton != null) {
            this.xaeroButton.setY(contentY(this.xaeroRel));
        }
        if (this.merchantBarButton != null) {
            this.merchantBarButton.setY(contentY(this.merchantBarRel));
        }
        if (this.wheelSetupButton != null) {
            this.wheelSetupButton.setY(contentY(this.wheelSetupRel));
        }
        if (this.wheelBlockCreativeButton != null) {
            this.wheelBlockCreativeButton.setY(contentY(this.wheelBlockCreativeRel));
        }
        if (this.wheelInvertButton != null) {
            this.wheelInvertButton.setY(contentY(this.wheelInvertRel));
        }
        if (this.debugButton != null) {
            this.debugButton.setY(contentY(this.debugRel));
        }
        this.updateContentVisibility();
    }

    private void updateContentVisibility() {
        boolean t = inView(this.thresholdFieldRel, 20);
        boolean i = inView(this.inertiaFieldRel, 20);
        boolean x = inView(this.xaeroRel, 20);
        boolean m = inView(this.merchantBarRel, 20);
        boolean w = inView(this.wheelSetupRel, 20);
        boolean wbc = inView(this.wheelBlockCreativeRel, 20);
        boolean winv = inView(this.wheelInvertRel, 20);
        boolean d = inView(this.debugRel, 20);
        if (this.enabledButton != null) {
            this.enabledButton.visible = inView(this.enabledRel, 20);
        }
        if (this.thresholdBox != null) {
            this.thresholdBox.visible = t;
        }
        if (this.thresholdReset != null) {
            this.thresholdReset.visible = t;
        }
        if (this.inertiaBox != null) {
            this.inertiaBox.visible = i;
        }
        if (this.inertiaReset != null) {
            this.inertiaReset.visible = i;
        }
        if (this.xaeroButton != null) {
            this.xaeroButton.visible = x;
        }
        if (this.merchantBarButton != null) {
            this.merchantBarButton.visible = m;
        }
        if (this.wheelSetupButton != null) {
            this.wheelSetupButton.visible = w;
        }
        if (this.wheelBlockCreativeButton != null) {
            this.wheelBlockCreativeButton.visible = wbc;
            this.wheelBlockCreativeButton.active = wbc;
        }
        if (this.wheelInvertButton != null) {
            this.wheelInvertButton.visible = winv;
            this.wheelInvertButton.active = winv;
        }
        if (this.debugButton != null) {
            this.debugButton.visible = d;
        }
    }

    private boolean inView(int rel, int h) {
        int y = contentY(rel);
        return y + h > this.listTop && y < this.listBottom;
    }

    @Override
    public void tick() {
        super.tick();
        this.updateResetButtons();
        this.updateSaveButton();
    }

    private void updateResetButtons() {
        if (this.thresholdReset != null && this.thresholdBox != null) {
            boolean atDefault = this.thresholdBox.getValue().trim()
                    .equals(Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD));
            this.thresholdReset.active = !atDefault;
        }
        if (this.inertiaReset != null && this.inertiaBox != null) {
            boolean atDefault = this.inertiaBox.getValue().trim()
                    .equals(Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH));
            this.inertiaReset.active = !atDefault;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Finger up must move content down (towards the start of the list).
        // Mixin sends positive scrollY on finger-up for non-inverted screens.
        this.scrollAmount -= scrollY * 12.0;
        this.repositionContent();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event != null && isPrimary(event.button()) && this.maxScroll() > 0
                && overScrollbar(event.x(), event.y())) {
            this.draggingBar = true;
            this.scrollFromBar(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.draggingBar && event != null && isPrimary(event.button())) {
            this.scrollFromBar(event.y());
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

    private boolean overScrollbar(double x, double y) {
        int barX = this.width - 12;
        return x >= barX - 4 && x <= barX + SCROLLBAR_W + 4
                && y >= this.listTop && y <= this.listBottom;
    }

    private void scrollFromBar(double mouseY) {
        int view = Math.max(1, this.listBottom - this.listTop);
        int max = this.maxScroll();
        if (max <= 0) {
            return;
        }
        int thumbH = Math.max(16, view * view / Math.max(view + max, 1));
        double t = (mouseY - this.listTop - thumbH / 2.0) / Math.max(1, view - thumbH);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        this.scrollAmount = t * max;
        this.repositionContent();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        guiGraphics.centeredText(this.font, this.title, centerX, 8, 0xFFFFFFFF);

        int enabledDescY = contentY(this.enabledDescRel);
        Component enabledDesc = Component.translatable("dragscroll.config.enabled.description");
        drawWrapped(guiGraphics, enabledDesc, centerX, enabledDescY, 0xFFA0A0A0);
        if (SafeMode.isFallbackActive()) {
            drawWrapped(guiGraphics, this.safeModeStatus(), centerX,
                    enabledDescY + this.wrappedHeight(enabledDesc) + 4, 0xFFFFD060);
        }

        drawWrapped(guiGraphics, "dragscroll.config.threshold.description", centerX, contentY(this.thresholdDescRel), 0xFFA0A0A0);
        if (inView(this.thresholdLabelRel, this.font.lineHeight)) {
            guiGraphics.centeredText(
                    this.font,
                    Component.translatable("dragscroll.config.threshold.label"),
                    centerX,
                    contentY(this.thresholdLabelRel),
                    0xFFFFFFFF
            );
        }

        drawWrapped(guiGraphics, "dragscroll.config.inertia.description", centerX, contentY(this.inertiaDescRel), 0xFFA0A0A0);
        if (inView(this.inertiaLabelRel, this.font.lineHeight)) {
            guiGraphics.centeredText(
                    this.font,
                    Component.translatable("dragscroll.config.inertia.label"),
                    centerX,
                    contentY(this.inertiaLabelRel),
                    0xFFFFFFFF
            );
        }

        drawWrapped(guiGraphics, "dragscroll.config.xaero_slider.description", centerX, contentY(this.xaeroDescRel), 0xFFA0A0A0);
        drawWrapped(guiGraphics, "dragscroll.config.merchant_bar.description", centerX, contentY(this.merchantBarDescRel), 0xFFA0A0A0);
        drawWrapped(guiGraphics, "dragscroll.config.wheel_setup.description", centerX, contentY(this.wheelSetupDescRel), 0xFFA0A0A0);
        drawWrapped(guiGraphics, "dragscroll.config.wheel_block_creative.description", centerX, contentY(this.wheelBlockCreativeDescRel), 0xFFA0A0A0);
        drawWrapped(guiGraphics, "dragscroll.config.wheel_invert.description", centerX, contentY(this.wheelInvertDescRel), 0xFFA0A0A0);
        drawWrapped(guiGraphics, "dragscroll.config.debug.description", centerX, contentY(this.debugDescRel), 0xFFA0A0A0);

        drawScrollbar(guiGraphics);

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
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

    private void drawWrapped(GuiGraphicsExtractor guiGraphics, String key, int centerX, int startY, int color) {
        this.drawWrapped(guiGraphics, Component.translatable(key), centerX, startY, color);
    }

    private void drawWrapped(GuiGraphicsExtractor guiGraphics, Component text, int centerX, int startY, int color) {
        int y = startY;
        for (FormattedCharSequence line : this.font.split(text, this.wrapWidth)) {
            if (y + this.font.lineHeight > this.listTop && y < this.listBottom) {
                guiGraphics.centeredText(this.font, line, centerX, y, color);
            }
            y += this.font.lineHeight;
        }
    }

    private void saveAndClose() {
        int threshold = DragScrollConfig.getDragThreshold();
        try {
            if (!this.thresholdBox.getValue().isEmpty()) {
                threshold = Integer.parseInt(this.thresholdBox.getValue().trim());
            }
        } catch (NumberFormatException ignored) {
        }

        int inertia = DragScrollConfig.getInertiaStrength();
        try {
            if (!this.inertiaBox.getValue().isEmpty()) {
                inertia = Integer.parseInt(this.inertiaBox.getValue().trim());
            }
        } catch (NumberFormatException ignored) {
        }

        DragScrollConfig.setDragThreshold(threshold);
        DragScrollConfig.setInertiaStrength(inertia);
        DragScrollConfig.save();
        if (DragScrollConfig.isEnabled()) {
            // Switching the mod on also undoes an automatic safe mode
            // (takes effect at the next start, when the mixins are applied).
            SafeMode.clearDisableFile();
        }
        this.savedEnabled = DragScrollConfig.isEnabled();
        this.savedThreshold = DragScrollConfig.getDragThreshold();
        this.savedInertia = DragScrollConfig.getInertiaStrength();
        this.savedDebug = DragScrollConfig.isDebugEnabled();
        this.savedXaero = DragScrollConfig.isXaeroSliderEnabled();
        this.savedMerchantBar = DragScrollConfig.isMerchantWideBarEnabled();
        this.savedWheelBlockCreative = DragScrollConfig.isWheelBlockCreativeEnabled();
        this.savedWheelInvert = DragScrollConfig.isWheelInvertEnabled();
        this.updateSaveButton();
        this.minecraft.gui.setScreen(this.parent);
    }

    private Component getDebugButtonText() {
        return Component.translatable(
                DragScrollConfig.isDebugEnabled()
                        ? "dragscroll.config.debug.on"
                        : "dragscroll.config.debug.off"
        );
    }

    private Component getEnabledButtonText() {
        return Component.translatable(
                DragScrollConfig.isEnabled()
                        ? "dragscroll.config.enabled.on"
                        : "dragscroll.config.enabled.off"
        );
    }

    private void updateEnabledButton() {
        if (this.enabledButton != null) {
            this.enabledButton.setMessage(this.getEnabledButtonText());
        }
    }

    private Component getXaeroButtonText() {
        return Component.translatable(
                DragScrollConfig.isXaeroSliderEnabled()
                        ? "dragscroll.config.xaero_slider.on"
                        : "dragscroll.config.xaero_slider.off"
        );
    }

    private void updateDebugButton() {
        if (this.debugButton != null) {
            this.debugButton.setMessage(this.getDebugButtonText());
        }
    }

    private void updateXaeroButton() {
        if (this.xaeroButton != null) {
            this.xaeroButton.setMessage(this.getXaeroButtonText());
        }
    }

    private Component getMerchantBarButtonText() {
        return Component.translatable(
                DragScrollConfig.isMerchantWideBarEnabled()
                        ? "dragscroll.config.merchant_bar.on"
                        : "dragscroll.config.merchant_bar.off"
        );
    }

    private void updateMerchantBarButton() {
        if (this.merchantBarButton != null) {
            this.merchantBarButton.setMessage(this.getMerchantBarButtonText());
        }
    }

    private Component getWheelBlockCreativeButtonText() {
        return Component.translatable(
                DragScrollConfig.isWheelBlockCreativeEnabled()
                        ? "dragscroll.config.wheel_block_creative.on"
                        : "dragscroll.config.wheel_block_creative.off"
        );
    }

    private void updateWheelBlockCreativeButton() {
        if (this.wheelBlockCreativeButton != null) {
            this.wheelBlockCreativeButton.setMessage(this.getWheelBlockCreativeButtonText());
        }
    }

    private Component getWheelInvertButtonText() {
        return Component.translatable(DragScrollConfig.isWheelInvertEnabled()
                ? "dragscroll.config.wheel_invert.on"
                : "dragscroll.config.wheel_invert.off");
    }

    private void updateWheelInvertButton() {
        if (this.wheelInvertButton != null) {
            this.wheelInvertButton.setMessage(this.getWheelInvertButtonText());
        }
    }

    private void updateSaveButton() {
        if (this.saveButton == null) {
            return;
        }
        this.saveButton.active = isDirty();
    }

    private boolean isDirty() {
        int threshold = this.savedThreshold;
        int inertia = this.savedInertia;
        try {
            if (this.thresholdBox != null && !this.thresholdBox.getValue().isEmpty()) {
                threshold = Integer.parseInt(this.thresholdBox.getValue().trim());
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            if (this.inertiaBox != null && !this.inertiaBox.getValue().isEmpty()) {
                inertia = Integer.parseInt(this.inertiaBox.getValue().trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return threshold != this.savedThreshold
                || inertia != this.savedInertia
                || DragScrollConfig.isDebugEnabled() != this.savedDebug
                || DragScrollConfig.isXaeroSliderEnabled() != this.savedXaero
                || DragScrollConfig.isMerchantWideBarEnabled() != this.savedMerchantBar
                || DragScrollConfig.isWheelBlockCreativeEnabled() != this.savedWheelBlockCreative
                || DragScrollConfig.isWheelInvertEnabled() != this.savedWheelInvert
                || DragScrollConfig.isEnabled() != this.savedEnabled;
    }

    private static boolean isPrimary(int button) {
        return button == 0 || button == 1;
    }

    public boolean isOverOwnScrollbar(double x, double y) {
        return this.maxScroll() > 0 && overScrollbar(x, y);
    }

    public boolean isBarDragging() {
        return this.draggingBar;
    }

    /** Cancel / ESC: the toggles apply live, so undo what was not saved. */
    @Override
    public void onClose() {
        DragScrollConfig.setDebugEnabled(this.savedDebug);
        DragScrollConfig.setXaeroSliderEnabled(this.savedXaero);
        DragScrollConfig.setMerchantWideBarEnabled(this.savedMerchantBar);
        DragScrollConfig.setWheelBlockCreativeEnabled(this.savedWheelBlockCreative);
        DragScrollConfig.setWheelInvertEnabled(this.savedWheelInvert);
        DragScrollConfig.setEnabled(this.savedEnabled);
        this.minecraft.gui.setScreen(this.parent);
    }
}
