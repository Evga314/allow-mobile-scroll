package ru.evga314.dragscroll.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.evga314.dragscroll.DragScrollConfig;
import ru.evga314.dragscroll.compat.SafeMode;

/**
 * Villager trading screen: make the trade scrollbar (thumb + click hitbox)
 * twice as wide, extending to the right. The vanilla bar is 6 px wide with its
 * left edge at {@code leftPos + 94}; both the drawn scroller sprites and the
 * drag/cursor hitbox use the literal {@code 6}, so widening that single width
 * value keeps the left edge fixed and grows the bar rightwards.
 *
 * <p>Gated by {@link DragScrollConfig#isMerchantWideBarEnabled()} and by
 * {@link SafeMode}: when off, the vanilla width is returned unchanged.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {

    /** Width used when the wide bar is on (2x of the vanilla 6 px). */
    private static final int DRAGSCROLL_WIDE_SCROLLER_WIDTH = 12;

    /** Vanilla scroll track geometry (from MerchantScreen constants). */
    private static final int DRAGSCROLL_SCROLL_BAR_START_X = 94;
    private static final int DRAGSCROLL_SCROLL_BAR_TOP_Y = 18;
    private static final int DRAGSCROLL_SCROLL_BAR_HEIGHT = 140;

    /**
     * Both scroller widths and the hover hitbox in extractScroller use the
     * literal 6 (SCROLLER_WIDTH). Widen all of them together so the thumb, the
     * disabled thumb and the pointer region stay consistent.
     */
    @ModifyConstant(
            method = "extractScroller",
            constant = @Constant(intValue = 6),
            require = 0)
    private int dragscroll$wideScrollerRender(int original) {
        return dragscroll$scrollerWidth(original);
    }

    /**
     * mouseClicked uses the same literal 6 for the click hitbox
     * ({@code event.x() < xo + 94 + 6}). Widen it so the bigger thumb is
     * grabbable across its full drawn width.
     */
    @ModifyConstant(
            method = "mouseClicked",
            constant = @Constant(intValue = 6),
            require = 0)
    private int dragscroll$wideScrollerClick(int original) {
        return dragscroll$scrollerWidth(original);
    }

    /**
     * Draw a wider scroll track over the vanilla one. The vanilla track is baked
     * into the villager.png background (6 px wide); the widened thumb otherwise
     * sits over a too-narrow groove. This paints a matching darker track the full
     * width of the widened bar, right before the thumb is drawn on top.
     */
    @Inject(method = "extractContents", at = @At("HEAD"))
    private void dragscroll$drawWideTrack(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                          float partialTick, CallbackInfo ci) {
        if (SafeMode.bypass() || !DragScrollConfig.isMerchantWideBarEnabled()) {
            return;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) this;
        int leftPos = accessor.dragscroll$getLeftPos();
        int topPos = accessor.dragscroll$getTopPos();

        int x0 = leftPos + DRAGSCROLL_SCROLL_BAR_START_X;
        int y0 = topPos + DRAGSCROLL_SCROLL_BAR_TOP_Y;
        int x1 = x0 + DRAGSCROLL_WIDE_SCROLLER_WIDTH;
        int y1 = y0 + DRAGSCROLL_SCROLL_BAR_HEIGHT;
        // Sunken groove: dark fill + subtle 3D border, matching the vanilla panel.
        graphics.fill(x0, y0, x1, y1, 0xFF373737);
        graphics.fill(x0, y0, x1, y0 + 1, 0xFF2B2B2B);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFF565656);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFF2B2B2B);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFF565656);
    }

    private static int dragscroll$scrollerWidth(int original) {
        if (!SafeMode.bypass() && DragScrollConfig.isMerchantWideBarEnabled()) {
            return DRAGSCROLL_WIDE_SCROLLER_WIDTH;
        }
        return original;
    }
}
