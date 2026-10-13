package com.bgsoftware.superiorskyblock.pvc.island;

/** Expected admission result, distinct from failed SQL or world operations. */
public final class PvcCreationDeniedException extends IllegalStateException {
    public enum Reason { QUOTA_REACHED, QUEUE_FULL, NAME_OCCUPIED, POLICY_UNAVAILABLE }
    private final Reason reason;
    private final int limit;
    public PvcCreationDeniedException(Reason reason, int limit) { this(reason, limit, null); }
    public PvcCreationDeniedException(Reason reason, int limit, Throwable cause) {
        super(reason == Reason.QUOTA_REACHED ? "Owned island limit reached" : "Island creation denied: " + reason, cause);
        this.reason = reason; this.limit = limit;
    }
    public Reason getReason() { return reason; }
    public int getLimit() { return limit; }
    public static PvcCreationDeniedException find(Throwable error) {
        int depth = 0;
        for (Throwable current = error; current != null && depth++ < 64 && current.getCause() != current; current = current.getCause())
            if (current instanceof PvcCreationDeniedException) return (PvcCreationDeniedException) current;
        return null;
    }
}
