package com.bgsoftware.superiorskyblock.missions.blocks;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;

public class ChunkBitSet {

    private static final BitSet[] EMPTY_BIT_SET_ARRAY = new BitSet[0];

    private BitSet[] bitSets = EMPTY_BIT_SET_ARRAY;

    public ChunkBitSet() {

    }

    public synchronized void set(int index) {
        BitSet bitSet = getBitSetForBlock(index, true);
        if (bitSet == null)
            throw new IllegalStateException();
        int blockIdx = index & 0xFFF;
        bitSet.set(blockIdx);
    }

    public synchronized boolean clear(int index) {
        BitSet bitSet = getBitSetForBlock(index, false);
        if (bitSet == null)
            return false;
        int blockIdx = index & 0xFFF;
        boolean old = bitSet.get(blockIdx);
        bitSet.clear(blockIdx);
        return old;
    }

    public synchronized boolean get(int index) {
        BitSet bitSet = getBitSetForBlock(index, false);
        if (bitSet == null)
            return false;
        int blockIdx = index & 0xFFF;
        return bitSet.get(blockIdx);
    }

    public void forEach(IntConsumer consumer) {
        BitSet[] snapshot = snapshot();
        for (int i = 0; i < snapshot.length; ++i) {
            BitSet bitSet = snapshot[i];
            if (bitSet != null) {
                for (int j = bitSet.nextSetBit(0); j != -1; j = bitSet.nextSetBit(j + 1)) {
                    consumer.accept(((i << 4) << 8) | j);
                }
            }
        }
    }

    public ChunkBitSet copy() {
        ChunkBitSet copy = new ChunkBitSet();
        copy.bitSets = snapshot();
        return copy;
    }

    private synchronized BitSet[] snapshot() {
        BitSet[] copy = new BitSet[bitSets.length];
        for (int i = 0; i < bitSets.length; i++) if (bitSets[i] != null) copy[i] = (BitSet) bitSets[i].clone();
        return copy;
    }

    private void ensureCapacity(int capacity) {
        if (bitSets.length >= capacity)
            return;

        bitSets = Arrays.copyOf(bitSets, capacity);
    }

    private BitSet getBitSetForBlock(int block, boolean createNew) {
        int sectionIdx = (block >> 8) >> 4;

        if (bitSets.length <= sectionIdx) {
            if (!createNew)
                return null;

            ensureCapacity(sectionIdx + 1);
        }

        BitSet bitSet = bitSets[sectionIdx];

        if (bitSet == null) {
            if (!createNew)
                return null;

            bitSet = bitSets[sectionIdx] = new BitSet();
        }

        return bitSet;
    }

}
