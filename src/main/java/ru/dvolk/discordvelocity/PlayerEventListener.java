package ru.dvolk.discordvelocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.query.QueryOptions;

import java.awt.Color;

public final class PlayerEventListener {

    private static final Color GREEN = new Color(0x43B581);
    private static final Color RED = new Color(0xF04747);
    private static final Color BLUE = new Color(0x5865F2);
    private static final Color GREY = new Color(0x99AAB5);

    private final DiscordBot bot;
    private volatile Config config;
    private volatile Messages messages;

    public PlayerEventListener(DiscordBot bot, Config config, Messages messages) {
        this.bot = bot;
        this.config = config;
        this.messages = messages;
    }

    public void reload(Config newConfig, Messages newMessages) {
        this.config = newConfig;
        this.messages = newMessages;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        bot.updatePresence();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String name = event.getPlayer().getUsername();
        bot.sendChatEmbed(messages.format("player.leave", "player", name), RED);
        bot.updatePresence();
    }

    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        String name = event.getPlayer().getUsername();
        String to = event.getServer().getServerInfo().getName();
        if (event.getPreviousServer().isEmpty()) {
            bot.sendChatEmbed(messages.format("player.join", "player", name, "server", to), GREEN);
        } else {
            String from = event.getPreviousServer().get().getServerInfo().getName();
            bot.sendChatEmbed(
                    messages.format("player.switch", "player", name, "from", from, "to", to),
                    BLUE);
        }
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (!config.chatMcToDiscord()) return;
        Player player = event.getPlayer();
        String server = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName()).orElse("?");
        boolean showPrefix = !config.chatNoPrefixServers().contains(server);
        String prefix = showPrefix ? stripFormatting(fetchMeta(player, true)) : "";
        String suffix = showPrefix ? stripFormatting(fetchMeta(player, false)) : "";
        String displayName;
        if (prefix.isEmpty() && suffix.isEmpty()) {
            displayName = player.getUsername();
        } else {
            String lSep = prefix.isEmpty() || prefix.endsWith(" ") ? "" : " ";
            String rSep = suffix.isEmpty() || suffix.startsWith(" ") ? "" : " ";
            displayName = prefix + lSep + player.getUsername() + rSep + suffix;
        }
        bot.sendChatPlain(messages.format("player.chat",
                "player", player.getUsername(),
                "displayname", displayName,
                "server", server,
                "message", event.getMessage()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String fetchMeta(Player player, boolean prefix) {
        try {
            var lp = LuckPermsProvider.get();
            var user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) return "";
            var cm = (net.luckperms.api.context.ContextManager) lp.getContextManager();
            QueryOptions qo = (QueryOptions) cm.getQueryOptions(player);
            var meta = user.getCachedData().getMetaData(qo);
            String value = prefix ? meta.getPrefix() : meta.getSuffix();
            return value != null ? value : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripFormatting(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replaceAll("<[^>]+>", "");
        s = s.replaceAll("[§&]x([§&][0-9a-fA-F]){6}", "");
        s = s.replaceAll("[§&]#[0-9a-fA-F]{6}", "");
        s = s.replaceAll("[§&][0-9a-fk-orxA-FK-ORX]", "");
        return s;
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent event) {
        if (!config.logCommands()) return;
        if (!(event.getCommandSource() instanceof com.velocitypowered.api.proxy.Player player)) return;
        bot.sendChatEmbed(
                messages.format("player.command", "player", player.getUsername(), "command", event.getCommand()),
                GREY);
    }
}
