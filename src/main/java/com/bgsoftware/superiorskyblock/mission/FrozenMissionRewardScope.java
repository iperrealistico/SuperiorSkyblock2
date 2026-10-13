package com.bgsoftware.superiorskyblock.mission;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** A scheduled reward retains its completed subject and never follows a later selection. */
final class FrozenMissionRewardScope {
    private final Island island;
    private final UUID islandId, owner;
    private final long revision;
    private final Predicate<Island> available;
    private final ToLongFunction<Island> revisionOf;

    FrozenMissionRewardScope(Island island, Predicate<Island> available, ToLongFunction<Island> revisionOf) {
        this.island = island;
        this.islandId = island == null ? null : island.getUniqueId();
        this.owner = island == null || island.getOwner() == null ? null : island.getOwner().getUniqueId();
        this.revision = island == null ? 0L : revisionOf.applyAsLong(island);
        this.available = available;
        this.revisionOf = revisionOf;
    }

    boolean isCurrent() {
        return island == null || (Objects.equals(islandId, island.getUniqueId()) && available.test(island) &&
                Objects.equals(owner, island.getOwner() == null ? null : island.getOwner().getUniqueId()) &&
                revision == revisionOf.applyAsLong(island));
    }

    boolean runIfCurrent(Runnable action) {
        if (!isCurrent()) return false;
        try (IslandExecutionScope ignored = IslandExecutionScope.open(island, true)) {
            action.run();
        }
        return true;
    }
}
