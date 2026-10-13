package com.bgsoftware.superiorskyblock.pvc.entitlement;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional permissions-provider capability for authoritative owner queries, including offline owners. */
public interface OwnerPermissionProvider {
    CompletableFuture<OwnerPermissionData> resolveOwnerPermissions(UUID owner, String stableIslandWorld,
            int gridCap, Set<Integer> configuredRadiusCandidates, int maxMatchingNodes);
}
