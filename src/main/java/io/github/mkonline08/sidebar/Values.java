package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import java.lang.management.ManagementFactory;
import java.util.*;

public final class Values {
    private Values() {}
    public static Map<String, Component> of(Player p, PlayerRecord record, Settings settings, Server server) {
        Map<String, Component> v = new HashMap<>();
        Location loc = p.getLocation();
        text(v,"player_name",p.getName()); v.put("player_displayname",p.displayName());
        text(v,"player_health",decimal(p.getHealth()));
        text(v,"player_max_health",decimal(p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue()));
        text(v,"player_food",p.getFoodLevel()); text(v,"player_level",p.getLevel()); text(v,"player_exp",Math.round(p.getExp()*100));
        int ping = Math.max(0,p.getPing()); text(v,"player_ping",ping); v.put("player_ping_color",Component.text(ping,pingColor(ping)));
        text(v,"player_world",p.getWorld().getName()); text(v,"player_gamemode",p.getGameMode().name());
        text(v,"player_x",loc.getBlockX()); text(v,"player_y",loc.getBlockY()); text(v,"player_z",loc.getBlockZ());
        text(v,"player_deaths",p.getStatistic(Statistic.DEATHS)); text(v,"player_kills",p.getStatistic(Statistic.PLAYER_KILLS));
        text(v,"player_blocks_walked",decimal(p.getStatistic(Statistic.WALK_ONE_CM)/100.0));
        text(v,"player_playtime_hours",String.format(Locale.ROOT,"%.2f",record.playMillis()/3_600_000.0));
        text(v,"player_armor",decimal(p.getAttribute(Attribute.ARMOR) == null ? 0 : p.getAttribute(Attribute.ARMOR).getValue()));
        text(v,"player_direction",direction(loc.getYaw())); text(v,"player_item_in_hand",p.getInventory().getItemInMainHand().getType().name());
        text(v,"player_biome",p.getWorld().getBiome(loc).getKey().getKey());
        v.put("player_rank",settings.rank(record).label()); v.put("player_rank_badge",settings.rank(record).badge());
        text(v,"server_name",settings.serverName()); text(v,"server_online",server.getOnlinePlayers().size()); text(v,"server_max_players",server.getMaxPlayers());
        double tps = server.getTPS()[0]; text(v,"server_tps",String.format(Locale.ROOT,"%.1f",Math.max(0,Math.min(20,tps))));
        text(v,"server_uptime",duration(ManagementFactory.getRuntimeMXBean().getUptime()));
        return v;
    }
    public static Map<String, Component> announcement(PlayerRecord record, Settings settings) {
        return Map.of("player_name",Component.text(record.name()),"player_rank",settings.rank(record).label(),"player_rank_badge",settings.rank(record).badge(),"server_name",Component.text(settings.serverName()));
    }
    private static void text(Map<String, Component> map,String key,Object value) { map.put(key,Component.text(String.valueOf(value))); }
    public static NamedTextColor pingColor(int ping) { return ping < 100 ? NamedTextColor.GREEN : ping < 200 ? NamedTextColor.YELLOW : NamedTextColor.RED; }
    public static String decimal(double value) { return String.format(Locale.ROOT,"%.1f",value); }
    public static String direction(float yaw) {
        String[] points = {"S","SW","W","NW","N","NE","E","SE"};
        return points[Math.floorMod((int)Math.floor((yaw+22.5)/45),8)];
    }
    public static String duration(long millis) {
        long seconds = Math.max(0,millis/1000), days = seconds/86400, hours = seconds/3600%24, minutes = seconds/60%60;
        return (days > 0 ? days+"d " : "") + (hours > 0 || days > 0 ? hours+"h " : "") + minutes+"m " + seconds%60+"s";
    }
}
