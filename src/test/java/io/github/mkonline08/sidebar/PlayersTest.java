package io.github.mkonline08.sidebar;

import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayersTest {
    private Player player(UUID uuid,String name,int ticks) {
        Player p=mock(Player.class); when(p.getUniqueId()).thenReturn(uuid); when(p.getName()).thenReturn(name); when(p.getStatistic(Statistic.PLAY_ONE_MINUTE)).thenReturn(ticks); return p;
    }
    @Test void importsExactlyOnceAndCountsWallTimeAcrossSessions() throws Exception {
        Settings settings=SettingsTest.defaults(); UUID id=UUID.randomUUID(); AtomicLong clock=new AtomicLong();
        Players players=new Players(Map.of(),clock::get); Player p=player(id,"Alex",72_000);
        players.join(p,settings); assertEquals(3_600_000,players.get(id).playMillis());
        clock.set(2_500_000_000L); players.quit(id); assertEquals(3_602_500,players.get(id).playMillis());
        clock.set(99_000_000_000L); players.join(player(id,"NewName",999_999),settings);
        clock.addAndGet(1_000_000_000L); players.checkpointAll();
        assertEquals(3_603_500,players.get(id).playMillis()); assertEquals("NewName",players.get(id).name());
    }
    @Test void promotesAtExactBoundaryOnceAndNeverDemotes() {
        UUID id=UUID.randomUUID(); Players players=new Players(Map.of(id,new PlayerRecord(id,"Alex",86_399_999,null,false,true,false)),System::nanoTime);
        assertTrue(players.promote(id,86_400_000).isEmpty()); players.put(players.get(id).addTime(1));
        assertTrue(players.promote(id,86_400_000).isPresent()); assertEquals("og_player",players.get(id).rankId());
        assertTrue(players.promote(id,86_400_000).isEmpty()); assertTrue(players.promote(id,172_800_000).isEmpty()); assertEquals("og_player",players.get(id).rankId());
    }
    @Test void protectsManualRanksAndResetRestoresEarnedRank() {
        UUID id=UUID.randomUUID(); Players players=new Players(Map.of(id,new PlayerRecord(id,"MK",90_000_000,"owner",false,true,false)),System::nanoTime);
        assertTrue(players.promote(id,86_400_000).isEmpty()); assertEquals("owner",players.get(id).rankId());
        players.put(players.get(id).override(null)); assertTrue(players.promote(id,86_400_000).isPresent());
        players.put(players.get(id).override("builder")); assertEquals("builder",players.get(id).rankId());
        players.put(players.get(id).override(null)); assertEquals("og_player",players.get(id).rankId());
    }
    @Test void offlineUuidAssignmentStillImportsOnFirstJoin() throws Exception {
        UUID id=UUID.randomUUID(); Players players=new Players(Map.of(),System::nanoTime);
        players.put(players.resolve(id.toString(),true).override("owner"));
        players.join(player(id,"MK",1_728_000),SettingsTest.defaults());
        assertEquals("owner",players.get(id).rankId()); assertEquals(86_400_000,players.get(id).playMillis()); assertTrue(players.get(id).playtimeImported());
        assertEquals(id,players.resolve("mk",false).uuid());
    }
    @Test void rejectsUnknownNamesAndAmbiguousNames() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(); Players players=new Players(Map.of(a,new PlayerRecord(a,"Alex",0,null,false,true,false),b,new PlayerRecord(b,"alex",0,null,false,true,false)),System::nanoTime);
        assertThrows(IllegalArgumentException.class,()->players.resolve("Alex",false)); assertThrows(IllegalArgumentException.class,()->players.resolve("Unknown",true));
        assertThrows(IllegalArgumentException.class,()->players.resolve(UUID.randomUUID().toString(),false));
    }
    @Test void startFreshDoesNotImportAndSubMillisecondTimeIsRetained() throws Exception {
        var yaml=SettingsTest.yaml(); yaml.set("promotion.import-existing-playtime",false); UUID id=UUID.randomUUID(); AtomicLong clock=new AtomicLong(); Players players=new Players(Map.of(),clock::get);
        players.join(player(id,"Alex",72_000),Settings.read(yaml)); assertEquals(0,players.get(id).playMillis());
        for(int i=0;i<10;i++){clock.addAndGet(500_000);players.checkpoint(id);} assertEquals(5,players.get(id).playMillis());
    }
}
