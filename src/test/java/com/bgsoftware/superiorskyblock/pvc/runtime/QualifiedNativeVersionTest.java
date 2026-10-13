package com.bgsoftware.superiorskyblock.pvc.runtime;

import com.bgsoftware.superiorskyblock.core.QualifiedNativeVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class QualifiedNativeVersionTest {
    @Test public void selectsExactLegacyBukkitMetadata() {
        assertEquals("v1_21_11", QualifiedNativeVersion.adapterFor("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("v26_2", QualifiedNativeVersion.adapterFor("26.2-R0.1-SNAPSHOT"));
        assertEquals("v1_21_11", QualifiedNativeVersion.adapterFor("1.21.11"));
        assertEquals("v26_2", QualifiedNativeVersion.adapterFor("26.2"));
    }

    @Test public void selectsCanonicalFoliaBuildMetadataAndStrippedVersion() {
        assertEquals("v26_2", QualifiedNativeVersion.adapterFor("26.2.build.7-beta"));
        assertEquals("v26_2", QualifiedNativeVersion.adapterFor("26.2.build.7"));
        assertEquals("v26_2", QualifiedNativeVersion.adapterFor("26.2.build.42-beta-R0.1-SNAPSHOT"));
        assertEquals("v1_21_11", QualifiedNativeVersion.adapterFor("1.21.11.build.14-beta"));
    }

    @Test public void neverAliasesOtherMinecraftReleasesOrMalformedBuilds() {
        for (String version : new String[] { null, "", "26.2.1", "26.2.1-R0.1-SNAPSHOT",
                "26.20-R0.1-SNAPSHOT", "26.3.build.7-beta", "1.21.110-R0.1-SNAPSHOT",
                "1.21.11.1", "26.2.build.beta", "26.2.build.", "26.2.build.7.1",
                "26.2.build.-7", "26.2-", "26.2 ", " 26.2", "26.2.build.7-beta\n" }) {
            assertNull("Unexpected native adapter for " + version, QualifiedNativeVersion.adapterFor(version));
        }
    }
}
