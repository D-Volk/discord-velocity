package ru.dvolk.discordvelocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VelocityCommand implements SimpleCommand {

    private static final String PERMISSION = "discordvelocity.admin";

    private final DiscordVelocityPlugin plugin;

    public VelocityCommand(DiscordVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            send(source, "&eUsage: /discord-velocity <info|reload>  &7(alias: /dv)");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "info" -> handleInfo(source);
            case "reload" -> handleReload(source);
            default -> send(source, "&cUnknown subcommand. Use: info | reload");
        }
    }

    private void handleInfo(CommandSource source) {
        DiscordBot bot = plugin.bot();
        Config cfg = plugin.config();

        if (bot == null || cfg == null) {
            send(source, "&cPlugin is not running (config error or missing token).");
            return;
        }

        var jda = bot.jda();
        String botTag = jda != null && jda.getSelfUser() != null
                ? jda.getSelfUser().getName() + " (id=" + jda.getSelfUser().getId() + ")"
                : "not connected";
        String chat = describeChannel(bot.chatChannel(), cfg.chatChannelId());
        String console = describeChannel(bot.consoleChannel(), cfg.consoleChannelId());
        String guild = cfg.guildId().isBlank() ? "(global commands)" : cfg.guildId();
        var entry = bot.currentEntry();
        String presence = entry == null
                ? "(none)"
                : entry.type() + " — " + entry.text() + (entry.at() != null ? " @ " + entry.at() : "");

        send(source, "&6=== Discord-Velocity ===");
        plugin.proxy().getPluginManager().getPlugin("discord-velocity").ifPresent(pc -> {
            var d = pc.getDescription();
            String version = d.getVersion().orElse("unknown");
            String name = d.getName().orElse(d.getId());
            String authors = d.getAuthors().isEmpty() ? "unknown" : String.join(", ", d.getAuthors());
            send(source, "&7Plugin: &f" + name + " &7v&f" + version + " &7by &f" + authors);
            d.getDescription().ifPresent(desc -> send(source, "&7Description: &f" + desc));
        });
        send(source, "&7Bot: &f" + botTag);
        send(source, "&7Guild: &f" + guild);
        send(source, "&7Chat channel: &f" + chat);
        send(source, "&7Console channel: &f" + console);
        // send(source, "&7Presence mode: &f" + cfg.presenceMode()
        //         + "  &7entries: &f" + cfg.presenceList().size());
        // send(source, "&7Current presence: &f" + presence);
        send(source, "&7Online players: &f" + plugin.proxy().getPlayerCount());
    }

    private String describeChannel(net.dv8tion.jda.api.entities.channel.concrete.TextChannel ch, String id) {
        if (ch == null) return id.isBlank() ? "(not configured)" : id + " (not found)";
        return "#" + ch.getName() + " (id=" + ch.getId() + ")";
    }

    private void handleReload(CommandSource source) {
        try {
            Config newConfig = Config.loadOrCreate(plugin.dataDir(), plugin.pluginLogger(), plugin.pluginVersion());
            Messages newMessages = Messages.loadOrCreate(plugin.dataDir(), plugin.pluginLogger(), plugin.pluginVersion());
            List<String> warnings = plugin.applyReload(newConfig, newMessages);
            send(source, "&aDiscord-Velocity reloaded.");
            for (String w : warnings) {
                send(source, "&e[warn] " + w);
            }
        } catch (Exception e) {
            send(source, "&cReload failed: " + e.getMessage());
            plugin.pluginLogger().error("Reload failed", e);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource src = invocation.source();
        // Console always allowed; players need the permission.
        if (!(src instanceof com.velocitypowered.api.proxy.Player)) return true;
        return src.hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            return Stream.of("info", "reload")
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private static void send(CommandSource source, String msg) {
        source.sendMessage(colorize(msg));
    }

    private static Component colorize(String s) {
        Component out = Component.empty();
        StringBuilder buf = new StringBuilder();
        NamedTextColor color = NamedTextColor.WHITE;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                NamedTextColor next = mapColor(s.charAt(i + 1));
                if (next != null) {
                    if (buf.length() > 0) {
                        out = out.append(Component.text(buf.toString(), color));
                        buf.setLength(0);
                    }
                    color = next;
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        if (buf.length() > 0) out = out.append(Component.text(buf.toString(), color));
        return out;
    }

    private static NamedTextColor mapColor(char c) {
        return switch (c) {
            case 'a' -> NamedTextColor.GREEN;
            case 'c' -> NamedTextColor.RED;
            case 'e' -> NamedTextColor.YELLOW;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case 'f' -> NamedTextColor.WHITE;
            case 'b' -> NamedTextColor.AQUA;
            case '9' -> NamedTextColor.BLUE;
            default -> null;
        };
    }
}
