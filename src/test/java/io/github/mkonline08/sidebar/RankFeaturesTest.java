package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RankFeaturesTest {
    private PlayerRecord record(long millis,String manual,boolean earned){return new PlayerRecord(UUID.randomUUID(),"Alex",millis,manual,earned,true,false);}
    private String progress(PlayerRecord r,Settings s){return String.join("\n",RankProgress.messages(r,s).stream().map(TemplatesTest::plain).toList());}
    @Test void notificationsWaitForSaveAndMainThreadAndFailedSavesStaySilent() throws Exception {
        Server server=mock(Server.class);Player a=mock(Player.class),b=mock(Player.class);ConsoleCommandSender console=mock(ConsoleCommandSender.class);
        when(server.getOnlinePlayers()).thenAnswer(i->List.of(a,b));when(server.getConsoleSender()).thenReturn(console);
        CompletableFuture<Void> saved=new CompletableFuture<>();AtomicReference<Runnable> queued=new AtomicReference<>();Component message=Component.text("Rank changed");PromotionSound sound=SettingsTest.defaults().promotionSound();
        RankEvents.afterSave(saved,server,message,sound,queued::set);assertNull(queued.get());verifyNoInteractions(a,b,console);
        saved.complete(null);assertNotNull(queued.get());verifyNoInteractions(a,b,console);queued.get().run();
        verify(a,times(1)).sendMessage(message);verify(b,times(1)).sendMessage(message);verify(a,times(1)).playSound(sound.sound());verify(b,times(1)).playSound(sound.sound());verify(console,times(1)).sendMessage(message);
        clearInvocations(a,b,console);queued.set(null);CompletableFuture<Void> failed=new CompletableFuture<>();
        RankEvents.afterSave(failed,server,message,sound,queued::set);failed.completeExceptionally(new IOException("Disk full"));assertNull(queued.get());verifyNoInteractions(a,b,console);
    }
    @Test void announcementAndSoundSwitchesAreIndependent() {
        Server server=mock(Server.class);Player p=mock(Player.class);ConsoleCommandSender console=mock(ConsoleCommandSender.class);when(server.getOnlinePlayers()).thenAnswer(i->List.of(p));when(server.getConsoleSender()).thenReturn(console);
        Component message=Component.text("Rank changed");PromotionSound muted=new PromotionSound(false,"minecraft:ui.toast.challenge_complete",0.7f,1);
        RankEvents.afterSave(CompletableFuture.completedFuture(null),server,message,muted,Runnable::run);verify(p).sendMessage(message);verify(p,never()).playSound(any(net.kyori.adventure.sound.Sound.class));
        clearInvocations(p,console);PromotionSound enabled=new PromotionSound(true,muted.key(),0.4f,1.5f);
        RankEvents.afterSave(CompletableFuture.completedFuture(null),server,null,enabled,Runnable::run);verify(p).playSound(enabled.sound());verify(p,never()).sendMessage(any(Component.class));verifyNoInteractions(console);
    }
    @Test void manualMessageSupportsOnlineAndOfflineTemplatesWithColoredRank() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("rank-change.announcement","%player_name% is now %player_rank% | %player_playtime_hours% | %player_biome%");Settings s=Settings.read(yaml);Server server=mock(Server.class);PlayerRecord r=record(3_600_000,"owner",false);
        Component offline=RankEvents.message(r,s,server,s.rankChangeAnnouncement());assertEquals("Alex is now OWNER | 1.00 | —",TemplatesTest.plain(offline));assertTrue(offline.toString().contains("red"));
        Player p=mock(Player.class);when(server.getPlayer(r.uuid())).thenReturn(p);when(p.getName()).thenReturn("Alex");yaml.set("rank-change.announcement","RANK CHANGE! %player_name% is now %player_rank%!");s=Settings.read(yaml);
        assertEquals("RANK CHANGE! Alex is now OWNER!",TemplatesTest.plain(RankEvents.message(r,s,server,s.rankChangeAnnouncement())));
    }
    @Test void progressReportsHoursPercentageAndRemainingTime() throws Exception {
        Settings s=SettingsTest.defaults();String result=progress(record(43_200_000,null,false),s);
        assertTrue(result.contains("Rank: New Player"));assertTrue(result.contains("Playtime: 12.00 hours"));assertTrue(result.contains("50.0%"));assertTrue(result.contains("12h 0m 0s"));
        String near=progress(record(s.promotionMillis()-1,null,false),s);assertTrue(near.contains("0m 1s"));
        String ready=progress(record(s.promotionMillis(),null,false),s);assertTrue(ready.contains("100.0%"));assertTrue(ready.contains("promotion will apply shortly"));
    }
    @Test void earnedOgStaysCompleteAndManualRanksPauseAutomaticProgress() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("promotion.hours",100);Settings s=Settings.read(yaml);
        String earned=progress(record(86_400_000,null,true),s);assertTrue(earned.contains("100% complete"));assertFalse(earned.contains("Time until"));
        String manual=progress(record(43_200_000,"owner",false),s);assertTrue(manual.contains("Rank: OWNER"));assertTrue(manual.contains("automatic promotion is paused"));assertFalse(manual.contains("Time until"));
        String both=progress(record(86_400_000,"owner",true),s);assertTrue(both.contains("100% complete"));assertTrue(both.contains("paused"));
    }
    @Test void upgradesV13SettingsAndPreservesCustomAnnouncementOverrides() throws Exception {
        var yaml=new YamlConfiguration();try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config-v5.yml")),StandardCharsets.UTF_8)){yaml.load(reader);}
        var before=List.copyOf(yaml.getStringList("sidebar.lines"));assertTrue(ConfigUpgrade.apply(yaml));Settings s=Settings.read(yaml);
        assertTrue(s.rankChangeEnabled());assertEquals(10,s.lines().size());assertTrue(s.lines().get(5).contains("%player_balance%"));assertFalse(ConfigUpgrade.apply(yaml));
        yaml.set("config-version",5);yaml.set("rank-change.enabled",false);yaml.set("rank-change.announcement","Custom %player_name% -> %player_rank%");assertTrue(ConfigUpgrade.apply(yaml));s=Settings.read(yaml);assertFalse(s.rankChangeEnabled());assertEquals("Custom %player_name% -> %player_rank%",s.rankChangeAnnouncement());
        yaml.set("rank-change.enabled","true");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));yaml.set("rank-change.enabled",true);yaml.set("rank-change.announcement","%unknown%");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
    }
}
