package ru.dvolk.discordvelocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

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
        bot.sendChatPlain(messages.format("player.chat",
                "player", event.getPlayer().getUsername(),
                "message", event.getMessage()));
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
