package ru.evga314.dragscroll.compat;

import java.util.List;

/** Sodium options-screen hooks, added only for Sodium versions they fit. */
public final class SodiumMixinPlugin extends DragScrollMixinPlugin {
    @Override
    protected void addOptionalMixins(List<String> mixins) {
        // One object parameter (@Coerce), no return value.
        addIfCompatible(mixins, "SodiumPageListMixin",
                "net.caffeinemc.mods.sodium.client.gui.widgets.PageListWidget",
                "switchSelected", "\\(L[^;]+;\\)V");
        addIfCompatible(mixins, "SodiumVideoSettingsMixin",
                "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen",
                "onSectionFocused", "\\(L[^;]+;\\)V");
        // The handlers return a boolean; isMouseOver also reads (x, y).
        addIfCompatible(mixins, "SodiumScrollbarMixin",
                "net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget",
                "mouseDragged", ".*\\)Z",
                "mouseClicked", ".*\\)Z",
                "isMouseOver", "\\(DD\\)Z");
        addIfCompatible(mixins, "SodiumAbstractWidgetMixin",
                "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget",
                "isMouseOver", "\\(DD\\)Z");
    }
}
