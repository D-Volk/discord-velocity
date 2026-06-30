package ru.dvolk.discordvelocity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

public final class ConsoleLogAppender extends AbstractAppender {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern ANSI = Pattern.compile("\\[[\\d;]*[a-zA-Z]");

    private final DiscordBot bot;
    private volatile List<String> excludedLoggers;

    private static String stripAnsi(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    public void setExcludedLoggers(List<String> excludedLoggers) {
        this.excludedLoggers = excludedLoggers;
    }

    private ConsoleLogAppender(String name, Layout<? extends Serializable> layout,
                               DiscordBot bot, List<String> excludedLoggers) {
        super(name, (Filter) null, layout, false, Property.EMPTY_ARRAY);
        this.bot = bot;
        this.excludedLoggers = excludedLoggers;
    }

    public static ConsoleLogAppender attach(DiscordBot bot, Config config) {
        Layout<String> layout = PatternLayout.newBuilder()
                .withPattern("%msg")
                .build();
        ConsoleLogAppender appender = new ConsoleLogAppender(
                "DiscordVelocityAppender", layout, bot, config.excludeLoggers());
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().addAppender(appender, null, null);
        ctx.updateLoggers();
        return appender;
    }

    public void detach() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
        } catch (Exception ignored) {
        }
        stop();
    }

    @Override
    public void append(LogEvent event) {
        try {
            String loggerName = event.getLoggerName();
            if (loggerName != null) {
                for (String prefix : excludedLoggers) {
                    if (loggerName.startsWith(prefix)) return;
                }
            }
            String msg = event.getMessage().getFormattedMessage();
            if (msg == null || msg.isEmpty()) return;
            msg = stripAnsi(msg);
            String line = "[" + LocalTime.now().format(TIME) + " "
                    + event.getLevel().name() + "] " + msg;
            bot.enqueueConsoleLine(line);
            if (event.getThrown() != null) {
                bot.enqueueConsoleLine("  " + event.getThrown().toString());
            }
        } catch (Throwable t) {
            // Never let the appender crash — would break logging globally.
        }
    }
}
