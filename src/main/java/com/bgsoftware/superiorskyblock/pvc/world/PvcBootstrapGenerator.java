package com.bgsoftware.superiorskyblock.pvc.world;

import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import java.util.Random;

/** Void generation usable during server world bootstrap before managers enable. */
public final class PvcBootstrapGenerator extends com.bgsoftware.superiorskyblock.world.generator.IslandsGenerator {
    @Override public org.bukkit.Location getFixedSpawnLocation(World world, Random random) {
        return new org.bukkit.Location(world, 0.5D, 100.0D, 0.5D);
    }
    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        return createChunkData(world);
    }
}
