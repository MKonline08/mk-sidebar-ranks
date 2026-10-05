package io.github.mkonline08.sidebar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {
    @TempDir Path temp;
    @Test void persistsRanksPromotionMarkersNamesAndToggleAcrossRestart() {
        UUID id=UUID.randomUUID();Path path=temp.resolve("players.db");PlayerRecord r=new PlayerRecord(id,"MK",90_000_000,"owner",true,true,true);
        try(PlayerStore store=new PlayerStore()) { assertTrue(store.open(path).join().isEmpty());store.save(List.of(r)).join(); }
        try(PlayerStore store=new PlayerStore()) { assertEquals(r,store.open(path).join().get(id));store.save(List.of(r.named("NewName").override(null))).join(); }
        try(PlayerStore store=new PlayerStore()) {var loaded=store.open(path).join().get(id);assertEquals("NewName",loaded.name());assertEquals("og_player",loaded.rankId());assertTrue(loaded.sidebarHidden());}
    }
    @Test void queuedWritesPreserveLatestStateAndSnapshotsAreImmutable() {
        UUID id=UUID.randomUUID();PlayerRecord r=new PlayerRecord(id,"Alex",0,null,false,false,false);Path path=temp.resolve("players.db");
        try(PlayerStore store=new PlayerStore()) {
            store.open(path).join();ArrayList<PlayerRecord> records=new ArrayList<>(List.of(r)); var first=store.save(records);records.clear();
            var second=store.save(List.of(r.override("owner").addTime(123)));first.join();second.join();
        }
        try(PlayerStore store=new PlayerStore()) {PlayerRecord loaded=store.open(path).join().get(id);assertEquals("owner",loaded.manualRank());assertEquals(123,loaded.playMillis());assertFalse(loaded.playtimeImported());}
    }
}
