package ru.evga314.dragscroll.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.evga314.dragscroll.DragScrollConfig;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Vanilla fallback. When it is on, the game runs exactly as if this mod's
 * input handling did not exist.
 *
 * <p>Two levels:
 * <ul>
 *   <li><b>Startup safe mode</b> (decided once, before any mixin is applied):
 *       no mixin of this mod is applied at all and the client entrypoint
 *       registers nothing. Triggered by the JVM argument
 *       {@code -Dallowmobilescroll.safemode=true}, by the file
 *       {@code config/allowmobilescroll.disable}, by {@code "enabled": false}
 *       in {@code config/allowmobilescroll.json}, or automatically when the
 *       newest crash report was thrown from this mod's code (the file above
 *       is then written, so the fallback sticks until the user undoes it).</li>
 *   <li><b>Runtime fallback</b> (this session only): the mixins stay in place
 *       but every hook passes straight through to vanilla. Triggered when the
 *       mod is switched off in its settings, or when its hooks fail
 *       repeatedly.</li>
 * </ul>
 *
 * <p>This class is loaded by the mixin plugin before Minecraft starts, so it
 * must not reference any Minecraft class.
 */
public final class SafeMode {
    private SafeMode() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("dragscroll");

    public static final String DISABLE_FILE = "allowmobilescroll.disable";
    public static final String SAFE_MODE_PROPERTY = "allowmobilescroll.safemode";
    private static final String CRASH_STAMP_FILE = "allowmobilescroll.crashcheck";
    private static final Pattern ENABLED_FALSE = Pattern.compile("\"enabled\"\\s*:\\s*false");
    /** Report name inside an automatic marker file. */
    private static final Pattern MARKER_REPORT = Pattern.compile("crash-reports/(crash-[^\\s)]+\\.txt)");
    /** "allowmobilescroll: Allow mobile scroll 1.4.2" in a crash report's mod list. */
    private static final Pattern MOD_LIST_ENTRY = Pattern.compile("(?m)^\\s*allowmobilescroll: (.*)$");

    /** Hook failures within the window that switch the session to vanilla input. */
    private static final int RUNTIME_FAILURE_LIMIT = 3;
    private static final long RUNTIME_FAILURE_WINDOW_NS = 10_000_000_000L;
    private static final int MAX_CRASH_REPORTS_SCANNED = 5;
    private static final int MAX_CRASH_REPORT_BYTES = 512 * 1024;

    private static volatile boolean decided;
    private static volatile boolean startupSafe;
    private static volatile boolean runtimeDisabled;
    private static volatile String reason = "";
    /** The fallback was chosen by the mod itself (crash report, marker file, repeated errors). */
    private static volatile boolean automatic;
    private static int failures;
    private static long firstFailureNs;
    private static Runnable runtimeDisableHook;

    // =====================================================================
    // Queries
    // =====================================================================

    /** Startup safe mode: this launch applies no mixin of this mod. */
    public static boolean isStartupSafe() {
        if (!decided) {
            decide();
        }
        return startupSafe;
    }

    /**
     * Hot-path check for every hook: true means "behave like vanilla now".
     */
    public static boolean bypass() {
        if (!decided) {
            decide();
        }
        return startupSafe || runtimeDisabled || !DragScrollConfig.isEnabled();
    }

    /** Vanilla input is in effect for this session, for whatever reason. */
    public static boolean isFallbackActive() {
        return isStartupSafe() || runtimeDisabled;
    }

    /** True when the mod switched itself to vanilla, not the user. */
    public static boolean isAutomatic() {
        return automatic;
    }

    /** Human-readable reason for the current fallback, or "". */
    public static String reason() {
        return reason;
    }

    // =====================================================================
    // Startup decision
    // =====================================================================

