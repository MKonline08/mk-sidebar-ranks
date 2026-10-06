package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import java.util.List;

/** One persistent text display, no armor stands and no forced chunk loads. */
final class MarketBoard {
    private final MKSidebarPlugin plugin;
    private final NamespacedKey key;
    private TextDisplay display;
    private Component last;
    MarketBoard(MKSidebarPlugin plugin){this.plugin=plugin;key=new NamespacedKey(plugin,"money_board");}
    void sync(MarketStore.Board saved,List<MarketStore.Account> rows,boolean enabled){
        if(saved==null||!enabled){if(display!=null){display.remove();display=null;last=null;}return;}
        World world=plugin.getServer().getWorld(saved.world());if(world==null)return;
        int cx=((int)Math.floor(saved.x()))>>4,cz=((int)Math.floor(saved.z()))>>4;
        if(!world.isChunkLoaded(cx,cz))return;
        Location loc=new Location(world,saved.x(),saved.y(),saved.z());
        if(display==null||!display.isValid())for(Entity e:world.getChunkAt(cx,cz).getEntities())if(tagged(e)){
            if(e instanceof TextDisplay td && display==null && td.getLocation().distanceSquared(loc)<0.01)display=td;
            else if(e!=display)e.remove();
        }
        if(display!=null&&(!display.isValid()||!display.getWorld().equals(world)||display.getLocation().distanceSquared(loc)>0.01)){display.remove();display=null;last=null;}
        if(display==null){display=world.spawn(loc,TextDisplay.class,t->{
            t.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);t.setPersistent(true);
            t.setBillboard(Display.Billboard.CENTER);t.setAlignment(TextDisplay.TextAlignment.CENTER);t.setShadowed(true);
            t.setBackgroundColor(Color.fromARGB(155,15,20,25));t.setLineWidth(400);t.setViewRange(0.4f);t.setGravity(false);
        });last=null;}
        Component text=Component.text("MK • TOP BALANCES",NamedTextColor.GOLD).append(Component.newline());
        int i=1;for(var a:rows)text=text.append(Component.text(i+++". ",NamedTextColor.GRAY)).append(Component.text(a.name(),NamedTextColor.WHITE)).append(Component.text("  "+Money.format(a.cents()),NamedTextColor.YELLOW)).append(Component.newline());
        text=text.append(Component.text("/baltop  •  Credits: MK/108e",NamedTextColor.AQUA));
        if(!text.equals(last)){display.text(text);last=text;}
    }
    boolean tagged(Entity e){return e.getPersistentDataContainer().has(key,PersistentDataType.BYTE);}
    void chunkLoaded(Chunk chunk,MarketStore.Board saved,boolean enabled){
        for(Entity e:chunk.getEntities())if(tagged(e)){
            boolean belongs=enabled&&saved!=null&&chunk.getWorld().getUID().equals(saved.world())&&chunk.getX()==((int)Math.floor(saved.x())>>4)&&chunk.getZ()==((int)Math.floor(saved.z())>>4);
            if(!belongs||!(e instanceof TextDisplay))e.remove();
        }
    }
    void removeLoaded(){
        if(display!=null&&display.isValid())display.remove();display=null;last=null;
        // Only scans loaded entities on explicit removal/disable, never in the regular tick loop.
        for(World w:plugin.getServer().getWorlds())for(Entity e:w.getEntities())if(tagged(e))e.remove();
    }
    void detach(){display=null;last=null;}
}
