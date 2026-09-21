package ru.evga314.dragscroll.modmenu;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import ru.evga314.dragscroll.DragScrollConfig;

public final class DragScrollConfigScreen extends Screen {
    private final Screen parent;
    private EditBox thresholdBox;

    public DragScrollConfigScreen(Screen parent) {
        super(Component.translatable("dragscroll.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 28;

        this.thresholdBox = new EditBox(
                this.font,
                centerX - 100,
                y,
                200,
                20,
                Component.translatable("dragscroll.config.threshold")
        );
        this.thresholdBox.setValue(Integer.toString(DragScrollConfig.getDragThreshold()));
        this.thresholdBox.setMaxLength(2);
        this.addRenderableWidget(this.thresholdBox);
        this.setInitialFocus(this.thresholdBox);

        this.addRenderableWidget(Button.builder(
                Component.translatable("dragscroll.config.save"),
                button -> this.saveAndClose()
        ).pos(centerX - 100, y + 28).size(98, 20).build());

        this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_CANCEL,
                button -> this.minecraft.gui.setScreen(this.parent)
        ).pos(centerX + 2, y + 28).size(98, 20).build());
    }

    private void saveAndClose() {
        int value = DragScrollConfig.getDragThreshold();
        try {
            if (!this.thresholdBox.getValue().isEmpty()) {
                value = Integer.parseInt(this.thresholdBox.getValue());
            }
        } catch (NumberFormatException ignored) {
        }

        DragScrollConfig.setDragThreshold(value);
        DragScrollConfig.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
