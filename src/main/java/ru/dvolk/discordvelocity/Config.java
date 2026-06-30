package ru.dvolk.discordvelocity;

import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Config {

    public enum PresenceMode { SINGLE, ROTATE, SCHEDULE }
    public enum StatusMode { AUTO, MANUAL }

    private static final DateTimeFormatter AT_FMT = DateTimeFormatter.ofPattern("H:mm");

    private final String token;
    private final String guildId;
    private final String chatChannelId;
    private final String consoleChannelId;
    private final boolean chatMcToDiscord;
    private final boolean chatDiscordToMc;
    private final boolean consoleBidirectional;
    private final long consoleFlushIntervalMs;
    private final List<String> excludeLoggers;
    private final String presenceFormat;
    private final Activity.ActivityType presenceType;
    private final PresenceMode presenceMode;
    private final long presenceRotationIntervalSec;
    private final List<PresenceEntry> presenceList;
    private final boolean logCommands;
    private final StatusMode statusMode;
    private final OnlineStatus statusManual;
    private final OnlineStatus statusActive;
    private final OnlineStatus statusEmpty;

    private Config(Map<String, Object> root, Logger logger) {
        this.token = Yml.getString(root, "discord.token", "").trim();
        this.guildId = Yml.getString(root, "discord.guild-id", "").trim();
        this.chatChannelId = Yml.getString(root, "discord.chat-channel-id", "").trim();
        this.consoleChannelId = Yml.getString(root, "discord.console-channel-id", "").trim();
        this.chatMcToDiscord = Yml.getBool(root, "chat.mc-to-discord", true);
        this.chatDiscordToMc = Yml.getBool(root, "chat.discord-to-mc", true);
        this.consoleBidirectional = Yml.getBool(root, "console.bidirectional", true);
        this.consoleFlushIntervalMs = Yml.getLong(root, "console.flush-interval-ms", 1500L);
        this.excludeLoggers = Yml.getStringList(root, "console.exclude-loggers");
        this.presenceFormat = Yml.getString(root, "presence.format", "%online%/%max% online");
        this.presenceType = PresenceEntry.parseType(Yml.getString(root, "presence.type", "PLAYING"));
        this.presenceMode = parseMode(Yml.getString(root, "presence.mode", "single"));
        this.presenceRotationIntervalSec = Yml.getLong(root, "presence.rotation-interval-sec", 30L);
        this.presenceList = parsePresenceList(Yml.getMapList(root, "presence.list"), logger);
        this.logCommands = Yml.getBool(root, "events.command-log", true);
        this.statusMode = parseStatusMode(Yml.getString(root, "presence.status.mode", "auto"));
        this.statusManual = parseStatus(Yml.getString(root, "presence.status.manual", "ONLINE"), OnlineStatus.ONLINE);
        this.statusActive = parseStatus(Yml.getString(root, "presence.status.active", "ONLINE"), OnlineStatus.ONLINE);
        this.statusEmpty = parseStatus(Yml.getString(root, "presence.status.empty", "IDLE"), OnlineStatus.IDLE);
    }

    private static StatusMode parseStatusMode(String s) {
        if (s == null) return StatusMode.AUTO;
        return "manual".equalsIgnoreCase(s.trim()) ? StatusMode.MANUAL : StatusMode.AUTO;
    }

    private static OnlineStatus parseStatus(String s, OnlineStatus def) {
        return parseStatusOrNull(s) == null ? def : parseStatusOrNull(s);
    }

    static OnlineStatus parseStatusOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        String norm = s.trim().toUpperCase().replace('-', '_');
        if (norm.equals("DND")) norm = "DO_NOT_DISTURB";
        try {
            return OnlineStatus.valueOf(norm);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PresenceMode parseMode(String s) {
        if (s == null) return PresenceMode.SINGLE;
        return switch (s.trim().toLowerCase()) {
            case "rotate" -> PresenceMode.ROTATE;
            case "schedule" -> PresenceMode.SCHEDULE;
            default -> PresenceMode.SINGLE;
        };
    }

    private static List<PresenceEntry> parsePresenceList(List<Map<String, Object>> rawList, Logger logger) {
        List<PresenceEntry> out = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            Map<String, Object> e = rawList.get(i);
            String text = Yml.str(e.get("text"));
            if (text == null || text.isBlank()) continue;
            Activity.ActivityType type = PresenceEntry.parseType(Yml.str(e.get("type")));
            String url = Yml.trimToNull(Yml.str(e.get("url")));
            LocalTime at = null;
            String atRaw = Yml.trimToNull(Yml.str(e.get("at")));
            if (atRaw != null) {
                try {
                    at = LocalTime.parse(atRaw, AT_FMT);
                } catch (Exception ex) {
                    logger.warn("Bad presence.list[{}].at='{}' (expected H:mm) — ignored", i, atRaw);
                }
            }
            OnlineStatus status = parseStatusOrNull(Yml.str(e.get("status")));
            out.add(new PresenceEntry(type, text, url, at, status));
        }
        out.sort(Comparator.comparing(e -> e.at() == null ? LocalTime.MIN : e.at()));
        return out;
    }

    public static Config loadOrCreate(Path dataDir, Logger logger, String pluginVersion) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("config.yml");

        Path legacy = dataDir.resolve("config.properties");
        if (!Files.exists(file) && Files.exists(legacy)) {
            Path backup = Yml.nextOldName(dataDir, "config.properties", "legacy");
            Files.move(legacy, backup);
            logger.warn("Migrated legacy config.properties to {}", backup.getFileName());
        }

        if (!Files.exists(file)) {
            Yml.copyDefault("/config.yml.default", file);
            logger.info("Created default config at {}", file);
        }

        Map<String, Object> root = Yml.read(file);
        String fileVersion = Yml.getString(root, "config-version", "").trim();
        if (!fileVersion.equals(pluginVersion)) {
            String marker = fileVersion.isEmpty() ? "unknown" : fileVersion;
            Path backup = Yml.nextOldName(dataDir, "config", marker);
            Files.move(file, backup);
            logger.warn("config.yml version mismatch (file='{}', plugin='{}'). Backed up to {}",
                    fileVersion, pluginVersion, backup.getFileName());
            Yml.copyDefault("/config.yml.default", file);
            root = Yml.read(file);
        }

        return new Config(root, logger);
    }

    public String token() { return token; }
    public String guildId() { return guildId; }
    public String chatChannelId() { return chatChannelId; }
    public String consoleChannelId() { return consoleChannelId; }
    public boolean chatMcToDiscord() { return chatMcToDiscord; }
    public boolean chatDiscordToMc() { return chatDiscordToMc; }
    public boolean consoleBidirectional() { return consoleBidirectional; }
    public long consoleFlushIntervalMs() { return consoleFlushIntervalMs; }
    public List<String> excludeLoggers() { return excludeLoggers; }
    public String presenceFormat() { return presenceFormat; }
    public Activity.ActivityType presenceType() { return presenceType; }
    public PresenceMode presenceMode() { return presenceMode; }
    public long presenceRotationIntervalSec() { return presenceRotationIntervalSec; }
    public List<PresenceEntry> presenceList() { return presenceList; }
    public boolean logCommands() { return logCommands; }
    public StatusMode statusMode() { return statusMode; }
    public OnlineStatus statusManual() { return statusManual; }
    public OnlineStatus statusActive() { return statusActive; }
    public OnlineStatus statusEmpty() { return statusEmpty; }

    static final class Yml {

        static Map<String, Object> read(Path file) throws IOException {
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    return cast;
                }
                return new HashMap<>();
            }
        }

        static void copyDefault(String resource, Path target) throws IOException {
            try (InputStream in = Config.class.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("Bundled resource not found: " + resource);
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        static Path nextOldName(Path dir, String base, String marker) {
            String stem;
            int dot = base.lastIndexOf('.');
            stem = dot > 0 ? base.substring(0, dot) : base;
            Path candidate = dir.resolve(stem + ".old." + marker + ".yml");
            int i = 1;
            while (Files.exists(candidate)) {
                candidate = dir.resolve(stem + ".old." + marker + "." + i++ + ".yml");
            }
            return candidate;
        }

        static Object get(Map<String, Object> root, String path) {
            if (root == null) return null;
            String[] parts = path.split("\\.");
            Object cur = root;
            for (String p : parts) {
                if (!(cur instanceof Map<?, ?> m)) return null;
                cur = m.get(p);
            }
            return cur;
        }

        static String getString(Map<String, Object> root, String path, String def) {
            Object v = get(root, path);
            return v == null ? def : String.valueOf(v);
        }

        static boolean getBool(Map<String, Object> root, String path, boolean def) {
            Object v = get(root, path);
            if (v instanceof Boolean b) return b;
            if (v == null) return def;
            return Boolean.parseBoolean(String.valueOf(v).trim());
        }

        static long getLong(Map<String, Object> root, String path, long def) {
            Object v = get(root, path);
            if (v instanceof Number n) return n.longValue();
            if (v == null) return def;
            try { return Long.parseLong(String.valueOf(v).trim()); } catch (NumberFormatException e) { return def; }
        }

        static int getInt(Map<String, Object> root, String path, int def) {
            Object v = get(root, path);
            if (v instanceof Number n) return n.intValue();
            if (v == null) return def;
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return def; }
        }

        static List<String> getStringList(Map<String, Object> root, String path) {
            Object v = get(root, path);
            if (!(v instanceof List<?> raw)) return Collections.emptyList();
            List<String> out = new ArrayList<>(raw.size());
            for (Object o : raw) if (o != null) out.add(String.valueOf(o));
            return out;
        }

        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> getMapList(Map<String, Object> root, String path) {
            Object v = get(root, path);
            if (!(v instanceof List<?> raw)) return Collections.emptyList();
            List<Map<String, Object>> out = new ArrayList<>(raw.size());
            for (Object o : raw) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }

        static String str(Object o) { return o == null ? null : String.valueOf(o); }

        static String trimToNull(String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
    }
}
