package com.bgsoftware.superiorskyblock.module.upgrades.commands;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ConfiguredUpgradeCommandsTest {
    @Test public void ownUpgradePreservesHookOrderAndPaysOnlyAfterActualLevelAcknowledgement() {
        List<String> effects = new ArrayList<>();
        AtomicInteger level = new AtomicInteger(1);
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("external-before", "setupgrade", "external-after"), "setupgrade"::equals,
                command -> { effects.add(command); if (command.equals("setupgrade")) level.set(2); return true; },
                () -> true, () -> level.get() == 2, () -> effects.add("pay"));
        assertEquals(ConfiguredUpgradeCommands.Result.SUCCESS, result);
        assertEquals(Arrays.asList("external-before", "setupgrade", "external-after", "pay"), effects);
    }

    @Test public void cancelledVoidUpgradeHandlerCannotAcknowledgeTheBusinessMutationOrCharge() {
        AtomicBoolean paid = new AtomicBoolean();
        List<String> dispatched = new ArrayList<>();
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("setupgrade", "success-message"), "setupgrade"::equals,
                command -> { dispatched.add(command); return true; },
                () -> true, () -> false, () -> paid.set(true));
        assertEquals(ConfiguredUpgradeCommands.Result.LEVEL_NOT_ADVANCED, result);
        assertFalse(paid.get());
        assertEquals(Arrays.asList("setupgrade"), dispatched);
    }

    @Test public void rejectedOwnCommandStopsLaterHooksWithoutCharging() {
        List<String> effects = new ArrayList<>();
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("before", "setupgrade", "after"), "setupgrade"::equals,
                command -> { effects.add(command); return !command.equals("setupgrade"); },
                () -> true, () -> false, () -> effects.add("pay"));
        assertEquals(ConfiguredUpgradeCommands.Result.COMMAND_REJECTED, result);
        assertEquals(Arrays.asList("before", "setupgrade"), effects);
    }

    @Test public void changedSubjectStopsBeforeAnotherCommandWithoutChargingOwnUpgrade() {
        List<String> effects = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("before", "setupgrade"), "setupgrade"::equals,
                command -> { effects.add(command); current.set(false); return true; },
                current::get, () -> false, () -> effects.add("pay"));
        assertEquals(ConfiguredUpgradeCommands.Result.TARGET_CHANGED, result);
        assertEquals(Arrays.asList("before"), effects);
    }

    @Test public void externalOnlyConfigurationRetainsPaymentBeforeHooksWithoutRequiringALevelMutation() {
        List<String> effects = new ArrayList<>();
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("external-a", "external-b"), command -> false,
                command -> { effects.add(command); return true; },
                () -> true, () -> false, () -> effects.add("pay"));
        assertEquals(ConfiguredUpgradeCommands.Result.SUCCESS, result);
        assertEquals(Arrays.asList("pay", "external-a", "external-b"), effects);
    }

    @Test public void externalPartialFailureDoesNotInventCompensationOrRunLaterHooks() {
        List<String> effects = new ArrayList<>();
        ConfiguredUpgradeCommands.Result result = ConfiguredUpgradeCommands.execute(
                Arrays.asList("external-a", "external-b"), command -> false,
                command -> { effects.add(command); return false; },
                () -> true, () -> false, () -> effects.add("pay"));
        assertEquals(ConfiguredUpgradeCommands.Result.COMMAND_REJECTED, result);
        assertEquals(Arrays.asList("pay", "external-a"), effects);
    }
}
