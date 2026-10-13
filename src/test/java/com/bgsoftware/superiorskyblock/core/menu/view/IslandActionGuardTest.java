package com.bgsoftware.superiorskyblock.core.menu.view;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class IslandActionGuardTest {
    private final UUID island = UUID.randomUUID(), owner = UUID.randomUUID();
    private final IslandActionGuard receipt = new IslandActionGuard(island, owner, 7, 11, 1000);

    @Test public void selectedIslandSwitchDoesNotRetargetAConfirmation() {
        assertTrue(receipt.actionCurrent(island, owner, 7, 11, 2000, true));
        assertFalse(receipt.actionCurrent(UUID.randomUUID(), owner, 7, 11, 2000, true));
    }

    @Test public void promotionAndRemoveRejoinInvalidateTheOldIntentEvenWithSameOwnerAndRoles() {
        assertFalse(receipt.actionCurrent(island, owner, 7, 12, 2000, true));
        assertFalse(receipt.actionCurrent(island, owner, 7, 13, 2000, true));
        // Configured menu hooks must also reject an expired membership before dispatch.
        assertFalse(receipt.contextCurrent(island, owner, 7, 12, true));
        assertTrue(receipt.contextCurrent(island, owner, 7, 11, true));
    }

    @Test public void ownerChangeRemovalOperationAndExpiryFailClosed() {
        assertFalse(receipt.actionCurrent(island, UUID.randomUUID(), 7, 11, 2000, true));
        assertFalse(receipt.actionCurrent(island, owner, 8, 11, 2000, true));
        assertFalse(receipt.actionCurrent(island, owner, 7, 11, 2000, false));
        assertFalse(receipt.actionCurrent(island, owner, 7, 11, 61001, true));
        assertFalse(receipt.actionCurrent(island, owner, 7, 11, 999, true));
    }

    @Test public void personalAndGlobalViewsAreIndependentOfAnyIslandButCannotClaimIslandActions() {
        IslandActionGuard global = new IslandActionGuard(null, null, 0, 0, 1000);
        assertTrue(global.contextCurrent(null, null, 99, 99, false));
        assertFalse(global.actionCurrent(island, owner, 7, 11, 2000, true));
    }
}
