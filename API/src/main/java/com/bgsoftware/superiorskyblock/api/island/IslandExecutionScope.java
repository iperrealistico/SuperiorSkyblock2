package com.bgsoftware.superiorskyblock.api.island;

/** Lexical target. A null target suppresses automatic event-location fallback. */
public final class IslandExecutionScope implements AutoCloseable {
    private static final ThreadLocal<IslandExecutionScope> CURRENT = new ThreadLocal<>();
    private final IslandExecutionScope previous;
    private final Island target;
    private final boolean explicit;
    private IslandExecutionScope(Island target, boolean explicit) { this.target = target; this.explicit = explicit; previous = CURRENT.get(); CURRENT.set(this); }
    public static IslandExecutionScope open(Island target) { return new IslandExecutionScope(target, true); }
    public static IslandExecutionScope open(Island target, boolean explicit) { return new IslandExecutionScope(target, explicit); }
    public static Island current() { IslandExecutionScope scope = CURRENT.get(); return scope == null ? null : scope.target; }
    public static boolean isActive() { return CURRENT.get() != null; }
    public static boolean isExplicitTarget() { IslandExecutionScope scope = CURRENT.get(); return scope != null && scope.explicit; }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
