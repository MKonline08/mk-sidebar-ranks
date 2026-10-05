package io.github.mkonline08.sidebar;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;

public record Settings(String serverName, boolean sidebarEnabled, String title, List<String> lines,
                       boolean tabEnabled, String tabFormat, String tabFooter, long promotionMillis, boolean importExisting,
                       String announcement, PromotionSound promotionSound, Map<String, Rank> ranks) {
    public static Settings read(YamlConfiguration yaml) {
        String name = required(yaml, "server-name");
        if (name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("server-name must contain 1–64 printable characters.");
        String title = required(yaml, "sidebar.title");
        Object rawLines = yaml.get("sidebar.lines");
        if (!(rawLines instanceof List<?> raw) || raw.isEmpty() || raw.size() > 15 || raw.stream().anyMatch(v -> !(v instanceof String))) throw new IllegalArgumentException("sidebar.lines must contain 1–15 text lines.");
        List<String> lines = raw.stream().map(String.class::cast).toList();
        String tab = required(yaml, "tab.format");
        if (yaml.contains("tab.footer") && !yaml.isString("tab.footer")) throw new IllegalArgumentException("tab.footer must be text.");
        String footer = yaml.getString("tab.footer", "<gray>Credits:</gray> <gold><bold>MK/108e</bold></gold>");
        double hours = yaml.getDouble("promotion.hours", Double.NaN);
        if (!Double.isFinite(hours) || hours <= 0 || hours > 1_000_000) throw new IllegalArgumentException("promotion.hours must be a positive number up to 1000000.");
        long millis = Math.max(1, Math.round(hours * 3_600_000));
        String announcement = required(yaml, "promotion.announcement");
        Map<String, Rank> ranks = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        if (section == null) throw new IllegalArgumentException("ranks section is missing.");
        for (String id : section.getKeys(false)) {
            ranks.put(id, new Rank(id, required(yaml, "ranks." + id + ".name"), required(yaml, "ranks." + id + ".color"), bool(yaml, "ranks." + id + ".bold")));
        }
        for (String id : List.of("new_player", "og_player", "owner")) if (!ranks.containsKey(id)) throw new IllegalArgumentException("Required rank missing: " + id);
        Templates.validate(title); lines.forEach(Templates::validate); Templates.validate(tab); Templates.validate(footer); Templates.validate(announcement);
        return new Settings(name, bool(yaml, "sidebar.enabled"), title, lines, bool(yaml, "tab.enabled"), tab, footer,
                millis, bool(yaml, "promotion.import-existing-playtime"), announcement, PromotionSound.read(yaml), Collections.unmodifiableMap(ranks));
    }
    private static String required(YamlConfiguration yaml, String key) {
        if (!yaml.isString(key) || yaml.getString(key).isBlank()) throw new IllegalArgumentException("Missing text setting: " + key);
        return yaml.getString(key);
    }
    private static boolean bool(YamlConfiguration yaml, String key) {
        if (!yaml.isBoolean(key)) throw new IllegalArgumentException("Missing boolean setting: " + key);
        return yaml.getBoolean(key);
    }
    public Rank rank(PlayerRecord record) {
        Rank rank = ranks.get(record.rankId());
        if (rank == null) throw new IllegalStateException("Assigned rank missing from configuration: " + record.rankId());
        return rank;
    }
}
