package ru.dvolk.discordvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Plugin(
        id = "discord-velocity",
        name = "Discord Velocity",
        version = "${version}",
        description = "Velocity ↔ Discord Server (chat + console)",
        authors = {"D-Volk"}
)
public final class DiscordVelocityPlugin {

    private static final Color GREEN = new Color(0x43B581);
    private static final Color RED = new Color(0xF04747);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private volatile Config config;
    private volatile Messages messages;
    private volatile String pluginVersion = "unknown";
    private DiscordBot bot;
    private ConsoleLogAppender appender;
    private PlayerEventListener playerListener;

    @Inject
    public DiscordVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.pluginVersion = proxy.getPluginManager().getPlugin("discord-velocity")
                .flatMap(pc -> pc.getDescription().getVersion())
                .orElse("unknown");
        try {
            this.config = Config.loadOrCreate(dataDir, logger, pluginVersion);
            this.messages = Messages.loadOrCreate(dataDir, logger, pluginVersion);
        } catch (IOException e) {
            logger.error("Failed to load config — plugin will not start", e);
            return;
        }

        if (config.token().isBlank()) {
            logger.warn("Discord token is empty in config.yml — fill it in and restart.");
            return;
        }

        this.bot = new DiscordBot(config, messages, proxy, logger);
        try {
            bot.start();
        } catch (Exception e) {
            logger.error("Failed to start Discord bot", e);
            return;
        }

        this.playerListener = new PlayerEventListener(bot, config, messages);
        proxy.getEventManager().register(this, playerListener);

        this.appender = ConsoleLogAppender.attach(bot, config);

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("discord-velocity")
                        .aliases("dv")
                        .plugin(this)
                        .build(),
                new VelocityCommand(this));

        bot.sendChatEmbed(messages.format("proxy.start"), GREEN);
        logger.info("Discord-Velocity enabled.");
    }

    public List<String> applyReload(Config newConfig, Messages newMessages) {
        this.config = newConfig;
        this.messages = newMessages;
        if (playerListener != null) playerListener.reload(newConfig, newMessages);
        if (appender != null) appender.setExcludedLoggers(newConfig.excludeLoggers());
        if (bot != null) return bot.reload(newConfig, newMessages);
        return java.util.List.of();
    }

    public ProxyServer proxy() { return proxy; }
    public Logger pluginLogger() { return logger; }
    public Path dataDir() { return dataDir; }
    public Config config() { return config; }
    public Messages messages() { return messages; }
    public DiscordBot bot() { return bot; }
    public String pluginVersion() { return pluginVersion; }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (bot != null && messages != null) {
            bot.sendChatEmbedBlocking(messages.format("proxy.stop"), RED);
        }
        if (appender != null) {
            appender.detach();
        }
        if (bot != null) {
            bot.shutdown();
        }
        logger.info("Discord-Velosity disabled.");
    }
}
