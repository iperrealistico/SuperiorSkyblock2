package com.bgsoftware.superiorskyblock.pvc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Missing-only textual config migration: existing comments, values and private settings are retained. */
public final class PvcConfig {
    public enum SizeMode { LEGACY, PERMISSION_AUTOMATIC }
    private static final Pattern KEY = Pattern.compile("^( *)([A-Za-z0-9_-]+):(?:[ \\t]*(.*))?$");
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("pvc", "");
        DEFAULTS.put("pvc.size-mode", "LEGACY");
        DEFAULTS.put("pvc.entitlements", "");
        DEFAULTS.put("pvc.entitlements.cache-capacity", "2048");
        DEFAULTS.put("pvc.entitlements.history-capacity", "65536");
        DEFAULTS.put("pvc.entitlements.refresh-seconds", "30");
        DEFAULTS.put("pvc.entitlements.request-timeout-seconds", "10");
        DEFAULTS.put("pvc.entitlements.max-in-flight", "4");
        DEFAULTS.put("pvc.entitlements.refresh-batch", "64");
        DEFAULTS.put("pvc.entitlements.queue-capacity", "4096");
        DEFAULTS.put("pvc.entitlements.max-permission-nodes", "4096");
        DEFAULTS.put("pvc.entitlements.radius-candidates", "[]");
        DEFAULTS.put("pvc.runtime", "");
        DEFAULTS.put("pvc.runtime.chunk-load-concurrency", "8");
        DEFAULTS.put("pvc.runtime.chunk-load-queue-limit", "512");
        DEFAULTS.put("pvc.runtime.chunk-load-timeout-seconds", "30");
        DEFAULTS.put("pvc.runtime.dragon-arena-chunk-budget", "1156");
        DEFAULTS.put("pvc.runtime.dragon-arena-queue-limit", "128");
        DEFAULTS.put("pvc.runtime.dragon-arena-activation-timeout-seconds", "60");
    }
    private final SizeMode sizeMode;
    private final int cacheCapacity, historyCapacity, timeoutSeconds, maxInFlight, refreshBatch, queueCapacity, maxNodes;
    private final long refreshMillis;
    private final Set<Integer> radiusCandidates;
    private final int chunkLoadConcurrency, chunkLoadQueueLimit, chunkLoadTimeoutSeconds;
    private final int dragonArenaChunkBudget, dragonArenaQueueLimit, dragonArenaActivationTimeoutSeconds;

    private PvcConfig(YamlConfiguration yaml) throws IOException {
        try {
            sizeMode = SizeMode.valueOf(yaml.getString("pvc.size-mode", "LEGACY").toUpperCase(java.util.Locale.ROOT));
            cacheCapacity = positive(yaml, "cache-capacity", 16, 1000000);
            historyCapacity = positive(yaml, "history-capacity", cacheCapacity, 1000000);
            timeoutSeconds = positive(yaml, "request-timeout-seconds", 1, 300);
            maxInFlight = positive(yaml, "max-in-flight", 1, 64);
            refreshBatch = positive(yaml, "refresh-batch", 1, 4096);
            queueCapacity = positive(yaml, "queue-capacity", maxInFlight, 1000000);
            maxNodes = positive(yaml, "max-permission-nodes", 1, 1000000);
            refreshMillis = positive(yaml, "refresh-seconds", 1, 3600) * 1000L;
            Set<Integer> candidates = new LinkedHashSet<>();
            for (Integer value : yaml.getIntegerList("pvc.entitlements.radius-candidates")) {
                if (value == null || value < 1) throw new IllegalArgumentException("Radius candidates must be positive integers");
                candidates.add(value);
            }
            if (candidates.size() > maxNodes) throw new IllegalArgumentException("Too many radius candidates");
            radiusCandidates = Collections.unmodifiableSet(candidates);
            chunkLoadConcurrency = bounded(yaml, "pvc.runtime.chunk-load-concurrency", 1, 256);
            chunkLoadQueueLimit = bounded(yaml, "pvc.runtime.chunk-load-queue-limit", 1, 1000000);
            chunkLoadTimeoutSeconds = bounded(yaml, "pvc.runtime.chunk-load-timeout-seconds", 1, 3600);
            dragonArenaChunkBudget = bounded(yaml, "pvc.runtime.dragon-arena-chunk-budget", 289, 1000000);
            dragonArenaQueueLimit = bounded(yaml, "pvc.runtime.dragon-arena-queue-limit", 1, 1000000);
            dragonArenaActivationTimeoutSeconds = bounded(yaml, "pvc.runtime.dragon-arena-activation-timeout-seconds", 1, 3600);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid PVC fork configuration: " + ex.getMessage(), ex);
        }
    }

    private static int positive(YamlConfiguration yaml, String key, int minimum, int maximum) {
        return bounded(yaml, "pvc.entitlements." + key, minimum, maximum);
    }
    private static int bounded(YamlConfiguration yaml, String key, int minimum, int maximum) {
        Object raw = yaml.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum || ((Number) raw).doubleValue() != value)
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        return (int) value;
    }

    public static PvcConfig prepareAndLoad(File dataFolder) throws IOException {
        File config = new File(dataFolder, "config.yml");
        if (!config.isFile()) throw new IOException("The upstream config.yml must be created before PVC migration");
        byte[] before = Files.readAllBytes(config.toPath());
        String original = new String(before, StandardCharsets.UTF_8);
        YamlConfiguration yaml = parse(original);
        String migrated = addMissingDefaults(original, yaml);
        YamlConfiguration validated = parse(migrated);
        PvcConfig result = new PvcConfig(validated);
        if (!original.equals(migrated)) PvcAtomicFiles.replace(config.toPath(), migrated.getBytes(StandardCharsets.UTF_8), ".pvc-backup");
        return result;
    }

    public static String addMissingDefaults(String original, YamlConfiguration parsed) throws IOException {
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>();
        Collections.addAll(lines, original.split("\\r?\\n", -1));
        boolean changed = false;
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String path = entry.getKey();
            if (parsed.contains(path)) continue;
            int dot = path.lastIndexOf('.');
            String parent = dot < 0 ? "" : path.substring(0, dot);
            String key = dot < 0 ? path : path.substring(dot + 1);
            if (!parent.isEmpty() && parsed.contains(parent) && !(parsed.get(parent) instanceof ConfigurationSection))
                throw new IOException("Cannot add PVC keys below non-mapping " + parent);
            Node node = parent.isEmpty() ? new Node(-1, -2) : find(lines, parent);
            if (node == null) throw new IOException("PVC configuration uses an inline or unsupported mapping at " + parent);
            int insert = lines.size();
            if (node.line >= 0) {
                insert = node.line + 1;
                while (insert < lines.size()) {
                    String line = lines.get(insert);
                    Matcher matcher = KEY.matcher(line);
                    if (matcher.matches() && matcher.group(1).length() <= node.indent) break;
                    ++insert;
                }
            }
            StringBuilder added = new StringBuilder();
            for (int i = 0; i < node.indent + 2; ++i) added.append(' ');
            added.append(key).append(':');
            if (!entry.getValue().isEmpty()) added.append(' ').append(entry.getValue());
            lines.add(insert, added.toString());
            changed = true;
            parsed = parse(join(lines, newline));
        }
        return changed ? join(lines, newline) : original;
    }

    private static Node find(List<String> lines, String path) {
        String[] keys = path.split("\\.");
        int from = 0, to = lines.size(), parentIndent = -2;
        Node selected = null;
        for (String key : keys) {
            selected = null;
            int childIndent = Integer.MAX_VALUE;
            for (int i = from; i < to; ++i) {
                Matcher matcher = KEY.matcher(lines.get(i));
                if (matcher.matches() && matcher.group(1).length() > parentIndent)
                    childIndent = Math.min(childIndent, matcher.group(1).length());
            }
            for (int i = from; i < to; ++i) {
                Matcher matcher = KEY.matcher(lines.get(i));
                if (matcher.matches() && matcher.group(1).length() == childIndent && matcher.group(2).equals(key)) {
                    selected = new Node(i, matcher.group(1).length());
                    String value = matcher.group(3);
                    if (value != null && !value.trim().isEmpty() && !value.trim().startsWith("#")) return null;
                    break;
                }
            }
            if (selected == null) return null;
            from = selected.line + 1;
            parentIndent = selected.indent;
            for (int i = from; i < to; ++i) {
                Matcher matcher = KEY.matcher(lines.get(i));
                if (matcher.matches() && matcher.group(1).length() <= parentIndent) { to = i; break; }
            }
        }
        return selected;
    }
    private static String join(List<String> lines, String newline) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.size(); ++i) {
            if (i > 0) result.append(newline);
            result.append(lines.get(i));
        }
        if (result.length() == 0 || !result.toString().endsWith(newline)) result.append(newline);
        return result.toString();
    }
    private static YamlConfiguration parse(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException ex) { throw new IOException("Invalid config.yml; original retained", ex); }
        return yaml;
    }
    private static final class Node {
        final int line, indent;
        Node(int line, int indent) { this.line = line; this.indent = indent; }
    }
    public SizeMode getSizeMode() { return sizeMode; }
    public int getCacheCapacity() { return cacheCapacity; }
    public int getHistoryCapacity() { return historyCapacity; }
    public long getRefreshMillis() { return refreshMillis; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxInFlight() { return maxInFlight; }
    public int getRefreshBatch() { return refreshBatch; }
    public int getQueueCapacity() { return queueCapacity; }
    public int getMaxNodes() { return maxNodes; }
    public Set<Integer> getRadiusCandidates() { return radiusCandidates; }
    public int getChunkLoadConcurrency() { return chunkLoadConcurrency; }
    public int getChunkLoadQueueLimit() { return chunkLoadQueueLimit; }
    public int getChunkLoadTimeoutSeconds() { return chunkLoadTimeoutSeconds; }
    public int getDragonArenaChunkBudget() { return dragonArenaChunkBudget; }
    public int getDragonArenaQueueLimit() { return dragonArenaQueueLimit; }
    public int getDragonArenaActivationTimeoutSeconds() { return dragonArenaActivationTimeoutSeconds; }
}
