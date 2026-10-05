package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PerformanceTest {
    @Test void readsOnlyRequestedStatsAndSharesServerStatsAcrossPlayers() throws Exception {
        Player p=mock(Player.class);when(p.getName()).thenReturn("Alex");Server server=mock(Server.class);when(server.getOnlinePlayers()).thenAnswer(i->List.of(p));when(server.getTPS()).thenReturn(new double[]{20,20,20});
        PlayerRecord record=new PlayerRecord(UUID.randomUUID(),"Alex",0,null,false,true,false);Map<String,Component> shared=new HashMap<>();
        Set<String> keys=Set.of("player_name","player_rank","server_online","server_tps");
        var first=Values.of(p,record,SettingsTest.defaults(),server,keys,shared);var second=Values.of(p,record,SettingsTest.defaults(),server,keys,shared);
        assertEquals(keys,first.keySet());assertSame(first.get("server_tps"),second.get("server_tps"));
        verify(server,times(1)).getTPS();verify(server,times(1)).getOnlinePlayers();verify(p,times(2)).getName();verifyNoMoreInteractions(p);
    }
    @Test void unchangedTabAndFooterStayCachedPingIsThrottledAndRankChangesAreImmediate() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("sidebar.enabled",false);Settings settings=Settings.read(yaml);
        Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn("Alex");
        AtomicReference<Component> tab=new AtomicReference<>(Component.text("Alex")),footer=new AtomicReference<>(null);
        when(p.playerListName()).thenAnswer(i->tab.get());doAnswer(i->{tab.set(i.getArgument(0));return null;}).when(p).playerListName(any(Component.class));
        when(p.playerListFooter()).thenAnswer(i->footer.get());doAnswer(i->{footer.set(i.getArgument(0));return null;}).when(p).sendPlayerListFooter(any(Component.class));
        AtomicLong clock=new AtomicLong();Displays displays=new Displays(null,Logger.getAnonymousLogger(),clock::get);
        var record=new PlayerRecord(p.getUniqueId(),"Alex",0,null,false,true,false);
        Map<String,Component> values=new HashMap<>();Templates.KEYS.forEach(k->values.put(k,Component.text("value")));
        values.put("player_name",Component.text("Alex"));values.put("player_rank_badge",Component.text("[New Player]"));values.put("player_ping_color",Component.text("50"));
        displays.update(p,record,settings,values);long renders=displays.renders();
        for(int i=0;i<200;i++)displays.update(p,record,settings,values);
        assertEquals(renders,displays.renders());assertEquals(1,displays.tabWrites());verify(p,times(1)).sendPlayerListFooter(any(Component.class));
        clock.set(1_000_000_000L);values.put("player_ping_color",Component.text("75"));displays.update(p,record,settings,values);assertTrue(TemplatesTest.plain(tab.get()).contains("50"));
        clock.set(2_000_000_000L);values.put("player_rank_badge",Component.text("[OWNER]"));displays.update(p,record.override("owner"),settings,values);assertTrue(TemplatesTest.plain(tab.get()).contains("[OWNER]"));assertTrue(TemplatesTest.plain(tab.get()).contains("75"));
        clock.set(3_000_000_000L);values.put("player_ping_color",Component.text("90"));displays.update(p,record,settings,values);assertTrue(TemplatesTest.plain(tab.get()).contains("75"));
        clock.set(7_000_000_000L);displays.update(p,record,settings,values);assertTrue(TemplatesTest.plain(tab.get()).contains("90"));assertEquals(3,displays.tabWrites());
    }
    @Test void dirtySavesIgnoreOfflineHistoryAndCannotLoseChangesMadeDuringSaving() {
        Map<UUID,PlayerRecord> initial=new HashMap<>();for(int i=0;i<10000;i++){UUID id=UUID.randomUUID();initial.put(id,new PlayerRecord(id,"Player"+i,0,null,false,true,false));}
        Players players=new Players(initial,System::nanoTime);assertTrue(players.dirtySnapshot().isEmpty());
        var record=initial.values().iterator().next();players.put(record.override("owner"));var snapshot=players.dirtySnapshot();assertEquals(1,snapshot.size());
        players.put(players.get(record.uuid()).addTime(100));players.saved(snapshot);assertEquals(1,players.dirtySnapshot().size());
        var latest=players.dirtySnapshot();players.saved(latest);assertTrue(players.dirtySnapshot().isEmpty());
    }
    @Test void queueStaysBoundedDeduplicatesAndRemovesDisconnectedPlayers() {
        UpdateQueue queue=new UpdateQueue();List<UUID> online=new ArrayList<>();for(int i=0;i<100;i++)online.add(UUID.randomUUID());
        for(int i=0;i<100;i++)queue.refresh(online);assertEquals(100,queue.size());assertEquals(online.getFirst(),queue.poll());
        queue.refresh(online);assertEquals(100,queue.size());assertEquals(online.get(1),queue.poll());
        queue.refresh(List.of(online.getFirst()));assertEquals(1,queue.size());assertEquals(online.getFirst(),queue.poll());assertNull(queue.poll());
    }
    @Test void rejectsUnsafePerformanceConfiguration() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("performance.max-updates-per-tick",0);assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("performance.max-updates-per-tick",32);yaml.set("performance.tab-ping-update-seconds",0.1);assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("performance.tab-ping-update-seconds",5);Settings settings=Settings.read(yaml);assertEquals(5,settings.tabPingSeconds());assertEquals(32,settings.maxUpdatesPerTick());
    }
}
