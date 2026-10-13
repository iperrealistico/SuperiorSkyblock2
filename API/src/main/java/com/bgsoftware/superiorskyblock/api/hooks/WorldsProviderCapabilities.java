package com.bgsoftware.superiorskyblock.api.hooks;

import java.util.Objects;

/** Admission checks for additive provider contracts; legacy single-island Paper needs none. */
public final class WorldsProviderCapabilities {
    private WorldsProviderCapabilities() { }

    public static void require(WorldsProvider provider, boolean folia, boolean multipleOwnedIslands,
                               boolean automaticIslandSize) {
        Objects.requireNonNull(provider, "Worlds provider is unavailable");
        if (folia && !provider.supportsFoliaExecution())
            throw unsupported(provider, "Folia execution", "supportsFoliaExecution");
        if (multipleOwnedIslands && !provider.supportsMultipleOwnedIslands())
            throw unsupported(provider, "multiple owned islands", "supportsMultipleOwnedIslands");
        if (automaticIslandSize && !provider.supportsAutomaticIslandSizePolicy())
            throw unsupported(provider, "automatic island size policy", "supportsAutomaticIslandSizePolicy");
    }

    private static IllegalStateException unsupported(WorldsProvider provider, String feature, String contract) {
        return new IllegalStateException("Worlds provider " + provider.getClass().getName() + " is not qualified for " +
                feature + ". Use the default shared-world provider or an addon implementing and testing " + contract + "().");
    }
}
