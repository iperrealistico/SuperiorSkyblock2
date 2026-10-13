package com.bgsoftware.superiorskyblock.pvc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

/** Checks resource structure, preserved placeholders and non-presentation state. */
public class PvcBrandingResourcesTest {
    private static final Pattern TOKEN = Pattern.compile("\\{[^{}]+\\}|%[^%\\r\\n]+%");
    private static final Pattern COLOR = Pattern.compile("[&\\u00a7]([0-9a-f])", Pattern.CASE_INSENSITIVE);
    private static YamlConfiguration read(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        return yaml;
    }
    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>(); Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
    private static void compare(Object before, Object after, String path) {
        if (Objects.equals(before, after)) return;
        if (before instanceof String && after instanceof String) {
            assertEquals("Placeholder contract changed: " + path, tokens((String) before), tokens((String) after));
            String[] semantic = {"type", "sound", "permission", "texture", "language", "command", "commands"};
            for (String leaf : semantic) if (path.endsWith("." + leaf) && !path.endsWith(".commands"))
                assertEquals("Non-presentation identifier changed: " + path, before, after);
            if (path.contains(".commands")) {
                Matcher command = Pattern.compile("^(is(?:land)? admin msg(?:all)?(?: [^ ]+)?) ").matcher((String) before);
                if (command.find()) assertTrue("Message command target changed: " + path, ((String) after).startsWith(command.group()));
                else assertEquals("Non-message command changed: " + path, before, after);
            }
        } else if (before instanceof List && after instanceof List) {
            List<?> left = (List<?>) before, right = (List<?>) after;
            assertEquals("List length changed: " + path, left.size(), right.size());
            for (int i = 0; i < left.size(); ++i) compare(left.get(i), right.get(i), path + "[" + i + "]");
        } else if (before instanceof Map && after instanceof Map) {
            Map<?, ?> left = (Map<?, ?>) before, right = (Map<?, ?>) after;
            assertEquals("Map keys changed: " + path, left.keySet(), right.keySet());
            for (Object key : left.keySet()) compare(left.get(key), right.get(key), path + "." + key);
        } else {
            assertEquals("Non-text setting changed: " + path, before, after);
        }
    }
    @Test public void publicResourceThemeRetainsYamlStructureAndSemanticValues() throws Exception {
        Path source = Paths.get("src/main/resources");
        String supplied = System.getProperty("pvc.brand.baseline");
        List<Path> files;
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            files = stream.filter(p -> p.toString().endsWith(".yml")).collect(Collectors.toList());
        }
        for (Path file : files) {
            String relative = source.relativize(file).toString().replace('\\', '/');
            if (!(relative.equals("lang/en-US.yml") || relative.equals("config.yml") || relative.startsWith("menus/") || relative.startsWith("modules/"))) continue;
            YamlConfiguration current = read(file);
            if (supplied == null) continue;
            Path baseline = Paths.get(supplied).resolve(relative);
            assertTrue("Baseline missing: " + relative, Files.isRegularFile(baseline));
            Map<String, Object> before = read(baseline).getValues(true), after = current.getValues(true);
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                String key = entry.getKey();
                assertTrue("Resource key removed: " + relative + ":" + key, after.containsKey(key));
                if (entry.getValue() instanceof ConfigurationSection) continue;
                if (relative.equals("config.yml") && key.equals("auto-language-detection")) { assertEquals(false, after.get(key)); continue; }
                compare(entry.getValue(), after.get(key), relative + ":" + key);
            }
            for (String key : after.keySet()) if (!before.containsKey(key))
                assertTrue("Unexpected resource key: " + relative + ":" + key,
                        relative.equals("config.yml") && key.equals("pvc.force-default-language") || relative.equals("lang/en-US.yml") && key.equals("PVC_SERVER_LANGUAGE_FIXED"));
        }
    }
    @Test public void englishServiceCatalogUsesSkyblockAndOnlyPvcColors() throws Exception {
        YamlConfiguration locale = read(Paths.get("src/main/resources/lang/en-US.yml"));
        for (Map.Entry<String, Object> entry : locale.getValues(true).entrySet()) if (entry.getValue() instanceof String) {
            Matcher colors = COLOR.matcher((String) entry.getValue());
            while (colors.find()) assertTrue("Non-PVC color: " + entry.getKey(), "6e78".contains(colors.group(1).toLowerCase(Locale.ROOT)));
        }
        assertTrue(locale.getString("PVC_OWNERSHIP_LIMIT_REACHED").startsWith(PvcMessages.PREFIX));
        assertTrue(locale.getString("PVC_SERVER_LANGUAGE_FIXED").contains("The server language is"));
    }
}
