package ru.evga314.dragscroll.mixin.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.config.DragScrollConfig;
import ru.evga314.dragscroll.loader.SafeMode;

/**
 * Villager trading screen: the trade scroll bar (thumb and click box) twice
 * as wide, growing to the right. The vanilla bar is 6 px wide with its left
 * edge at {@code leftPos + 94}; the drawn thumb and the drag and click hit
 * boxes all use the literal 6, so widening that literal keeps the left edge
 * and grows the bar rightwards. Off in safe mode and when the setting is off.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {

    /** Width of the wide bar (twice the vanilla 6 px). */
    @Unique
    private static final int DRAGSCROLL_WIDE_WIDTH = 12;

    /** Vanilla scroll track geometry (MerchantScreen constants). */
    @Unique
    private static final int DRAGSCROLL_TRACK_X = 94;
    @Unique
    private static final int DRAGSCROLL_TRACK_Y = 18;
    @Unique
    private static final int DRAGSCROLL_TRACK_HEIGHT = 140;

    @Unique
    private static int dragscroll$barWidth(int vanilla) {
        return !SafeMode.bypass() && DragScrollConfig.isMerchantWideBarEnabled() ? DRAGSCROLL_WIDE_WIDTH : vanilla;
    }

    /** The thumb, the disabled thumb and the hover box in extractScroller. */
    @ModifyConstant(method = "extractScroller", constant = @Constant(intValue = 6), require = 0)
    private int dragscroll$wideScrollerRender(int vanilla) {
        return dragscroll$barWidth(vanilla);
    }

    /** The click box ({@code event.x() < xo + 94 + 6}), so the whole drawn thumb can be grabbed. */
    @ModifyConstant(method = "mouseClicked", constant = @Constant(intValue = 6), require = 0)
    private int dragscroll$wideScrollerClick(int vanilla) {
        return dragscroll$barWidth(vanilla);
    }

    /**
     * The 6 px track groove is part of the villager.png background. A darker
     * track the full width of the wide bar is drawn over it, before the thumb.
     */
    @Inject(method = "extractContents", at = @At("HEAD"))
    private void dragscroll$drawWideTrack(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        if (SafeMode.bypass() || !DragScrollConfig.isMerchantWideBarEnabled()) {
            return;
        }
        AbstractContainerScreenAccessor screen = (AbstractContainerScreenAccessor) this;
        int x0 = screen.dragscroll$getLeftPos() + DRAGSCROLL_TRACK_X;
        int y0 = screen.dragscroll$getTopPos() + DRAGSCROLL_TRACK_Y;
        int x1 = x0 + DRAGSCROLL_WIDE_WIDTH;
        int y1 = y0 + DRAGSCROLL_TRACK_HEIGHT;
        // Sunken groove: dark fill with a subtle bevel, like the vanilla panel.
        graphics.fill(x0, y0, x1, y1, 0xFF373737);
        graphics.fill(x0, y0, x1, y0 + 1, 0xFF2B2B2B);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFF565656);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFF2B2B2B);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFF565656);
    }
}
