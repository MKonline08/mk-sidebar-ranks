package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Native inventory lobby and bounded cosmetic animation. The saved database outcome controls payout. */
final class Coinflips implements Listener {
    private final MKSidebarPlugin plugin;
    private final MarketStore store;
    private final Consumer<Player> ready;
    private final Consumer<Runnable> main;
    private final Consumer<Long> measured;
    private MarketSettings settings;
    private final SecureRandom random=new SecureRandom();
    private final Map<Long,Animation> animations=new HashMap<>();
    private final Map<UUID,Long> clicks=new HashMap<>();
    private final Map<UUID,PlayerProfileHead> heads=new HashMap<>();
    private long lobbyRevision;
    private int pulse;
    private boolean stopping;
    Coinflips(MKSidebarPlugin plugin,MarketStore store,MarketSettings settings,Consumer<Player> ready,Consumer<Runnable> main,Consumer<Long> measured){
        this.plugin=plugin;this.store=store;this.settings=settings;this.ready=ready;this.main=main;this.measured=measured;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::animate,5,5);
    }
    private enum Kind{LOBBY,JOIN,MATCH}
    private static final class Menu implements InventoryHolder{
        Inventory inventory;final Kind kind;final int page;final long ref;final Map<Integer,Long> ids=new HashMap<>();long revision;
        Menu(Kind kind,int page,long ref){this.kind=kind;this.page=page;this.ref=ref;}
        public Inventory getInventory(){return inventory;}
    }
    private static final class Animation{
        final MarketStore.Challenge flip;final boolean sound;final long duration;int frame=-1;boolean settling;long retryAt;
        Animation(MarketStore.Challenge flip,boolean sound,int seconds){this.flip=flip;this.sound=sound;this.duration=seconds*1000L;}
    }
    private record PlayerProfileHead(ItemStack item){}
    private Menu menu(Kind kind,int size,String title,int page,long ref){Menu h=new Menu(kind,page,ref);h.inventory=Bukkit.createInventory(h,size,Component.text(title,NamedTextColor.GOLD));return h;}
    private static ItemStack icon(Material type,String title,NamedTextColor color,String...lore){
        ItemStack item=new ItemStack(type);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,color).decoration(TextDecoration.ITALIC,false));
        meta.lore(Arrays.stream(lore).map(s->Component.text(s,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());item.setItemMeta(meta);return item;
    }
    private ItemStack head(UUID id,String title,String...lore){
        PlayerProfileHead cached=heads.get(id);
        if(cached==null){ItemStack item=new ItemStack(Material.PLAYER_HEAD);Player p=plugin.getServer().getPlayer(id);if(p!=null&&p.getPlayerProfile().hasTextures()){
            // Assigning a textureless owner causes Paper to resolve the profile for every copied menu head.
            // Use only the skin already attached to the online player; absent skins use a generic head.
            SkullMeta meta=(SkullMeta)item.getItemMeta();meta.setPlayerProfile(p.getPlayerProfile());item.setItemMeta(meta);
        }cached=new PlayerProfileHead(item);heads.put(id,cached);}
        ItemStack item=cached.item().clone();ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,NamedTextColor.AQUA).decoration(TextDecoration.ITALIC,false));meta.lore(Arrays.stream(lore).map(s->Component.text(s,NamedTextColor.GRAY).decoration(TextDecoration.ITALIC,false)).toList());item.setItemMeta(meta);return item;
    }
    private ItemStack resultHead(UUID id,String title,boolean won,String lore){ItemStack item=head(id,title,lore);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(title,won?NamedTextColor.GREEN:NamedTextColor.RED).decoration(TextDecoration.ITALIC,false));item.setItemMeta(meta);return item;}
    private static void say(Player p,String message){p.sendMessage(Component.text("MK » "+message,NamedTextColor.AQUA));}
    private <T>void complete(CompletableFuture<T> future,Player p,Consumer<T> success,Runnable failure){
        future.whenComplete((result,error)->main.accept(()->{
            if(stopping)return;long began=System.nanoTime();
            try{
                if(error==null)success.accept(result);
                else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                    if(p!=null&&p.isOnline())p.sendMessage(Component.text("MK » "+(cause instanceof IllegalArgumentException?cause.getMessage():"Match data could not save; your saved wager remains recoverable."),NamedTextColor.RED));
                    if(!(cause instanceof IllegalArgumentException))plugin.getLogger().log(Level.SEVERE,"Coin flip transaction error",cause);
                    if(failure!=null)failure.run();
                }
            }catch(Exception ex){plugin.getLogger().log(Level.SEVERE,"Coin flip menu error",ex);if(failure!=null)failure.run();}
            finally{measured.accept(System.nanoTime()-began);}
        }));
    }
    void command(Player p,String[] args){
        ready.accept(p);
        if(args.length==0){open(p);return;}
        if(args[0].equalsIgnoreCase("create")){
            if(args.length!=2)throw new IllegalArgumentException("Use /coinflip create <amount>, such as /coinflip create 50.");
            long amount=Money.parse(args[1]);if(amount<settings.minWager()||amount>settings.maxWager())throw new IllegalArgumentException("Wagers must be between "+Money.format(settings.minWager())+" and "+Money.format(settings.maxWager())+".");
            complete(store.createFlip(p.getUniqueId(),amount,System.currentTimeMillis()+settings.challengeSeconds()*1000L),p,id->{lobbyRevision++;if(!p.isOnline()){complete(store.disconnected(p.getUniqueId()),null,v->{},null);return;}say(p,"Created coin flip #"+id+" for "+Money.format(amount)+". Anyone can join with /coinflip.");lobby(p,0);},null);return;
        }
        if(args[0].equalsIgnoreCase("cancel")&&args.length==1){cancel(p);return;}
        throw new IllegalArgumentException("Use /coinflip to browse, /coinflip create <amount>, or /coinflip cancel.");
    }
    void joined(Player p){heads.remove(p.getUniqueId());if(p.isOnline()&&store.challenges().stream().anyMatch(f->f.state().equals("RUNNING")&&participant(f,p.getUniqueId())))open(p);}
    void quit(Player p){clicks.remove(p.getUniqueId());heads.remove(p.getUniqueId());lobbyRevision++;}
    void reload(MarketSettings settings){this.settings=settings;lobbyRevision++;}
    private static boolean participant(MarketStore.Challenge f,UUID id){return f.from().equals(id)||id.equals(f.to());}
    private void open(Player p){
        var active=store.challenges().stream().filter(f->f.state().equals("RUNNING")&&participant(f,p.getUniqueId())).findFirst();
        if(active.isPresent()){Animation a=animations.computeIfAbsent(active.get().id(),id->new Animation(active.get(),settings.flipSound(),settings.animationSeconds()));match(p,a);}
        else lobby(p,0);
    }
    private List<MarketStore.Challenge> waiting(){long now=System.currentTimeMillis();return store.challenges().stream().filter(f->f.state().equals("WAITING")&&f.expires()>now&&plugin.getServer().getPlayer(f.from())!=null).sorted(Comparator.comparingLong(MarketStore.Challenge::id).reversed()).toList();}
    private void lobby(Player p,int page){
        var rows=waiting();page=Math.max(0,Math.min(page,Math.max(0,(rows.size()-1)/45)));Menu h=menu(Kind.LOBBY,54,"MK COIN FLIPS • "+Money.format(store.balance(p.getUniqueId())),page,0);
        fillLobby(p,h,rows);p.openInventory(h.inventory);
    }
    private void fillLobby(Player p,Menu h,List<MarketStore.Challenge> rows){
        h.inventory.clear();h.ids.clear();int slot=0;
        for(var f:rows.stream().skip(h.page*45L).limit(45).toList()){
            h.inventory.setItem(slot,head(f.from(),store.name(f.from()),"Flip #"+f.id()+" • Waiting for a player","Wager: "+Money.format(f.amount()),"Pot: "+Money.format(f.amount()*2),"50% chance for each player",f.from().equals(p.getUniqueId())?"Your flip • use Cancel My Flip":"Click to review and join"));h.ids.put(slot++,f.id());
        }
        if(rows.isEmpty())h.inventory.setItem(22,icon(Material.GOLD_NUGGET,"No waiting flips",NamedTextColor.YELLOW,"Create one: /coinflip create 50"));
        h.inventory.setItem(45,icon(Material.ARROW,"Previous page",NamedTextColor.AQUA));h.inventory.setItem(46,icon(Material.EMERALD,"Create a flip",NamedTextColor.GREEN,"Use /coinflip create <amount>","Example: /coinflip create 50"));
        h.inventory.setItem(48,icon(Material.CLOCK,"Refresh • Page "+(h.page+1),NamedTextColor.AQUA));h.inventory.setItem(49,icon(Material.ARROW,"Next page",NamedTextColor.AQUA));
        h.inventory.setItem(50,icon(Material.RED_STAINED_GLASS_PANE,"Cancel My Flip",NamedTextColor.RED,"Refund your unmatched wager"));h.inventory.setItem(52,icon(Material.PAPER,"Fair coin flips",NamedTextColor.YELLOW,"Equal stakes • 50/50 chance","Once joined, the saved match finishes"));h.inventory.setItem(53,icon(Material.BARRIER,"Close",NamedTextColor.RED));h.revision=lobbyRevision;
    }
    private void confirm(Player p,long id){
        var f=store.flip(id);if(f==null||!f.state().equals("WAITING")||f.expires()<=System.currentTimeMillis())throw new IllegalArgumentException("That flip has already started or expired. Refresh the lobby.");
        if(f.from().equals(p.getUniqueId()))throw new IllegalArgumentException("You cannot join your own flip. Use Cancel My Flip.");
        Menu h=menu(Kind.JOIN,27,"CONFIRM COIN FLIP",0,id);h.inventory.setItem(13,head(f.from(),store.name(f.from()),"Match wager: "+Money.format(f.amount()),"Winner's pot: "+Money.format(f.amount()*2),"50% chance for each player"));
        h.inventory.setItem(11,icon(Material.EMERALD,"Join for "+Money.format(f.amount()),NamedTextColor.GREEN,"Your money is reserved until payout","A started match cannot be cancelled"));h.inventory.setItem(15,icon(Material.BARRIER,"Back",NamedTextColor.RED));p.openInventory(h.inventory);
    }
    private void join(Player p,long id){
        var f=store.flip(id);if(f==null||plugin.getServer().getPlayer(f.from())==null)throw new IllegalArgumentException("The host is no longer online. Refresh the lobby.");
        complete(store.joinFlip(p.getUniqueId(),id,random.nextBoolean(),System.currentTimeMillis(),settings.animationSeconds()),p,started->{
            Animation a=new Animation(started,settings.flipSound(),settings.animationSeconds());animations.put(id,a);lobbyRevision++;
            for(UUID who:List.of(started.from(),started.to())){Player viewer=plugin.getServer().getPlayer(who);if(viewer!=null){say(viewer,"Coin flip #"+id+" started. Both wagers are held during the flip.");try{ready.accept(viewer);match(viewer,a);}catch(IllegalArgumentException ignored){say(viewer,"Use /coinflip to reopen your match when your inventory is ready.");}}}
        },null);
    }
    private void cancel(Player p){
        var f=store.challenges().stream().filter(c->c.from().equals(p.getUniqueId())&&c.state().equals("WAITING")).findFirst().orElseThrow(()->new IllegalArgumentException("You have no waiting flip to cancel. Started matches finish normally."));
        complete(store.decline(p.getUniqueId(),f.id(),"Host cancelled public flip"),p,v->{lobbyRevision++;if(p.isOnline()){say(p,"Waiting flip cancelled; your wager was refunded.");lobby(p,0);}},null);
    }
    private static final int[] LEFT={0,1,2,9,11,18,19,20},RIGHT={6,7,8,15,17,24,25,26};
    private void match(Player p,Animation a){
        Menu h=menu(Kind.MATCH,27,"MK COIN FLIP #"+a.flip.id(),0,a.flip.id());
        h.inventory.setItem(10,head(a.flip.from(),store.name(a.flip.from()),"Wager: "+Money.format(a.flip.amount()),"50% chance"));h.inventory.setItem(16,head(a.flip.to(),store.name(a.flip.to()),"Wager: "+Money.format(a.flip.amount()),"50% chance"));
        border(h,Material.YELLOW_STAINED_GLASS_PANE,Material.YELLOW_STAINED_GLASS_PANE,"Flipping…","Flipping…");h.inventory.setItem(22,icon(Material.PAPER,"Pot: "+Money.format(a.flip.amount()*2),NamedTextColor.YELLOW,"Closing this menu does not cancel the match","Use /coinflip to reopen it"));p.openInventory(h.inventory);frame(p,h,a,frameNumber(a,System.currentTimeMillis()),false);
    }
    private static int frameNumber(Animation a,long now){long elapsed=Math.max(0,a.duration-Math.max(0,a.flip.finishAt()-now)),fast=a.duration*3/5,medium=a.duration/5;return elapsed<fast?(int)(elapsed/250):elapsed<fast+medium?100+(int)((elapsed-fast)/500):200+(int)((elapsed-fast-medium)/750);}
    private void border(Menu h,Material left,Material right,String l,String r){ItemStack leftIcon=icon(left,l,left==Material.GREEN_STAINED_GLASS_PANE?NamedTextColor.GREEN:NamedTextColor.RED),rightIcon=icon(right,r,right==Material.GREEN_STAINED_GLASS_PANE?NamedTextColor.GREEN:NamedTextColor.RED);for(int slot:LEFT)h.inventory.setItem(slot,leftIcon);for(int slot:RIGHT)h.inventory.setItem(slot,rightIcon);}
    private void frame(Player p,Menu h,Animation a,int frame,boolean sound){
        boolean green=(frame&1)==0;h.inventory.setItem(13,icon(green?Material.GREEN_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,"Flipping…",green?NamedTextColor.GREEN:NamedTextColor.RED,"50% chance • Result pending"));
        border(h,green?Material.GREEN_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,green?Material.RED_STAINED_GLASS_PANE:Material.GREEN_STAINED_GLASS_PANE,"Flipping…","Flipping…");
        if(sound&&a.sound)p.playSound(p.getLocation(),Sound.BLOCK_NOTE_BLOCK_HAT,0.25f,1.0f+((frame%3)*0.15f));
    }
    private void animate(){
        if(stopping)return;long began=System.nanoTime();
        try{
            long now=System.currentTimeMillis();
            for(Animation a:List.copyOf(animations.values())){
                if(now>=a.flip.finishAt()){
                    if(!a.settling&&now>=a.retryAt){a.settling=true;
                        for(UUID id:List.of(a.flip.from(),a.flip.to())){Player p=plugin.getServer().getPlayer(id);if(p!=null&&p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h&&h.kind==Kind.MATCH&&h.ref==a.flip.id())h.inventory.setItem(13,icon(Material.CLOCK,"Saving result…",NamedTextColor.YELLOW,"Your wagers remain reserved until saved"));}
                        complete(store.settleFlip(a.flip.id(),now),null,result->{result(a,result);animations.remove(a.flip.id());lobbyRevision++;},()->{a.settling=false;a.retryAt=System.currentTimeMillis()+3000;});}continue;
                }
                int f=frameNumber(a,now);if(a.frame==f)continue;a.frame=f;
                for(UUID id:List.of(a.flip.from(),a.flip.to())){Player p=plugin.getServer().getPlayer(id);if(p!=null&&p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h&&h.kind==Kind.MATCH&&h.ref==a.flip.id())frame(p,h,a,f,true);}
            }
            // Refresh only currently open lobby menus, once per second; head profiles are cached from online players.
            if(++pulse%4==0){var rows=waiting();long revision=rows.stream().mapToLong(MarketStore.Challenge::id).reduce(lobbyRevision,(a,b)->a*31+b);
                for(Player p:plugin.getServer().getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h&&h.kind==Kind.LOBBY&&h.revision!=revision){fillLobby(p,h,rows);h.revision=revision;}
            }
        }finally{measured.accept(System.nanoTime()-began);}
    }
    private void result(Animation a,MarketStore.Challenge f){
        for(UUID id:List.of(f.from(),f.to())){Player p=plugin.getServer().getPlayer(id);if(p==null)continue;
            say(p,store.name(f.winner())+" won "+Money.format(f.amount()*2)+" in coin flip #"+f.id()+"!");
            if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu h&&h.kind==Kind.MATCH&&h.ref==f.id()){
                boolean left=f.winner().equals(f.from());border(h,left?Material.GREEN_STAINED_GLASS_PANE:Material.RED_STAINED_GLASS_PANE,left?Material.RED_STAINED_GLASS_PANE:Material.GREEN_STAINED_GLASS_PANE,left?"WINNER":"LOSER",left?"LOSER":"WINNER");
                h.inventory.setItem(10,resultHead(f.from(),(left?"WINNER • ":"LOSER • ")+store.name(f.from()),left,left?"Paid "+Money.format(f.amount()*2):"Lost wager: "+Money.format(f.amount())));h.inventory.setItem(16,resultHead(f.to(),(!left?"WINNER • ":"LOSER • ")+store.name(f.to()),!left,!left?"Paid "+Money.format(f.amount()*2):"Lost wager: "+Money.format(f.amount())));
                h.inventory.setItem(13,icon(Material.GOLD_NUGGET,"Winner: "+store.name(f.winner()),NamedTextColor.GOLD,"Pot paid: "+Money.format(f.amount()*2)));h.inventory.setItem(22,icon(Material.ARROW,"Back to lobby",NamedTextColor.AQUA));
            }
            if(a.sound)p.playSound(p.getLocation(),id.equals(f.winner())?Sound.ENTITY_PLAYER_LEVELUP:Sound.BLOCK_NOTE_BLOCK_BASS,0.4f,id.equals(f.winner())?1.4f:0.75f);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!(e.getView().getTopInventory().getHolder() instanceof Menu h))return;e.setCancelled(true);
        int slot=e.getRawSlot();if(slot<0||slot>=h.inventory.getSize())return;
        main.accept(()->{if(stopping||!p.isOnline()||p.getOpenInventory().getTopInventory()!=h.inventory)return;long began=System.nanoTime();
            try{ready.accept(p);if(!p.hasPermission("mksidebar.market"))throw new IllegalArgumentException("You do not have permission.");Long last=clicks.get(p.getUniqueId());long now=System.nanoTime();if(last!=null&&now-last<300_000_000L)return;clicks.put(p.getUniqueId(),now);
                if(h.kind==Kind.JOIN){if(slot==11)join(p,h.ref);else if(slot==15)lobby(p,0);}
                else if(h.kind==Kind.MATCH){if(slot==22&&store.flip(h.ref)==null)lobby(p,0);}
                else if(h.ids.containsKey(slot))confirm(p,h.ids.get(slot));
                else switch(slot){case 45->lobby(p,h.page-1);case 46->{p.closeInventory();say(p,"Choose your wager: /coinflip create <amount>. Example: /coinflip create 50.");}case 48->lobby(p,h.page);case 49->lobby(p,h.page+1);case 50->cancel(p);case 53->p.closeInventory();default->{}}
            }catch(IllegalArgumentException ex){p.sendMessage(Component.text("MK » "+ex.getMessage(),NamedTextColor.RED));}finally{measured.accept(System.nanoTime()-began);}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Menu)e.setCancelled(true);}
    void close(){stopping=true;animations.clear();heads.clear();clicks.clear();for(Player p:plugin.getServer().getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)p.closeInventory();}
}
