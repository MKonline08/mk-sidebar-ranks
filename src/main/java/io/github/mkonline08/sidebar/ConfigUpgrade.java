package io.github.mkonline08.sidebar;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class ConfigUpgrade {
    private ConfigUpgrade() {}
    static boolean apply(YamlConfiguration yaml) throws Exception {
        if (yaml.getInt("config-version", 1) >= 8) return false;
        YamlConfiguration defaults = resource("/config.yml"), legacy = resource("/config-v1.yml"), previous = resource("/config-v2.yml"), compact = resource("/config-v3.yml");
        // Only replace the unmodified shipped layout. Custom lines and all player/rank data stay intact.
        if (Objects.equals(yaml.get("sidebar.lines"), legacy.get("sidebar.lines")) || Objects.equals(yaml.get("sidebar.lines"),previous.get("sidebar.lines")) || Objects.equals(yaml.get("sidebar.lines"),compact.get("sidebar.lines")) || Objects.equals(yaml.get("sidebar.lines"),resource("/config-v6.yml").get("sidebar.lines"))) yaml.set("sidebar.lines",defaults.get("sidebar.lines"));
        for(String key : new String[]{"rank-change.enabled","rank-change.announcement","nametags.enabled","tab.footer","promotion.sound.enabled","promotion.sound.key","promotion.sound.volume","promotion.sound.pitch","performance.max-updates-per-tick","performance.tab-ping-update-seconds"}) {
            if(!yaml.contains(key)) yaml.set(key,defaults.get(key));
        }
        if(!yaml.contains("coinflip.waiting-seconds")&&yaml.isInt("coinflip.challenge-seconds")&&yaml.getInt("coinflip.challenge-seconds")!=60)yaml.set("coinflip.waiting-seconds",yaml.getInt("coinflip.challenge-seconds"));
        yaml.set("coinflip.challenge-seconds",null);
        if(Objects.equals(yaml.get("market-reminders.messages"),resource("/config-v7.yml").get("market-reminders.messages")))yaml.set("market-reminders.messages",defaults.get("market-reminders.messages"));
        for(String key:defaults.getKeys(true))if((key.startsWith("economy.")||key.startsWith("auction.")||key.startsWith("coinflip.")||key.startsWith("money-leaderboard.")||key.startsWith("market-reminders."))&&!defaults.isConfigurationSection(key)&&!yaml.contains(key))yaml.set(key,defaults.get(key));
        yaml.set("config-version",8);
        return true;
    }
    private static YamlConfiguration resource(String name) throws Exception {
        YamlConfiguration result=new YamlConfiguration();
        try(var reader=new InputStreamReader(Objects.requireNonNull(ConfigUpgrade.class.getResourceAsStream(name)),StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }
}
