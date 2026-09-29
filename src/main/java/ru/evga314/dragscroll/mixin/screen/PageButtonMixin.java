package ru.evga314.dragscroll.mixin.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.PageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Page turn arrows of books (reading, lectern, writing). They are 23x13 px,
 * too small for a finger: taps count up to 35 % of their width further to
 * the left and to the right. Only the hit test changes; the arrow is drawn
 * as before.
 */
@Mixin(PageButton.class)
public abstract class PageButtonMixin {

    @Unique
    private static final double DRAGSCROLL_PAD_X = 0.35;

    /** Overrides AbstractWidget.isMouseOver (PageButton does not declare one). */
    public boolean isMouseOver(double x, double y) {
        AbstractWidget self = (AbstractWidget) (Object) this;
        double pad = SafeMode.bypass() ? 0.0 : self.getWidth() * DRAGSCROLL_PAD_X;
        return self.isActive()
                && x >= self.getX() - pad && x < self.getRight() + pad
                && y >= self.getY() && y < self.getBottom();
    }
}
