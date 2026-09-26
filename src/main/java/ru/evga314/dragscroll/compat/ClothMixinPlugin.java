package ru.evga314.dragscroll.compat;

import java.util.List;

/**
 * Cloth Config hook: only for versions with the exact signature it patches.
 *
 * (An older hook forced isSmoothScrolling() to false on
 * DynamicNewSmoothScrollingEntryListWidget. Cloth's config screens are built
 * on DynamicSmoothScrollingEntryListWidget and read the smoothScrolling field
 * directly, so it never had an effect and was removed.)
 */
public final class ClothMixinPlugin extends DragScrollMixinPlugin {
    @Override
    protected void addOptionalMixins(List<String> mixins) {
        // The handler sets a long return value; a changed return type would
        // throw ClassCastException inside Cloth Config, so it must match.
        addIfCompatible(mixins, "ClothConfigInitializerMixin",
                "me.shedaniel.clothconfig2.ClothConfigInitializer",
                "getScrollDuration", "\\(\\)J");
    }
}
