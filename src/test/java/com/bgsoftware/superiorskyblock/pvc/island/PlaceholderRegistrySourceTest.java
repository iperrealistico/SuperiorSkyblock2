package com.bgsoftware.superiorskyblock.pvc.island;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Prevent a fatal ImmutableMap initializer before a native Bukkit server is available. */
public class PlaceholderRegistrySourceTest {
    @Test public void literalImmutableRegistryKeysAreUnique() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/bgsoftware/superiorskyblock/service/placeholders/PlaceholdersServiceImpl.java")), StandardCharsets.UTF_8);
        for (String registry : new String[]{"PLAYER_PARSES", "ISLAND_PARSES"}) {
            int start = source.indexOf("Map<String, " + (registry.equals("PLAYER_PARSES") ? "Player" : "Island") + "PlaceholderParser> " + registry);
            assertTrue("Missing immutable placeholder registry: " + registry, start >= 0);
            int end = source.indexOf(".build();", start); assertTrue(end > start);
            Matcher matcher = Pattern.compile("\\.put\\(\"([^\"]+)\"").matcher(source.substring(start, end));
            Set<String> keys = new HashSet<>();
            while (matcher.find()) assertTrue("Duplicate startup placeholder key in " + registry + ": " + matcher.group(1), keys.add(matcher.group(1)));
            assertFalse("Registry was not inspected", keys.isEmpty());
        }
    }
}
