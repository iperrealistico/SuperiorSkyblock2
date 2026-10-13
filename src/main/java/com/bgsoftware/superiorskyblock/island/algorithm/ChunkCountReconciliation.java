package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.core.ChunkPosition;
import org.bukkit.Location;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short, per-tracker accounting barrier. No world access, provider calls or I/O belongs under this monitor. */
public final class ChunkCountReconciliation {
    private static final ThreadLocal<Position> MUTATION_POSITION = new ThreadLocal<>();
    private static final ThreadLocal<EntityIdentity> ENTITY_IDENTITY = new ThreadLocal<>();
    private final Object lock = new Object();
    private long epoch;
    private long active;
    private long version;
    private boolean valid;
    private boolean configured;
    private boolean ownedSnapshots;
    private final Set<Position> expected = new HashSet<>();
    private final Set<Position> captured = new HashSet<>();
    private final Map<Key, BigInteger> deltas = new HashMap<>();
    private final Map<Key, BigInteger> rawDeltas = new HashMap<>();

    public Object getLock() { return lock; }

    public static Scope at(Location location) {
        return new Scope(new Position(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
    }

    static Scope at(String world, int chunkX, int chunkZ) {
        return new Scope(new Position(world, chunkX, chunkZ));
    }

    public static EntityScope forEntity(UUID uuid, Key key) { return new EntityScope(new EntityIdentity(uuid, key)); }
    public static EntityIdentity currentEntity() { return ENTITY_IDENTITY.get(); }

    public long begin() {
        synchronized (lock) {
            if (active != 0) throw new IllegalStateException("Tracker calculation already active");
            active = ++epoch;
            version = 0;
            valid = true;
            configured = false;
            ownedSnapshots = false;
            expected.clear(); captured.clear(); deltas.clear(); rawDeltas.clear();
            return active;
        }
    }

    public long activeToken() { synchronized (lock) { return active; } }

    public void configure(long token, Iterable<ChunkPosition> positions, boolean ownedSnapshots) {
        Set<Position> detached = new HashSet<>();
        for (ChunkPosition position : positions) detached.add(Position.of(position));
        synchronized (lock) {
            if (token != active || token == 0) return;
            this.expected.addAll(detached);
            this.configured = true;
            this.ownedSnapshots = ownedSnapshots;
        }
    }

    /** Called immediately before the synchronous, owner-local read; events on that chunk cannot interleave. */
    public void captured(long token, ChunkPosition position) {
        synchronized (lock) {
            if (token == active && token != 0) captured.add(Position.of(position));
        }
    }

    /** Caller holds getLock() so live mutation and journal admission share one linearization point. */
    public void record(Key key, BigInteger delta) {
        record(key, key, delta);
    }

    public void record(Key key, Key rawKey, BigInteger delta) {
        if (active == 0) return;
        ++version;
        Position position = MUTATION_POSITION.get();
        if (!configured || !ownedSnapshots || position == null || currentEntity() != null) {
            valid = false;
            return;
        }
        // Mutations before a planned chunk's photograph are already present in its eventual result.
        // New/non-enumerated chunks have no result, so all their events must be replayed.
        if (expected.contains(position) && !captured.contains(position)) return;
        BigInteger value = deltas.getOrDefault(key, BigInteger.ZERO).add(delta);
        if (value.signum() == 0) deltas.remove(key); else deltas.put(key, value);
        value = rawDeltas.getOrDefault(rawKey, BigInteger.ZERO).add(delta);
        if (value.signum() == 0) rawDeltas.remove(rawKey); else rawDeltas.put(rawKey, value);
    }

    public void invalidate() { if (active != 0) { valid = false; ++version; } }
    public void touch() { if (active != 0) ++version; }

    public Prepared prepare(long token, Map<Key, BigInteger> reconstructed) {
        return prepare(token, reconstructed, reconstructed);
    }

    public Prepared prepare(long token, Map<Key, BigInteger> reconstructed, Map<Key, BigInteger> rawReconstructed) {
        Map<Key, BigInteger> additions;
        Map<Key, BigInteger> rawAdditions;
        long capturedVersion;
        synchronized (lock) {
            if (token != active || token == 0 || !valid || !configured) return null;
            additions = new HashMap<>(deltas);
            rawAdditions = new HashMap<>(rawDeltas);
            capturedVersion = version;
        }
        Map<Key, BigInteger> result = new HashMap<>(reconstructed);
        additions.forEach((key, delta) -> result.merge(key, delta, BigInteger::add));
        result.entrySet().removeIf(entry -> entry.getValue().signum() <= 0);
        Map<Key, BigInteger> rawResult = new HashMap<>(rawReconstructed);
        rawAdditions.forEach((key, delta) -> rawResult.merge(key, delta, BigInteger::add));
        rawResult.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
        return new Prepared(token, capturedVersion, Collections.unmodifiableMap(result), Collections.unmodifiableMap(rawResult));
    }

    /** Use only for an in-memory commit while holding getLock(); false means retry preparation or cancel. */
    public boolean current(Prepared prepared) {
        return prepared != null && active == prepared.token && valid && version == prepared.version;
    }

    public void finish(long token) {
        if (token == active) { active = 0; expected.clear(); captured.clear(); deltas.clear(); rawDeltas.clear(); }
    }

    public void cancel(long token) { synchronized (lock) { finish(token); } }

    public static final class Prepared {
        private final long token;
        private final long version;
        private final Map<Key, BigInteger> counts;
        private final Map<Key, BigInteger> rawCounts;
        private Prepared(long token, long version, Map<Key, BigInteger> counts, Map<Key, BigInteger> rawCounts) {
            this.token = token; this.version = version; this.counts = counts; this.rawCounts = rawCounts;
        }
        public Map<Key, BigInteger> getCounts() { return counts; }
        /** Signed raw contributions preserve individual worth/level prices even when display counts share a global alias. */
        public Map<Key, BigInteger> getRawCounts() { return rawCounts; }
        public long getToken() { return token; }
    }

    public static final class Scope implements AutoCloseable {
        private final Position previous;
        private Scope(Position position) { previous = MUTATION_POSITION.get(); MUTATION_POSITION.set(position); }
        @Override public void close() {
            if (previous == null) MUTATION_POSITION.remove(); else MUTATION_POSITION.set(previous);
        }
    }

    public static final class EntityIdentity {
        private final UUID uuid;
        private final Key key;
        private EntityIdentity(UUID uuid, Key key) { this.uuid = uuid; this.key = key; }
        public UUID getUuid() { return uuid; }
        public Key getKey() { return key; }
    }

    public static final class EntityScope implements AutoCloseable {
        private final EntityIdentity previous;
        private EntityScope(EntityIdentity identity) { previous = ENTITY_IDENTITY.get(); ENTITY_IDENTITY.set(identity); }
        @Override public void close() {
            if (previous == null) ENTITY_IDENTITY.remove(); else ENTITY_IDENTITY.set(previous);
        }
    }

    private static final class Position {
        private final String world;
        private final int x, z;
        private Position(String world, int x, int z) { this.world = world; this.x = x; this.z = z; }
        private static Position of(ChunkPosition position) {
            return new Position(position.getWorldName(), position.getX(), position.getZ());
        }
        @Override public int hashCode() { return Objects.hash(world, x, z); }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof Position)) return false;
            Position other = (Position) obj;
            return x == other.x && z == other.z && world.equals(other.world);
        }
    }
}
