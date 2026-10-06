package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public final class Commands implements CommandExecutor,TabCompleter {
    private final MKSidebarPlugin plugin;
    public Commands(MKSidebarPlugin plugin) { this.plugin=plugin; }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try {
            if(command.getName().equalsIgnoreCase("rank")) {
                exact(args,0,"/rank");
                if(!(sender instanceof Player player))throw new IllegalArgumentException("Use /mkrank info <player|UUID> from the console.");
                require(sender,"mksidebar.rank");plugin.showRank(player);return true;
            }
            if(command.getName().equalsIgnoreCase("mksb")) {
                if(args.length==1&&args[0].equalsIgnoreCase("toggle")) {
                    if(!(sender instanceof Player p)) throw new IllegalArgumentException("Only players can toggle their sidebar.");
                    require(sender,"mksidebar.toggle"); plugin.toggle(p); return true;
                }
                if(args.length==1&&args[0].equalsIgnoreCase("reload")) {
                    require(sender,"mksidebar.admin"); plugin.reloadSettings(); success(sender,"Configuration reloaded."); return true;
                }
                if(args.length==1&&args[0].equalsIgnoreCase("performance")) {require(sender,"mksidebar.admin");info(sender,plugin.performanceReport());return true;}
                info(sender,"/mksb toggle — show/hide your sidebar");
                if(sender.hasPermission("mksidebar.admin")) {info(sender,"/mksb reload — validate and reload settings");info(sender,"/mksb performance — check plugin and server tick time");}
                return true;
            }
            require(sender,"mksidebar.admin");
            String action=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "testsound" -> {exact(args,1,"/mkrank testsound");int count=plugin.testSound();success(sender,"Test sound sent to "+count+" online players.");}
                case "list" -> { exact(args,1,"/mkrank list"); for(Rank rank:plugin.settings().ranks().values()) sender.sendMessage(Component.text(rank.id()+" » ",NamedTextColor.DARK_GRAY).append(rank.label())); }
                case "create" -> {
                    if(args.length<4) throw new IllegalArgumentException("Usage: /mkrank create <id> <color> <display name...>");
                    plugin.editRank(args[1].toLowerCase(Locale.ROOT),args[2].toLowerCase(Locale.ROOT),String.join(" ",Arrays.copyOfRange(args,3,args.length)),true);
                    success(sender,"Created rank "+args[1]+".");
                }
                case "color" -> { exact(args,3,"/mkrank color <id> <color>"); plugin.editRank(args[1].toLowerCase(Locale.ROOT),args[2].toLowerCase(Locale.ROOT),null,false); success(sender,"Updated rank color."); }
                case "set" -> { exact(args,3,"/mkrank set <player|UUID> <rank>"); plugin.assign(args[1],args[2].toLowerCase(Locale.ROOT)); success(sender,"Assigned "+args[2]+" to "+args[1]+"."); }
                case "reset" -> { exact(args,2,"/mkrank reset <player|UUID>"); plugin.assign(args[1],null); success(sender,"Restored automatic rank for "+args[1]+"."); }
                case "info" -> {
                    exact(args,2,"/mkrank info <player|UUID>"); PlayerRecord r=plugin.players().resolve(args[1],false); plugin.players().checkpoint(r.uuid()); r=plugin.players().get(r.uuid());
                    info(sender,r.name()+" | "+r.uuid()); sender.sendMessage(Component.text("Rank: ",NamedTextColor.AQUA).append(plugin.settings().rank(r).label()));
                    info(sender,"Playtime: "+String.format(Locale.ROOT,"%.2f",r.playMillis()/3_600_000.0)+" hours | "+(r.manualRank()==null?"automatic":"manual override"));
                }
                default -> help(sender);
            }
        } catch(Exception ex) {
            sender.sendMessage(Component.text("MK » "+(ex.getMessage()==null?"Command failed; check the server log.":ex.getMessage()),NamedTextColor.RED));
            if(!(ex instanceof IllegalArgumentException)) plugin.getLogger().log(java.util.logging.Level.WARNING,"Command failed",ex);
        }
        return true;
    }
    private static void exact(String[] args,int count,String usage) { if(args.length!=count) throw new IllegalArgumentException("Usage: "+usage); }
    private static void require(CommandSender sender,String permission) { if(!sender.hasPermission(permission)) throw new IllegalArgumentException("You do not have permission to do that."); }
    private static void success(CommandSender sender,String message) { sender.sendMessage(Component.text("MK » "+message,NamedTextColor.GREEN)); }
    private static void info(CommandSender sender,String message) { sender.sendMessage(Component.text(message,NamedTextColor.AQUA)); }
    private static void help(CommandSender sender) {
        sender.sendMessage(Component.text("MK RANKS • Created by MK",NamedTextColor.GOLD));
        for(String line:List.of("/mkrank list","/mkrank create <id> <color> <display name...>","/mkrank color <id> <color>","/mkrank set <player|UUID> <rank>","/mkrank reset <player|UUID>","/mkrank info <player|UUID>","/mkrank testsound","/rank — your rank and OG progress")) info(sender,line);
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> options=new ArrayList<>();
        if(command.getName().equalsIgnoreCase("rank"))return List.of();
        if(command.getName().equalsIgnoreCase("mksb")) {
            if(args.length==1) { if(sender instanceof Player&&sender.hasPermission("mksidebar.toggle")) options.add("toggle"); if(sender.hasPermission("mksidebar.admin")) options.addAll(List.of("reload","performance")); }
        } else if(sender.hasPermission("mksidebar.admin")) {
            if(args.length==1) options.addAll(List.of("list","create","color","set","reset","info","testsound"));
            else if(args.length==2) {
                if(List.of("set","reset","info").contains(args[0].toLowerCase(Locale.ROOT))) plugin.players().snapshot().forEach(r -> options.add(r.name()));
                else if(args[0].equalsIgnoreCase("color")) options.addAll(plugin.settings().ranks().keySet());
            } else if(args.length==3) {
                if(args[0].equalsIgnoreCase("set")) options.addAll(plugin.settings().ranks().keySet());
                else if(args[0].equalsIgnoreCase("create")||args[0].equalsIgnoreCase("color")) options.addAll(List.of("red","gold","gray","green","aqua","blue","yellow","light_purple","white","dark_red","dark_green","dark_aqua","dark_blue","dark_purple","dark_gray","black","#ff5555"));
            }
        }
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }
}
