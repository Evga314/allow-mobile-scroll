package ru.evga314.dragscroll.loader;

import java.util.List;

/** Cloth Config hook, added only for versions with the exact signature it patches. */
public final class ClothMixinPlugin extends DragScrollMixinPlugin {
    @Override
    protected void addOptionalMixins(List<String> mixins) {
        // The handler sets a long return value; a changed return type would
        // throw ClassCastException inside Cloth Config, so it must match.
        addIfCompatible(mixins, "compat.cloth.ClothConfigInitializerMixin",
                "me.shedaniel.clothconfig2.ClothConfigInitializer",
                "getScrollDuration", "\\(\\)J");
    }
}
