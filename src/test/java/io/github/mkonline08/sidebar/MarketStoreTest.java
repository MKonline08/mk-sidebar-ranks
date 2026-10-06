package io.github.mkonline08.sidebar;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketStoreTest {
    @TempDir Path dir;
    final UUID alex=UUID.randomUUID(),sam=UUID.randomUUID(),lee=UUID.randomUUID();
    MarketStore store;
    @BeforeEach void start(){store=new MarketStore();store.open(dir.resolve("market.db")).join();for(UUID id:List.of(alex,sam,lee))store.ensure(id,id.equals(alex)?"Alex":id.equals(sam)?"Sam":"Lee",50000).join();}
    @AfterEach void close(){store.close();}
    void fails(CompletableFuture<?> action){assertThrows(CompletionException.class,action::join);}
    long listing(long price,long expiry){long op=store.prepareListing(alex,new byte[]{1,2},"DIAMOND",price,expiry,5,100,new byte[]{3},new byte[]{4}).join();store.finishInventory(op,true).join();return store.listings().stream().filter(l->l.state().equals("ACTIVE")).mapToLong(MarketStore.Listing::id).max().orElseThrow();}
    @Test void integerCurrencyValidation(){
        assertEquals(12525,Money.parse("125.25"));assertEquals("$1,000.05",Money.format(100005));
        for(String bad:List.of("-1","0","NaN","Infinity","1e3","1.001",".5"," 5","90000000001"))assertThrows(IllegalArgumentException.class,()->Money.parse(bad));
    }
    @Test void starterIsGrantedOnceAcrossRenamesAndRestarts(){
        store.adjust(alex,100,"Console").join();store.ensure(alex,"Renamed",90000).join();assertEquals(50100,store.balance(alex));
        store.close();store=new MarketStore();store.open(dir.resolve("market.db")).join();store.ensure(alex,"Alex",50000).join();assertEquals(50100,store.balance(alex));
        assertEquals(1,store.history(alex,0).join().stream().filter(h->h.kind().equals("STARTER")).count());
    }
    @Test void payIsAtomicAndConcurrentSpendingCannotOverdraw(){
        var first=store.pay(alex,sam,40000);var second=store.pay(alex,lee,40000);first.join();fails(second);
        assertEquals(10000,store.balance(alex));assertEquals(90000,store.balance(sam));assertEquals(50000,store.balance(lee));fails(store.pay(alex,alex,1));
    }
    @Test void adminCannotMakeNegativeOrOverflowBalances(){
        fails(store.adjust(alex,-50001,"Console"));assertEquals(50000,store.balance(alex));
        fails(store.adjust(alex,Money.LIMIT,"Console"));assertEquals(50000,store.balance(alex));
        store.adjust(alex,-50000,"Console").join();assertEquals(0,store.balance(alex));
    }
    @Test void purchaseOnlyOnceAndTransfersExactStackAndFunds(){
        long ref=listing(12500,System.currentTimeMillis()+60000);
        var a=store.buy(sam,ref,0,System.currentTimeMillis());var b=store.buy(lee,ref,0,System.currentTimeMillis());assertEquals(alex,a.join());fails(b);
        assertEquals(62500,store.balance(alex));assertEquals(37500,store.balance(sam));assertEquals(50000,store.balance(lee));
        assertEquals(1,store.mailbox(sam).size());assertArrayEquals(new byte[]{1,2},store.mailbox(sam).getFirst().item());assertTrue(store.listings().isEmpty());
    }
    @Test void failedAndSelfPurchaseLeaveListingAndWalletsUntouched(){
        long ref=listing(60000,System.currentTimeMillis()+60000);fails(store.buy(sam,ref,0,System.currentTimeMillis()));fails(store.buy(alex,ref,0,System.currentTimeMillis()));
        assertEquals(50000,store.balance(sam));assertEquals(50000,store.balance(alex));assertEquals("ACTIVE",store.listing(ref).state());assertTrue(store.mailbox(sam).isEmpty());
    }
    @Test void feesApplyToSellerAndHistoryPersistsForOfflineWallets(){
        long ref=listing(10000,System.currentTimeMillis()+60000);store.buy(sam,ref,5,System.currentTimeMillis()).join();assertEquals(59500,store.balance(alex));
        assertTrue(store.history(alex,0).join().stream().anyMatch(h->h.kind().equals("SALE")&&h.delta()==9500));
    }
    @Test void cancelAndExpireReturnItemsOnce(){
        long ref=listing(100,System.currentTimeMillis()+60000);store.cancel(alex,ref).join();fails(store.cancel(alex,ref));assertEquals(1,store.mailbox(alex).size());
        listing(100,1);store.expire(2).join();store.expire(3).join();assertEquals(2,store.mailbox(alex).size());assertTrue(store.listings().isEmpty());
    }
    @Test void inventoryJournalAndReviewSurviveRestartWithoutReplay(){
        long op=store.prepareListing(alex,new byte[]{7},"STONE",100,Long.MAX_VALUE,5,100,new byte[]{1},new byte[]{2}).join();
        store.close();store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals("PENDING",store.operations(alex).getFirst().state());
        store.review(op).join();assertEquals(1,store.reviews().size());store.resolveReview(op,true,"Console").join();assertEquals("ACTIVE",store.listings().getFirst().state());assertTrue(store.operations(alex).isEmpty());
        fails(store.finishInventory(op,true));
    }
    @Test void claimCanBeCancelledThenCompletedExactlyOnce(){
        long ref=listing(100,System.currentTimeMillis()+60000);store.cancel(alex,ref).join();long mail=store.mailbox(alex).getFirst().id();
        long op=store.prepareClaim(alex,mail,new byte[]{1},new byte[]{2}).join();fails(store.prepareClaim(alex,mail,new byte[]{1},new byte[]{2}));store.finishInventory(op,false).join();assertEquals("READY",store.mailbox(alex).getFirst().state());
        op=store.prepareClaim(alex,mail,new byte[]{1},new byte[]{2}).join();store.finishInventory(op,true).join();assertTrue(store.mailbox(alex).isEmpty());fails(store.prepareClaim(alex,mail,new byte[]{1},new byte[]{2}));
    }
    @Test void playerListingLimitsIncludePendingAndReviewedItems(){
        long op=store.prepareListing(alex,new byte[]{7},"STONE",100,Long.MAX_VALUE,1,100,new byte[]{1},new byte[]{2}).join();
        fails(store.prepareListing(alex,new byte[]{7},"STONE",100,Long.MAX_VALUE,1,100,new byte[]{1},new byte[]{2}));store.finishInventory(op,false).join();assertTrue(store.listings().isEmpty());
    }
    @Test void flipsReserveAndPayOnceWithoutCreatingCurrency(){
        long flip=store.challenge(alex,sam,10000,Long.MAX_VALUE).join();assertEquals(40000,store.balance(alex));fails(store.challenge(alex,lee,100,Long.MAX_VALUE));
        assertEquals(sam,store.accept(sam,flip,false,0).join());fails(store.accept(sam,flip,true,0));
        assertEquals(40000,store.balance(alex));assertEquals(60000,store.balance(sam));assertEquals(150000,store.balance(alex)+store.balance(sam)+store.balance(lee));
    }
    @Test void unaffordableAcceptanceDoesNotLoseReservedFunds(){
        long id=store.challenge(alex,sam,10000,Long.MAX_VALUE).join();store.adjust(sam,-45000,"Console").join();fails(store.accept(sam,id,true,0));assertEquals(40000,store.balance(alex));
        store.decline(sam,id,"declined").join();assertEquals(50000,store.balance(alex));
    }
    @Test void cancelledExpiredDisconnectedAndRestartedFlipsRefund(){
        long first=store.challenge(alex,sam,10000,Long.MAX_VALUE).join();store.decline(sam,first,"decline").join();assertEquals(50000,store.balance(alex));
        store.challenge(alex,sam,10000,1).join();store.expire(2).join();assertEquals(50000,store.balance(alex));
        store.challenge(alex,sam,10000,Long.MAX_VALUE).join();store.disconnected(sam).join();assertEquals(50000,store.balance(alex));
        store.challenge(alex,sam,10000,Long.MAX_VALUE).join();store.close();store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals(50000,store.balance(alex));assertTrue(store.challenges().isEmpty());
    }
    @Test void failingDiskTransactionRollsBackBothPaymentSidesAndListing(){
        long ref=listing(10000,Long.MAX_VALUE);
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("market.db"));Statement s=c.createStatement()){
            s.execute("CREATE TRIGGER fail_sale BEFORE INSERT ON ledger WHEN NEW.kind='SALE' BEGIN SELECT RAISE(ABORT,'forced storage failure'); END");
        }catch(SQLException e){throw new RuntimeException(e);}
        fails(store.buy(sam,ref,0,0));assertEquals(50000,store.balance(alex));assertEquals(50000,store.balance(sam));assertEquals("ACTIVE",store.listing(ref).state());assertTrue(store.mailbox(sam).isEmpty());
    }
    @Test void leaderboardAndLocationPersist(){
        store.adjust(sam,500,"Console").join();assertEquals(sam,store.refreshTop().join().getFirst().uuid());var location=new MarketStore.Board(UUID.randomUUID(),1,65,-12);store.setBoard(location).join();
        store.close();store=new MarketStore();store.open(dir.resolve("market.db")).join();assertEquals(location,store.board());store.setBoard(null).join();assertNull(store.board());
    }
    @Test void migrationPreservesCustomSettingsAndValidatesEconomy()throws Exception{
        var y=SettingsTest.yaml();y.set("config-version",6);y.set("auction.sale-fee-percent",2);y.set("sidebar.lines",List.of("Custom %player_balance%"));assertTrue(ConfigUpgrade.apply(y));
        var config=MarketSettings.read(y);assertEquals(2,config.feePercent());assertEquals(50000,config.starter());assertEquals(List.of("Custom %player_balance%"),y.getStringList("sidebar.lines"));assertFalse(ConfigUpgrade.apply(y));
        y.set("coinflip.minimum",500);y.set("coinflip.maximum",100);assertThrows(IllegalArgumentException.class,()->MarketSettings.read(y));
    }
}
