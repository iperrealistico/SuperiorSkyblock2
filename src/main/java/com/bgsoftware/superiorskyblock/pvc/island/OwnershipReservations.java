package com.bgsoftware.superiorskyblock.pvc.island;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small atomic metadata critical sections; no Bukkit access, I/O, or waiting under this monitor. */
public final class OwnershipReservations {
    private final Map<UUID, UUID> operations = new HashMap<>();
    private final Map<UUID, Integer> pending = new HashMap<>();
    private final java.util.Set<UUID> converted = new java.util.HashSet<>();
    private final int admissionCapacity;
    public OwnershipReservations(int admissionCapacity) { this.admissionCapacity = admissionCapacity; }
    public synchronized boolean reserve(UUID operation, UUID owner, int owned, int limit) {
        if (operations.containsKey(operation)) return owner.equals(operations.get(operation));
        int reserved = pending.getOrDefault(owner, 0);
        if (operations.size() >= admissionCapacity || (long) owned + reserved >= limit) return false;
        operations.put(operation, owner); pending.put(owner, reserved + 1); return true;
    }
    public synchronized boolean canCommit(UUID operation, int owned, int limit) {
        UUID owner = operations.get(operation);
        return owner != null && (long) owned + pending.getOrDefault(owner, 0) <= limit;
    }
    public synchronized void release(UUID operation) {
        UUID owner = operations.remove(operation);
        if (owner == null) return;
        if (converted.remove(operation)) return;
        decrement(owner);
    }
    /** The durable operation stays admitted, while its slot is represented by the owned index. */
    public synchronized boolean convert(UUID operation) {
        UUID owner = operations.get(operation);
        if (owner == null) return false;
        if (converted.add(operation)) decrement(owner);
        return true;
    }
    private void decrement(UUID owner) {
        int count = pending.getOrDefault(owner, 0) - 1;
        if (count <= 0) pending.remove(owner); else pending.put(owner, count);
    }
    public synchronized int pending(UUID owner) { return pending.getOrDefault(owner, 0); }
    public synchronized int size() { return operations.size(); }
    public synchronized <T> T atomic(java.util.function.Supplier<T> action) { return action.get(); }
}
