package ru.dvolk.discordvelocity;

import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;

import java.time.LocalTime;

public final class PresenceEntry {

    private final Activity.ActivityType type;
    private final String text;
    private final String url;
    private final LocalTime at;
    private final OnlineStatus status;

    public PresenceEntry(Activity.ActivityType type, String text, String url, LocalTime at, OnlineStatus status) {
        this.type = type;
        this.text = text;
        this.url = url;
        this.at = at;
        this.status = status;
    }

    public PresenceEntry(Activity.ActivityType type, String text, String url, LocalTime at) {
        this(type, text, url, at, null);
    }

    public Activity.ActivityType type() { return type; }
    public String text() { return text; }
    public String url() { return url; }
    public LocalTime at() { return at; }
    public OnlineStatus status() { return status; }

    public static Activity.ActivityType parseType(String s) {
        if (s == null || s.isBlank()) return Activity.ActivityType.PLAYING;
        return switch (s.trim().toUpperCase()) {
            case "LISTENING" -> Activity.ActivityType.LISTENING;
            case "WATCHING" -> Activity.ActivityType.WATCHING;
            case "STREAMING" -> Activity.ActivityType.STREAMING;
            case "COMPETING" -> Activity.ActivityType.COMPETING;
            case "CUSTOM", "CUSTOM_STATUS" -> Activity.ActivityType.CUSTOM_STATUS;
            default -> Activity.ActivityType.PLAYING;
        };
    }
}
