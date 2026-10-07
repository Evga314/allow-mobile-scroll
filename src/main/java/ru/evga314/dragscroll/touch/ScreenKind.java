package ru.evga314.dragscroll.touch;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;

/**
 * What the mod knows about a screen class, decided once per class.
 *
 * <p>Most mod UIs are recognised by their class name: their libraries are
 * optional, many mods ship their own copy of them, and the names are stable
 * across versions. The input hooks ask these questions on every event, so the
 * answers are cached per class instead of re-scanning the name each time.
 */
public final class ScreenKind {
    private static final ClassValue<ScreenKind> CACHE = new ClassValue<>() {
        @Override
        protected ScreenKind computeValue(Class<?> type) {
            return new ScreenKind(type);
        }
    };

    private static final ScreenKind NONE = new ScreenKind(Object.class);

    /** In-game chat (ChatScreen, SleepingChatScreen). */
    public final boolean chat;
    /** Cloth Config / AutoConfig screens. */
    public final boolean cloth;
    /** Sodium's own option screens. */
    public final boolean sodium;
    /** Reese's Sodium Options (replaces Sodium's video settings). */
    public final boolean reeses;
    /** REI's config screen (Cloth ScrollingContainer lists). */
    public final boolean rei;
    /** TRender / LibGui (Cotton) screens: EntityCulling, Skin Layers 3D, ... */
    public final boolean trender;
    /** Traben's tconfig (Entity Model / Texture Features). */
    public final boolean tconfig;
    /** Shulker Box Tooltip config. */
    public final boolean shulkerConfig;
    /** Yet Another Config Lib. */
    public final boolean yacl;
    /** Easy Install. */
    public final boolean easyInstall;
    /** Screens of MaLiLib and its mods (Litematica, MiniHUD, Tweakeroo, ...). */
    public final boolean malilibFamily;
    /** Xaero's World Map view (not its settings). */
    public final boolean xaeroMap;
    /**
     * mouseScrolled scrolls these screens the opposite way to a vanilla list,
     * so the finger drag sends the opposite sign.
     */
    public final boolean invertedScroll;
    /**
     * Screens scrolled through Screen.mouseScrolled because their lists are no
     * AbstractScrollArea (or none is under the finger).
     */
    public final boolean customScroll;

    private ScreenKind(Class<?> type) {
        String name = type.getName().toLowerCase(Locale.ROOT);

        // MC classes have intermediary names at runtime (class_408): match by type.
        chat = ChatScreen.class.isAssignableFrom(type);
        cloth = name.contains("clothconfig") || name.contains("cloth_config")
                || name.contains("me.shedaniel.autoconfig");
        reeses = name.contains("reeses_sodium_options");
        sodium = !reeses && name.contains("sodium")
                && (name.contains("videosettings") || name.contains("option") || name.contains("gui"));
        rei = name.startsWith("me.shedaniel.rei.impl.client.gui.config.");
        trender = name.contains("trender") || name.contains("cottonclientscreen") || name.contains("cotton.gui")
                || name.contains("libgui") || name.contains("entityculling")
                || name.contains("lightweightguidescription");
        tconfig = name.contains("traben.tconfig") || name.contains("tconfig.gui")
                || name.contains("tconfigscreenlist");
        shulkerConfig = name.contains("shulkerboxtooltip") && name.contains("config");
        yacl = name.contains("yacl") || name.contains("yetanotherconfig") || name.contains("yet_another_config");
        easyInstall = name.contains("easy_install") || name.contains("easyinstall");
        malilibFamily = name.contains("litematica") || name.contains("malilib") || name.contains("tweakeroo")
                || name.contains("minihud") || name.contains("itemscroller") || name.startsWith("fi.dy.masa.");
        xaeroMap = isXaeroMap(name);

        // This mod's own screens scroll the natural way with the inverted sign.
        boolean ownScreen = type.getName().startsWith("ru.evga314.dragscroll.gui.");
        invertedScroll = chat || cloth || yacl || trender || easyInstall || ownScreen || rei
                || name.contains("sodium")
                || name.contains("lambdynlights")
                || name.contains("fzzy")
                || name.contains("midnightlib")
                || name.contains("resourcefulconfig")
                || name.contains("spruceui")
                || name.contains("modmenu")
                || name.contains("shulkerboxtooltip");
        customScroll = invertedScroll || tconfig || shulkerConfig;
    }

    private static boolean isXaeroMap(String name) {
        if (!name.contains("xaero")) {
            return false;
        }
        // Settings, config and profile screens also contain "worldmap".
        if (name.contains("setting") || name.contains("config") || name.contains("option")
                || name.contains("profile") || name.contains("menu")) {
            return false;
        }
        return name.contains("guimap") || name.contains("guiworldmap")
                || name.contains("mapscreen") || name.contains("worldmap");
    }

    public static ScreenKind of(Screen screen) {
        return screen == null ? NONE : CACHE.get(screen.getClass());
    }
}
