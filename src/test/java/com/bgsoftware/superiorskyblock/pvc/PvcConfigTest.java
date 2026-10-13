package com.bgsoftware.superiorskyblock.pvc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PvcConfigTest {
    @Test public void missingOnlyMigrationPreservesOriginalCommentsCredentialsAndValues() throws Exception {
        String original = "# private fixture\r\nmax-island-size: 1000\r\ndatabase:\r\n  password: 'fixture-not-a-real-secret' # retain\r\ndefault-values:\r\n  island-size: 100\r\n";
        YamlConfiguration parsed = new YamlConfiguration(); parsed.loadFromString(original);
        String migrated = PvcConfig.addMissingDefaults(original, parsed);
        assertTrue(migrated.startsWith(original));
        assertTrue(migrated.contains("size-mode: LEGACY"));
        assertFalse(migrated.replace("\r\n", "").contains("\n"));
        YamlConfiguration after = new YamlConfiguration(); after.loadFromString(migrated);
        assertEquals(1000, after.getInt("max-island-size"));
        assertEquals("fixture-not-a-real-secret", after.getString("database.password"));
        assertEquals(migrated, PvcConfig.addMissingDefaults(migrated, after));
    }
    @Test public void existingPvcValuesAreRetainedAndSiblingsStayOutsideTheSection() throws Exception {
        String original = "pvc:\n  size-mode: PERMISSION_AUTOMATIC\n  entitlements:\n    refresh-seconds: 45 # operator choice\nworlds:\n  name: SuperiorWorld\n";
        YamlConfiguration parsed = new YamlConfiguration(); parsed.loadFromString(original);
        String migrated = PvcConfig.addMissingDefaults(original, parsed);
        YamlConfiguration after = new YamlConfiguration(); after.loadFromString(migrated);
        assertEquals("PERMISSION_AUTOMATIC", after.getString("pvc.size-mode"));
        assertEquals(45, after.getInt("pvc.entitlements.refresh-seconds"));
        assertEquals("SuperiorWorld", after.getString("worlds.name"));
        assertTrue(migrated.contains("45 # operator choice"));
    }
    @Test public void inlineMappingIsNotReformattedOrOverwritten() throws Exception {
        String original = "pvc: {size-mode: LEGACY}\n";
        YamlConfiguration parsed = new YamlConfiguration(); parsed.loadFromString(original);
        try { PvcConfig.addMissingDefaults(original, parsed); fail("Unsupported inline maps must fail closed"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("inline")); }
    }
    @Test public void atomicMigrationBacksUpExactOriginalAndIsIdempotent() throws Exception {
        Path folder = Files.createTempDirectory("superior-config-migration");
        Path file = folder.resolve("config.yml");
        byte[] original = "max-island-size: 1000\ndefault-values:\n  island-size: 100\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        PvcConfig first = PvcConfig.prepareAndLoad(folder.toFile());
        assertEquals(PvcConfig.SizeMode.LEGACY, first.getSizeMode());
        assertTrue(first.isDefaultLanguageForced());
        assertEquals(8, first.getChunkLoadConcurrency());
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("config.yml.pvc-backup")));
        byte[] migrated = Files.readAllBytes(file);
        PvcConfig.prepareAndLoad(folder.toFile());
        assertArrayEquals(migrated, Files.readAllBytes(file));
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("config.yml.pvc-backup")));
    }
    @Test public void invalidOperatorSettingIsNotSilentlyCorrected() throws Exception {
        Path folder = Files.createTempDirectory("superior-config-invalid");
        byte[] original = "pvc:\n  size-mode: LEGACY\n  entitlements:\n    refresh-seconds: -1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("config.yml"), original);
        try { PvcConfig.prepareAndLoad(folder.toFile()); fail("Invalid supplied values must fail closed"); }
        catch (java.io.IOException expected) { assertArrayEquals(original, Files.readAllBytes(folder.resolve("config.yml"))); }
    }
    @Test public void explicitMultilingualOptOutSurvivesMissingKeyMigration() throws Exception {
        Path folder = Files.createTempDirectory("superior-language-policy");
        Path file = folder.resolve("config.yml");
        String original = "pvc:\n  force-default-language: false # intentional opt-out\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        assertFalse(PvcConfig.prepareAndLoad(folder.toFile()).isDefaultLanguageForced());
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("false # intentional opt-out"));
    }
    @Test public void invalidLanguagePolicyRetainsOriginalConfiguration() throws Exception {
        Path folder = Files.createTempDirectory("superior-language-policy-invalid");
        Path file = folder.resolve("config.yml");
        byte[] original = "pvc:\n  force-default-language: 'invalid'\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        try { PvcConfig.prepareAndLoad(folder.toFile()); fail("Invalid language policy must be rejected"); }
        catch (java.io.IOException expected) { assertArrayEquals(original, Files.readAllBytes(file)); }
    }
    @Test public void optionalPrivateOperatorFixturePreservesEveryExistingLeafValue() throws Exception {
        String supplied = System.getProperty("pvc.fixture.config");
        org.junit.Assume.assumeNotNull(supplied);
        byte[] bytes = Files.readAllBytes(java.nio.file.Paths.get(supplied));
        String original = new String(bytes, StandardCharsets.UTF_8);
        YamlConfiguration before = new YamlConfiguration(); before.loadFromString(original);
        String migrated = PvcConfig.addMissingDefaults(original, before);
        YamlConfiguration after = new YamlConfiguration(); after.loadFromString(migrated);
        for (java.util.Map.Entry<String, Object> entry : before.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof org.bukkit.configuration.ConfigurationSection))
                assertEquals("Existing setting changed: " + entry.getKey(), entry.getValue(), after.get(entry.getKey()));
        }
        assertTrue("Original fixture lines must remain an exact prefix when PVC section is absent", before.contains("pvc") || migrated.startsWith(original));
        assertArrayEquals("Read-only fixture must never be rewritten", bytes, Files.readAllBytes(java.nio.file.Paths.get(supplied)));
    }
}
