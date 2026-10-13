package com.bgsoftware.superiorskyblock.commands;

import org.junit.Test;
import static org.junit.Assert.*;

public class PublicIslandSelectorsTest {
    @Test public void publicInformationUsesTheSameTargetsAsItsPositionalSyntax() {
        for (String family : new String[]{"counts", "values", "team", "show", "visit", "warp", "rate", "ratings"})
            assertTrue(family, CommandsManagerImpl.allowsExternalIsland(family));
    }

    @Test public void publicInspectionDoesNotEnableForeignIslandManagement() {
        for (String family : new String[]{"ban", "kick", "disband", "name", "setwarp", "delwarp", "setdiscord", "invite", "promote"})
            assertFalse(family, CommandsManagerImpl.allowsExternalIsland(family));
    }
}
