package com.bgsoftware.superiorskyblock.module.upgrades.commands;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Executes configured hooks in their original order without inventing an external transaction. */
final class ConfiguredUpgradeCommands {

    enum Result {
        SUCCESS, TARGET_CHANGED, COMMAND_REJECTED, LEVEL_NOT_ADVANCED
    }

    private ConfiguredUpgradeCommands() { }

    static Result execute(List<String> commands, Predicate<String> ownUpgradeMutation,
                          Function<String, Boolean> dispatch, BooleanSupplier targetCurrent,
                          BooleanSupplier expectedLevelReached, Runnable withdrawCosts) {
        boolean requiresLevelAcknowledgement = commands.stream().anyMatch(ownUpgradeMutation);
        if (!targetCurrent.getAsBoolean()) return Result.TARGET_CHANGED;

        // An external-only configuration retains the upstream payment-before-hooks contract.
        // External providers do not expose a general compensation or commit protocol.
        if (!requiresLevelAcknowledgement) withdrawCosts.run();

        for (String command : commands) {
            if (!targetCurrent.getAsBoolean()) return Result.TARGET_CHANGED;
            boolean ownMutation = ownUpgradeMutation.test(command);
            if (!Boolean.TRUE.equals(dispatch.apply(command))) return Result.COMMAND_REJECTED;
            if (ownMutation && !expectedLevelReached.getAsBoolean()) return Result.LEVEL_NOT_ADVANCED;
        }

        if (!targetCurrent.getAsBoolean()) return Result.TARGET_CHANGED;
        if (requiresLevelAcknowledgement) {
            // Bukkit's boolean acknowledges dispatch, not a void handler's business mutation.
            if (!expectedLevelReached.getAsBoolean()) return Result.LEVEL_NOT_ADVANCED;
            withdrawCosts.run();
        }
        return Result.SUCCESS;
    }
}
