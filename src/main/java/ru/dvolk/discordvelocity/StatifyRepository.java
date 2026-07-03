package ru.dvolk.discordvelocity;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class StatifyRepository {

    private static final String DRIVER_CLASS = "ru.dvolk.discordvelocity.shaded.mariadb.Driver";

    private volatile Config config;
    private final Logger logger;
    private volatile Driver driver;

    public StatifyRepository(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    private Driver driver() throws SQLException {
        Driver d = driver;
        if (d != null) return d;
        try {
            d = (Driver) Class.forName(DRIVER_CLASS, true, StatifyRepository.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            throw new SQLException("MariaDB driver not found in plugin classloader: " + DRIVER_CLASS, ex);
        }
        this.driver = d;
        return d;
    }

    public void reload(Config newConfig) {
        this.config = newConfig;
    }

    public boolean enabled() {
        return config.statifyEnabled();
    }

    private String jdbcUrl() {
        return "jdbc:mariadb://" + config.statifyHost() + ":" + config.statifyPort()
                + "/" + config.statifyDatabase()
                + "?useSSL=" + config.statifyUseSsl()
                + "&connectTimeout=5000&socketTimeout=10000";
    }

    private Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", config.statifyUsername());
        props.setProperty("password", config.statifyPassword());
        Connection c = driver().connect(jdbcUrl(), props);
        if (c == null) throw new SQLException("Driver returned no connection for " + jdbcUrl());
        return c;
    }

    /**
     * Resolve player uuid + canonical name by input. Case-insensitive match on stored name.
     * Returns null when no such player is known.
     */
    public PlayerRef findPlayer(String nameInput) throws SQLException {
        String sql = "SELECT uuid, name FROM statify_players WHERE LOWER(name) = LOWER(?) LIMIT 1";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, nameInput);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new PlayerRef(rs.getString(1), rs.getString(2));
            }
        }
    }

    /**
     * Total seconds per server for a player over an optional [from..to] inclusive range (server-local dates).
     * When {@code servers} is empty, returns an empty map (nothing to show).
     * When range is null, aggregates over all recorded days.
     * Preserves the order of {@code servers}.
     */
    public Map<String, Long> playtimePerServer(String uuid, List<String> servers, LocalDate from, LocalDate to) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : servers) out.put(s, 0L);
        if (servers.isEmpty()) return out;

        StringBuilder sql = new StringBuilder(
                "SELECT server, COALESCE(SUM(seconds),0) FROM statify_daily WHERE uuid = ? AND server IN (");
        for (int i = 0; i < servers.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');
        if (from != null && to != null) sql.append(" AND day BETWEEN ? AND ?");
        sql.append(" GROUP BY server");

        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, uuid);
            for (String s : servers) ps.setString(idx++, s);
            if (from != null && to != null) {
                ps.setDate(idx++, java.sql.Date.valueOf(from));
                ps.setDate(idx, java.sql.Date.valueOf(to));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    public record PlayerRef(String uuid, String name) {}
}
