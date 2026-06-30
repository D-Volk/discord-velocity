package ru.dvolk.discordvelocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class DiscordBot {

    private static final int DISCORD_MSG_LIMIT = 1900;

    private volatile Config config;
    private volatile Messages messages;
    private final ProxyServer proxy;
    private final Logger logger;

    private final ConcurrentLinkedQueue<String> consoleBuffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-velocity-scheduler");
                t.setDaemon(true);
                return t;
            });

    private volatile JDA jda;
    private volatile TextChannel chatChannel;
    private volatile TextChannel consoleChannel;
    private volatile PresenceEntry currentEntry;
    private volatile DiscordListener listener;
    private volatile ScheduledFuture<?> presenceTask;
    private int rotateIndex = 0;

    public DiscordBot(Config config, Messages messages, ProxyServer proxy, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.proxy = proxy;
        this.logger = logger;
    }

    public void start() throws InterruptedException {
        JDABuilder builder = JDABuilder.createLight(config.token(),
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS,
                        CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .setActivity(Activity.playing("starting..."))
                .addEventListeners(this.listener = new DiscordListener(this, config, messages, proxy, logger));

        this.jda = builder.build();
        jda.awaitReady();

        if (!config.chatChannelId().isBlank()) {
            this.chatChannel = jda.getTextChannelById(config.chatChannelId());
            if (chatChannel == null) {
                logger.warn("Chat channel id={} not found or bot has no access", config.chatChannelId());
            }
        }
        if (!config.consoleChannelId().isBlank()) {
            this.consoleChannel = jda.getTextChannelById(config.consoleChannelId());
            if (consoleChannel == null) {
                logger.warn("Console channel id={} not found or bot has no access", config.consoleChannelId());
            }
        }

        scheduler.scheduleWithFixedDelay(this::flushConsoleBuffer,
                config.consoleFlushIntervalMs(), config.consoleFlushIntervalMs(), TimeUnit.MILLISECONDS);
        startPresenceScheduler();

        registerSlashCommands();

        logger.info("Discord bot connected as {}", jda.getSelfUser().getAsTag());
    }

    private void registerSlashCommands() {
        var commandsList = new net.dv8tion.jda.api.interactions.commands.build.SlashCommandData[]{
                Commands.slash("commands", "List all available proxy console commands")
        };
        if (!config.guildId().isBlank()) {
            Guild guild = jda.getGuildById(config.guildId());
            if (guild != null) {
                guild.updateCommands().addCommands(commandsList).queue(
                        ok -> logger.info("Registered {} slash command(s) in guild {}", commandsList.length, guild.getName()),
                        err -> logger.warn("Failed to register guild slash commands: {}", err.toString()));
                return;
            }
            logger.warn("Guild id={} not found — falling back to global slash commands", config.guildId());
        }
        jda.updateCommands().addCommands(commandsList).queue(
                ok -> logger.info("Registered {} global slash command(s) (may take up to 1h to appear)", commandsList.length),
                err -> logger.warn("Failed to register global slash commands: {}", err.toString()));
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            flushConsoleBuffer();
        } catch (Exception ignored) {
        }
        if (jda != null) {
            jda.shutdown();
            try {
                if (!jda.awaitShutdown(10, TimeUnit.SECONDS)) {
                    jda.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                jda.shutdownNow();
            }
        }
    }

    public void updatePresence() {
        applyEntry(currentEntry);
    }

    public Config config() { return config; }
    public Messages messages() { return messages; }
    public JDA jda() { return jda; }
    public TextChannel chatChannel() { return chatChannel; }
    public TextChannel consoleChannel() { return consoleChannel; }
    public PresenceEntry currentEntry() { return currentEntry; }

    /**
     * Reload config and messages without restarting the bot.
     * Returns warnings about static fields that changed but cannot be applied (token, channel-ids, guild-id).
     */
    public List<String> reload(Config newConfig, Messages newMessages) {
        List<String> warnings = new ArrayList<>();
        if (!config.token().equals(newConfig.token()))
            warnings.add("discord.token changed — requires proxy restart");
        if (!config.guildId().equals(newConfig.guildId()))
            warnings.add("discord.guild-id changed — requires proxy restart");
        if (!config.chatChannelId().equals(newConfig.chatChannelId()))
            warnings.add("discord.chat-channel-id changed — requires proxy restart");
        if (!config.consoleChannelId().equals(newConfig.consoleChannelId()))
            warnings.add("discord.console-channel-id changed — requires proxy restart");
        if (config.consoleFlushIntervalMs() != newConfig.consoleFlushIntervalMs())
            warnings.add("console.flush-interval-ms changed — requires proxy restart");

        this.config = newConfig;
        this.messages = newMessages;
        if (listener != null) listener.reload(newConfig, newMessages);
        startPresenceScheduler();
        return warnings;
    }

    private void startPresenceScheduler() {
        if (presenceTask != null) {
            presenceTask.cancel(false);
            presenceTask = null;
        }
        rotateIndex = 0;

        List<PresenceEntry> list = config.presenceList();
        Config.PresenceMode mode = config.presenceMode();

        if (list.isEmpty() || mode == Config.PresenceMode.SINGLE) {
            currentEntry = new PresenceEntry(config.presenceType(), config.presenceFormat(), null, null);
            applyEntry(currentEntry);
            presenceTask = scheduler.scheduleWithFixedDelay(this::updatePresence, 60, 60, TimeUnit.SECONDS);
            return;
        }

        if (mode == Config.PresenceMode.ROTATE) {
            currentEntry = list.get(0);
            applyEntry(currentEntry);
            long sec = Math.max(5, config.presenceRotationIntervalSec());
            presenceTask = scheduler.scheduleWithFixedDelay(() -> {
                List<PresenceEntry> cur = config.presenceList();
                if (cur.isEmpty()) return;
                rotateIndex = (rotateIndex + 1) % cur.size();
                currentEntry = cur.get(rotateIndex);
                applyEntry(currentEntry);
            }, sec, sec, TimeUnit.SECONDS);
            return;
        }

        // SCHEDULE
        presenceTask = scheduler.scheduleWithFixedDelay(() -> {
            PresenceEntry pick = pickScheduledEntry(config.presenceList());
            if (pick == null) return;
            currentEntry = pick;
            applyEntry(currentEntry);
        }, 0, 60, TimeUnit.SECONDS);
    }

    private static PresenceEntry pickScheduledEntry(List<PresenceEntry> list) {
        java.time.LocalTime now = java.time.LocalTime.now();
        PresenceEntry chosen = null;
        PresenceEntry fallback = null;
        for (PresenceEntry e : list) {
            if (e.at() == null) continue;
            if (fallback == null || e.at().isAfter(fallback.at())) fallback = e;
            if (!e.at().isAfter(now)) {
                if (chosen == null || e.at().isAfter(chosen.at())) chosen = e;
            }
        }
        if (chosen != null) return chosen;
        return fallback; // before the first slot of today → use yesterday's last
    }

    private void applyEntry(PresenceEntry entry) {
        if (jda == null || entry == null) return;
        try {
            int online = proxy.getPlayerCount();
            int max = proxy.getConfiguration().getShowMaxPlayers();
            String text = entry.text()
                    .replace("%online%", String.valueOf(online))
                    .replace("%max%", String.valueOf(max));
            Activity activity = entry.type() == Activity.ActivityType.STREAMING && entry.url() != null
                    ? Activity.of(Activity.ActivityType.STREAMING, text, entry.url())
                    : Activity.of(entry.type(), text);
            jda.getPresence().setPresence(pickStatus(online, entry), activity, false);
        } catch (Exception e) {
            logger.debug("Failed to update presence", e);
        }
    }

    private net.dv8tion.jda.api.OnlineStatus pickStatus(int online, PresenceEntry entry) {
        if (entry != null && entry.status() != null) return entry.status();
        Config cfg = config;
        if (cfg.statusMode() == Config.StatusMode.MANUAL) return cfg.statusManual();
        return online > 0 ? cfg.statusActive() : cfg.statusEmpty();
    }

    public void sendChatEmbed(String text, Color color) {
        TextChannel ch = chatChannel;
        if (ch == null) return;
        MessageEmbed embed = new EmbedBuilder()
                .setDescription(sanitize(text))
                .setColor(color)
                .build();
        ch.sendMessageEmbeds(embed).queue(null, err ->
                logger.warn("Failed to send Discord embed: {}", err.toString()));
    }

    public void sendChatEmbedBlocking(String text, Color color) {
        TextChannel ch = chatChannel;
        if (ch == null) return;
        MessageEmbed embed = new EmbedBuilder()
                .setDescription(sanitize(text))
                .setColor(color)
                .build();
        try {
            ch.sendMessageEmbeds(embed).complete();
        } catch (Exception e) {
            logger.warn("Failed to send blocking Discord embed: {}", e.toString());
        }
    }

    public void sendChatPlain(String text) {
        TextChannel ch = chatChannel;
        if (ch == null) return;
        ch.sendMessage(sanitize(text)).queue(null, err ->
                logger.warn("Failed to send chat message: {}", err.toString()));
    }

    public void enqueueConsoleLine(String line) {
        if (consoleChannel == null || line == null) return;
        consoleBuffer.offer(line);
    }

    private void flushConsoleBuffer() {
        TextChannel ch = consoleChannel;
        if (ch == null || consoleBuffer.isEmpty()) return;

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String line;
        while ((line = consoleBuffer.poll()) != null) {
            if (line.length() > DISCORD_MSG_LIMIT) {
                line = line.substring(0, DISCORD_MSG_LIMIT - 3) + "...";
            }
            if (current.length() + line.length() + 1 > DISCORD_MSG_LIMIT) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) chunks.add(current.toString());

        for (String chunk : chunks) {
            try {
                ch.sendMessage("```log\n" + chunk + "\n```").queue(null, err ->
                        logger.debug("Failed to send console chunk: {}", err.toString()));
            } catch (Exception e) {
                logger.debug("Failed to send console chunk", e);
            }
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("@everyone", "@​everyone")
                .replace("@here", "@​here");
    }
}
