package com.bgsoftware.superiorskyblock.island.algorithm;

import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.algorithms.IslandBlocksTrackerAlgorithm;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.bgsoftware.superiorskyblock.api.key.KeyMap;
import com.bgsoftware.superiorskyblock.core.ServerVersion;
import com.bgsoftware.superiorskyblock.core.key.BaseKey;
import com.bgsoftware.superiorskyblock.core.key.KeyIndicator;
import com.bgsoftware.superiorskyblock.core.key.map.KeyMaps;
import com.bgsoftware.superiorskyblock.core.key.MaterialKeySource;
import com.bgsoftware.superiorskyblock.core.key.types.MaterialKey;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;
import com.bgsoftware.superiorskyblock.core.values.BlockValue;
import com.bgsoftware.superiorskyblock.island.upgrade.IslandUpgradeConstants;
import com.google.common.base.Preconditions;

import java.math.BigInteger;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultIslandBlocksTrackerAlgorithm implements IslandBlocksTrackerAlgorithm {

    private static final SuperiorSkyblockPlugin plugin = SuperiorSkyblockPlugin.getPlugin();

    private final KeyMap<BigInteger> blockCounts = KeyMaps.createConcurrentHashMap(KeyIndicator.MATERIAL);
    private final ChunkCountReconciliation reconciliation = new ChunkCountReconciliation();

    private final Island island;
    private final ThreadLocal<Boolean> loadingDataMode = new ThreadLocal<>();

    public DefaultIslandBlocksTrackerAlgorithm(Island island) {
        this.island = island;
    }

    @Override
    public boolean trackBlock(Key key, BigInteger amount) {
        BlockMutation mutation = prepareBlockMutation(key, amount, false, Boolean.TRUE.equals(loadingDataMode.get()));
        if (mutation == null) return false;
        Log.debug(Debug.BLOCK_PLACE, island.getOwner().getName(), key, amount);
        return applyBlockMutation(mutation);
    }

    @Override
    public boolean untrackBlock(Key key, BigInteger amount) {
        BlockMutation mutation = prepareBlockMutation(key, amount, true, Boolean.TRUE.equals(loadingDataMode.get()));
        if (mutation == null) return false;
        Log.debug(Debug.BLOCK_BREAK, island.getOwner().getName(), key, amount);
        return applyBlockMutation(mutation);
    }

    /** Resolve provider/configuration aliases before a caller acquires the totals/publication barrier. */
    public BlockMutation prepareBlockMutation(Key key, BigInteger amount, boolean removal, boolean raw) {
        Preconditions.checkNotNull(key, "key parameter cannot be null.");
        Preconditions.checkNotNull(amount, "amount parameter cannot be null.");

        if (amount.compareTo(BigInteger.ZERO) == 0)
            return null;

        if (!ServerVersion.isLegacy() && key instanceof MaterialKey &&
                ((MaterialKey) key).getMaterialKeySource() == MaterialKeySource.ITEM)
            key = ((MaterialKey) key).toGlobalKey();

        BlockValue blockValue = plugin.getBlockValues().getBlockValue(key);

        if (blockValue == BlockValue.ZERO && island.getBlockLimit(key) == IslandUpgradeConstants.NO_LIMIT_VALUE &&
                !plugin.getBlockValues().isValuesMenu(key)) return null;
        List<Key> aliases = countedKeys(key, raw);
        return new BlockMutation(aliases.get(0), key, amount, removal, raw, aliases);
    }

    public boolean applyBlockMutation(BlockMutation mutation) {
        if (mutation == null) return false;
        synchronized (getMutationLock()) {
            if (mutation.removal) mutation.countedKeys.forEach(key -> removeCounts(key, mutation.amount));
            else mutation.countedKeys.forEach(key -> blockCounts.merge(key, mutation.amount, BigInteger::add));
            if (mutation.raw) reconciliation.invalidate();
            reconciliation.record(mutation.key, mutation.rawKey, mutation.removal ? mutation.amount.negate() : mutation.amount);
        }
        return true;
    }

    @Override
    public BigInteger getBlockCount(Key key) {
        Preconditions.checkNotNull(key, "key parameter cannot be null.");
        synchronized (getMutationLock()) { return blockCounts.getOrDefault(key, BigInteger.ZERO); }
    }

    @Override
    public BigInteger getExactBlockCount(Key key) {
        Preconditions.checkNotNull(key, "key parameter cannot be null.");
        synchronized (getMutationLock()) { return blockCounts.getRaw(key, BigInteger.ZERO); }
    }

    @Override
    public Map<Key, BigInteger> getBlockCounts() {
        synchronized (getMutationLock()) {
            KeyMap<BigInteger> snapshot = KeyMaps.createHashMap(KeyIndicator.MATERIAL);
            snapshot.putAll(this.blockCounts);
            return Collections.unmodifiableMap(snapshot);
        }
    }

    @Override
    public void clearBlockCounts() {
        synchronized (getMutationLock()) {
            reconciliation.invalidate();
            this.blockCounts.clear();
        }
    }

    @Override
    public void setLoadingDataMode(boolean loadingDataMode) {
        synchronized (getMutationLock()) {
            if (loadingDataMode) {
                reconciliation.invalidate();
                this.loadingDataMode.set(Boolean.TRUE);
            } else {
                this.loadingDataMode.remove();
            }
        }
    }

    /** Resolve value/limit/global aliases outside the accounting monitor. */
    private List<Key> countedKeys(Key key, boolean raw) {
        Key valueKey = plugin.getBlockValues().getBlockKey(key);
        List<Key> keys = new ArrayList<>(3);
        keys.add(valueKey);
        if (raw) return keys;

        Key limitKey = island.getBlockLimitKey(valueKey);
        Key globalKey = ((BaseKey<?>) valueKey).toGlobalKey();
        boolean limitCount = false;

        if (!limitKey.equals(valueKey)) {
            keys.add(limitKey);
            limitCount = true;
        }

        if (!globalKey.equals(valueKey) && (!limitCount || !globalKey.equals(limitKey))) {
            BlockValue blockValue = plugin.getBlockValues().getBlockValue(globalKey);
            if (blockValue != BlockValue.ZERO) {
                keys.add(globalKey);
            }
        }
        return keys;
    }

    private void removeCounts(Key key, BigInteger amount) {
        blockCounts.computeIfPresent(key, (ignored, current) -> current.compareTo(amount) <= 0 ?
                null : current.subtract(amount));
    }

    public Object getMutationLock() { return reconciliation.getLock(); }
    public boolean isLoadingDataMode() { return Boolean.TRUE.equals(loadingDataMode.get()); }
    public long beginRecalculation() { return reconciliation.begin(); }
    public long getRecalculationToken() { return reconciliation.activeToken(); }
    public void configureRecalculation(long token, Iterable<com.bgsoftware.superiorskyblock.core.ChunkPosition> positions,
                                       boolean ownedSnapshots) { reconciliation.configure(token, positions, ownedSnapshots); }
    public void markChunkSnapshot(long token, com.bgsoftware.superiorskyblock.core.ChunkPosition position) {
        reconciliation.captured(token, position);
    }
    public ChunkCountReconciliation.Prepared prepareRecalculation(long token, Map<Key, BigInteger> counts) {
        // Region events and the NMS photograph need not use the same material sub-key.
        // Canonicalize both sides before replay, otherwise a negative STONE:0 delta could be
        // discarded while the photograph's global STONE count remained untouched.
        KeyMap<BigInteger> canonical = KeyMaps.createHashMap(KeyIndicator.MATERIAL);
        counts.forEach((key, amount) -> canonical.merge(plugin.getBlockValues().getBlockKey(key), amount, BigInteger::add));
        return reconciliation.prepare(token, canonical, counts);
    }

    /** Detached normalization; all plugin/provider queries happen here, outside finishRecalculation's monitor. */
    public Map<Key, BigInteger> normalizeRecalculationCounts(Map<Key, BigInteger> physicalCounts) {
        KeyMap<BigInteger> normalized = KeyMaps.createHashMap(KeyIndicator.MATERIAL);
        physicalCounts.forEach((key, amount) -> {
            if (amount.signum() <= 0) return;
            BlockValue value = plugin.getBlockValues().getBlockValue(key);
            if (value != BlockValue.ZERO || island.getBlockLimit(key) != IslandUpgradeConstants.NO_LIMIT_VALUE ||
                    plugin.getBlockValues().isValuesMenu(key))
                countedKeys(key, false).forEach(counted -> normalized.merge(counted, amount, BigInteger::add));
        });
        return normalized;
    }

    /** publication is limited to prepared in-memory totals/worth. Returns false if preparation became stale. */
    public boolean finishRecalculation(ChunkCountReconciliation.Prepared prepared,
                                       Map<Key, BigInteger> normalizedCounts, Runnable publication) {
        synchronized (getMutationLock()) {
            if (!reconciliation.current(prepared)) return false;
            blockCounts.clear();
            blockCounts.putAll(normalizedCounts);
            reconciliation.finish(prepared.getToken());
            publication.run();
            return true;
        }
    }

    public void cancelRecalculation(long token) { reconciliation.cancel(token); }

    public static final class BlockMutation {
        private final Key key;
        private final Key rawKey;
        private final BigInteger amount;
        private final boolean removal, raw;
        private final List<Key> countedKeys;
        private BlockMutation(Key key, Key rawKey, BigInteger amount, boolean removal, boolean raw, List<Key> countedKeys) {
            this.key = key; this.rawKey = rawKey; this.amount = amount; this.removal = removal; this.raw = raw; this.countedKeys = countedKeys;
        }
    }

}
