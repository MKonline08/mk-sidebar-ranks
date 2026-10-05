package io.github.mkonline08.sidebar;

import java.util.UUID;

public record PlayerRecord(UUID uuid, String name, long playMillis, String manualRank, boolean ogEarned, boolean playtimeImported, boolean sidebarHidden) {
    public PlayerRecord {
        if (uuid == null || name == null || playMillis < 0) throw new IllegalArgumentException("Invalid player record.");
    }
    public PlayerRecord named(String value) { return new PlayerRecord(uuid, value, playMillis, manualRank, ogEarned, playtimeImported, sidebarHidden); }
    public PlayerRecord addTime(long millis) { return new PlayerRecord(uuid, name, Math.addExact(playMillis, Math.max(0, millis)), manualRank, ogEarned, playtimeImported, sidebarHidden); }
    public PlayerRecord override(String rank) { return new PlayerRecord(uuid, name, playMillis, rank, ogEarned, playtimeImported, sidebarHidden); }
    public PlayerRecord promoted() { return new PlayerRecord(uuid, name, playMillis, manualRank, true, playtimeImported, sidebarHidden); }
    public PlayerRecord hidden(boolean value) { return new PlayerRecord(uuid, name, playMillis, manualRank, ogEarned, playtimeImported, value); }
    public PlayerRecord imported(long millis) { return new PlayerRecord(uuid, name, Math.max(0, millis), manualRank, ogEarned, true, sidebarHidden); }
    public boolean eligible(long threshold) { return manualRank == null && !ogEarned && playMillis >= threshold; }
    public String rankId() { return manualRank != null ? manualRank : ogEarned ? "og_player" : "new_player"; }
}
