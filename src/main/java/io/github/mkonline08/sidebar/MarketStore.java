package io.github.mkonline08.sidebar;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Every economic mutation is a durable SQLite transaction on one worker, never on a server tick. */
final class MarketStore implements AutoCloseable {
    record Account(UUID uuid,String name,long cents) {}
    record Listing(long id,UUID seller,byte[] item,String material,long price,long expires,String state) {}
    record Mail(long id,UUID owner,byte[] item,String detail,String state) {}
    record Challenge(long id,UUID from,UUID to,long amount,long expires,String state,UUID winner,long finishAt) {}
    record InventoryOp(long id,UUID owner,String kind,long ref,byte[] before,byte[] after,String state) {}
    record History(long at,long delta,String kind,String detail) {}
    record Board(UUID world,double x,double y,double z) {}
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"MK-Market-Database"));
    private final Map<UUID,Account> accounts=new ConcurrentHashMap<>();
    private final Map<Long,Listing> listings=new ConcurrentHashMap<>();
    private final Map<Long,Mail> mail=new ConcurrentHashMap<>();
    private final Map<Long,Challenge> challenges=new ConcurrentHashMap<>();
    private final Map<Long,InventoryOp> operations=new ConcurrentHashMap<>();
    private volatile Map<UUID,List<Mail>> mailByOwner=Map.of();
    private volatile Map<UUID,List<InventoryOp>> opsByOwner=Map.of();
    private Connection db;
    private volatile Board board;
    private volatile List<Account> top=List.of();
    private final Set<UUID> changed=new HashSet<>();
    private boolean marketChanged,topDirty=true;

    CompletableFuture<Void> open(Path file){return task(()->{
        Class.forName("org.sqlite.JDBC");db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(Statement s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS wallets(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,cents INTEGER NOT NULL CHECK(cents>=0 AND cents<=9000000000000))");
            s.execute("CREATE INDEX IF NOT EXISTS wallet_rich ON wallets(cents DESC,name)");
            s.execute("CREATE TABLE IF NOT EXISTS ledger(id INTEGER PRIMARY KEY,uuid TEXT NOT NULL,delta INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL,at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS ledger_owner ON ledger(uuid,id DESC)");
            s.execute("CREATE TABLE IF NOT EXISTS listings(id INTEGER PRIMARY KEY,seller TEXT NOT NULL,item BLOB NOT NULL,material TEXT NOT NULL,price INTEGER NOT NULL,expires INTEGER NOT NULL,state TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS listings_owner ON listings(seller,state)");
            s.execute("CREATE TABLE IF NOT EXISTS mail(id INTEGER PRIMARY KEY,owner TEXT NOT NULL,item BLOB NOT NULL,detail TEXT NOT NULL,state TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS mail_owner ON mail(owner,state)");
            s.execute("CREATE TABLE IF NOT EXISTS inventory_ops(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,kind TEXT NOT NULL,ref INTEGER NOT NULL,before BLOB NOT NULL,after BLOB NOT NULL,state TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS flips(id INTEGER PRIMARY KEY,sender TEXT NOT NULL,recipient TEXT NOT NULL,amount INTEGER NOT NULL,expires INTEGER NOT NULL,state TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS board(id INTEGER PRIMARY KEY CHECK(id=1),world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL)");
        }
        // Preserve v1.5 wallets/listings and upgrade existing challenge records in place.
        Set<String> flipColumns=new HashSet<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info(flips)")){while(r.next())flipColumns.add(r.getString("name"));}
        if(!flipColumns.contains("winner"))sql("ALTER TABLE flips ADD COLUMN winner TEXT");
        if(!flipColumns.contains("finish_at"))sql("ALTER TABLE flips ADD COLUMN finish_at INTEGER NOT NULL DEFAULT 0");
        sql("CREATE INDEX IF NOT EXISTS flips_active ON flips(state,sender,recipient,winner)");
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM wallets")){while(r.next()){Account a=account(r);accounts.put(a.uuid(),a);}}
        reloadMarket();
        transaction(()->{for(Challenge c:List.copyOf(challenges.values())){if(c.state().equals("RUNNING"))settle(c);else refund(c,"Server restart");}return null;});
        readBoard();refreshTopInternal();return null;
    });}
    long balance(UUID id){Account a=accounts.get(id);return a==null?0:a.cents();}
    boolean hasAccount(UUID id){return accounts.containsKey(id);}
    String name(UUID id){Account a=accounts.get(id);return a==null?id.toString():a.name();}
    List<Listing> listings(){return List.copyOf(listings.values());}
    Listing listing(long id){return listings.get(id);}
    List<Mail> mailbox(UUID owner){return mailByOwner.getOrDefault(owner,List.of());}
    List<Challenge> challenges(){return List.copyOf(challenges.values());}
    Challenge flip(long id){return challenges.get(id);}
    List<InventoryOp> operations(UUID owner){return opsByOwner.getOrDefault(owner,List.of());}
    List<InventoryOp> reviews(){return operations.values().stream().filter(o->o.state().equals("REVIEW")).toList();}
    List<Account> top(){return top;}
    Board board(){return board;}
    CompletableFuture<Void> ensure(UUID id,String name,long starter){return task(()->transaction(()->{
        if(!accounts.containsKey(id)){
            sql("INSERT INTO wallets VALUES(?,?,?)",id,name,starter);log(id,starter,"STARTER","One-time starter balance");changed.add(id);topDirty=true;
        }else{sql("UPDATE wallets SET name=? WHERE uuid=?",name,id);changed.add(id);topDirty=true;}
        return null;
    }));}
    CompletableFuture<Void> seed(Collection<PlayerRecord> records,long starter){return task(()->transaction(()->{
        for(PlayerRecord r:records)if(!accounts.containsKey(r.uuid())){
            sql("INSERT OR IGNORE INTO wallets VALUES(?,?,?)",r.uuid(),r.name(),starter);log(r.uuid(),starter,"STARTER","One-time starter balance");changed.add(r.uuid());topDirty=true;
        }return null;
    }));}
    CompletableFuture<Long> adjust(UUID id,long amount,String actor){return task(()->transaction(()->{
        change(id,amount,"ADMIN",actor);return actualBalance(id);
    }));}
    CompletableFuture<Void> pay(UUID from,UUID to,long amount){return task(()->transaction(()->{
        if(from.equals(to))throw new IllegalArgumentException("You cannot pay yourself.");positive(amount);
        change(from,-amount,"PAY","To "+name(to));change(to,amount,"PAY","From "+name(from));return null;
    }));}
    CompletableFuture<Long> prepareListing(UUID owner,byte[] item,String material,long price,long expires,int limit,int total,byte[] before,byte[] after){return task(()->transaction(()->{
        positive(price);
        if(scalar("SELECT COUNT(*) FROM listings WHERE seller=? AND state IN ('ACTIVE','PENDING','REVIEW')",owner)>=limit)throw new IllegalArgumentException("You have reached your active listing limit.");
        if(scalar("SELECT COUNT(*) FROM listings WHERE state IN ('ACTIVE','PENDING','REVIEW')")>=total)throw new IllegalArgumentException("The market is full. Try again later.");
        long ref=insert("INSERT INTO listings(seller,item,material,price,expires,state) VALUES(?,?,?,?,?,'PENDING')",owner,item,material,price,expires);
        long op=insert("INSERT INTO inventory_ops(owner,kind,ref,before,after,state) VALUES(?,'LIST',?,?,?,'PENDING')",owner,ref,before,after);
        marketChanged=true;return op;
    }));}
    CompletableFuture<Long> prepareClaim(UUID owner,long ref,byte[] before,byte[] after){return task(()->transaction(()->{
        if(sql("UPDATE mail SET state='PENDING' WHERE id=? AND owner=? AND state='READY'",ref,owner)!=1)throw new IllegalArgumentException("That item is already being claimed or is unavailable.");
        long op=insert("INSERT INTO inventory_ops(owner,kind,ref,before,after,state) VALUES(?,'CLAIM',?,?,?,'PENDING')",owner,ref,before,after);
        marketChanged=true;return op;
    }));}
    CompletableFuture<Void> finishInventory(long id,boolean delivered){return task(()->transaction(()->{
        InventoryOp op=operations.get(id);if(op==null)throw new IllegalArgumentException("Inventory operation is no longer pending.");
        if(op.kind().equals("LIST")){
            sql("UPDATE listings SET state=? WHERE id=?",delivered?"ACTIVE":"CANCELLED",op.ref());
            if(delivered)log(op.owner(),0,"LIST","Listing #"+op.ref()+" created");
        }else sql("UPDATE mail SET state=? WHERE id=?",delivered?"DELIVERED":"READY",op.ref());
        sql("DELETE FROM inventory_ops WHERE id=?",id);marketChanged=true;return null;
    }));}
    CompletableFuture<Void> review(long id){return task(()->transaction(()->{
        InventoryOp o=operations.get(id);if(o==null)return null;
        sql("UPDATE inventory_ops SET state='REVIEW' WHERE id=?",id);
        sql("UPDATE "+(o.kind().equals("LIST")?"listings":"mail")+" SET state='REVIEW' WHERE id=?",o.ref());marketChanged=true;return null;
    }));}
    CompletableFuture<Void> resolveReview(long id,boolean inventoryApplied,String actor){return task(()->transaction(()->{
        InventoryOp o=operations.get(id);if(o==null||!o.state().equals("REVIEW"))throw new IllegalArgumentException("Unknown review operation.");
        // For a listing, applied means the seller's inventory was removed and the listing may be activated.
        sql("UPDATE "+(o.kind().equals("LIST")?"listings":"mail")+" SET state=? WHERE id=?",o.kind().equals("LIST")?(inventoryApplied?"ACTIVE":"CANCELLED"):(inventoryApplied?"DELIVERED":"READY"),o.ref());
        sql("DELETE FROM inventory_ops WHERE id=?",id);log(o.owner(),0,"RECOVERY",actor+" resolved #"+id+" applied="+inventoryApplied);marketChanged=true;return null;
    }));}
    CompletableFuture<UUID> buy(UUID buyer,long ref,int fee,long now){return task(()->transaction(()->{
        if(fee<0||fee>25)throw new IllegalArgumentException("Invalid sale fee.");
        Listing l=listings.get(ref);
        if(l==null||!l.state().equals("ACTIVE")||l.expires()<=now)throw new IllegalArgumentException("That listing has sold or expired.");
        if(l.seller().equals(buyer))throw new IllegalArgumentException("You cannot buy your own listing.");
        long proceeds=l.price()-l.price()*fee/100;
        change(buyer,-l.price(),"BUY","Listing #"+ref+" from "+name(l.seller()));
        change(l.seller(),proceeds,"SALE","Listing #"+ref+" to "+name(buyer)+(fee>0?" (fee "+fee+"%)":""));
        sql("UPDATE listings SET state='SOLD' WHERE id=? AND state='ACTIVE'",ref);
        insert("INSERT INTO mail(owner,item,detail,state) VALUES(?,?,?,'READY')",buyer,l.item(),"Purchased listing #"+ref);
        marketChanged=true;return l.seller();
    }));}
    CompletableFuture<Void> cancel(UUID owner,long ref){return task(()->transaction(()->{
        Listing l=listings.get(ref);if(l==null||!l.state().equals("ACTIVE")||!l.seller().equals(owner))throw new IllegalArgumentException("That active listing is not yours.");
        returnListing(l,"Cancelled listing #"+ref);return null;
    }));}
    CompletableFuture<Long> createFlip(UUID from,long amount,long expires){return task(()->transaction(()->{
        positive(amount);if(amount>Money.LIMIT/2)throw new IllegalArgumentException("Wager is too large.");
        if(inFlip(from))throw new IllegalArgumentException("You already have a waiting or active coin flip.");
        change(from,-amount,"FLIP_HOLD","Public coin flip wager");
        long id=insert("INSERT INTO flips(sender,recipient,amount,expires,state) VALUES(?,'',?,?,'WAITING')",from,amount,expires);marketChanged=true;return id;
    }));}
    CompletableFuture<Challenge> joinFlip(UUID recipient,long id,boolean senderWins,long now,int animationSeconds){return task(()->transaction(()->{
        Challenge c=challenges.get(id);
        if(c==null||!c.state().equals("WAITING")||c.expires()<=now)throw new IllegalArgumentException("That flip has already started or expired.");
        if(c.from().equals(recipient))throw new IllegalArgumentException("You cannot join your own coin flip.");
        if(inFlip(recipient))throw new IllegalArgumentException("You already have a waiting or active coin flip.");
        if(actualBalance(recipient)<c.amount())throw new IllegalArgumentException("Not enough money to match this wager.");
        // Both possible winners must have room for the pot; no outcome-dependent retry at the balance cap.
        if(actualBalance(c.from())>Money.LIMIT-c.amount()*2||actualBalance(recipient)>Money.LIMIT-c.amount())throw new IllegalArgumentException("This pot would exceed a player's wallet limit.");
        change(recipient,-c.amount(),"FLIP_STAKE","Against "+name(c.from()));
        UUID winner=senderWins?c.from():recipient;long finish=now+animationSeconds*1000L;
        sql("UPDATE flips SET recipient=?,winner=?,finish_at=?,state='RUNNING' WHERE id=?",recipient,winner,finish,id);marketChanged=true;
        return new Challenge(id,c.from(),recipient,c.amount(),c.expires(),"RUNNING",winner,finish);
    }));}
    CompletableFuture<Challenge> settleFlip(long id,long now){return task(()->transaction(()->{
        Challenge c=readFlip(id);if(c==null)throw new IllegalArgumentException("Unknown match.");
        if(c.state().equals("DONE"))return c;
        if(!c.state().equals("RUNNING")||c.finishAt()>now)throw new IllegalArgumentException("The match has not finished yet.");
        settle(c);return new Challenge(c.id(),c.from(),c.to(),c.amount(),c.expires(),"DONE",c.winner(),c.finishAt());
    }));}
    private boolean inFlip(UUID id)throws SQLException{return scalar("SELECT COUNT(*) FROM flips WHERE state IN ('OPEN','WAITING','RUNNING') AND (sender=? OR recipient=?)",id,id)>0;}
    CompletableFuture<Void> decline(UUID owner,long id,String reason){return task(()->transaction(()->{
        Challenge c=challenges.get(id);if(c==null||!owner.equals(c.from())||!c.state().equals("WAITING"))throw new IllegalArgumentException("Only your waiting flip can be cancelled. Started matches finish normally.");
        refund(c,reason);return null;
    }));}
    CompletableFuture<Void> disconnected(UUID owner){return task(()->transaction(()->{
        for(Challenge c:List.copyOf(challenges.values()))if(!c.state().equals("RUNNING")&&(c.from().equals(owner)||owner.equals(c.to())))refund(c,"Player disconnected");return null;
    }));}
    CompletableFuture<Void> expire(long now){return task(()->transaction(()->{
        for(Listing l:List.copyOf(listings.values()))if(l.state().equals("ACTIVE")&&l.expires()<=now)returnListing(l,"Expired listing #"+l.id());
        for(Challenge c:List.copyOf(challenges.values())){if(c.state().equals("RUNNING")){if(c.finishAt()<=now)settle(c);}else if(c.expires()<=now)refund(c,"Waiting flip expired");}return null;
    }));}
    CompletableFuture<List<History>> history(UUID id,int page){return task(()->{
        List<History> rows=new ArrayList<>();
        try(PreparedStatement p=prepare("SELECT at,delta,kind,detail FROM ledger WHERE uuid=? ORDER BY id DESC LIMIT 10 OFFSET ?",id,Math.max(0,page)*10);ResultSet r=p.executeQuery()){
            while(r.next())rows.add(new History(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4)));
        }return rows;
    });}
    CompletableFuture<List<Account>> refreshTop(){return task(()->{refreshTopInternal();return top;});}
    CompletableFuture<Void> setBoard(Board b){return task(()->transaction(()->{
        sql("DELETE FROM board");if(b!=null)sql("INSERT INTO board VALUES(1,?,?,?,?)",b.world(),b.x(),b.y(),b.z());return null;
    })).thenRun(()->board=b);}
    private void returnListing(Listing l,String detail)throws SQLException{
        sql("UPDATE listings SET state='RETURNED' WHERE id=?",l.id());insert("INSERT INTO mail(owner,item,detail,state) VALUES(?,?,?,'READY')",l.seller(),l.item(),detail);log(l.seller(),0,"RETURN",detail);marketChanged=true;
    }
    private void refund(Challenge c,String reason)throws SQLException{
        change(c.from(),c.amount(),"FLIP_REFUND",reason);sql("UPDATE flips SET state='REFUNDED' WHERE id=?",c.id());marketChanged=true;
    }
    private void settle(Challenge c)throws SQLException{
        if(c.winner()==null||c.to()==null)throw new SQLException("Running flip has no saved winner/participant");
        sql("UPDATE flips SET state='DONE' WHERE id=? AND state='RUNNING'",c.id());
        change(c.winner(),c.amount()*2,"FLIP_WIN","Coin flip #"+c.id());
        log(c.winner().equals(c.from())?c.to():c.from(),0,"FLIP_LOSS","Coin flip #"+c.id());marketChanged=true;
    }
    private void positive(long amount){if(amount<=0||amount>Money.LIMIT)throw new IllegalArgumentException("Invalid amount.");}
    private long actualBalance(UUID owner)throws SQLException{
        try(PreparedStatement p=prepare("SELECT cents FROM wallets WHERE uuid=?",owner);ResultSet r=p.executeQuery()){
            if(!r.next())throw new IllegalArgumentException("That player has no wallet yet.");return r.getLong(1);
        }
    }
    private void change(UUID owner,long delta,String kind,String detail)throws SQLException{
        long old=actualBalance(owner),next=Math.addExact(old,delta);
        if(next<0)throw new IllegalArgumentException("Not enough money.");
        long held=scalar("SELECT COALESCE(SUM(CASE WHEN state='RUNNING' THEN amount*2 ELSE amount END),0) FROM flips WHERE (sender=? AND state IN ('OPEN','WAITING')) OR (winner=? AND state='RUNNING')",owner,owner);
        if(next>Money.LIMIT||next>Money.LIMIT-held && !kind.equals("FLIP_REFUND") && !kind.equals("FLIP_WIN"))throw new IllegalArgumentException("That wallet would exceed the supported limit.");
        sql("UPDATE wallets SET cents=? WHERE uuid=?",next,owner);log(owner,delta,kind,detail);changed.add(owner);topDirty=true;
    }
    private void log(UUID owner,long delta,String kind,String detail)throws SQLException{sql("INSERT INTO ledger(uuid,delta,kind,detail,at) VALUES(?,?,?,?,?)",owner,delta,kind,detail,System.currentTimeMillis());}
    private PreparedStatement prepare(String query,Object...args)throws SQLException{
        PreparedStatement p=db.prepareStatement(query);
        for(int i=0;i<args.length;i++){Object a=args[i];if(a instanceof UUID)a=a.toString();if(a instanceof byte[] bytes)p.setBytes(i+1,bytes);else p.setObject(i+1,a);}return p;
    }
    private int sql(String query,Object...args)throws SQLException{try(PreparedStatement p=prepare(query,args)){return p.executeUpdate();}}
    private long insert(String query,Object...args)throws SQLException{sql(query,args);return scalar("SELECT last_insert_rowid()");}
    private long scalar(String query,Object...args)throws SQLException{try(PreparedStatement p=prepare(query,args);ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}
    private Account account(ResultSet r)throws SQLException{return new Account(UUID.fromString(r.getString("uuid")),r.getString("name"),r.getLong("cents"));}
    private Challenge flipRow(ResultSet r)throws SQLException{String to=r.getString("recipient"),winner=r.getString("winner");return new Challenge(r.getLong("id"),UUID.fromString(r.getString("sender")),to==null||to.isBlank()?null:UUID.fromString(to),r.getLong("amount"),r.getLong("expires"),r.getString("state"),winner==null?null:UUID.fromString(winner),r.getLong("finish_at"));}
    private Challenge readFlip(long id)throws SQLException{try(PreparedStatement p=prepare("SELECT * FROM flips WHERE id=?",id);ResultSet r=p.executeQuery()){return r.next()?flipRow(r):null;}}
    private void readBoard()throws SQLException{try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM board")){board=r.next()?new Board(UUID.fromString(r.getString("world")),r.getDouble("x"),r.getDouble("y"),r.getDouble("z")):null;}}
    private record MarketData(Map<Long,Listing> listings,Map<Long,Mail> mail,Map<Long,Challenge> challenges,Map<Long,InventoryOp> ops,Map<UUID,List<Mail>> byOwner,Map<UUID,List<InventoryOp>> pending){}
    private MarketData readMarket()throws SQLException{
        Map<Long,Listing> ls=new HashMap<>();Map<Long,Mail> ms=new HashMap<>();Map<Long,Challenge> cs=new HashMap<>();Map<Long,InventoryOp> os=new HashMap<>();
        Map<UUID,List<Mail>> byOwner=new HashMap<>();Map<UUID,List<InventoryOp>> pending=new HashMap<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM listings WHERE state IN ('ACTIVE','PENDING','REVIEW')")){while(r.next()){long id=r.getLong("id");ls.put(id,new Listing(id,UUID.fromString(r.getString("seller")),r.getBytes("item"),r.getString("material"),r.getLong("price"),r.getLong("expires"),r.getString("state")));}}
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM mail WHERE state IN ('READY','PENDING','REVIEW') ORDER BY id DESC")){while(r.next()){long id=r.getLong("id");Mail m=new Mail(id,UUID.fromString(r.getString("owner")),r.getBytes("item"),r.getString("detail"),r.getString("state"));ms.put(id,m);byOwner.computeIfAbsent(m.owner(),k->new ArrayList<>()).add(m);}}
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM flips WHERE state IN ('OPEN','WAITING','RUNNING')")){while(r.next()){Challenge c=flipRow(r);cs.put(c.id(),c);}}
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM inventory_ops")){while(r.next()){long id=r.getLong("id");InventoryOp o=new InventoryOp(id,UUID.fromString(r.getString("owner")),r.getString("kind"),r.getLong("ref"),r.getBytes("before"),r.getBytes("after"),r.getString("state"));os.put(id,o);pending.computeIfAbsent(o.owner(),k->new ArrayList<>()).add(o);}}
        byOwner.replaceAll((id,values)->List.copyOf(values));pending.replaceAll((id,values)->List.copyOf(values));return new MarketData(ls,ms,cs,os,Map.copyOf(byOwner),Map.copyOf(pending));
    }
    private void publish(MarketData d){listings.clear();listings.putAll(d.listings());mail.clear();mail.putAll(d.mail());challenges.clear();challenges.putAll(d.challenges());operations.clear();operations.putAll(d.ops());mailByOwner=d.byOwner();opsByOwner=d.pending();}
    private void reloadMarket()throws SQLException{publish(readMarket());}
    private void refreshTopInternal()throws SQLException{
        if(!topDirty)return;
        List<Account> rows=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM wallets ORDER BY cents DESC,name COLLATE NOCASE,uuid LIMIT 10")){while(r.next())rows.add(account(r));}
        top=List.copyOf(rows);topDirty=false;
    }
    private <T>T transaction(SqlWork<T> work)throws Exception{
        changed.clear();marketChanged=false;db.setAutoCommit(false);T value;Map<UUID,Account> updates=new HashMap<>();MarketData data;
        try{
            value=work.run();
            for(UUID id:changed)try(PreparedStatement p=prepare("SELECT * FROM wallets WHERE uuid=?",id);ResultSet r=p.executeQuery()){if(r.next())updates.put(id,account(r));}
            data=marketChanged?readMarket():null;db.commit();
        }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
        // No fallible reads after commit: callers must never mistake a saved transaction for a failed one.
        accounts.putAll(updates);if(data!=null)publish(data);return value;
    }
    private <T>CompletableFuture<T> task(SqlWork<T> work){return CompletableFuture.supplyAsync(()->{try{return work.run();}catch(Exception e){throw new CompletionException(e);}},worker);}
    @FunctionalInterface private interface SqlWork<T>{T run()throws Exception;}
    @Override public void close(){try{task(()->{if(db!=null){transaction(()->{for(Challenge c:List.copyOf(challenges.values())){if(c.state().equals("RUNNING"))settle(c);else refund(c,"Server shutdown");}return null;});db.close();}return null;}).join();}finally{worker.shutdown();}}
}
