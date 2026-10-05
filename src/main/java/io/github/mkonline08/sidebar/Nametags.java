package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.util.*;
import java.util.logging.Logger;

/** Main-thread, change-driven teams on each viewer's current scoreboard. */
final class Nametags {
    private record Target(UUID id,String name,Component prefix) {}
    private record Owned(Team team,String name,Component prefix) {}
    private static final class BoardState {
        int viewers;
        long revision=-1;
        final Map<UUID,Owned> teams=new HashMap<>();
        final Set<UUID> blocked=new HashSet<>();
    }
    private final Map<UUID,Target> targets=new LinkedHashMap<>();
    private final Map<UUID,Scoreboard> viewers=new HashMap<>();
    private final Map<Scoreboard,BoardState> boards=new IdentityHashMap<>();
    private final Logger logger;
    private long revision;
    Nametags(Logger logger){this.logger=logger;}
    void target(Player player,Rank rank) {
        Target next=new Target(player.getUniqueId(),player.getName(),rank.badge().append(Component.space()));
        if(!next.equals(targets.put(next.id(),next)))revision++;
    }
    void sync(Player viewer,boolean enabled) {
        Scoreboard board=enabled?viewer.getScoreboard():null;
        Scoreboard old=viewers.get(viewer.getUniqueId());
        if(old!=board) {
            detach(viewer.getUniqueId());
            if(board!=null){viewers.put(viewer.getUniqueId(),board);boards.computeIfAbsent(board,b->new BoardState()).viewers++;}
        }
        if(board==null)return;
        BoardState state=boards.get(board);
        if(state.revision==revision)return;
        for(var iterator=state.teams.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();Target wanted=targets.get(entry.getKey());Owned owned=entry.getValue();
            if(wanted==null || !wanted.name().equals(owned.name())) {release(board,owned);iterator.remove();}
        }
        for(Target wanted:targets.values()) {
            if(state.blocked.contains(wanted.id()))continue;
            Owned owned=state.teams.get(wanted.id());Team current=board.getEntryTeam(wanted.name());
            if((owned!=null&&!owns(board,owned)) || (current!=null && (owned==null||!current.equals(owned.team())))) {
                if(owned!=null){release(board,owned);state.teams.remove(wanted.id());}
                state.blocked.add(wanted.id());
                logger.warning("Overhead rank paused for "+wanted.name()+" on a scoreboard controlled by another team plugin.");
                continue;
            }
            if(owned==null) {
                String name="mk_"+wanted.id().toString().replace("-","").substring(0,13);
                if(board.getTeam(name)!=null){state.blocked.add(wanted.id());logger.warning("Overhead team name already in use for "+wanted.name()+"; leaving it unchanged.");continue;}
                Team team=board.registerNewTeam(name);
                team.color(NamedTextColor.WHITE);team.prefix(wanted.prefix());team.addEntry(wanted.name());
                state.teams.put(wanted.id(),new Owned(team,wanted.name(),wanted.prefix()));
            } else if(!owned.prefix().equals(wanted.prefix())) {
                owned.team().prefix(wanted.prefix());state.teams.put(wanted.id(),new Owned(owned.team(),wanted.name(),wanted.prefix()));
            }
        }
        state.blocked.retainAll(targets.keySet());state.revision=revision;
    }
    private boolean owns(Scoreboard board,Owned owned) {
        return owned.team().equals(board.getTeam(owned.team().getName())) && owned.prefix().equals(owned.team().prefix())
                && owned.team().getEntries().equals(Set.of(owned.name()));
    }
    private void release(Scoreboard board,Owned owned) {
        if(owned.team().equals(board.getTeam(owned.team().getName())) && owned.prefix().equals(owned.team().prefix())
                && Set.of(owned.name()).containsAll(owned.team().getEntries()))owned.team().unregister();
    }
    private void detach(UUID viewer) {
        Scoreboard board=viewers.remove(viewer);if(board==null)return;
        BoardState state=boards.get(board);
        if(--state.viewers==0){state.teams.values().forEach(team->release(board,team));boards.remove(board);}
    }
    void remove(Player player) {if(targets.remove(player.getUniqueId())!=null)revision++;detach(player.getUniqueId());}
    void clear() {boards.forEach((board,state)->state.teams.values().forEach(team->release(board,team)));boards.clear();viewers.clear();targets.clear();revision++;}
}
