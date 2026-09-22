package ru.evga314.dragscroll.modmenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import ru.evga314.dragscroll.DragScrollConfig;

public final class DragScrollConfigScreen extends Screen {
    private final Screen parent;
    private EditBox thresholdBox;
    private EditBox inertiaBox;
    private Button debugButton;

    public DragScrollConfigScreen(Screen parent) {
        super(Component.translatable("dragscroll.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        // Threshold field
        int thresholdY = this.height / 2 - 40;
        this.thresholdBox = new EditBox(
                this.font,
                centerX - 100,
                thresholdY,
                200,
                20,
                Component.translatable("dragscroll.config.threshold")
        );
        this.thresholdBox.setValue(Integer.toString(DragScrollConfig.getDragThreshold()));
        this.thresholdBox.setMaxLength(2);
        this.addRenderableWidget(this.thresholdBox);
        this.setInitialFocus(this.thresholdBox);

        // Inertia strength field + small reset button beside it
        int inertiaY = thresholdY + 52;
        this.inertiaBox = new EditBox(
                this.font,
                centerX - 100,
                inertiaY,
                160,
                20,
                Component.translatable("dragscroll.config.inertia")
        );
        this.inertiaBox.setValue(Integer.toString(DragScrollConfig.getInertiaStrength()));
        this.inertiaBox.setMaxLength(3);
        this.addRenderableWidget(this.inertiaBox);

        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.inertia.reset"),
                button -> this.inertiaBox.setValue(
                        Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH))
        ).pos(centerX + 66, inertiaY).size(34, 20).build());

        // Save / Reset all / Cancel
        int buttonsY = inertiaY + 28;
        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.save"),
                button -> this.saveAndClose()
        ).pos(centerX - 100, buttonsY).size(64, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.reset"),
                button -> {
                    this.thresholdBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_DRAG_THRESHOLD));
                    this.inertiaBox.setValue(Integer.toString(DragScrollConfig.DEFAULT_INERTIA_STRENGTH));
                    DragScrollConfig.setDebugEnabled(false);
                    this.updateDebugButton();
                }
        ).pos(centerX - 32, buttonsY).size(64, 20).build());

        this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_CANCEL,
                button -> this.minecraft.gui.setScreen(this.parent)
        ).pos(centerX + 36, buttonsY).size(64, 20).build());

        // Debug toggle
        this.debugButton = this.addRenderableWidget(Button.builder(
                this.getDebugButtonText(),
                button -> {
                    DragScrollConfig.setDebugEnabled(!DragScrollConfig.isDebugEnabled());
                    this.updateDebugButton();
                }
        ).pos(centerX - 100, buttonsY + 26).size(200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int thresholdY = this.height / 2 - 40;
        int inertiaY = thresholdY + 52;

        guiGraphics.centeredText(this.font, this.title, centerX, this.height / 2 - 100, 0xFFFFFFFF);

        // Threshold description (above field)
        int descY = this.height / 2 - 84;
        for (var line : this.font.split(Component.translatable("dragscroll.config.threshold.description"), 360)) {
            guiGraphics.centeredText(this.font, line, centerX, descY, 0xFFA0A0A0);
            descY += 9;
        }
        guiGraphics.centeredText(
                this.font,
                Component.translatable("dragscroll.config.threshold.label"),
                centerX,
                thresholdY - 12,
                0xFFFFFFFF
        );

        // Inertia description sits between the two fields
        int inertiaDescY = thresholdY + 24;
        for (var line : this.font.split(Component.translatable("dragscroll.config.inertia.description"), 360)) {
            guiGraphics.centeredText(this.font, line, centerX, inertiaDescY, 0xFFA0A0A0);
            inertiaDescY += 9;
        }
        guiGraphics.centeredText(
                this.font,
                Component.translatable("dragscroll.config.inertia.label"),
                centerX,
                inertiaY - 12,
                0xFFFFFFFF
        );

        // Debug description under the debug button
        int debugDescriptionY = this.height / 2 + 78;
        for (var line : this.font.split(Component.translatable("dragscroll.config.debug.description"), 360)) {
            guiGraphics.centeredText(this.font, line, centerX, debugDescriptionY, 0xFFA0A0A0);
            debugDescriptionY += 9;
        }

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
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
        this.minecraft.gui.setScreen(this.parent);
    }

    private Component getDebugButtonText() {
        return Component.translatable(
                DragScrollConfig.isDebugEnabled()
                        ? "dragscroll.config.debug.on"
                        : "dragscroll.config.debug.off"
        );
    }

    private void updateDebugButton() {
        if (this.debugButton != null) {
            this.debugButton.setMessage(this.getDebugButtonText());
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
