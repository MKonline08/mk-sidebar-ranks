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
    private static final Set<String> LOCATION_KEYS=Set.of("player_x","player_y","player_z","player_direction","player_biome");
    private Values() {}
    public static Map<String, Component> of(Player p, PlayerRecord record, Settings settings, Server server) {
        return of(p,record,settings,server,Templates.KEYS,new HashMap<>());
    }
    public static Map<String, Component> of(Player p, PlayerRecord record, Settings settings, Server server,Set<String> requested,Map<String,Component> shared) {
        Map<String, Component> v = new HashMap<>();
        Location loc=null;
        Integer ping=null;
        for(String key:requested) {
            if(key.startsWith("server_")) {
                v.put(key,shared.computeIfAbsent(key,ignored->Component.text(switch(key) {
                    case "server_name" -> settings.serverName();
                    case "server_online" -> Integer.toString(server.getOnlinePlayers().size());
                    case "server_max_players" -> Integer.toString(server.getMaxPlayers());
                    case "server_tps" -> decimal(Math.max(0,Math.min(20,server.getTPS()[0])));
                    case "server_uptime" -> duration(ManagementFactory.getRuntimeMXBean().getUptime());
                    default -> throw new IllegalArgumentException("Unknown server placeholder: "+key);
                })));
                continue;
            }
            if(LOCATION_KEYS.contains(key) && loc==null) loc=p.getLocation();
            if((key.equals("player_ping")||key.equals("player_ping_color"))&&ping==null) ping=Math.max(0,p.getPing());
            Component value=switch(key) {
                case "player_name" -> Component.text(p.getName());
                case "player_displayname" -> p.displayName();
                case "player_health" -> Component.text(decimal(p.getHealth()));
                case "player_max_health" -> Component.text(decimal(attribute(p,Attribute.MAX_HEALTH,20)));
                case "player_food" -> Component.text(p.getFoodLevel());
                case "player_level" -> Component.text(p.getLevel());
                case "player_exp" -> Component.text(Math.round(p.getExp()*100));
                case "player_ping" -> Component.text(ping);
                case "player_ping_color" -> Component.text(ping,pingColor(ping));
                case "player_world" -> Component.text(p.getWorld().getName());
                case "player_gamemode" -> Component.text(p.getGameMode().name());
                case "player_x" -> Component.text(loc.getBlockX());
                case "player_y" -> Component.text(loc.getBlockY());
                case "player_z" -> Component.text(loc.getBlockZ());
                case "player_deaths" -> Component.text(p.getStatistic(Statistic.DEATHS));
                case "player_kills" -> Component.text(p.getStatistic(Statistic.PLAYER_KILLS));
                case "player_blocks_walked" -> Component.text(decimal(p.getStatistic(Statistic.WALK_ONE_CM)/100.0));
                case "player_playtime_hours" -> Component.text(String.format(Locale.ROOT,"%.2f",record.playMillis()/3_600_000.0));
                case "player_armor" -> Component.text(decimal(attribute(p,Attribute.ARMOR,0)));
                case "player_direction" -> Component.text(direction(loc.getYaw()));
                case "player_item_in_hand" -> Component.text(p.getInventory().getItemInMainHand().getType().name());
                case "player_biome" -> Component.text(p.getWorld().getBiome(loc).getKey().getKey());
                case "player_rank" -> settings.rank(record).label();
                case "player_rank_badge" -> settings.rank(record).badge();
                case "player_balance" -> Component.text("—");
                default -> throw new IllegalArgumentException("Unknown player placeholder: "+key);
            };
            v.put(key,value);
        }
        return v;
    }
    private static double attribute(Player p,Attribute attribute,double fallback) {
        var instance=p.getAttribute(attribute);return instance==null?fallback:instance.getValue();
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
