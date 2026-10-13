package com.bgsoftware.superiorskyblock.pvc.entitlement;

import com.bgsoftware.superiorskyblock.pvc.PvcAtomicFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fork-owned storage, never the upstream fixed-size field. All writes occur on the bounded I/O lane. */
public final class PvcPolicyStore {
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private final Path file;
    private final int historyCapacity;
    private final Map<UUID, Integer> overrides = new ConcurrentHashMap<>();
    private final Map<UUID, EntitlementSnapshot> history = new ConcurrentHashMap<>();
    private final LinkedHashMap<UUID, EntitlementSnapshot> orderedHistory = new LinkedHashMap<>();

    public PvcPolicyStore(Path file, int historyCapacity) throws IOException {
        this.file = file;
        this.historyCapacity = historyCapacity;
        if (!Files.exists(file)) return;
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Owner policy storage exceeds its safe size limit");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        if (!"1".equals(properties.getProperty("format"))) throw new IOException("Unsupported owner policy format");
        try {
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("override.")) {
                    UUID owner = UUID.fromString(key.substring("override.".length()));
                    int radius = Integer.parseInt(properties.getProperty(key));
                    if (radius < 1) throw new IllegalArgumentException("Non-positive override");
                    overrides.put(owner, radius);
                } else if (key.startsWith("snapshot.")) {
                    UUID owner = UUID.fromString(key.substring("snapshot.".length()));
                    String[] fields = properties.getProperty(key).split(",", -1);
                    if (fields.length != 3) throw new IllegalArgumentException("Malformed last-known snapshot");
                    int quota = Integer.parseInt(fields[0]);
                    int radius = Integer.parseInt(fields[1]);
                    long checked = Long.parseLong(fields[2]);
                    if (quota < 1 || radius < 1 || checked < 0) throw new IllegalArgumentException("Invalid last-known snapshot");
                    orderedHistory.put(owner, new EntitlementSnapshot(quota, radius, checked, 0, false));
                } else if (!key.equals("format")) {
                    throw new IllegalArgumentException("Unknown policy field");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid owner policy storage; refusing to discard existing overrides", ex);
        }
        trimHistory();
        history.putAll(orderedHistory);
    }

    public Integer getOverride(UUID owner) { return overrides.get(owner); }
    public EntitlementSnapshot getLastKnown(UUID owner) { return history.get(owner); }
    public Map<UUID, Integer> getOverrides() { return Collections.unmodifiableMap(new LinkedHashMap<>(overrides)); }

    public synchronized void setOverride(UUID owner, Integer radius) throws IOException {
        if (radius != null && radius < 1) throw new IllegalArgumentException("Override radius must be positive");
        Map<UUID, Integer> next = new LinkedHashMap<>(overrides);
        if (radius == null) next.remove(owner); else next.put(owner, radius);
        write(next, orderedHistory);
        if (radius == null) overrides.remove(owner); else overrides.put(owner, radius);
    }

    /** Atomic read/modify/durable-publish, so two additive requests cannot lose an increment. */
    public synchronized int addOverride(UUID owner, int verifiedFallbackRadius, int delta, int gridCap) throws IOException {
        Integer stored = overrides.get(owner);
        int base = Math.min(stored == null ? verifiedFallbackRadius : stored, gridCap);
        long radius = (long) base + delta;
        if (base < 1 || gridCap < 1 || radius < 1 || radius > gridCap)
            throw new IllegalArgumentException("The resulting radius is outside the saved grid cap");
        setOverride(owner, (int) radius);
        return (int) radius;
    }

    public synchronized void remember(Map<UUID, EntitlementSnapshot> snapshots) throws IOException {
        LinkedHashMap<UUID, EntitlementSnapshot> next = new LinkedHashMap<>(orderedHistory);
        for (Map.Entry<UUID, EntitlementSnapshot> entry : snapshots.entrySet()) {
            if (!entry.getValue().isAuthoritative()) continue;
            next.remove(entry.getKey());
            next.put(entry.getKey(), entry.getValue().lastKnown());
        }
        while (next.size() > historyCapacity) next.remove(next.keySet().iterator().next());
        write(overrides, next);
        orderedHistory.clear();
        orderedHistory.putAll(next);
        history.keySet().retainAll(next.keySet());
        history.putAll(next);
    }

    private void trimHistory() {
        while (orderedHistory.size() > historyCapacity) orderedHistory.remove(orderedHistory.keySet().iterator().next());
    }

    private void write(Map<UUID, Integer> ownerOverrides, Map<UUID, EntitlementSnapshot> snapshots) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        for (Map.Entry<UUID, Integer> entry : ownerOverrides.entrySet())
            properties.setProperty("override." + entry.getKey(), entry.getValue().toString());
        for (Map.Entry<UUID, EntitlementSnapshot> entry : snapshots.entrySet()) {
            EntitlementSnapshot value = entry.getValue();
            properties.setProperty("snapshot." + entry.getKey(), value.getOwnershipLimit() + "," +
                    value.getRadius() + "," + value.getVerifiedAtMillis());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "SuperiorSkyblock2 PVC owner overrides and non-authoritative last-known cache");
        if (output.size() > MAX_FILE_BYTES) throw new IOException("Owner policy storage exceeds its safe size limit");
        PvcAtomicFiles.replace(file, output.toByteArray(), ".bak");
    }
}
