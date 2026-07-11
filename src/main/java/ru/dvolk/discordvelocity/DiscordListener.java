package ru.dvolk.discordvelocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.awt.Color;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DiscordListener extends ListenerAdapter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final DateTimeFormatter DATE_INPUT = new DateTimeFormatterBuilder()
            .appendPattern("d.M.")
            .appendValueReduced(ChronoField.YEAR, 2, 4, LocalDate.of(2000, 1, 1))
            .toFormatter();
    private static final DateTimeFormatter DATE_OUTPUT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static LocalDate parseDate(String s) {
        return LocalDate.parse(s.trim(), DATE_INPUT);
    }

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

        String roleTag = buildRoleTag(event);

        String raw = messages.format("discord.chat.mc",
                "player", escapeMini(name),
                "role", roleTag,
                "message", escapeMini(content));
        Component msg = MINI.deserialize(raw);

        proxy.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }

    private static String buildRoleTag(MessageReceivedEvent event) {
        if (event.getMember() == null) return "Discord";
        return event.getMember().getRoles().stream().findFirst().map(role -> {
            String name = escapeMini(role.getName());
            java.awt.Color color = role.getColor();
            if (color == null) return name;
            String hex = String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
            return "<color:" + hex + ">" + name + "</color>";
        }).orElse("Discord");
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
        switch (event.getName()) {
            case "commands" -> handleCommands(event);
            case "time" -> handleProfile(event);
            case "players" -> handlePlayers(event);
            default -> {}
        }
    }

    private void handleCommands(SlashCommandInteractionEvent event) {
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

    private void handleProfile(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        if (!config.statifyEnabled()) {
            event.getHook().sendMessage(messages.format("command.profile.disabled")).setEphemeral(true).queue();
            return;
        }
        OptionMapping playerOpt = event.getOption("player");
        if (playerOpt == null) return;
        String playerName = playerOpt.getAsString().trim();
        String period = event.getOption("period", "all", OptionMapping::getAsString);
        String fromRaw = event.getOption("from", null, OptionMapping::getAsString);
        String toRaw = event.getOption("to", null, OptionMapping::getAsString);

        LocalDate today = LocalDate.now();
        LocalDate from = null;
        LocalDate to = null;
        switch (period) {
            case "day" -> { from = today; to = today; }
            case "week" -> { from = today.minusDays(6); to = today; }
            case "month" -> { from = today.minusDays(29); to = today; }
            case "year" -> { from = today.minusDays(364); to = today; }
            case "custom" -> {
                try {
                    if (fromRaw != null) from = parseDate(fromRaw);
                    if (toRaw != null) to = parseDate(toRaw);
                } catch (Exception ex) {
                    event.getHook().sendMessage(messages.format("command.profile.db-error",
                            "error", "неверный формат даты (ожидается DD.MM.YYYY или DD.MM.YY)")).setEphemeral(true).queue();
                    return;
                }
                if (from == null || to == null) {
                    event.getHook().sendMessage(messages.format("command.profile.db-error",
                            "error", "для period=custom нужны from и to")).setEphemeral(true).queue();
                    return;
                }
                if (from.isAfter(to)) {
                    LocalDate tmp = from; from = to; to = tmp;
                }
            }
            case "all" -> {}
            default -> {}
        }

        final LocalDate fFrom = from;
        final LocalDate fTo = to;
        final String fPeriod = period;
        java.util.concurrent.CompletableFuture.runAsync(() -> replyProfile(event, playerName, fPeriod, fFrom, fTo));
    }

    private void replyProfile(SlashCommandInteractionEvent event, String playerName,
                              String period, LocalDate from, LocalDate to) {
        try {
            StatifyRepository repo = bot.statify();
            StatifyRepository.PlayerRef ref = repo.findPlayer(playerName);
            if (ref == null) {
                event.getHook().sendMessage(messages.format("command.profile.unknown-player",
                        "player", playerName)).setEphemeral(true).queue();
                return;
            }
            List<String> servers = config.statifyServers();
            Map<String, Long> perServer = repo.playtimePerServer(ref.uuid(), servers, from, to);

            StringBuilder body = new StringBuilder();
            long total = 0;
            for (Map.Entry<String, Long> e : perServer.entrySet()) {
                long seconds = e.getValue();
                total += seconds;
                body.append(messages.format("command.profile.server-line",
                                "server", e.getKey(),
                                "time", formatDuration(seconds)))
                        .append('\n');
            }
            if (body.length() == 0) {
                body.append(messages.format("command.profile.server-line",
                        "server", "—", "time", messages.format("command.profile.time.zero")));
                body.append('\n');
            }
            body.append(messages.format("command.profile.total-line",
                    "time", formatDuration(total)));

            String periodLabel = periodLabel(period, from, to);
            var embed = new EmbedBuilder()
                    .setTitle(messages.format("command.profile.title", "player", ref.name()))
                    .setDescription(body.toString())
                    .setFooter(messages.format("command.profile.period", "period", periodLabel))
                    .setColor(new Color(0x5865F2))
                    .build();
            event.getHook().sendMessageEmbeds(embed).queue();
        } catch (java.sql.SQLException ex) {
            logger.warn("Failed to query Statify DB: {}", ex.toString());
            event.getHook().sendMessage(messages.format("command.profile.db-error",
                    "error", ex.getMessage() == null ? "SQL error" : ex.getMessage())).setEphemeral(true).queue();
        } catch (Exception ex) {
            logger.warn("Unexpected /profile error", ex);
            event.getHook().sendMessage(messages.format("command.profile.db-error",
                    "error", ex.toString())).setEphemeral(true).queue();
        }
    }

    private String periodLabel(String period, LocalDate from, LocalDate to) {
        return switch (period) {
            case "day" -> messages.format("command.profile.period-day");
            case "week" -> messages.format("command.profile.period-week");
            case "month" -> messages.format("command.profile.period-month");
            case "year" -> messages.format("command.profile.period-year");
            case "custom" -> messages.format("command.profile.period-custom",
                    "from", from == null ? "?" : from.format(DATE_OUTPUT),
                    "to", to == null ? "?" : to.format(DATE_OUTPUT));
            default -> messages.format("command.profile.period-all");
        };
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return messages.format("command.profile.time.zero");
        long days = seconds / 86400;
        long rem = seconds % 86400;
        long hours = rem / 3600;
        long minutes = (rem % 3600) / 60;
        if (days > 0) {
            return messages.format("command.profile.time.format-full",
                    "days", String.valueOf(days),
                    "hours", String.valueOf(hours),
                    "minutes", String.valueOf(minutes));
        }
        if (hours > 0) {
            return messages.format("command.profile.time.format-hm",
                    "hours", String.valueOf(hours),
                    "minutes", String.valueOf(minutes));
        }
        return messages.format("command.profile.time.format-m",
                "minutes", String.valueOf(minutes));
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("players") || !event.getFocusedOption().getName().equals("server")) return;
        String typed = event.getFocusedOption().getValue().toLowerCase();
        List<Command.Choice> choices = proxy.getAllServers().stream()
                .map(s -> s.getServerInfo().getName())
                .filter(n -> n.toLowerCase().startsWith(typed))
                .limit(25)
                .map(n -> new Command.Choice(n, n))
                .collect(Collectors.toList());
        event.replyChoices(choices).queue();
    }

    private void handlePlayers(SlashCommandInteractionEvent event) {
        String serverFilter = event.getOption("server", null, OptionMapping::getAsString);

        List<RegisteredServer> servers;
        if (serverFilter != null && !serverFilter.isBlank()) {
            var found = proxy.getServer(serverFilter);
            if (found.isEmpty()) {
                event.reply(messages.format("command.players.unknown-server", "server", serverFilter))
                        .setEphemeral(true).queue();
                return;
            }
            servers = List.of(found.get());
        } else {
            servers = new ArrayList<>(proxy.getAllServers());
            servers.sort(Comparator.comparing(s -> s.getServerInfo().getName()));
        }

        var embed = new EmbedBuilder()
                .setTitle(messages.format("command.players.title"))
                .setColor(new Color(0x5865F2));

        int total = 0;
        for (RegisteredServer server : servers) {
            var players = server.getPlayersConnected();
            total += players.size();
            String names = players.isEmpty()
                    ? messages.format("command.players.empty")
                    : players.stream().map(p -> p.getUsername()).collect(Collectors.joining(", "));
            if (names.length() > 1024) names = names.substring(0, 1021) + "…";
            String fieldName = messages.format("command.players.server-field",
                    "server", server.getServerInfo().getName(),
                    "count", String.valueOf(players.size()));
            embed.addField(fieldName, names, false);
        }

        embed.setFooter(messages.format("command.players.total", "count", String.valueOf(total)));
        event.replyEmbeds(embed.build()).queue();
    }
}
