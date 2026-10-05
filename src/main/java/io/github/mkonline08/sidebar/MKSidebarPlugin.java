package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class MKSidebarPlugin extends JavaPlugin implements Listener {
    private Settings settings;
    private Players players;
    private PlayerStore store;
    private Displays displays;
    private Nametags nametags;
    private volatile boolean stopping;
    private Path configFile;
    private final UpdateQueue updateQueue=new UpdateQueue();
    private final PerformanceSamples performance=new PerformanceSamples();
    private Map<String,Component> sharedValues=new HashMap<>();
    private int tick;

    @Override public void onEnable() {
        try {
            saveDefaultConfig(); configFile=getDataFolder().toPath().resolve("config.yml");
            YamlConfiguration yaml=readConfig(); boolean upgraded=ConfigUpgrade.apply(yaml);
            settings=Settings.read(yaml);
            store=new PlayerStore();
            players=new Players(store.open(getDataFolder().toPath().resolve("players.db")).join(),System::nanoTime);
            validateAssignedRanks(settings);
            if(upgraded) {
                Files.copy(configFile,getDataFolder().toPath().resolve("config-before-v"+getPluginMeta().getVersion()+"-"+System.currentTimeMillis()+".yml"));
                writeConfig(yaml);
                getLogger().info("Upgraded configuration to v"+getPluginMeta().getVersion()+"; the previous configuration was backed up.");
            }
            displays=new Displays(Objects.requireNonNull(getServer().getScoreboardManager()),getLogger());
            nametags=new Nametags(getLogger());
            Commands commands=new Commands(this);
            for(String name:List.of("mksb","mkrank")) {
                PluginCommand command=Objects.requireNonNull(getCommand(name));
                command.setExecutor(commands); command.setTabCompleter(commands);
            }
            getServer().getPluginManager().registerEvents(this,this);
            for(Player p:getServer().getOnlinePlayers()) joined(p);
            getServer().getScheduler().runTaskTimer(this,this::pulse,1L,1L);
            getServer().getScheduler().runTaskTimer(this,() -> { players.checkpointAll(); save(players.dirtySnapshot()); },1200L,1200L);
            getLogger().info("MK Sidebar & Ranks enabled | Created by MK | "+settings.ranks().size()+" ranks loaded.");
        } catch(Exception ex) {
            getLogger().log(Level.SEVERE,"Could not start MK Sidebar & Ranks. Fix the configuration/storage error and restart.",ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private void pulse() {
        long started=System.nanoTime();
        if(tick++%20==0) {
            sharedValues=new HashMap<>();
            updateQueue.refresh(getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList());
        }
        for(int i=0;i<settings.maxUpdatesPerTick();i++) {
            UUID id=updateQueue.poll();if(id==null)break;
            Player p=getServer().getPlayer(id);if(p!=null){promote(id);update(p,sharedValues);}
            if(System.nanoTime()-started>=1_000_000L)break;
        }
        performance.add(System.nanoTime()-started);
    }
    String performanceReport(){return performance.report(updateQueue.size(),getServer().getAverageTickTime(),getServer().getTPS()[0],displays);}
    @Override public void onDisable() {
        stopping=true;
        getServer().getScheduler().cancelTasks(this);
        if(nametags!=null)nametags.clear();
        if(displays!=null) for(Player p:getServer().getOnlinePlayers()) displays.remove(p);
        if(store!=null) {
            try { if(players!=null) { players.checkpointAll(); store.save(players.snapshot()).join(); } }
            catch(Exception e) { getLogger().log(Level.SEVERE,"Could not save player data at shutdown.",e); }
            finally { try { store.close(); } catch(Exception e) { getLogger().log(Level.SEVERE,"Could not close player database.",e); } }
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { joined(e.getPlayer()); }
    private void joined(Player p) {
        players.join(p,settings); save(List.of(players.get(p.getUniqueId())));
        promote(p.getUniqueId()); update(p);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); players.quit(p.getUniqueId()); save(List.of(players.get(p.getUniqueId()))); nametags.remove(p);displays.remove(p);
    }
    boolean promote(UUID id) {
        Optional<PlayerRecord> earned=players.promote(id,settings.promotionMillis());
        earned.ifPresent(record -> {
            Settings snapshot=settings;
            Player online=getServer().getPlayer(id);
            Map<String,Component> values;
            if(online!=null) values=Values.of(online,record,snapshot,getServer(),snapshot.template(snapshot.announcement()).keys(),new HashMap<>());
            else {
                values=new HashMap<>(); Templates.KEYS.forEach(key -> values.put(key,Component.text("—")));
                values.putAll(Values.announcement(record,snapshot));
                values.put("player_playtime_hours",Component.text(String.format(Locale.ROOT,"%.2f",record.playMillis()/3_600_000.0)));
            }
            Component message=snapshot.template(snapshot.announcement()).render(values);
            // Save the earned marker before announcing, preventing repeated announcements after restart.
            save(List.of(record)).thenRun(() -> onMain(() -> {
                for(Player p:getServer().getOnlinePlayers()) {
                    p.sendMessage(message);
                    if(snapshot.promotionSound().enabled()) p.playSound(snapshot.promotionSound().sound());
                }
                getServer().getConsoleSender().sendMessage(message);
            }));
        });
        return earned.isPresent();
    }
    CompletableFuture<Void> save(Collection<PlayerRecord> snapshot) {
        if(snapshot.isEmpty())return CompletableFuture.completedFuture(null);
        Collection<PlayerRecord> saved=List.copyOf(snapshot);
        CompletableFuture<Void> future=store.save(saved);
        future.whenComplete((ignored,error) -> {
            if(error!=null) getLogger().log(Level.SEVERE,"Player data could not be saved. Check disk space and database access.",error);
            else onMain(()->players.saved(saved));
        });
        return future;
    }
    private void onMain(Runnable runnable) {
        if(stopping) return;
        try { getServer().getScheduler().runTask(this,() -> { if(!stopping) runnable.run(); }); }
        catch(org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Shutdown can race with a database completion. */ }
    }
    void update(Player player) {
        update(player,new HashMap<>());
    }
    private void update(Player player,Map<String,Component> shared) {
        PlayerRecord record=players.get(player.getUniqueId());
        if(record!=null) {
            nametags.target(player,settings.rank(record));
            displays.update(player,record,settings,Values.of(player,record,settings,getServer(),settings.displayKeys(!record.sidebarHidden()),shared));
            nametags.sync(player,settings.nametagsEnabled());
        }
    }
    void refreshAll() {sharedValues=new HashMap<>();for(Player p:getServer().getOnlinePlayers())updateQueue.offer(p.getUniqueId());}
    Settings settings() { return settings; }
    Players players() { return players; }
    void toggle(Player p) {
        PlayerRecord record=players.get(p.getUniqueId());
        record=record.hidden(!record.sidebarHidden()); players.put(record); save(List.of(record));
        displays.resetSidebarConflict(p); update(p);
        p.sendMessage(Component.text(record.sidebarHidden()?"Sidebar hidden.":"Sidebar enabled.",NamedTextColor.AQUA));
    }
    void reloadSettings() throws Exception {
        Settings candidate=Settings.read(readConfig()); validateAssignedRanks(candidate); settings=candidate;
        for(Player p:getServer().getOnlinePlayers()) promote(p.getUniqueId());
        refreshAll();
    }
    void editRank(String id,String color,String label,boolean create) throws Exception {
        YamlConfiguration yaml=readConfig();
        boolean exists=settings.ranks().containsKey(id);
        if(create&&exists) throw new IllegalArgumentException("That rank already exists.");
        if(!create&&!exists) throw new IllegalArgumentException("Unknown rank: "+id);
        Rank rank=new Rank(id,create?label:settings.ranks().get(id).name(),color,create?false:settings.ranks().get(id).bold());
        yaml.set("ranks."+id+".name",rank.name()); yaml.set("ranks."+id+".color",rank.color()); yaml.set("ranks."+id+".bold",rank.bold());
        Settings candidate=Settings.read(yaml); validateAssignedRanks(candidate);
        writeConfig(yaml);
        settings=candidate; refreshAll();
    }
    private void writeConfig(YamlConfiguration yaml) throws Exception {
        Path temp=Files.createTempFile(getDataFolder().toPath(),"config-",".tmp");
        try {
            Files.writeString(temp,yaml.saveToString());
            try { Files.move(temp,configFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(temp,configFile,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private YamlConfiguration readConfig() throws Exception { YamlConfiguration yaml=new YamlConfiguration(); yaml.load(configFile.toFile()); return yaml; }
    private void validateAssignedRanks(Settings candidate) {
        for(PlayerRecord record:players.snapshot()) if(record.manualRank()!=null&&!candidate.ranks().containsKey(record.manualRank())) throw new IllegalArgumentException("Cannot remove assigned rank '"+record.manualRank()+"'. Reassign "+record.name()+" first.");
    }
    void assign(String target,String rank) {
        if(rank!=null&&!settings.ranks().containsKey(rank)) throw new IllegalArgumentException("Unknown rank: "+rank);
        PlayerRecord record=players.resolve(target,rank!=null);
        players.checkpoint(record.uuid());
        PlayerRecord current=players.get(record.uuid()); if(current!=null) record=current;
        String previousRank=record.rankId();
        record=record.override(rank); players.put(record);
        CompletableFuture<Void> saved=save(List.of(record));
        boolean promoted=rank==null && promote(record.uuid());
        if(!promoted && !previousRank.equals(players.get(record.uuid()).rankId())) {
            PromotionSound sound=settings.promotionSound();
            if(sound.enabled()) saved.thenRun(() -> onMain(() -> {
                for(Player listener:getServer().getOnlinePlayers()) listener.playSound(sound.sound());
            }));
        }
        Player p=getServer().getPlayer(record.uuid()); if(p!=null) update(p);
    }
}
