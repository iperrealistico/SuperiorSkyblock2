package com.bgsoftware.superiorskyblock.core.menu.view;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity/epoch receipt for a deferred island menu action. */
final class IslandActionGuard {
    final UUID island, owner;
    final long revision, membershipRevision, createdAt;

    IslandActionGuard(UUID island, UUID owner, long revision, long membershipRevision, long createdAt) {
        this.island = island; this.owner = owner; this.revision = revision;
        this.membershipRevision = membershipRevision; this.createdAt = createdAt;
    }

    boolean contextCurrent(UUID currentIsland, UUID currentOwner, long currentRevision, long currentMembershipRevision, boolean available) {
        return island == null || available && Objects.equals(island, currentIsland) &&
                Objects.equals(owner, currentOwner) && revision == currentRevision && membershipRevision == currentMembershipRevision;
    }

    boolean actionCurrent(UUID currentIsland, UUID currentOwner, long currentRevision,
                          long currentMembershipRevision, long now, boolean available) {
        return island != null && now >= createdAt && now - createdAt <= 60000L &&
                contextCurrent(currentIsland, currentOwner, currentRevision, currentMembershipRevision, available);
    }
}
