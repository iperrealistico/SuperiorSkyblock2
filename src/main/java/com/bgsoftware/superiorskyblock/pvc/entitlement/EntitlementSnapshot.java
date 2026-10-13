package com.bgsoftware.superiorskyblock.pvc.entitlement;

/** Immutable owner policy. Last-known state is explicitly distinct from a fresh grant. */
public final class EntitlementSnapshot {
    private final int ownershipLimit;
    private final int radius;
    private final long verifiedAtMillis;
    private final long revision;
    private final boolean authoritative;
    private final long ownerEpoch;
    private final long globalEpoch;

    public EntitlementSnapshot(int ownershipLimit, int radius, long verifiedAtMillis,
                               long revision, boolean authoritative) {
        this(ownershipLimit, radius, verifiedAtMillis, revision, authoritative, 0L, 0L);
    }
    public EntitlementSnapshot(int ownershipLimit, int radius, long verifiedAtMillis,
                               long revision, boolean authoritative, long ownerEpoch, long globalEpoch) {
        this.ownershipLimit = ownershipLimit;
        this.radius = radius;
        this.verifiedAtMillis = verifiedAtMillis;
        this.revision = revision;
        this.authoritative = authoritative;
        this.ownerEpoch = ownerEpoch;
        this.globalEpoch = globalEpoch;
    }
    public int getOwnershipLimit() { return ownershipLimit; }
    public int getRadius() { return radius; }
    public long getVerifiedAtMillis() { return verifiedAtMillis; }
    public long getRevision() { return revision; }
    public boolean isAuthoritative() { return authoritative; }
    public long getOwnerEpoch() { return ownerEpoch; }
    public long getGlobalEpoch() { return globalEpoch; }
    public boolean isFresh(long nowMillis, long ttlMillis) {
        return authoritative && nowMillis >= verifiedAtMillis && nowMillis - verifiedAtMillis < ttlMillis;
    }
    public EntitlementSnapshot lastKnown() {
        return new EntitlementSnapshot(ownershipLimit, radius, verifiedAtMillis, revision, false, ownerEpoch, globalEpoch);
    }
}
