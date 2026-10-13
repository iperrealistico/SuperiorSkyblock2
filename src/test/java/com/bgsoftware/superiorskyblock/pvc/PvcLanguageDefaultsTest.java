package com.bgsoftware.superiorskyblock.pvc;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class PvcLanguageDefaultsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void missingMessagesAppendWithoutChangingExistingTextOrCommentsAndAreIdempotent() throws Exception {
        java.io.File locale = temporary.newFile("it-IT.yml");
        String original = "# Preserve the operator's Italian customization\r\nPREFIX: 'Cielo àè'\r\nPVC_OWNERSHIP_LIMIT_REACHED: 'Custom {0}'\r\n";
        Files.write(locale.toPath(), original.getBytes(StandardCharsets.UTF_8));
        PvcLanguageDefaults.merge(locale);
        byte[] first = Files.readAllBytes(locale.toPath()); String migrated = new String(first, StandardCharsets.UTF_8);
        assertTrue(migrated.startsWith(original)); assertFalse(migrated.contains("You have reached"));
        assertTrue(migrated.contains("PVC_ISLAND_CREATION_QUEUE_FULL:")); assertTrue(migrated.contains("PVC_OWNERSHIP_POLICY_UNAVAILABLE:"));
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(locale.toPath().resolveSibling("it-IT.yml.pvc-backup")));
        PvcLanguageDefaults.merge(locale); assertArrayEquals(first, Files.readAllBytes(locale.toPath()));
    }
    @Test public void explicitEmptyAndNullMessagesRemainDisabled() throws Exception {
        java.io.File locale = temporary.newFile("en-US.yml");
        String original = "PVC_OWNERSHIP_LIMIT_REACHED: ''\n'PVC_ISLAND_CREATION_QUEUE_FULL': null\nPVC_OWNERSHIP_POLICY_UNAVAILABLE: ''\nPVC_SERVER_LANGUAGE_FIXED: ''\n";
        Files.write(locale.toPath(), original.getBytes(StandardCharsets.UTF_8)); PvcLanguageDefaults.merge(locale);
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(locale.toPath()));
    }
    @Test public void malformedLocaleIsRetainedRatherThanReplaced() throws Exception {
        java.io.File locale = temporary.newFile("broken.yml"); byte[] original = "PREFIX: [broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(locale.toPath(), original);
        try { PvcLanguageDefaults.merge(locale); fail("Malformed YAML must not be rewritten"); }
        catch (java.io.IOException expected) { }
        assertArrayEquals(original, Files.readAllBytes(locale.toPath()));
    }
}
