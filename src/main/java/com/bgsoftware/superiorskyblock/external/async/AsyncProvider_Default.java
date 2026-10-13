package com.bgsoftware.superiorskyblock.external.async;

import com.bgsoftware.superiorskyblock.external.async.AsyncProvider;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

public class AsyncProvider_Default implements AsyncProvider {

    @Override
    public void teleport(Entity entity, Location location, Consumer<Boolean> teleportResult) {
        if (com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.isFolia())
            throw new IllegalStateException("Folia requires the asynchronous teleport provider");
        com.bgsoftware.superiorskyblock.core.threads.BukkitExecutor.entity(entity, () -> {
            boolean result = entity.teleport(location);
            if (teleportResult != null) teleportResult.accept(result);
        }, 0L, () -> { if (teleportResult != null) teleportResult.accept(false); });
    }

}
