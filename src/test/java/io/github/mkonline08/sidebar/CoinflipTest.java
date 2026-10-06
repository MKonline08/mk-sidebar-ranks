package io.github.mkonline08.sidebar;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CoinflipTest {
    @TempDir Path dir;MarketStore store;UUID host=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    @BeforeEach void open(){store=new MarketStore();store.open(dir.resolve("market.db")).join();for(UUID id:List.of(host,a,b))store.ensure(id,"Player",50000).join();}
    @AfterEach void close(){store.close();}
    void fails(CompletableFuture<?> f){assertThrows(CompletionException.class,f::join);}
    void sql(String query){try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("market.db"));Statement s=c.createStatement()){s.execute(query);}catch(SQLException e){throw new RuntimeException(e);}}
    @Test void twoJoinersCannotStartTheSameMatch(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();var first=store.joinFlip(a,id,true,0,5);var second=store.joinFlip(b,id,false,0,5);assertEquals(a,first.join().to());fails(second);assertEquals(40000,store.balance(a));assertEquals(50000,store.balance(b));assertEquals(40000,store.balance(host));
    }
    @Test void selfJoinAndMultipleMatchesAreRejected(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();fails(store.joinFlip(host,id,true,0,5));store.createFlip(a,100,Long.MAX_VALUE).join();fails(store.joinFlip(a,id,true,0,5));assertEquals("WAITING",store.flip(id).state());
    }
    @Test void startedMatchesDoNotRefundOnCancelOrDisconnect(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,true,0,5).join();fails(store.decline(host,id,"cancel"));store.disconnected(host).join();store.disconnected(a).join();assertEquals(40000,store.balance(host));assertEquals(40000,store.balance(a));store.settleFlip(id,5000).join();assertEquals(60000,store.balance(host));assertEquals(40000,store.balance(a));
    }
    @Test void shutdownCompletesSavedWinnerExactlyOnce(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,false,System.currentTimeMillis(),10).join();store.close();store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals(60000,store.balance(a));assertEquals(40000,store.balance(host));assertEquals(a,store.settleFlip(id,Long.MAX_VALUE).join().winner());assertEquals(1,store.history(a,0).join().stream().filter(h->h.kind().equals("FLIP_WIN")).count());
    }
    @Test void failedJoinRollsBackSecondStakeAndKeepsWaiting(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();sql("CREATE TRIGGER reject_join BEFORE UPDATE OF state ON flips WHEN NEW.state='RUNNING' BEGIN SELECT RAISE(ABORT,'forced failure'); END");fails(store.joinFlip(a,id,true,0,5));assertEquals(50000,store.balance(a));assertEquals("WAITING",store.flip(id).state());sql("DROP TRIGGER reject_join");store.decline(host,id,"cancel").join();assertEquals(50000,store.balance(host));
    }
    @Test void failedPayoutKeepsWinnerAndRetryPaysOnce(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,false,0,5).join();sql("CREATE TRIGGER reject_win BEFORE INSERT ON ledger WHEN NEW.kind='FLIP_WIN' BEGIN SELECT RAISE(ABORT,'forced failure'); END");fails(store.settleFlip(id,5000));assertEquals("RUNNING",store.flip(id).state());assertEquals(a,store.flip(id).winner());assertEquals(40000,store.balance(a));sql("DROP TRIGGER reject_win");store.settleFlip(id,6000).join();store.settleFlip(id,7000).join();assertEquals(60000,store.balance(a));
    }
    @Test void winnerPayoutCapacityIsProtectedWhileRunning(){
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,true,0,5).join();fails(store.adjust(host,Money.LIMIT-50000,"Console"));assertEquals(40000,store.balance(host));store.settleFlip(id,5000).join();assertEquals(60000,store.balance(host));
    }
    @Test void restartRecoversPersistedRunningMatchAfterAbruptLoss(){
        // Restore the exact committed RUNNING state in a stopped fixture, as if the process ended before payout.
        long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,true,0,5).join();store.close();
        sql("UPDATE wallets SET cents=40000");sql("UPDATE flips SET state='RUNNING' WHERE id="+id);sql("DELETE FROM ledger WHERE kind IN ('FLIP_WIN','FLIP_LOSS')");
        store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals(60000,store.balance(host));assertEquals(40000,store.balance(a));assertEquals(40000,store.balance(b));assertTrue(store.challenges().isEmpty());
    }
    @Test void v15DatabaseMigrationRefundsOldDirectedChallenges(){
        store.close();sql("DROP TABLE flips");sql("CREATE TABLE flips(id INTEGER PRIMARY KEY,sender TEXT NOT NULL,recipient TEXT NOT NULL,amount INTEGER NOT NULL,expires INTEGER NOT NULL,state TEXT NOT NULL)");
        sql("UPDATE wallets SET cents=40000 WHERE uuid='"+host+"'");sql("INSERT INTO flips VALUES(1,'"+host+"','"+a+"',10000,9999999999999,'OPEN')");
        store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals(50000,store.balance(host));assertTrue(store.challenges().isEmpty());long id=store.createFlip(host,10000,Long.MAX_VALUE).join();store.joinFlip(a,id,true,0,5).join();store.settleFlip(id,5000).join();assertEquals(60000,store.balance(host));
    }
    @Test void configMigrationPreservesCustomExpiryAndReminders()throws Exception{
        var y=SettingsTest.yaml();y.set("config-version",7);y.set("coinflip.waiting-seconds",null);y.set("coinflip.challenge-seconds",150);y.set("coinflip.sound-enabled",false);y.set("market-reminders.messages",List.of("Custom message"));assertTrue(ConfigUpgrade.apply(y));var settings=MarketSettings.read(y);assertEquals(150,settings.challengeSeconds());assertFalse(settings.flipSound());assertEquals(List.of("Custom message"),y.getStringList("market-reminders.messages"));assertNull(y.get("coinflip.challenge-seconds"));y.set("coinflip.animation-seconds",0);assertThrows(IllegalArgumentException.class,()->MarketSettings.read(y));
    }
}
