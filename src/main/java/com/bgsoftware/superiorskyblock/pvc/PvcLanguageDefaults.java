package com.bgsoftware.superiorskyblock.pvc;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Append only missing PVC messages; retain existing locale text, comments and explicit empty values. */
public final class PvcLanguageDefaults {
    private static final Map<String, String> DEFAULTS;
    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PVC_OWNERSHIP_LIMIT_REACHED", PvcMessages.service("You have reached your limit of &e{0} &7owned islands."));
        values.put("PVC_ISLAND_CREATION_QUEUE_FULL", PvcMessages.service("Island creation is busy. Please try again shortly."));
        values.put("PVC_OWNERSHIP_POLICY_UNAVAILABLE", PvcMessages.service("Your island permissions could not be verified. Please try again shortly."));
        values.put("PVC_SERVER_LANGUAGE_FIXED", PvcMessages.service("The server language is &e{0}&7."));
        DEFAULTS = Collections.unmodifiableMap(values);
    }
    private PvcLanguageDefaults() { }
    public static void merge(File file) throws IOException {
        String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        YamlConfiguration yaml = parse(original);
        StringBuilder result = new StringBuilder(original);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) if (!present(yaml, original, entry.getKey())) {
            if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') result.append(newline);
            result.append(entry.getKey()).append(": '").append(entry.getValue().replace("'", "''")).append("'").append(newline);
        }
        String migrated = result.toString();
        parse(migrated); // Validate before replacing the original locale.
        if (!original.equals(migrated)) PvcAtomicFiles.replace(file.toPath(), migrated.getBytes(StandardCharsets.UTF_8), ".pvc-backup");
    }
    /** In-memory English fallback when a read-only locale could not receive appended keys. */
    public static void applyMissing(ConfigurationSection config, File file) {
        String original;
        try { original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (IOException unavailable) { original = ""; }
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet())
            if (!present(config, original, entry.getKey())) config.set(entry.getKey(), entry.getValue());
    }
    private static boolean present(ConfigurationSection config, String original, String key) {
        if (config.getKeys(false).contains(key)) return true;
        return Pattern.compile("(?m)^(?:" + Pattern.quote(key) + "|'" + Pattern.quote(key) + "'|\"" + Pattern.quote(key) + "\")\\s*:").matcher(original).find();
    }
    private static YamlConfiguration parse(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (org.bukkit.configuration.InvalidConfigurationException invalid) { throw new IOException("Invalid locale; PVC message migration did not replace it", invalid); }
        return yaml;
    }
}
