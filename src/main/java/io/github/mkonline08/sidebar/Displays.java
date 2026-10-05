package io.github.mkonline08.sidebar;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.function.LongSupplier;

public final class Displays {
    private static final Set<String> PING_KEYS=Set.of("player_ping","player_ping_color");
    private record Cached(Component component,Map<String,Component> inputs) {}
    private static final class View {
        Scoreboard originalBoard, board;
        Objective objective;
        Component originalTab, lastTab;
        Component originalFooter, lastFooter;
        boolean footerConflict;
        boolean sidebarConflict, tabConflict;
        int lineCount;
        Settings settings;
        Map<String,Cached> cache=new HashMap<>();
        Map<String,Component> lastTabInputs;
        long nextTabRefresh;
        View(Player p) { originalBoard=p.getScoreboard(); originalTab=p.playerListName(); originalFooter=p.playerListFooter(); }
    }
    private final Map<UUID,View> views = new HashMap<>();
    private final ScoreboardManager manager;
    private final Logger logger;
    private final LongSupplier nanos;
    private long renders,tabWrites,sidebarWrites;
    public Displays(ScoreboardManager manager, Logger logger) { this(manager,logger,System::nanoTime); }
    Displays(ScoreboardManager manager,Logger logger,LongSupplier nanos) { this.manager=manager; this.logger=logger; this.nanos=nanos; }
    long renders(){return renders;} long tabWrites(){return tabWrites;} long sidebarWrites(){return sidebarWrites;}
    private Component render(View v,String source,Settings settings,Map<String,Component> inputs) {
        var compiled=settings.template(source);Cached cached=v.cache.get(source);
        if(cached!=null&&compiled.sameInputs(cached.inputs(),inputs,Set.of())) return cached.component();
        Component result=compiled.render(inputs);v.cache.put(source,new Cached(result,inputs));renders++;return result;
    }
    public void update(Player player, PlayerRecord record, Settings settings, Map<String,Component> values) {
        View v = views.computeIfAbsent(player.getUniqueId(), ignored -> new View(player));
        if(v.settings!=settings) {v.settings=settings;v.cache.clear();v.lastTabInputs=null;}
        values=Map.copyOf(values);
        if (!settings.sidebarEnabled() || record.sidebarHidden()) releaseSidebar(player,v);
        else if (!v.sidebarConflict) {
            if (v.board != null && !ownsSidebar(player,v)) {
                v.sidebarConflict=true; v.board=null; v.objective=null;
                logger.warning("Sidebar replaced by another plugin for "+player.getName()+". Sidebar updates paused until reconnect or toggle.");
            } else {
                if (v.board == null) {
                    v.originalBoard=player.getScoreboard(); v.board=manager.getNewScoreboard();
                    v.objective=v.board.registerNewObjective("mk_sidebar",Criteria.DUMMY,render(v,settings.title(),settings,values));
                    v.objective.setDisplaySlot(DisplaySlot.SIDEBAR); v.objective.numberFormat(NumberFormat.blank());
                    player.setScoreboard(v.board);
                }
                Component title=render(v,settings.title(),settings,values);
                if (!v.objective.displayName().equals(title)) v.objective.displayName(title);
                for (int i=0;i<settings.lines().size();i++) {
                    Score score=v.objective.getScore("mk_line_"+i);
                    Component line=render(v,settings.lines().get(i),settings,values);
                    if (!line.equals(score.customName())) {score.customName(line);sidebarWrites++;}
                    int order=settings.lines().size()-i;
                    if (score.getScore()!=order) score.setScore(order);
                }
                for (int i=settings.lines().size();i<v.lineCount;i++) v.board.resetScores("mk_line_"+i);
                v.lineCount=settings.lines().size();
            }
        }
        if (!settings.tabEnabled()) releaseTab(player,v);
        else if (!v.tabConflict) {
            if (v.lastTab!=null && !player.playerListName().equals(v.lastTab)) {
                v.tabConflict=true; v.lastTab=null;
                logger.warning("Tab formatting replaced by another plugin for "+player.getName()+". Tab updates paused until reconnect.");
            } else if(nanos.getAsLong()>=v.nextTabRefresh || !settings.template(settings.tabFormat()).sameInputs(v.lastTabInputs,values,PING_KEYS)) {
                Component tab=render(v,settings.tabFormat(),settings,values);
                if (!tab.equals(v.lastTab)) { player.playerListName(tab); v.lastTab=tab;tabWrites++; }
                v.lastTabInputs=values;v.nextTabRefresh=nanos.getAsLong()+settings.tabPingSeconds()*1_000_000_000L;
            }
        }
        if(settings.tabEnabled() && !v.footerConflict) {
            if(v.lastFooter!=null && !Objects.equals(player.playerListFooter(),v.lastFooter)) {
                v.footerConflict=true; v.lastFooter=null;
                logger.warning("Tab footer replaced by another plugin for "+player.getName()+". Footer updates paused until reconnect.");
            } else {
                Component footer=render(v,settings.tabFooter(),settings,values);
                if(!footer.equals(v.lastFooter)) { player.sendPlayerListFooter(footer); v.lastFooter=footer; }
            }
        }
    }
    private void releaseSidebar(Player p,View v) {
        if (v.board!=null && ownsSidebar(p,v)) p.setScoreboard(v.originalBoard);
        v.board=null; v.objective=null; v.lineCount=0;
    }
    private boolean ownsSidebar(Player p,View v) {
        // Paper can return a fresh Objective wrapper on each lookup; Java reference identity is not ownership.
        return p.getScoreboard()==v.board && v.objective.getScoreboard()!=null && v.objective.getDisplaySlot()==DisplaySlot.SIDEBAR;
    }
    private void releaseTab(Player p,View v) {
        if (v.lastTab!=null && p.playerListName().equals(v.lastTab)) p.playerListName(v.originalTab);
        v.lastTab=null;
        v.lastTabInputs=null;
        if(v.lastFooter!=null && Objects.equals(p.playerListFooter(),v.lastFooter)) p.sendPlayerListFooter(v.originalFooter==null?Component.empty():v.originalFooter);
        v.lastFooter=null;
    }
    public void resetSidebarConflict(Player p) { View v=views.get(p.getUniqueId()); if(v!=null) v.sidebarConflict=false; }
    public void remove(Player p) {
        View v=views.remove(p.getUniqueId());
        if(v!=null) { releaseSidebar(p,v); releaseTab(p,v); }
    }
}
