package io.github.mkonline08.sidebar;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.*;
import java.util.logging.Logger;

public final class Displays {
    private static final class View {
        Scoreboard originalBoard, board;
        Objective objective;
        Component originalTab, lastTab;
        Component originalFooter, lastFooter;
        boolean footerConflict;
        boolean sidebarConflict, tabConflict;
        int lineCount;
        View(Player p) { originalBoard=p.getScoreboard(); originalTab=p.playerListName(); originalFooter=p.playerListFooter(); }
    }
    private final Map<UUID,View> views = new HashMap<>();
    private final ScoreboardManager manager;
    private final Logger logger;
    public Displays(ScoreboardManager manager, Logger logger) { this.manager=manager; this.logger=logger; }
    public void update(Player player, PlayerRecord record, Settings settings, Map<String,Component> values) {
        View v = views.computeIfAbsent(player.getUniqueId(), ignored -> new View(player));
        if (!settings.sidebarEnabled() || record.sidebarHidden()) releaseSidebar(player,v);
        else if (!v.sidebarConflict) {
            if (v.board != null && !ownsSidebar(player,v)) {
                v.sidebarConflict=true; v.board=null; v.objective=null;
                logger.warning("Sidebar replaced by another plugin for "+player.getName()+". Sidebar updates paused until reconnect or toggle.");
            } else {
                if (v.board == null) {
                    v.originalBoard=player.getScoreboard(); v.board=manager.getNewScoreboard();
                    v.objective=v.board.registerNewObjective("mk_sidebar",Criteria.DUMMY,Templates.render(settings.title(),values));
                    v.objective.setDisplaySlot(DisplaySlot.SIDEBAR); v.objective.numberFormat(NumberFormat.blank());
                    player.setScoreboard(v.board);
                }
                Component title=Templates.render(settings.title(),values);
                if (!v.objective.displayName().equals(title)) v.objective.displayName(title);
                for (int i=0;i<settings.lines().size();i++) {
                    Score score=v.objective.getScore("mk_line_"+i);
                    Component line=Templates.render(settings.lines().get(i),values);
                    if (!line.equals(score.customName())) score.customName(line);
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
            } else {
                Component tab=Templates.render(settings.tabFormat(),values);
                if (!tab.equals(v.lastTab)) { player.playerListName(tab); v.lastTab=tab; }
            }
        }
        if(settings.tabEnabled() && !v.footerConflict) {
            if(v.lastFooter!=null && !Objects.equals(player.playerListFooter(),v.lastFooter)) {
                v.footerConflict=true; v.lastFooter=null;
                logger.warning("Tab footer replaced by another plugin for "+player.getName()+". Footer updates paused until reconnect.");
            } else {
                Component footer=Templates.render(settings.tabFooter(),values);
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
        if(v.lastFooter!=null && Objects.equals(p.playerListFooter(),v.lastFooter)) p.sendPlayerListFooter(v.originalFooter==null?Component.empty():v.originalFooter);
        v.lastFooter=null;
    }
    public void resetSidebarConflict(Player p) { View v=views.get(p.getUniqueId()); if(v!=null) v.sidebarConflict=false; }
    public void remove(Player p) {
        View v=views.remove(p.getUniqueId());
        if(v!=null) { releaseSidebar(p,v); releaseTab(p,v); }
    }
}
