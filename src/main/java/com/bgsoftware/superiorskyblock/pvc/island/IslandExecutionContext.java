package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.island.Island;
import java.util.function.Supplier;

/** A lexical target, never a permission grant or a mutable global selection. */
public final class IslandExecutionContext implements AutoCloseable {
    private final com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope scope;
    private IslandExecutionContext(Island island) { scope = com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.open(island); }
    public static IslandExecutionContext open(Island island) { return new IslandExecutionContext(island); }
    public static Island current() { return com.bgsoftware.superiorskyblock.api.island.IslandExecutionScope.current(); }
    public static void run(Island island, Runnable operation) { try (IslandExecutionContext ignored = open(island)) { operation.run(); } }
    public static <T> T get(Island island, Supplier<T> operation) { try (IslandExecutionContext ignored = open(island)) { return operation.get(); } }
    @Override public void close() { scope.close(); }
}
