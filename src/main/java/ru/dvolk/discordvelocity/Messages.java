package ru.dvolk.discordvelocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Messages {

    private final Map<String, String> flat;
    private final Map<String, String> defaults;
    private final Logger logger;

    private Messages(Map<String, String> flat, Map<String, String> defaults, Logger logger) {
        this.flat = flat;
        this.defaults = defaults;
        this.logger = logger;
    }

    public static Messages loadOrCreate(Path dataDir, Logger logger, String pluginVersion) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("messages.yml");

        Path legacy = dataDir.resolve("messages.properties");
        if (!Files.exists(file) && Files.exists(legacy)) {
            Path backup = Config.Yml.nextOldName(dataDir, "messages.properties", "legacy");
            Files.move(legacy, backup);
            logger.warn("Migrated legacy messages.properties to {}", backup.getFileName());
        }

        if (!Files.exists(file)) {
            Config.Yml.copyDefault("/messages.yml.default", file);
            logger.info("Created default messages at {}", file);
        }

        Map<String, Object> root = Config.Yml.read(file);
        String fileVersion = Config.Yml.getString(root, "messages-version", "").trim();
        if (!fileVersion.equals(pluginVersion)) {
            String marker = fileVersion.isEmpty() ? "unknown" : fileVersion;
            Path backup = Config.Yml.nextOldName(dataDir, "messages", marker);
            Files.move(file, backup);
            logger.warn("messages.yml version mismatch (file='{}', plugin='{}'). Backed up to {}",
                    fileVersion, pluginVersion, backup.getFileName());
            Config.Yml.copyDefault("/messages.yml.default", file);
            root = Config.Yml.read(file);
        }

        Map<String, String> flat = new HashMap<>();
        flatten("", root, flat);

        Map<String, Object> defRoot;
        try (var in = Messages.class.getResourceAsStream("/messages.yml.default")) {
            if (in == null) throw new IOException("Bundled messages.yml.default not found");
            Object loaded = new org.yaml.snakeyaml.Yaml().load(in);
            defRoot = loaded instanceof Map<?, ?> m
                    ? castMap(m)
                    : new HashMap<>();
        }
        Map<String, String> defaults = new HashMap<>();
        flatten("", defRoot, defaults);

        return new Messages(flat, defaults, logger);
    }

    public String format(String key, Object... placeholders) {
        String template = flat.get(key);
        if (template == null) template = defaults.get(key);
        if (template == null) {
            logger.warn("Missing message key: {}", key);
            return "[missing:" + key + "]";
        }
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        String result = template;
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            String value = String.valueOf(placeholders[i + 1]);
            result = result.replace("%" + name + "%", value);
        }
        return result;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String next = prefix.isEmpty() ? key : prefix + "." + key;
                flatten(next, e.getValue(), out);
            }
        } else if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
