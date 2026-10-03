package ru.evga314.dragscroll.mixin.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Page turn arrows of books (reading, lectern, writing). They are 23x13 px,
 * too small for a finger: taps count up to 35 % of their width further to
 * the left and to the right. Only the hit test changes; the arrow is drawn
 * as before.
 *
 * <p>The mixin extends Button so the override is remapped to the runtime
 * (intermediary) name of isMouseOver.
 */
@Mixin(PageButton.class)
public abstract class PageButtonMixin extends Button {

    @Unique
    private static final double DRAGSCROLL_PAD_X = 0.35;

    private PageButtonMixin(int x, int y, int width, int height, Component message,
                            OnPress onPress, CreateNarration narration) {
        super(x, y, width, height, message, onPress, narration);
    }

    /** Overrides AbstractWidget.isMouseOver (PageButton does not declare one). */
    @Override
    public boolean isMouseOver(double x, double y) {
        double pad = SafeMode.bypass() ? 0.0 : this.getWidth() * DRAGSCROLL_PAD_X;
        return this.isActive()
                && x >= this.getX() - pad && x < this.getRight() + pad
                && y >= this.getY() && y < this.getBottom();
    }
}
