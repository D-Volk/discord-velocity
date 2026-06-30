package ru.dvolk.discordvelocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

public final class DiscordListener extends ListenerAdapter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DiscordBot bot;
    private volatile Config config;
    private volatile Messages messages;
    private final ProxyServer proxy;
    private final Logger logger;

    public DiscordListener(DiscordBot bot, Config config, Messages messages,
                           ProxyServer proxy, Logger logger) {
        this.bot = bot;
        this.config = config;
        this.messages = messages;
        this.proxy = proxy;
        this.logger = logger;
    }

    public void reload(Config newConfig, Messages newMessages) {
        this.config = newConfig;
        this.messages = newMessages;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;
        if (!event.isFromGuild()) return;

        String channelId = event.getChannel().getId();
        String content = event.getMessage().getContentDisplay();
        if (content.isBlank()) return;

        if (config.chatDiscordToMc() && channelId.equals(config.chatChannelId())) {
            handleChat(event, content);
        } else if (config.consoleBidirectional() && channelId.equals(config.consoleChannelId())) {
            handleConsole(event, content);
        }
    }

    private void handleChat(MessageReceivedEvent event, String content) {
        String name = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getName();

        String raw = messages.format("discord.chat.mc",
                "player", escapeMini(name),
                "message", escapeMini(content));
        Component msg = MINI.deserialize(raw);

        proxy.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }

    private void handleConsole(MessageReceivedEvent event, String content) {
        String command = content.startsWith("/") ? content.substring(1) : content;
        if (command.isBlank()) return;

        proxy.getCommandManager()
                .executeAsync(proxy.getConsoleCommandSource(), command)
                .whenComplete((ok, err) -> {
                    if (err != null) {
                        logger.warn("Discord console command failed: {}", err.toString());
                        event.getMessage().addReaction(Emoji.fromUnicode("❌")).queue(null, e -> {});
                    } else {
                        event.getMessage().addReaction(Emoji.fromUnicode(ok ? "✅" : "⚠️")).queue(null, e -> {});
                    }
                });
    }

    private static String escapeMini(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("commands")) return;

        if (!event.getChannel().getId().equals(config.consoleChannelId())) {
            event.reply(messages.format("command.list.wrong-channel"))
                    .setEphemeral(true).queue();
            return;
        }

        List<String> aliases = proxy.getCommandManager().getAliases().stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (aliases.isEmpty()) {
            event.reply(messages.format("command.list.empty")).setEphemeral(true).queue();
            return;
        }

        String header = messages.format("command.list.header",
                "count", String.valueOf(aliases.size()));
        String list = aliases.stream().map(s -> "/" + s).collect(Collectors.joining(", "));

        String body = "```\n" + list + "\n```";
        int maxBody = 1900 - header.length() - 2;
        if (body.length() > maxBody) {
            body = body.substring(0, maxBody - 6) + "…\n```";
        }
        event.reply(header + "\n" + body).setEphemeral(true).queue();
    }
}
