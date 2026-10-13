package com.bgsoftware.superiorskyblock.api.island;

/** Lexical target. A null target suppresses automatic event-location fallback. */
public final class IslandExecutionScope implements AutoCloseable {
    private static final ThreadLocal<IslandExecutionScope> CURRENT = new ThreadLocal<>();
    private final IslandExecutionScope previous;
    private final Island target;
    private final boolean explicit;
    private final boolean inherited;
    private IslandExecutionScope(Island target, boolean explicit, boolean inherited) { this.target = target; this.explicit = explicit; this.inherited = inherited; previous = CURRENT.get(); CURRENT.set(this); }
    public static IslandExecutionScope open(Island target) { return new IslandExecutionScope(target, true, false); }
    public static IslandExecutionScope open(Island target, boolean explicit) { return new IslandExecutionScope(target, explicit, false); }
    /** A captured console chain supplies a nickname fallback, never overrides an explicit destination. */
    public static IslandExecutionScope openInherited(Island target) { return new IslandExecutionScope(target, false, true); }
    public static Island current() { IslandExecutionScope scope = CURRENT.get(); return scope == null ? null : scope.target; }
    public static boolean isActive() { return CURRENT.get() != null; }
    public static boolean isExplicitTarget() { IslandExecutionScope scope = CURRENT.get(); return scope != null && scope.explicit; }
    public static boolean isInheritedTarget() { IslandExecutionScope scope = CURRENT.get(); return scope != null && scope.inherited; }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
