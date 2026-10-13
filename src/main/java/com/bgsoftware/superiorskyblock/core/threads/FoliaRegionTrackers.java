package com.bgsoftware.superiorskyblock.core.threads;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.ServerVersion;
import com.bgsoftware.superiorskyblock.core.QualifiedNativeVersion;
import org.bukkit.World;

import java.lang.reflect.Method;

/** Each installation is executed on the region, and rechecks after split/merge. */
public final class FoliaRegionTrackers {
    private static volatile Method installer;
    private static volatile Method preparer;
    private FoliaRegionTrackers() { }
    public static void install(World world) {
        if (!BukkitExecutor.isFolia()) return;
        try {
            Method method = installer;
            if (method == null) {
                synchronized (FoliaRegionTrackers.class) {
                    method = installer;
                    if (method == null) {
                        String version = ServerVersion.getBukkitVersion();
                        String adapter = QualifiedNativeVersion.adapterFor(version);
                        if (adapter == null) throw new IllegalStateException("Unqualified Folia NMS tracking version: " + version);
                        installer = method = Class.forName("com.bgsoftware.superiorskyblock.platform.folia.nms." + adapter +
                                ".RegionalTrackingFactory", true, SuperiorSkyblockPlugin.getPlugin().getPluginClassLoader())
                                .getMethod("install", World.class);
                    }
                }
            }
            method.invoke(null, world);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Folia regional block tracking installation failed", error);
        }
    }

    public static void prepare(World world) {
        if (!BukkitExecutor.isFolia()) return;
        try {
            Method method = preparer;
            if (method == null) {
                String version = ServerVersion.getBukkitVersion();
                String adapter = QualifiedNativeVersion.adapterFor(version);
                if (adapter == null) throw new IllegalStateException("Unqualified Folia NMS tracking version: " + version);
                preparer = method = Class.forName("com.bgsoftware.superiorskyblock.platform.folia.nms." + adapter +
                        ".RegionalTrackingFactory", true, SuperiorSkyblockPlugin.getPlugin().getPluginClassLoader())
                        .getMethod("prepare", World.class);
            }
            method.invoke(null, world);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Folia regional tracking bootstrap failed", error);
        }
    }
}
