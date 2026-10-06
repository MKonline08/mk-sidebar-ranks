package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Main-thread menus/inventory handoffs. SQL and all balance mutations are delegated to MarketStore. */
final class Market implements Listener,CommandExecutor,TabCompleter,AutoCloseable {
    private final MKSidebarPlugin plugin;
    private final MarketStore store=new MarketStore();
    private final MarketBoard board;
    private MarketSettings settings;
    private final Set<UUID> busy=new HashSet<>(),recovering=new HashSet<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private final ArrayDeque<Runnable> inventoryTransfers=new ArrayDeque<>();
    private final SecureRandom random=new SecureRandom();
    private boolean stopping;
    private int seconds,reminder;
    private long inventorySaveMax;
    private int inventorySaves;
    private final PerformanceSamples timings=new PerformanceSamples();
    Market(MKSidebarPlugin plugin,MarketSettings settings){this.plugin=plugin;this.settings=settings;board=new MarketBoard(plugin);}
    void start(Collection<PlayerRecord> initial){
        store.open(plugin.getDataFolder().toPath().resolve("market.db")).join();store.seed(initial,settings.starter()).join();
        for(String name:List.of("auction","money","pay","baltop","coinflip","mkmarket")){
            PluginCommand c=Objects.requireNonNull(plugin.getCommand(name));c.setExecutor(this);c.setTabCompleter(this);
        }
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        if(!settings.leaderboard()||store.board()==null)board.removeLoaded();
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::maintenance,20,20);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::inventoryTick,1,1);
        if(!store.reviews().isEmpty())plugin.getLogger().warning("Market has "+store.reviews().size()+" inventory transfers awaiting /mkmarket recovery review.");
    }
    Component balance(UUID id){return Component.text(store.hasAccount(id)?Money.format(store.balance(id)):"…",NamedTextColor.YELLOW);}
    void joined(Player p){
        if(!store.operations(p.getUniqueId()).isEmpty())busy.add(p.getUniqueId());
        complete(store.ensure(p.getUniqueId(),p.getName(),settings.starter()),p,v->{recover(p);},()->busy.remove(p.getUniqueId()));
    }
    void quit(Player p){complete(store.disconnected(p.getUniqueId()),null,v->{},null);cooldown.remove(p.getUniqueId());}
    void reload(MarketSettings candidate){boolean turnedOff=settings.leaderboard()&&!candidate.leaderboard();settings=candidate;seconds=0;if(turnedOff)board.removeLoaded();syncBoard();}
    private void maintenance(){
        long start=System.nanoTime();int now=++seconds;
        complete(store.expire(System.currentTimeMillis()),null,v->{},null);
        if(now%settings.leaderboardSeconds()==0||now==1){
            complete(store.refreshTop(),null,v->{syncBoard();},null);
        }
        if(settings.reminders()&&now%settings.reminderSeconds()==0&&!plugin.getServer().getOnlinePlayers().isEmpty()){
            Component message=settings.messages().get(reminder++%settings.messages().size());for(Player p:plugin.getServer().getOnlinePlayers())p.sendMessage(message);
        }
        timings.add(System.nanoTime()-start);
    }
    private void syncBoard(){board.sync(store.board(),store.top(),settings.leaderboard());}
    private void inventoryTick(){
        // One saved inventory handoff per tick; a burst of listings/claims cannot stack all disk writes in one tick.
        Runnable transfer=inventoryTransfers.poll();if(transfer==null)return;
        long began=System.nanoTime();try{transfer.run();}finally{timings.add(System.nanoTime()-began);}
    }
    @EventHandler public void chunk(ChunkLoadEvent e){
        var saved=store.board();board.chunkLoaded(e.getChunk(),saved,settings.leaderboard());
        if(saved!=null&&saved.world().equals(e.getWorld().getUID())&&e.getChunk().getX()==((int)Math.floor(saved.x())>>4)&&e.getChunk().getZ()==((int)Math.floor(saved.z())>>4))syncBoard();
    }
    private void main(Runnable action){if(stopping)return;try{plugin.getServer().getScheduler().runTask(plugin,()->{if(!stopping)action.run();});}catch(org.bukkit.plugin.IllegalPluginAccessException ignored){}}
    private <T>void complete(CompletableFuture<T> future,CommandSender sender,Consumer<T> success,Runnable failure){
        future.whenComplete((result,error)->main(()->{
            long started=System.nanoTime();
            try{
            if(error==null){try{success.accept(result);}catch(Exception e){report(sender,e);if(failure!=null)failure.run();}}
            else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();report(sender,cause);if(failure!=null)failure.run();}
            }finally{timings.add(System.nanoTime()-started);}
        }));
    }
    private void report(CommandSender sender,Throwable error){
        String message=error instanceof IllegalArgumentException?error.getMessage():"The operation could not complete. Your transaction records were kept; contact an administrator.";
        if(sender!=null)sender.sendMessage(Component.text("MK » "+message,NamedTextColor.RED));
        if(!(error instanceof IllegalArgumentException))plugin.getLogger().log(Level.SEVERE,"Market transaction error",error);
    }
    private static void say(CommandSender s,String text){s.sendMessage(Component.text("MK » "+text,NamedTextColor.AQUA));}
    private Player player(CommandSender sender){if(sender instanceof Player p)return p;throw new IllegalArgumentException("This command is for players.");}
    private UUID target(String input){return plugin.players().resolve(input,false).uuid();}
    private void available(Player p){if(!store.hasAccount(p.getUniqueId()))throw new IllegalArgumentException("Your wallet is loading. Try again in a moment.");if(busy.contains(p.getUniqueId())||!store.operations(p.getUniqueId()).isEmpty())throw new IllegalArgumentException("An inventory transfer is still pending. Wait, or ask an administrator to review it.");}
    private void limit(Player p){long now=System.nanoTime();Long last=cooldown.get(p.getUniqueId());if(last!=null&&now-last<300_000_000L)throw new IllegalArgumentException("Please wait a moment before another market action.");cooldown.put(p.getUniqueId(),now);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        long start=System.nanoTime();
        try{
            String name=command.getName();
            if(name.equals("mkmarket")){
                if(!sender.hasPermission("mksidebar.admin"))throw new IllegalArgumentException("You do not have permission.");admin(sender,args);return true;
            }
            if(!sender.hasPermission("mksidebar.market"))throw new IllegalArgumentException("You do not have permission.");
            if(sender instanceof Player viewer)limit(viewer);
            if(name.equals("baltop")){top(sender);return true;}
            Player p=player(sender);
            if(!store.hasAccount(p.getUniqueId()))throw new IllegalArgumentException("Your wallet is loading. Try again in a moment.");
            switch(name){
                case "money" -> {if(args.length==0)say(p,"Balance: "+Money.format(store.balance(p.getUniqueId())));else if(args[0].equalsIgnoreCase("history")&&args.length<=2)history(p,args.length==2?positiveInt(args[1])-1:0);else throw new IllegalArgumentException("Use /money or /money history [page].");}
                case "pay" -> {available(p);if(args.length!=2)throw new IllegalArgumentException("Use /pay <player|UUID> <amount>.");UUID id=target(args[0]);long amount=Money.parse(args[1]);complete(store.pay(p.getUniqueId(),id,amount),p,v->{say(p,"Sent "+Money.format(amount)+" to "+store.name(id)+".");Player recipient=plugin.getServer().getPlayer(id);if(recipient!=null)say(recipient,"Received "+Money.format(amount)+" from "+p.getName()+".");},null);}
                case "auction" -> auction(p,args);
                case "coinflip" -> flip(p,args);
                default -> throw new IllegalArgumentException("Unknown market command.");
            }
        }catch(IllegalArgumentException e){report(sender,e);}finally{timings.add(System.nanoTime()-start);}
        return true;
    }
    private void auction(Player p,String[] args){
        available(p);
        if(args.length==0){browse(p,"ALL","NEWEST",0,false);return;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "sell" -> {if(args.length!=2)throw new IllegalArgumentException("Hold the stack to sell, then use /auction sell <total price>.");sell(p,Money.parse(args[1]));}
            case "mail" -> mailbox(p,0);
            case "mine" -> browse(p,"ALL","NEWEST",0,true);
            case "cancel" -> {if(args.length!=2)throw new IllegalArgumentException("Use /auction cancel <listing ID>.");cancel(p,positiveLong(args[1]));}
            default -> throw new IllegalArgumentException("Use /auction, /auction sell <price>, /auction mine, or /auction mail.");
        }
    }
    private void sell(Player p,long price){
        if(p.getGameMode()!=GameMode.SURVIVAL&&p.getGameMode()!=GameMode.ADVENTURE)throw new IllegalArgumentException("Sell from Survival or Adventure mode.");
        if(p.getOpenInventory().getTopInventory().getType()!=InventoryType.CRAFTING)throw new IllegalArgumentException("Close your open inventory before listing an item.");
        if(!p.getItemOnCursor().isEmpty())throw new IllegalArgumentException("Clear your cursor first.");
        ItemStack held=p.getInventory().getItemInMainHand().clone();if(held.isEmpty())throw new IllegalArgumentException("Hold the item stack you want to sell.");
        ItemStack[] before=p.getInventory().getStorageContents(),after=copy(before);after[p.getInventory().getHeldItemSlot()]=null;
        long expires=System.currentTimeMillis()+settings.listingDays()*86_400_000L;
        handoff(p,before,after,store.prepareListing(p.getUniqueId(),held.serializeAsBytes(),held.getType().name(),price,expires,settings.listingLimit(),settings.totalLimit(),snapshot(before),snapshot(after)),
                "Listed "+held.getAmount()+" "+displayName(held)+" for "+Money.format(price)+" (total stack price).");
    }
    private void claim(Player p,MarketStore.Mail entry){
        available(p);if(!entry.state().equals("READY"))throw new IllegalArgumentException("That mailbox entry is pending review.");
        ItemStack item=ItemStack.deserializeBytes(entry.item());ItemStack[] before=p.getInventory().getStorageContents();
        Inventory simulation=Bukkit.createInventory(null,36);simulation.setContents(copy(before));
        if(!simulation.addItem(item).isEmpty())throw new IllegalArgumentException("Make room for this whole stack in your inventory first.");
        ItemStack[] after=simulation.getContents();
        handoff(p,before,after,store.prepareClaim(p.getUniqueId(),entry.id(),snapshot(before),snapshot(after)),"Item claimed from your mailbox.");
    }
    private void handoff(Player p,ItemStack[] before,ItemStack[] after,CompletableFuture<Long> prepared,String success){
        UUID id=p.getUniqueId();busy.add(id);p.closeInventory();
        complete(prepared,p,op->inventoryTransfers.add(()->{
            try{applyHandoff(p,op,before,after,success);}catch(Exception e){report(p,e);busy.remove(id);recover(p);}
        }),()->busy.remove(id));
    }
    private void applyHandoff(Player p,long op,ItemStack[] before,ItemStack[] after,String success){
        UUID id=p.getUniqueId();
            if(!p.isOnline()||p.isDead()||!same(p.getInventory().getStorageContents(),before)){
                complete(store.finishInventory(op,false),p,v->{busy.remove(id);say(p,"Inventory changed; transfer cancelled safely.");},()->busy.remove(id));return;
            }
            try{
                p.getInventory().setStorageContents(copy(after));
                // Persist the inventory before confirming escrow ownership. A crash is reconciled using these snapshots.
                persistInventory(p);
            }catch(Exception e){
                p.getInventory().setStorageContents(copy(before));persistInventory(p);
                complete(store.finishInventory(op,false),p,v->busy.remove(id),()->busy.remove(id));throw e;
            }
            complete(store.finishInventory(op,true),p,v->{busy.remove(id);if(p.isOnline()){say(p,success);p.playSound(p.getLocation(),Sound.ENTITY_EXPERIENCE_ORB_PICKUP,0.4f,1.2f);}},()->{
                // Outcome is retained for reconciliation; never replay or roll back an uncertain saved inventory.
                busy.remove(id);recover(p);
            });
    }
    private void recover(Player p){
        UUID id=p.getUniqueId();if(!p.isOnline()){busy.remove(id);return;}
        List<MarketStore.InventoryOp> ops=store.operations(id);
        if(ops.isEmpty()){busy.remove(id);recovering.remove(id);return;}
        if(ops.stream().anyMatch(o->o.state().equals("REVIEW"))){busy.remove(id);recovering.remove(id);say(p,"A saved inventory transfer needs admin review. Items are held safely; use /mkmarket recovery as an administrator.");return;}
        if(!recovering.add(id))return;busy.add(id);
        List<CompletableFuture<Void>> pending=new ArrayList<>();
        for(var op:ops){
            ItemStack[] current=p.getInventory().getStorageContents();
            boolean before=same(current,ItemStack.deserializeItemsFromBytes(op.before())),after=same(current,ItemStack.deserializeItemsFromBytes(op.after()));
            pending.add(before||after?store.finishInventory(op.id(),after):store.review(op.id()));
        }
        complete(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)),p,v->{busy.remove(id);recovering.remove(id);if(!store.operations(id).isEmpty())say(p,"Inventory changed during an interrupted transfer. An administrator can resolve the saved record.");},()->{busy.remove(id);recovering.remove(id);});
    }
    private static byte[] snapshot(ItemStack[] items){return ItemStack.serializeItemsAsBytes(Arrays.asList(copy(items)));}
    private void persistInventory(Player p){long began=System.nanoTime();try{p.saveData();}finally{inventorySaves++;inventorySaveMax=Math.max(inventorySaveMax,System.nanoTime()-began);}}
    private static ItemStack[] copy(ItemStack[] values){return Arrays.stream(values).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);}
    private static boolean same(ItemStack[] a,ItemStack[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++){ItemStack x=a[i],y=b[i];if(x!=null&&x.isEmpty())x=null;if(y!=null&&y.isEmpty())y=null;if(!Objects.equals(x,y))return false;}return true;}
    private static String displayName(ItemStack item){return item.getType().name().toLowerCase(Locale.ROOT).replace('_',' ');}
    private void cancel(Player p,long ref){complete(store.cancel(p.getUniqueId(),ref),p,v->{say(p,"Listing cancelled. Collect the item with /auction mail.");},null);}
    private void buy(Player p,long ref){
        available(p);busy.add(p.getUniqueId());
        complete(store.buy(p.getUniqueId(),ref,settings.feePercent(),System.currentTimeMillis()),p,seller->{
            busy.remove(p.getUniqueId());p.closeInventory();say(p,"Purchase complete. Collect your item with /auction mail.");
            Player recipient=plugin.getServer().getPlayer(seller);if(recipient!=null)say(recipient,"Your listing #"+ref+" sold! Money is in your wallet; /money history shows the receipt.");
            if(p.isOnline())p.playSound(p.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,0.3f,1.5f);
        },()->busy.remove(p.getUniqueId()));
    }
    private void history(Player p,int page){complete(store.history(p.getUniqueId(),page),p,rows->{
        say(p,"Money history • page "+(page+1));if(rows.isEmpty())say(p,"No more transactions.");
        DateTimeFormatter fmt=DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());
        for(var row:rows)p.sendMessage(Component.text(fmt.format(Instant.ofEpochMilli(row.at()))+"  ",NamedTextColor.GRAY).append(Component.text(row.kind()+" ",NamedTextColor.AQUA)).append(Component.text((row.delta()>=0?"+":"-")+Money.format(Math.abs(row.delta())),row.delta()<0?NamedTextColor.RED:NamedTextColor.GREEN)).append(Component.text(" • "+row.detail(),NamedTextColor.GRAY)));
    },null);}
    private void top(CommandSender s){complete(store.refreshTop(),s,rows->{
        say(s,"Top balances");int i=1;for(var row:rows)s.sendMessage(Component.text(i+++". ",NamedTextColor.GOLD).append(Component.text(row.name()+"  ",NamedTextColor.WHITE)).append(Component.text(Money.format(row.cents()),NamedTextColor.YELLOW)));if(rows.isEmpty())say(s,"No wallets yet.");
    },null);}
    private void flip(Player p,String[] args){
        available(p);
        if(args.length==0){say(p,"Challenge a player: /coinflip <player> <amount>. Accept or decline: /coinflip accept|decline [ID]. Cancel: /coinflip cancel.");return;}
        if(Set.of("accept","decline","cancel").contains(args[0].toLowerCase(Locale.ROOT))){
            if(args.length>2)throw new IllegalArgumentException("Use /coinflip accept|decline|cancel [ID].");
            var rows=store.challenges().stream().filter(c->args[0].equalsIgnoreCase("cancel")?c.from().equals(p.getUniqueId()):c.to().equals(p.getUniqueId())).toList();
            var c=args.length==2?rows.stream().filter(row->row.id()==positiveLong(args[1])).findFirst().orElse(null):rows.stream().findFirst().orElse(null);
            if(c==null)throw new IllegalArgumentException("You have no matching coin flip challenge.");
            if(args[0].equalsIgnoreCase("accept")){
                Player other=plugin.getServer().getPlayer(c.from());if(other==null)throw new IllegalArgumentException("The challenger has disconnected. The wager will be refunded.");
                complete(store.accept(p.getUniqueId(),c.id(),random.nextBoolean(),System.currentTimeMillis()),p,winner->{
                    String text=store.name(winner)+" won "+Money.format(c.amount()*2)+" in coin flip #"+c.id()+"!";
                    for(UUID id:List.of(c.from(),c.to())){Player viewer=plugin.getServer().getPlayer(id);if(viewer!=null){say(viewer,text);viewer.playSound(viewer.getLocation(),Sound.BLOCK_NOTE_BLOCK_BELL,0.5f,winner.equals(id)?1.5f:0.8f);}}
                },null);
            }else complete(store.decline(p.getUniqueId(),c.id(),args[0]),p,v->{say(p,"Challenge closed; the held wager was refunded.");Player other=plugin.getServer().getPlayer(c.from().equals(p.getUniqueId())?c.to():c.from());if(other!=null)say(other,"Coin flip #"+c.id()+" was closed; the held wager was refunded.");},null);
            return;
        }
        if(args.length!=2)throw new IllegalArgumentException("Use /coinflip <player> <amount>.");
        Player recipient=plugin.getServer().getPlayerExact(args[0]);if(recipient==null)throw new IllegalArgumentException("Challenge a player who is online.");available(recipient);
        if(!recipient.hasPermission("mksidebar.market"))throw new IllegalArgumentException("That player cannot use coin flips.");
        long amount=Money.parse(args[1]);if(amount<settings.minWager()||amount>settings.maxWager())throw new IllegalArgumentException("Wagers must be between "+Money.format(settings.minWager())+" and "+Money.format(settings.maxWager())+".");
        complete(store.challenge(p.getUniqueId(),recipient.getUniqueId(),amount,System.currentTimeMillis()+settings.challengeSeconds()*1000L),p,id->{
            if(!p.isOnline()||!recipient.isOnline()){complete(store.decline(p.getUniqueId(),id,"Player disconnected"),null,v->{},null);return;}
            say(p,"Challenged "+recipient.getName()+" for "+Money.format(amount)+". Your wager is held until the challenge ends.");
            say(recipient,p.getName()+" challenged you! Wager: "+Money.format(amount)+" each; pot: "+Money.format(amount*2)+". Expires in "+settings.challengeSeconds()+" seconds.");
            recipient.sendMessage(Component.text("[ACCEPT]",NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/coinflip accept "+id)).append(Component.text("  ")).append(Component.text("[DECLINE]",NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/coinflip decline "+id))));
        },null);
    }
    private void admin(CommandSender s,String[] args){
        if(args.length==0){say(s,"/mkmarket money add|remove <player|UUID> <amount> • leaderboard place|remove • recovery • performance");return;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "money" -> {
                if(args.length!=4||!Set.of("add","remove").contains(args[1].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Use /mkmarket money add|remove <player|UUID> <amount>.");
                UUID id=target(args[2]);long value=Money.parse(args[3]);long delta=args[1].equalsIgnoreCase("remove")?-value:value;
                complete(store.adjust(id,delta,s.getName()),s,result->{say(s,"Updated "+store.name(id)+": "+Money.format(result));plugin.getLogger().info(s.getName()+" changed "+id+" balance by "+delta+" cents.");Player p=plugin.getServer().getPlayer(id);if(p!=null)say(p,"An administrator "+(delta>0?"added ":"removed ")+Money.format(value)+". Balance: "+Money.format(result));},null);
            }
            case "leaderboard" -> {
                if(args.length!=2)throw new IllegalArgumentException("Use /mkmarket leaderboard place|remove.");
                MarketStore.Board location;
                if(args[1].equalsIgnoreCase("place")){if(!settings.leaderboard())throw new IllegalArgumentException("Enable money-leaderboard.enabled first.");Location loc=player(s).getLocation().add(0,2.5,0);location=new MarketStore.Board(loc.getWorld().getUID(),loc.getX(),loc.getY(),loc.getZ());}
                else if(args[1].equalsIgnoreCase("remove"))location=null;else throw new IllegalArgumentException("Use place or remove.");
                complete(store.setBoard(location),s,v->{board.removeLoaded();complete(store.refreshTop(),s,rows->{syncBoard();say(s,location==null?"Money leaderboard removed.":"Money leaderboard placed above your position.");},null);},null);
            }
            case "recovery" -> {
                if(args.length==1){var rows=store.reviews();say(s,"Review operations: "+rows.size());for(var op:rows)say(s,"#"+op.id()+" "+store.name(op.owner())+" "+op.kind()+" ref #"+op.ref());say(s,"After checking that player's inventory: /mkmarket recovery <ID> applied|unapplied. See MARKET.md before resolving.");}
                else if(args.length==3&&Set.of("applied","unapplied").contains(args[2].toLowerCase(Locale.ROOT))){complete(store.resolveReview(positiveLong(args[1]),args[2].equalsIgnoreCase("applied"),s.getName()),s,v->{say(s,"Saved recovery resolution.");},null);}
                else throw new IllegalArgumentException("Use /mkmarket recovery [ID applied|unapplied].");
            }
            case "performance" -> say(s,timings.report(0,plugin.getServer().getAverageTickTime(),plugin.getServer().getTPS()[0],null)+String.format(Locale.ROOT," inventory-saves=%d save-max=%.3fms (since enable)",inventorySaves,inventorySaveMax/1_000_000.0));
            default -> throw new IllegalArgumentException("Unknown market admin action.");
        }
    }
    private enum View{BROWSE,CONFIRM,MAIL}
    private static final class Menu implements InventoryHolder{
        private Inventory inventory;private final View view;private final String category,sort;private final int page;private final boolean mine;
        private final Map<Integer,Long> ids=new HashMap<>();private final long reference;
        Menu(View view,String category,String sort,int page,boolean mine,long reference){this.view=view;this.category=category;this.sort=sort;this.page=page;this.mine=mine;this.reference=reference;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private Menu menu(View view,int size,String title,String category,String sort,int page,boolean mine,long ref){Menu h=new Menu(view,category,sort,page,mine,ref);h.inventory=Bukkit.createInventory(h,size,Component.text(title,NamedTextColor.GOLD));return h;}
    private static ItemStack icon(Material material,String title,String...lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.AQUA).decoration(TextDecoration.ITALIC,false));meta.lore(Arrays.stream(lore).map(t->Component.text(t,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());item.setItemMeta(meta);return item;}
    private void browse(Player p,String category,String sort,int page,boolean mine){
        List<MarketStore.Listing> rows=store.listings().stream().filter(l->l.state().equals("ACTIVE")&&l.expires()>System.currentTimeMillis()&&(!mine||l.seller().equals(p.getUniqueId()))&&matches(l.material(),category)).sorted(switch(sort){case "PRICE ↑"->Comparator.comparingLong(MarketStore.Listing::price).thenComparingLong(MarketStore.Listing::id);case "PRICE ↓"->Comparator.comparingLong(MarketStore.Listing::price).reversed().thenComparingLong(MarketStore.Listing::id);default->Comparator.comparingLong(MarketStore.Listing::id).reversed();}).toList();
        page=Math.max(0,Math.min(page,Math.max(0,(rows.size()-1)/45)));
        Menu h=menu(View.BROWSE,54,(mine?"MY LISTINGS":"MK MARKET")+" • "+Money.format(store.balance(p.getUniqueId())),category,sort,page,mine,0);
        int slot=0;for(var l:rows.stream().skip(page*45L).limit(45).toList()){
            ItemStack item=ItemStack.deserializeBytes(l.item());ItemMeta meta=item.getItemMeta();List<Component> lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());
            for(String line:List.of("Listing #"+l.id(),"Seller: "+store.name(l.seller()),"Total price: "+Money.format(l.price()),"Expires: "+Values.duration(l.expires()-System.currentTimeMillis()),mine?"Click to cancel and return to mailbox":"Click to review purchase"))lore.add(Component.text(line,NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC,false));meta.lore(lore);item.setItemMeta(meta);h.inventory.setItem(slot,item);h.ids.put(slot++,l.id());
        }
        h.inventory.setItem(45,icon(Material.CHEST,"Category: "+category,"Click to change category"));h.inventory.setItem(46,icon(Material.REDSTONE,"Sort: "+sort));
        h.inventory.setItem(47,icon(Material.ARROW,"Previous page"));h.inventory.setItem(48,icon(Material.PAPER,"Page "+(page+1),"Hold an item: /auction sell <total price>"));h.inventory.setItem(49,icon(Material.ARROW,"Next page"));
        h.inventory.setItem(50,icon(Material.WRITABLE_BOOK,mine?"All listings":"My listings"));h.inventory.setItem(51,icon(Material.ENDER_CHEST,"Mailbox","Claim purchases and returned items","Sale earnings go directly to your wallet"));h.inventory.setItem(52,icon(Material.CLOCK,"Transaction history"));h.inventory.setItem(53,icon(Material.BARRIER,"Close"));p.openInventory(h.inventory);
    }
    private static boolean matches(String material,String category){Material m=Material.matchMaterial(material);if(m==null)return false;return switch(category){case "BLOCKS"->m.isBlock();case "FOOD"->m.isEdible();case "TOOLS"->material.matches(".*(SWORD|PICKAXE|AXE|SHOVEL|HOE|BOW|HELMET|CHESTPLATE|LEGGINGS|BOOTS|TRIDENT|ELYTRA)$");case "ITEMS"->!m.isBlock()&&!m.isEdible();default->true;};}
    private void confirm(Player p,long ref){MarketStore.Listing l=store.listing(ref);if(l==null||!l.state().equals("ACTIVE"))throw new IllegalArgumentException("That listing is no longer available.");
        if(l.seller().equals(p.getUniqueId()))throw new IllegalArgumentException("Use My listings to cancel your own item.");
        Menu h=menu(View.CONFIRM,27,"CONFIRM PURCHASE","ALL","NEWEST",0,false,ref);h.inventory.setItem(13,ItemStack.deserializeBytes(l.item()));
        h.inventory.setItem(11,icon(Material.EMERALD,"Confirm: "+Money.format(l.price()),"Seller: "+store.name(l.seller()),"Balance after: "+(store.balance(p.getUniqueId())>=l.price()?Money.format(store.balance(p.getUniqueId())-l.price()):"Not enough money"),"The item will be in /auction mail"));h.inventory.setItem(15,icon(Material.BARRIER,"Cancel"));p.openInventory(h.inventory);
    }
    private void mailbox(Player p,int page){
        var rows=store.mailbox(p.getUniqueId());page=Math.max(0,Math.min(page,Math.max(0,(rows.size()-1)/45)));
        Menu h=menu(View.MAIL,54,"MK MAILBOX","ALL","NEWEST",page,false,0);int slot=0;
        for(var m:rows.stream().skip(page*45L).limit(45).toList()){ItemStack item=ItemStack.deserializeBytes(m.item());ItemMeta meta=item.getItemMeta();var lore=new ArrayList<>(meta.lore()==null?List.of():meta.lore());for(String line:List.of(m.detail(),m.state().equals("READY")?"Click to claim (make room first)":"Held for recovery review"))lore.add(Component.text(line,NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC,false));meta.lore(lore);item.setItemMeta(meta);h.inventory.setItem(slot,item);h.ids.put(slot++,m.id());}
        h.inventory.setItem(45,icon(Material.ARROW,"Previous page"));h.inventory.setItem(49,icon(Material.ARROW,"Next page"));h.inventory.setItem(50,icon(Material.CHEST,"Back to market"));h.inventory.setItem(52,icon(Material.PAPER,"Sale receipts / history","Earnings are already in your wallet"));h.inventory.setItem(53,icon(Material.BARRIER,"Close"));p.openInventory(h.inventory);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(busy.contains(p.getUniqueId())){e.setCancelled(true);return;}
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu h))return;e.setCancelled(true);
        if(e.getRawSlot()<0||e.getRawSlot()>=h.inventory.getSize())return;
        // Process next tick after Bukkit finishes the click, checking this exact menu is still open.
        main(()->{if(!p.isOnline()||p.getOpenInventory().getTopInventory()!=h.inventory)return;long start=System.nanoTime();try{
            available(p);limit(p);int slot=e.getRawSlot();
            if(h.view==View.CONFIRM){if(slot==11)buy(p,h.reference);else if(slot==15)browse(p,"ALL","NEWEST",0,false);}
            else if(h.ids.containsKey(slot)){long ref=h.ids.get(slot);if(h.view==View.MAIL){var entry=store.mailbox(p.getUniqueId()).stream().filter(m->m.id()==ref).findFirst().orElseThrow(()->new IllegalArgumentException("That item is already claimed."));claim(p,entry);}else if(h.mine){cancel(p,ref);p.closeInventory();}else confirm(p,ref);}
            else if(h.view==View.MAIL){if(slot==45)mailbox(p,h.page-1);else if(slot==49)mailbox(p,h.page+1);else if(slot==50)browse(p,"ALL","NEWEST",0,false);else if(slot==52){p.closeInventory();history(p,0);}else if(slot==53)p.closeInventory();}
            else{switch(slot){case 45->{List<String> cats=List.of("ALL","BLOCKS","ITEMS","FOOD","TOOLS");browse(p,cats.get((cats.indexOf(h.category)+1)%cats.size()),h.sort,0,h.mine);}case 46->{List<String> sorts=List.of("NEWEST","PRICE ↑","PRICE ↓");browse(p,h.category,sorts.get((sorts.indexOf(h.sort)+1)%sorts.size()),0,h.mine);}case 47->browse(p,h.category,h.sort,h.page-1,h.mine);case 49->browse(p,h.category,h.sort,h.page+1,h.mine);case 50->browse(p,h.category,h.sort,0,!h.mine);case 51->mailbox(p,0);case 52->{p.closeInventory();history(p,0);}case 53->p.closeInventory();default->{}}}
        }catch(Exception ex){report(p,ex);}finally{timings.add(System.nanoTime()-start);}});
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&(busy.contains(p.getUniqueId())||e.getView().getTopInventory().getHolder() instanceof Menu))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void drop(PlayerDropItemEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void swap(PlayerSwapHandItemsEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void use(PlayerInteractEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void entityUse(PlayerInteractEntityEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void breaking(BlockBreakEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&busy.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void consuming(PlayerItemConsumeEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void damage(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p&&busy.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void arrow(PlayerPickupArrowEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void gamemode(PlayerGameModeChangeEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void held(PlayerItemHeldEvent e){if(busy.contains(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void inventoryOpen(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&busy.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void commandWhileBusy(PlayerCommandPreprocessEvent e){if(busy.contains(e.getPlayer().getUniqueId())){e.setCancelled(true);say(e.getPlayer(),"Wait for your inventory transfer to finish.");}}
    private static int positiveInt(String input){try{int value=Integer.parseInt(input);if(value<1||value>10000)throw new NumberFormatException();return value;}catch(NumberFormatException e){throw new IllegalArgumentException("Use a valid positive number.");}}
    private static long positiveLong(String input){try{long value=Long.parseLong(input);if(value<1)throw new NumberFormatException();return value;}catch(NumberFormatException e){throw new IllegalArgumentException("Use a valid positive ID.");}}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] a){
        List<String> choices=new ArrayList<>();
        if(c.getName().equals("mkmarket")){if(!s.hasPermission("mksidebar.admin"))return List.of();if(a.length==1)choices.addAll(List.of("money","leaderboard","recovery","performance"));else if(a.length==2){if(a[0].equals("money"))choices.addAll(List.of("add","remove"));if(a[0].equals("leaderboard"))choices.addAll(List.of("place","remove"));}else if(a.length==3&&a[0].equals("money"))plugin.getServer().getOnlinePlayers().forEach(p->choices.add(p.getName()));else if(a.length==3&&a[0].equals("recovery"))choices.addAll(List.of("applied","unapplied"));}
        else if(!s.hasPermission("mksidebar.market"))return List.of();
        else if(c.getName().equals("auction")&&a.length==1)choices.addAll(List.of("sell","mail","mine","cancel"));
        else if(c.getName().equals("money")&&a.length==1)choices.add("history");
        else if(a.length==1&&(c.getName().equals("pay")||c.getName().equals("coinflip"))){plugin.getServer().getOnlinePlayers().forEach(p->choices.add(p.getName()));if(c.getName().equals("coinflip"))choices.addAll(List.of("accept","decline","cancel"));}
        String prefix=a.length==0?"":a[a.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
    @Override public void close(){stopping=true;inventoryTransfers.clear();for(Player p:plugin.getServer().getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)p.closeInventory();board.detach();store.close();}
}
