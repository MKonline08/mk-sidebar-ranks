package io.github.mkonline08.sidebar;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.LongSupplier;

/** Main-thread state. Snapshots handed to the database worker are immutable. */
public final class Players {
    private final Map<UUID, PlayerRecord> records;
    private final Map<UUID, Long> sessions = new HashMap<>();
    private final LongSupplier nanos;
    public Players(Map<UUID, PlayerRecord> initial, LongSupplier nanos) { records = new HashMap<>(initial); this.nanos = nanos; }
    public void join(Player player, Settings settings) {
        UUID id = player.getUniqueId();
        PlayerRecord r = records.getOrDefault(id, new PlayerRecord(id, player.getName(), 0, null, false, false, false)).named(player.getName());
        if (!r.playtimeImported()) r = r.imported(settings.importExisting() ? Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE)) * 50L : 0);
        records.put(id, r); sessions.put(id, nanos.getAsLong());
    }
    public void checkpoint(UUID id) {
        Long previous = sessions.get(id);
        if (previous == null) return;
        long now = nanos.getAsLong();
        long millis = Math.max(0, (now - previous) / 1_000_000L);
        records.put(id, records.get(id).addTime(millis));
        sessions.put(id, previous + millis * 1_000_000L);
    }
    public void checkpointAll() { for (UUID id : List.copyOf(sessions.keySet())) checkpoint(id); }
    public void quit(UUID id) { checkpoint(id); sessions.remove(id); }
    public Optional<PlayerRecord> promote(UUID id, long threshold) {
        checkpoint(id);
        PlayerRecord r = records.get(id);
        if (r != null && r.eligible(threshold)) {
            r = r.promoted(); records.put(id, r); return Optional.of(r);
        }
        return Optional.empty();
    }
    public PlayerRecord get(UUID id) { return records.get(id); }
    public Collection<PlayerRecord> snapshot() { return List.copyOf(records.values()); }
    public PlayerRecord resolve(String input, boolean allowNewUuid) {
        try {
            UUID id = UUID.fromString(input);
            PlayerRecord r = records.get(id);
            if (r == null && allowNewUuid) return new PlayerRecord(id, input, 0, null, false, false, false);
            if (r == null) throw new IllegalArgumentException("No saved player has that UUID.");
            return r;
        } catch (IllegalArgumentException e) {
            if (input.length() == 36) throw new IllegalArgumentException("Unknown or invalid player UUID.");
        }
        List<PlayerRecord> matches = records.values().stream().filter(r -> r.name().equalsIgnoreCase(input)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "Unknown player. They must join first, or use their UUID." : "That name matches multiple records. Use a UUID.");
        return matches.getFirst();
    }
    public void put(PlayerRecord record) { records.put(record.uuid(), record); }
}
