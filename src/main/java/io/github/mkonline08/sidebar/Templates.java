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
    public static void validate(String template) {
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) if (!KEYS.contains(matcher.group(1))) throw new IllegalArgumentException("Unknown placeholder: " + matcher.group());
        Map<String, Component> dummy = new HashMap<>(); KEYS.forEach(k -> dummy.put(k, Component.text("value")));
        render(template, dummy);
    }
    public static Component render(String template, Map<String, Component> values) {
        Matcher matcher = TOKEN.matcher(template);
        String transformed = matcher.replaceAll(match -> "<mk_" + match.group(1) + ">");
        TagResolver.Builder builder = TagResolver.builder();
        values.forEach((key, value) -> builder.resolver(Placeholder.component("mk_" + key, value)));
        return MM.deserialize(transformed, builder.build());
    }
}
