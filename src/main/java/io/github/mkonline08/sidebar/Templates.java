package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import java.util.*;
import java.util.regex.*;

public final class Templates {
    public static final Set<String> KEYS = Set.of("player_name", "player_displayname", "player_health", "player_max_health", "player_food", "player_level", "player_exp", "player_ping", "player_ping_color", "player_world", "player_gamemode", "player_x", "player_y", "player_z", "player_deaths", "player_kills", "player_blocks_walked", "player_playtime_hours", "player_armor", "player_direction", "player_item_in_hand", "player_biome", "player_rank", "player_rank_badge", "server_name", "server_online", "server_max_players", "server_tps", "server_uptime");
    private static final Pattern TOKEN = Pattern.compile("%([a-zA-Z0-9_]+)%");
    private static final MiniMessage MM = MiniMessage.builder().strict(true).build();
    private Templates() {}
    public record Compiled(String source, Set<String> keys, Component constant) {
        public Component render(Map<String, Component> values) {
            if (constant != null) return constant;
            TagResolver.Builder builder = TagResolver.builder();
            for (String key : keys) builder.resolver(Placeholder.component("mk_"+key, Objects.requireNonNull(values.get(key),"Missing placeholder: "+key)));
            return MM.deserialize(source,builder.build());
        }
        public boolean sameInputs(Map<String, Component> previous, Map<String, Component> current, Set<String> ignored) {
            if (previous == null) return false;
            for (String key : keys) if (!ignored.contains(key) && !Objects.equals(previous.get(key),current.get(key))) return false;
            return true;
        }
    }
    public static Compiled compile(String template) {
        Set<String> keys = new HashSet<>();
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            if (!KEYS.contains(matcher.group(1))) throw new IllegalArgumentException("Unknown placeholder: " + matcher.group());
            keys.add(matcher.group(1));
        }
        String source = TOKEN.matcher(template).replaceAll(match -> "<mk_"+match.group(1)+">");
        Compiled compiled = new Compiled(source,Set.copyOf(keys),keys.isEmpty()?MM.deserialize(source):null);
        Map<String,Component> dummy = new HashMap<>(); keys.forEach(k -> dummy.put(k,Component.text("value")));
        compiled.render(dummy); // Validate formatting at configuration load time.
        return compiled;
    }
    public static void validate(String template) { compile(template); }
    public static Component render(String template, Map<String, Component> values) {
        return compile(template).render(values);
    }
}
