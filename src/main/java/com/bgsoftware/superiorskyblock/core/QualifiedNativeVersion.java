package com.bgsoftware.superiorskyblock.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact Minecraft ABI selection, accepting Bukkit's legacy and build metadata formats. */
public final class QualifiedNativeVersion {
    private static final Pattern VERSION = Pattern.compile(
            "^(1\\.21\\.11|26\\.2)(?:-[^\\s]+|\\.build\\.[0-9]+(?:-[^\\s]+)?)?$");

    private QualifiedNativeVersion() { }

    /** Unknown Minecraft releases are intentionally not mapped to a nearby native adapter. */
    public static String adapterFor(String bukkitVersion) {
        if (bukkitVersion == null) return null;
        Matcher matcher = VERSION.matcher(bukkitVersion);
        if (!matcher.matches()) return null;
        return "1.21.11".equals(matcher.group(1)) ? "v1_21_11" : "v26_2";
    }
}
