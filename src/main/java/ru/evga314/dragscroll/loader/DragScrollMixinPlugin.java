package ru.evga314.dragscroll.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mixin plugin of every config of this mod, and base of the Cloth Config and
 * Sodium plugins.
 *
 * <ul>
 *   <li>Safe mode ({@link SafeMode}): no mixin is applied, the game is vanilla.</li>
 *   <li>The Cloth and Sodium configs list no mixins in their JSON. Their
 *       plugins add a mixin only when its target class is installed and still
 *       has the method signature the handler was written for, so another
 *       version of those mods skips that one hook instead of crashing.</li>
 * </ul>
 */
public class DragScrollMixinPlugin implements IMixinConfigPlugin {
    protected static final Logger LOGGER = LoggerFactory.getLogger("dragscroll");

    @Override
    public void onLoad(String mixinPackage) {
        // Decide (and log) the safe mode before the first mixin is applied.
        SafeMode.isStartupSafe();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !SafeMode.isStartupSafe();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        if (SafeMode.isStartupSafe()) {
            return null;
        }
        List<String> mixins = new ArrayList<>();
        this.addOptionalMixins(mixins);
        return mixins.isEmpty() ? null : mixins;
    }

    /** Optional configs add the mixins whose targets are present and compatible. */
    protected void addOptionalMixins(List<String> mixins) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    // ---------------------------------------------------------------- helpers

    /** Reads a class without loading it; null when it is not installed. */
    protected static ClassNode readClass(String className) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = DragScrollMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The first declared overload of {@code name} whose descriptor does not
     * match {@code descriptorRegex}, or null when all of them match (or none
     * exists: Mixin simply skips a require = 0 injector without a target).
     * A name-only injector hits every overload, so each one must fit the
     * handler; a mismatch would fail at class load or throw inside the mod.
     */
    protected static String incompatibleOverload(ClassNode node, String name, String descriptorRegex) {
        if (node.methods == null) {
            return null;
        }
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && !m.desc.matches(descriptorRegex)) {
                return m.name + m.desc;
            }
        }
        return null;
    }

    /**
     * Adds {@code mixin} when {@code target} is installed and every method
     * the mixin injects into has the signature its handler was written for.
     * {@code methodChecks} holds pairs: method name, descriptor regex.
     * Otherwise the hook is left out and the reason is logged once at start,
     * so the other mod keeps its own behaviour instead of crashing.
     */
    protected static void addIfCompatible(List<String> mixins, String mixin, String target,
                                          String... methodChecks) {
        ClassNode node = readClass(target);
        if (node == null) {
            return;
        }
        for (int i = 0; i + 1 < methodChecks.length; i += 2) {
            String bad = incompatibleOverload(node, methodChecks[i], methodChecks[i + 1]);
            if (bad != null) {
                LOGGER.info("[Allow mobile scroll] {} skipped: {}.{} has an unexpected signature in this version,"
                        + " keeping its own behaviour", mixin, target, bad);
                return;
            }
        }
        mixins.add(mixin);
    }
}
