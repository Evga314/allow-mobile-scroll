package ru.evga314.dragscroll.mixin.screen;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Recipe book page arrows are 12x17 px, too small for a finger. Accept taps
 * in a larger box around each arrow (35% of the width on each side, 25% of
 * the height above and below) by moving such a tap onto the arrow itself.
 * The arrows are tested before the recipe buttons, so they win the thin
 * overlap with neighbours.
 */
@Mixin(RecipeBookPage.class)
public abstract class RecipeBookPageMixin {

    @Unique
    private static final double DRAGSCROLL_ARROW_PAD_X = 0.35;
    @Unique
    private static final double DRAGSCROLL_ARROW_PAD_Y = 0.25;

    @Redirect(
            method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;IIIIZ)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ImageButton;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"
            ),
            require = 0
    )
    private boolean dragscroll$widenArrowHitbox(ImageButton button, MouseButtonEvent event, boolean doubleClick) {
        if (SafeMode.bypass() || button == null || event == null || !button.isActive()) {
            return button != null && button.mouseClicked(event, doubleClick);
        }
        double padX = button.getWidth() * DRAGSCROLL_ARROW_PAD_X;
        double padY = button.getHeight() * DRAGSCROLL_ARROW_PAD_Y;
        double x = event.x();
        double y = event.y();
        boolean inWideBox = x >= button.getX() - padX && x < button.getX() + button.getWidth() + padX
                && y >= button.getY() - padY && y < button.getY() + button.getHeight() + padY;
        if (inWideBox && !button.isMouseOver(x, y)) {
            event = new MouseButtonEvent(
                    button.getX() + button.getWidth() / 2.0,
                    button.getY() + button.getHeight() / 2.0,
                    event.buttonInfo());
        }
        return button.mouseClicked(event, doubleClick);
    }
}