    private static synchronized void decide() {
        if (decided) {
            return;
        }
        String why = null;
        boolean auto = false;
        try {
            if (Boolean.getBoolean(SAFE_MODE_PROPERTY)) {
                why = "JVM argument -D" + SAFE_MODE_PROPERTY + "=true";
            }
            Path configDir = FabricLoader.getInstance().getConfigDir();
            if (why == null && Files.exists(configDir.resolve(DISABLE_FILE))) {
                if (isStaleAutoMarker(configDir, FabricLoader.getInstance().getGameDir())) {
                    // Left by 1.4.1 for a crash of an older version of the mod.
                    Files.deleteIfExists(configDir.resolve(DISABLE_FILE));
                    LOGGER.info("[Allow mobile scroll] removed config/{}: the crash it pointed at was not"
                            + " thrown by this version of the mod", DISABLE_FILE);
                } else {
                    why = "the file config/" + DISABLE_FILE + " exists";
                    auto = true;
                }
            }
            if (why == null && configSaysDisabled(configDir)) {
                why = "the mod is switched off in its settings (\"enabled\": false)";
            }
            if (why == null) {
                String report = scanCrashReports(FabricLoader.getInstance().getGameDir(), configDir);
                if (report != null) {
                    why = "the last crash (crash-reports/" + report + ") came from this mod";
                    auto = true;
                    writeDisableFile(configDir, why);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[Allow mobile scroll] safe-mode check failed, starting normally", t);
        }
        startupSafe = why != null;
        automatic = auto;
        reason = why == null ? "" : why;
        decided = true;
        if (startupSafe) {
            LOGGER.warn("[Allow mobile scroll] SAFE MODE: no mixins applied, the game uses vanilla input. Reason: {}."
                    + " To turn the mod back on, switch it on in its settings (Mod Menu) or delete config/{},"
                    + " then restart.", why, DISABLE_FILE);
        }
    }

    private static boolean configSaysDisabled(Path configDir) {
        for (String name : new String[]{"allowmobilescroll.json", "dragscroll.json"}) {
            Path file = configDir.resolve(name);
            try {
                if (Files.exists(file)) {
                    return ENABLED_FALSE.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * Returns the name of a crash report newer than the last check whose
     * exception was thrown from this mod, or null. Every report is judged
     * once: the newest timestamp seen is stored, so turning the mod back on
     * is not undone by the same old report on the next start.
     */
    private static String scanCrashReports(Path gameDir, Path configDir) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        long stamp = readStamp(configDir);
        if (stamp <= 0L) {
            // First start with this check: every report already there was
            // written before it ran, typically by an older version of the
            // mod. Only crashes from now on are judged.
            writeStamp(configDir, System.currentTimeMillis());
            return null;
        }
        List<Path> fresh = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("crash-") && n.endsWith(".txt") && modified(p) > stamp;
            }).forEach(fresh::add);
        } catch (Throwable t) {
            return null;
        }
        if (fresh.isEmpty()) {
            return null;
        }
        fresh.sort(Comparator.comparingLong(SafeMode::modified).reversed());
        writeStamp(configDir, modified(fresh.get(0)));
        for (int i = 0; i < Math.min(MAX_CRASH_REPORTS_SCANNED, fresh.size()); i++) {
            Path report = fresh.get(i);
            if (blamesThisVersion(readHead(report), currentVersion())) {
                return report.getFileName().toString();
            }
        }
        return null;
    }

    /**
     * An automatic marker that points at a crash report which this version of
     * the mod would not blame on itself (an older version crashed there).
     * A marker made by hand has no report name and is always respected.
     */
    private static boolean isStaleAutoMarker(Path configDir, Path gameDir) {
        try {
            String text = Files.readString(configDir.resolve(DISABLE_FILE), StandardCharsets.UTF_8);
            Matcher m = MARKER_REPORT.matcher(text);
            if (!m.find()) {
                return false;
            }
            Path report = gameDir.resolve("crash-reports").resolve(m.group(1));
            return !Files.exists(report) || !blamesThisVersion(readHead(report), currentVersion());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String currentVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("allowmobilescroll")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@link #blamesThisMod} for the running version only: when the report
     * lists the loaded mods, this mod must be in it with the same version.
     * A crash of an older build says nothing about this one.
     */
    public static boolean blamesThisVersion(String report, String version) {
        if (!blamesThisMod(report)) {
            return false;
        }
        if (version == null) {
            return true;
        }
        Matcher mods = MOD_LIST_ENTRY.matcher(report);
        boolean listed = false;
        while (mods.find()) {
            listed = true;
            if (mods.group(1).trim().endsWith(" " + version)) {
                return true;
            }
        }
        // No mod list in the report (very early crash): trust the timestamp.
        return !listed && !report.contains("Fabric Mods:");
    }

    /**
     * True when a crash report was thrown from this mod: the top frame of
     * the exception (or of one of its causes) is our code, or a mixin error
     * names one of our mixin configs. Crashes that only pass through our
     * hooks further down the stack (another mod's screen failing inside a
     * replayed click) do not count, and neither does running out of memory.
     */
    public static boolean blamesThisMod(String report) {
        if (report == null) {
            return false;
        }
        int start = report.indexOf("Description:");
        if (start < 0) {
            start = 0;
        }
        int end = report.indexOf("A detailed walkthrough of the error", start);
        if (end < 0) {
            end = Math.min(report.length(), start + 40_000);
        }
        String header = null;
        for (String raw : report.substring(start, end).split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("...") || line.startsWith("Description:")) {
                continue;
            }
            if (line.startsWith("at ")) {
                if (header != null) {
                    if (!header.contains("OutOfMemoryError") && isOurFrame(line)) {
                        return true;
                    }
                    header = null;
                }
                continue;
            }
            header = line;
            if ((line.contains("Exception") || line.contains("Error"))
                    && line.contains("dragscroll.") && line.contains(".mixins.json")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOurFrame(String frame) {
        return frame.contains("ru.evga314.dragscroll") || frame.contains("dragscroll$");
    }

    private static String readHead(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_CRASH_REPORT_BYTES), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long readStamp(Path configDir) {
        try {
            return Long.parseLong(Files.readString(configDir.resolve(CRASH_STAMP_FILE), StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void writeStamp(Path configDir, long value) {
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve(CRASH_STAMP_FILE), Long.toString(value), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static void writeDisableFile(Path configDir, String why) {
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve(DISABLE_FILE),
                    "Allow mobile scroll is in safe mode (vanilla input) because " + why + ".\n"
                            + "Delete this file, or switch the mod on in its settings, and restart the game\n"
                            + "to turn the mod back on.\n",
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    /** Called when the user switches the mod back on in its settings. */
    public static void clearDisableFile() {
        try {
            Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve(DISABLE_FILE));
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // Runtime fallback
    // =====================================================================

    /** Set by the client entrypoint: resets gesture state and shows a toast. */
    public static void setRuntimeDisableHook(Runnable hook) {
        runtimeDisableHook = hook;
    }

    /**
     * An unexpected error escaped one of the input hooks. The first one is
     * logged with its stack trace; several in a short time switch this
     * session to vanilla input instead of misbehaving on every frame.
     */
    public static void reportFailure(String where, Throwable t) {
        if (runtimeDisabled || startupSafe) {
            return;
        }
        long now = System.nanoTime();
        if (failures == 0 || now - firstFailureNs > RUNTIME_FAILURE_WINDOW_NS) {
            failures = 0;
            firstFailureNs = now;
        }
        failures++;
        if (failures == 1) {
            LOGGER.warn("[Allow mobile scroll] unexpected error in {}", where, t);
        }
        if (failures >= RUNTIME_FAILURE_LIMIT) {
            runtimeDisabled = true;
            automatic = true;
            reason = "repeated errors in " + where + " (" + t + ")";
            LOGGER.error("[Allow mobile scroll] switching to vanilla input for this session: {}", reason);
            Runnable hook = runtimeDisableHook;
            if (hook != null) {
                try {
                    hook.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
