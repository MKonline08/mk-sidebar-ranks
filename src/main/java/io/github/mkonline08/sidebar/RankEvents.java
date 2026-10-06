package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class RankEvents {
    private RankEvents() {}
    static Component message(PlayerRecord record,Settings settings,Server server,String source) {
        Player online=server.getPlayer(record.uuid());Map<String,Component> values;
        if(online!=null) values=Values.of(online,record,settings,server,settings.template(source).keys(),new HashMap<>());
        else {
            values=new HashMap<>();Templates.KEYS.forEach(key->values.put(key,Component.text("—")));
            values.putAll(Values.announcement(record,settings));
            values.put("player_playtime_hours",Component.text(String.format(Locale.ROOT,"%.2f",record.playMillis()/3_600_000.0)));
        }
        return settings.template(source).render(values);
    }
    static void afterSave(CompletableFuture<Void> saved,Server server,Component message,PromotionSound sound,Consumer<Runnable> onMain) {
        saved.thenRun(()->onMain.accept(()->{
            for(Player player:server.getOnlinePlayers()) {
                if(message!=null)player.sendMessage(message);
                if(sound.enabled())player.playSound(sound.sound());
            }
            if(message!=null)server.getConsoleSender().sendMessage(message);
        }));
    }
}
