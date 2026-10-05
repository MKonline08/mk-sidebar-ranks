package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplaysTest {
    private static MockedStatic<Bukkit> bukkit;
    @BeforeAll static void criteriaProvider() {
        bukkit=mockStatic(Bukkit.class);
        Criteria dummy=new Criteria() {
            public String getName(){return "dummy";}
            public boolean isReadOnly(){return false;}
            public RenderType getDefaultRenderType(){return RenderType.INTEGER;}
        };
        bukkit.when(()->Bukkit.getScoreboardCriteria(anyString())).thenReturn(dummy);
    }
    @AfterAll static void closeProvider(){ bukkit.close(); }
    @Test void keepsSidebarStableHandlesBlankLinesAndRestoresOwnedDisplays() throws Exception {
        Player p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn("Alex");
        Scoreboard original=mock(Scoreboard.class), board=mock(Scoreboard.class);ScoreboardManager manager=mock(ScoreboardManager.class);Objective objective=mock(Objective.class);
        AtomicReference<Scoreboard> current=new AtomicReference<>(original);when(p.getScoreboard()).thenAnswer(i->current.get());doAnswer(i->{current.set(i.getArgument(0));return null;}).when(p).setScoreboard(any());
        Component oldTab=Component.text("Alex");AtomicReference<Component> tab=new AtomicReference<>(oldTab);when(p.playerListName()).thenAnswer(i->tab.get());doAnswer(i->{tab.set(i.getArgument(0));return null;}).when(p).playerListName(any());
        when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewObjective(anyString(),any(Criteria.class),any(Component.class))).thenReturn(objective);when(board.getObjective(DisplaySlot.SIDEBAR)).thenReturn(objective);
        when(objective.getScoreboard()).thenReturn(board);when(objective.getDisplaySlot()).thenReturn(DisplaySlot.SIDEBAR);when(objective.displayName()).thenReturn(Component.empty());Map<String,Score> scores=new HashMap<>();when(objective.getScore(anyString())).thenAnswer(i->scores.computeIfAbsent(i.getArgument(0),k->mock(Score.class)));
        var yaml=SettingsTest.yaml();yaml.set("sidebar.lines",List.of("","","same","same"));Settings settings=Settings.read(yaml);
        PlayerRecord r=new PlayerRecord(id,"Alex",0,null,false,true,false);Map<String,Component> values=new HashMap<>();Templates.KEYS.forEach(k->values.put(k,Component.text("value")));
        Displays displays=new Displays(manager,Logger.getAnonymousLogger());displays.update(p,r,settings,values);displays.update(p,r,settings,values);
        verify(manager,times(1)).getNewScoreboard();assertEquals(4,scores.size());verify(scores.get("mk_line_0"),atLeastOnce()).customName(Component.empty());
        displays.remove(p);assertSame(original,current.get());assertEquals(oldTab,tab.get());
    }
    @Test void yieldsToOtherPluginsAndDoesNotRestoreTheirDisplays() throws Exception {
        Player p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn("Alex");
        Scoreboard original=mock(Scoreboard.class),board=mock(Scoreboard.class),external=mock(Scoreboard.class);Objective objective=mock(Objective.class);ScoreboardManager manager=mock(ScoreboardManager.class);
        AtomicReference<Scoreboard> current=new AtomicReference<>(original);when(p.getScoreboard()).thenAnswer(i->current.get());doAnswer(i->{current.set(i.getArgument(0));return null;}).when(p).setScoreboard(any());
        AtomicReference<Component> tab=new AtomicReference<>(Component.text("Alex"));when(p.playerListName()).thenAnswer(i->tab.get());doAnswer(i->{tab.set(i.getArgument(0));return null;}).when(p).playerListName(any());
        when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewObjective(anyString(),any(Criteria.class),any(Component.class))).thenReturn(objective);when(board.getObjective(DisplaySlot.SIDEBAR)).thenReturn(objective);when(objective.displayName()).thenReturn(Component.empty());when(objective.getScore(anyString())).thenAnswer(i->mock(Score.class));
        when(objective.getScoreboard()).thenReturn(board);when(objective.getDisplaySlot()).thenReturn(DisplaySlot.SIDEBAR);
        Map<String,Component> values=new HashMap<>();Templates.KEYS.forEach(k->values.put(k,Component.text("value")));Settings s=SettingsTest.defaults();PlayerRecord r=new PlayerRecord(id,"Alex",0,null,false,true,false);
        Displays displays=new Displays(manager,Logger.getAnonymousLogger());displays.update(p,r,s,values);current.set(external);Component externalTab=Component.text("External");tab.set(externalTab);
        displays.update(p,r,s,values);displays.update(p,r,s,values);displays.remove(p);
        assertSame(external,current.get());assertEquals(externalTab,tab.get());verify(manager,times(1)).getNewScoreboard();
    }
    @Test void yieldsWhenAnotherPluginReplacesObjectiveInsideTheSameBoard() throws Exception {
        Player p=mock(Player.class);UUID id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn("Alex");
        Scoreboard original=mock(Scoreboard.class),board=mock(Scoreboard.class);Objective objective=mock(Objective.class),external=mock(Objective.class);ScoreboardManager manager=mock(ScoreboardManager.class);
        AtomicReference<Scoreboard> current=new AtomicReference<>(original);when(p.getScoreboard()).thenAnswer(i->current.get());doAnswer(i->{current.set(i.getArgument(0));return null;}).when(p).setScoreboard(any());
        AtomicReference<Component> tab=new AtomicReference<>(Component.text("Alex"));when(p.playerListName()).thenAnswer(i->tab.get());doAnswer(i->{tab.set(i.getArgument(0));return null;}).when(p).playerListName(any());
        when(manager.getNewScoreboard()).thenReturn(board);when(board.registerNewObjective(anyString(),any(Criteria.class),any(Component.class))).thenReturn(objective);when(board.getObjective(DisplaySlot.SIDEBAR)).thenReturn(objective);when(objective.displayName()).thenReturn(Component.empty());when(objective.getScore(anyString())).thenAnswer(i->mock(Score.class));
        Map<String,Component> values=new HashMap<>();Templates.KEYS.forEach(k->values.put(k,Component.text("value")));Settings s=SettingsTest.defaults();PlayerRecord r=new PlayerRecord(id,"Alex",0,null,false,true,false);
        when(objective.getScoreboard()).thenReturn(board);when(objective.getDisplaySlot()).thenReturn(DisplaySlot.SIDEBAR);
        Displays displays=new Displays(manager,Logger.getAnonymousLogger());displays.update(p,r,s,values);clearInvocations(objective);when(board.getObjective(DisplaySlot.SIDEBAR)).thenReturn(external);when(objective.getDisplaySlot()).thenReturn(null);
        displays.update(p,r,s,values);displays.remove(p);assertSame(board,current.get());verify(objective,never()).displayName(any(Component.class));verify(objective,never()).getScore(anyString());
    }
}
