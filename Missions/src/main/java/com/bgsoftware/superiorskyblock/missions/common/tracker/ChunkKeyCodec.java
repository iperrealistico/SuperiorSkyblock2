package com.bgsoftware.superiorskyblock.missions.common.tracker;

/** Stable coordinate codec. Legacy sign extension destroyed X whenever Z was negative. */
public final class ChunkKeyCodec {
    public static final int VERSION = 2;
    private ChunkKeyCodec() { }
    public static long encode(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
    public static boolean isAmbiguousLegacy(long key, int version) { return version < VERSION && (int) key < 0; }
}
