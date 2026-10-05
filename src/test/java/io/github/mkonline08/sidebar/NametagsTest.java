package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NametagsTest {
    private static final class Board {
        final Scoreboard board=mock(Scoreboard.class);
        final Map<String,Team> teams=new HashMap<>();
        Board(){
            when(board.getTeam(anyString())).thenAnswer(i->teams.get(i.getArgument(0)));
            when(board.getEntryTeam(anyString())).thenAnswer(i->teams.values().stream().filter(t->t.getEntries().contains(i.getArgument(0))).findFirst().orElse(null));
            when(board.registerNewTeam(anyString())).thenAnswer(i->create(i.getArgument(0)));
        }
        Team create(String name){
            Team team=mock(Team.class);Set<String> entries=new HashSet<>();AtomicReference<Component> prefix=new AtomicReference<>(Component.empty());
            when(team.getName()).thenReturn(name);when(team.getEntries()).thenAnswer(i->Set.copyOf(entries));when(team.prefix()).thenAnswer(i->prefix.get());
            doAnswer(i->{prefix.set(i.getArgument(0));return null;}).when(team).prefix(any(Component.class));
            doAnswer(i->{String entry=i.getArgument(0);teams.values().forEach(other->{if(other!=team)other.removeEntry(entry);});entries.add(entry);return null;}).when(team).addEntry(anyString());
            when(team.removeEntry(anyString())).thenAnswer(i->entries.remove(i.getArgument(0)));
            doAnswer(i->{teams.remove(name,team);return null;}).when(team).unregister();teams.put(name,team);return team;
        }
    }
    private Player player(String name,Board board){Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn(name);when(p.getScoreboard()).thenReturn(board.board);return p;}
    private Rank rank(String id,String name,String color){return new Rank(id,name,color,false);}
    @Test void sharedBoardCachesTeamsAndUpdatesRankWithoutRecreatingThem(){
        Board board=new Board();Player alex=player("Alex",board),bob=player("Bob",board);Nametags tags=new Nametags(Logger.getAnonymousLogger());
        tags.target(alex,rank("owner","OWNER","red"));tags.target(bob,rank("og_player","OG Player","gold"));tags.sync(alex,true);tags.sync(bob,true);
        assertEquals(2,board.teams.size());Team team=board.board.getEntryTeam("Alex");assertEquals("[OWNER] ",TemplatesTest.plain(team.prefix()));clearInvocations(team,board.board);
        for(int i=0;i<200;i++){tags.target(alex,rank("owner","OWNER","red"));tags.sync(alex,true);tags.sync(bob,true);}
        verify(team,never()).prefix(any(Component.class));verify(board.board,never()).registerNewTeam(anyString());
        tags.target(alex,rank("builder","Builder","aqua"));tags.sync(bob,true);assertEquals("[Builder] ",TemplatesTest.plain(team.prefix()));assertEquals(2,board.teams.size());
        tags.sync(alex,false);assertEquals(2,board.teams.size());tags.sync(bob,false);assertTrue(board.teams.isEmpty());
    }
    @Test void followsViewerBoardsAndRemovesDisconnectedTargets(){
        Board first=new Board(),second=new Board();Player viewer=player("Alex",first),target=player("Bob",first);Nametags tags=new Nametags(Logger.getAnonymousLogger());
        tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);assertEquals(1,first.teams.size());
        when(viewer.getScoreboard()).thenReturn(second.board);tags.sync(viewer,true);assertTrue(first.teams.isEmpty());assertEquals(1,second.teams.size());
        tags.remove(target);tags.sync(viewer,true);assertTrue(second.teams.isEmpty());
    }
    @Test void respectsExistingTeamsAndYieldsWhenAnotherPluginMovesAnEntry(){
        Board board=new Board();Player viewer=player("Viewer",board),target=player("Alex",board);Nametags tags=new Nametags(Logger.getAnonymousLogger());
        Team external=board.create("external");external.addEntry("Alex");tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);
        assertEquals(1,board.teams.size());assertSame(external,board.board.getEntryTeam("Alex"));tags.clear();verify(external,never()).unregister();
        external.removeEntry("Alex");tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);Team owned=board.board.getEntryTeam("Alex");assertNotSame(external,owned);
        external.addEntry("Alex");tags.target(target,rank("builder","Builder","aqua"));tags.sync(viewer,true);assertSame(external,board.board.getEntryTeam("Alex"));verify(owned).unregister();tags.clear();verify(external,never()).unregister();
    }
    @Test void preservesExternallyEditedPrefixesAndTeamNameCollisions(){
        Board board=new Board();Player viewer=player("Viewer",board),target=player("Alex",board);Nametags tags=new Nametags(Logger.getAnonymousLogger());
        tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);Team owned=board.board.getEntryTeam("Alex");owned.prefix(Component.text("External "));
        tags.target(target,rank("builder","Builder","aqua"));tags.sync(viewer,true);tags.clear();assertEquals("External ",TemplatesTest.plain(owned.prefix()));verify(owned,never()).unregister();
        Board collision=new Board();when(viewer.getScoreboard()).thenReturn(collision.board);String key="mk_"+target.getUniqueId().toString().replace("-","").substring(0,13);Team unrelated=collision.create(key);
        tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);assertNull(collision.board.getEntryTeam("Alex"));tags.clear();verify(unrelated,never()).unregister();
    }
    @Test void followsUsernameChangesAndClearsOnlyOwnedTeams(){
        Board board=new Board();Player viewer=player("Viewer",board),target=player("Alex",board);Nametags tags=new Nametags(Logger.getAnonymousLogger());
        Team external=board.create("external");external.addEntry("Other");tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);
        when(target.getName()).thenReturn("NewName");tags.target(target,rank("owner","OWNER","red"));tags.sync(viewer,true);assertNull(board.board.getEntryTeam("Alex"));assertNotNull(board.board.getEntryTeam("NewName"));
        tags.clear();assertEquals(Set.of("external"),board.teams.keySet());verify(external,never()).unregister();
    }
}
