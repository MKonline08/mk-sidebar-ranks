package io.github.mkonline08.sidebar;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerStore implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "MK-Ranks-Database"));
    private Connection connection;
    public CompletableFuture<Map<UUID, PlayerRecord>> open(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000");
                    s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, play_ms INTEGER NOT NULL CHECK(play_ms >= 0), manual_rank TEXT, og_earned INTEGER NOT NULL, playtime_imported INTEGER NOT NULL, sidebar_hidden INTEGER NOT NULL)");
                }
                Map<UUID, PlayerRecord> records = new HashMap<>();
                try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM players")) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        records.put(uuid, new PlayerRecord(uuid, rs.getString("name"), rs.getLong("play_ms"), rs.getString("manual_rank"), rs.getBoolean("og_earned"), rs.getBoolean("playtime_imported"), rs.getBoolean("sidebar_hidden")));
                    }
                }
                return records;
            } catch (Exception ex) { throw new CompletionException(ex); }
        }, worker);
    }
    public CompletableFuture<Void> save(Collection<PlayerRecord> records) {
        List<PlayerRecord> snapshot = List.copyOf(records);
        return CompletableFuture.runAsync(() -> {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement s = connection.prepareStatement("INSERT INTO players VALUES (?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,play_ms=excluded.play_ms,manual_rank=excluded.manual_rank,og_earned=excluded.og_earned,playtime_imported=excluded.playtime_imported,sidebar_hidden=excluded.sidebar_hidden")) {
                    for (PlayerRecord r : snapshot) {
                        s.setString(1, r.uuid().toString()); s.setString(2, r.name()); s.setLong(3, r.playMillis()); s.setString(4, r.manualRank()); s.setBoolean(5, r.ogEarned()); s.setBoolean(6, r.playtimeImported()); s.setBoolean(7, r.sidebarHidden()); s.addBatch();
                    }
                    s.executeBatch(); connection.commit();
                } catch (SQLException ex) { connection.rollback(); throw ex; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException ex) { throw new CompletionException(ex); }
        }, worker);
    }
    @Override public void close() {
        try {
            CompletableFuture.runAsync(() -> {
                try { if (connection != null) connection.close(); } catch (SQLException e) { throw new CompletionException(e); }
            }, worker).join();
        } finally { worker.shutdown(); }
    }
}
