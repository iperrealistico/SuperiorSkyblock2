package com.bgsoftware.superiorskyblock.pvc.island;

import com.bgsoftware.superiorskyblock.api.hooks.WorldsProvider;
import com.bgsoftware.superiorskyblock.api.hooks.WorldsProviderCapabilities;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.BlockPosition;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

public class WorldsProviderCapabilitiesTest {
    @Test public void unchangedLegacyAddonRetainsPaperSingleIslandContract() {
        WorldsProvider legacy = new LegacyProvider();
        assertFalse(legacy.supportsFoliaExecution());
        assertFalse(legacy.supportsMultipleOwnedIslands());
        assertFalse(legacy.supportsAutomaticIslandSizePolicy());
        WorldsProviderCapabilities.require(legacy, false, false, false);
        refused(legacy, true, false, false, "supportsFoliaExecution");
        refused(legacy, false, true, false, "supportsMultipleOwnedIslands");
        refused(legacy, false, false, true, "supportsAutomaticIslandSizePolicy");
    }

    @Test public void independentCapabilitiesCannotSilentlyAuthorizeAnotherFeature() {
        WorldsProvider partial = new LegacyProvider() {
            @Override public boolean supportsMultipleOwnedIslands() { return true; }
        };
        WorldsProviderCapabilities.require(partial, false, true, false);
        refused(partial, true, true, false, "supportsFoliaExecution");
        refused(partial, false, true, true, "supportsAutomaticIslandSizePolicy");
        WorldsProvider complete = new LegacyProvider() {
            @Override public boolean supportsFoliaExecution() { return true; }
            @Override public boolean supportsMultipleOwnedIslands() { return true; }
            @Override public boolean supportsAutomaticIslandSizePolicy() { return true; }
        };
        WorldsProviderCapabilities.require(complete, true, true, true);
    }

    private static void refused(WorldsProvider provider, boolean folia, boolean multiple, boolean automatic, String contract) {
        try { WorldsProviderCapabilities.require(provider, folia, multiple, automatic); fail("Unqualified addon was admitted"); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(contract));
            assertTrue(expected.getMessage().contains("default shared-world provider"));
        }
    }

    /** Deliberately implements only the original API, exercising its additive defaults. */
    private static class LegacyProvider implements WorldsProvider {
        @Override public void prepareWorlds() { }
        @Override public World getIslandsWorld(Island island, Dimension dimension) { return null; }
        @Override public Dimension getIslandsWorldDimension(World world) { return null; }
        @Override public boolean isIslandsWorld(World world) { return false; }
        @Override public Location getNextLocation(BlockPosition previous, int height, int size, UUID owner, UUID island) { return null; }
        @Override public void finishIslandCreation(Location location, UUID owner, UUID island) { }
        @Override public void prepareTeleport(Island island, Location location, Runnable finish) { finish.run(); }
        @Override public boolean isDimensionEnabled(Dimension dimension) { return false; }
        @Override public boolean isDimensionUnlocked(Dimension dimension) { return false; }
    }
}
