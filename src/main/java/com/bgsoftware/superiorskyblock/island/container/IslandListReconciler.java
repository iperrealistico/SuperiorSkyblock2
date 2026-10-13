package com.bgsoftware.superiorskyblock.island.container;

import com.bgsoftware.superiorskyblock.api.island.Island;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A stale sort may keep order, but must never resurrect removed islands or omit new ones. */
final class IslandListReconciler {
    private IslandListReconciler() { }
    static List<Island> reconcile(List<Island> sorted, Collection<Island> current) {
        Map<UUID, Island> remaining = new HashMap<>();
        for (Island island : current) if (!island.isIgnored()) remaining.put(island.getUniqueId(), island);
        List<Island> result = new ArrayList<>(remaining.size());
        for (Island island : sorted) {
            Island live = remaining.remove(island.getUniqueId());
            if (live != null) result.add(live);
        }
        result.addAll(remaining.values());
        return result;
    }
}
