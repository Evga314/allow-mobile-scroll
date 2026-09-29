package ru.evga314.dragscroll.loader;

import java.util.List;

/** Sodium option-screen hooks, added only for Sodium versions they fit. */
public final class SodiumMixinPlugin extends DragScrollMixinPlugin {
    @Override
    protected void addOptionalMixins(List<String> mixins) {
        // The handlers return a boolean; isMouseOver also reads (x, y).
        addIfCompatible(mixins, "compat.sodium.SodiumScrollbarMixin",
                "net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget",
                "mouseDragged", ".*\\)Z",
                "mouseClicked", ".*\\)Z",
                "isMouseOver", "\\(DD\\)Z");
        addIfCompatible(mixins, "compat.sodium.SodiumAbstractWidgetMixin",
                "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget",
                "isMouseOver", "\\(DD\\)Z");
    }
}
