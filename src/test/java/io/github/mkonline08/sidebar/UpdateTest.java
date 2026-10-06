package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdateTest {
    @Test void upgradesCompactV12ConfigWithHoursAndPreservesFeatureOverrides() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("config-version",4);yaml.set("nametags",null);
        var compact=new YamlConfiguration();
        try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config-v3.yml")),StandardCharsets.UTF_8)){compact.load(reader);}
        yaml.set("sidebar.lines",compact.get("sidebar.lines"));assertTrue(ConfigUpgrade.apply(yaml));Settings s=Settings.read(yaml);
        assertTrue(s.nametagsEnabled());assertEquals(10,s.lines().size());assertTrue(s.lines().get(4).contains("%player_playtime_hours%"));assertFalse(ConfigUpgrade.apply(yaml));
        yaml.set("config-version",4);yaml.set("nametags.enabled",false);yaml.set("sidebar.lines",List.of("Custom"));assertTrue(ConfigUpgrade.apply(yaml));s=Settings.read(yaml);
        assertFalse(s.nametagsEnabled());assertEquals(List.of("Custom"),s.lines());
        yaml.set("nametags.enabled","yes");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
    }
    private YamlConfiguration legacy() throws Exception {
        var yaml=new YamlConfiguration();
        try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config-v1.yml")),StandardCharsets.UTF_8)) { yaml.load(reader); }
        return yaml;
    }
    @Test void upgradesOriginalLayoutAndAddsSoundAndExactCreditsOnce() throws Exception {
        var yaml=legacy();yaml.set("server-name","Custom SMP");yaml.set("ranks.vip.name","VIP");yaml.set("ranks.vip.color","gold");yaml.set("ranks.vip.bold",false);
        assertTrue(ConfigUpgrade.apply(yaml));var settings=Settings.read(yaml);
        assertEquals(10,settings.lines().size());assertEquals("Custom SMP",settings.serverName());assertTrue(settings.ranks().containsKey("vip"));
        assertTrue(settings.lines().get(3).contains("%player_x%"));assertTrue(settings.lines().get(3).contains("%player_z%"));
        assertTrue(settings.promotionSound().enabled());assertEquals("minecraft:ui.toast.challenge_complete",settings.promotionSound().key());
        assertEquals("Credits: MK/108e",TemplatesTest.plain(Templates.render(settings.tabFooter(),Map.of())));assertFalse(ConfigUpgrade.apply(yaml));
    }
    @Test void upgradesV11LayoutWithoutChangingSoundFooterOrPromotionSettings() throws Exception {
        var yaml=new YamlConfiguration();
        try(var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config-v2.yml")),StandardCharsets.UTF_8)) {yaml.load(reader);}
        yaml.set("promotion.hours",50);yaml.set("promotion.sound.enabled",false);yaml.set("tab.footer","My credits");
        assertTrue(ConfigUpgrade.apply(yaml));var s=Settings.read(yaml);assertEquals(10,s.lines().size());assertTrue(s.lines().get(1).contains("Rank:"));assertEquals(180_000_000,s.promotionMillis());assertFalse(s.promotionSound().enabled());assertEquals("My credits",s.tabFooter());assertFalse(ConfigUpgrade.apply(yaml));
    }
    @Test void preservesCustomLayoutsAndNewFeatureOverrides() throws Exception {
        var yaml=legacy();yaml.set("sidebar.lines",List.of("My layout","%player_rank%"));yaml.set("tab.footer","Custom footer");yaml.set("promotion.sound.enabled",false);
        ConfigUpgrade.apply(yaml);var settings=Settings.read(yaml);assertEquals(List.of("My layout","%player_rank%"),settings.lines());assertEquals("Custom footer",settings.tabFooter());assertFalse(settings.promotionSound().enabled());
    }
    @Test void rejectsInvalidSoundSettingsAndSupportsDisablingSound() throws Exception {
        var yaml=SettingsTest.yaml();yaml.set("promotion.sound.volume",-1);assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("promotion.sound.volume",0.7);yaml.set("promotion.sound.pitch","loud");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("promotion.sound.pitch",1);yaml.set("promotion.sound.key","invalid key");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("promotion.sound.key","minecraft:ui.toast.challenge_complete");yaml.set("promotion.sound.enabled",false);assertFalse(Settings.read(yaml).promotionSound().enabled());
    }
    @Test void footerRestoresPriorContentAndDoesNotOverwriteExternalChanges() throws Exception {
        Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn("Alex");
        AtomicReference<Component> name=new AtomicReference<>(Component.text("Alex")),footer=new AtomicReference<>(Component.text("Prior footer"));
        when(p.playerListName()).thenAnswer(i->name.get());doAnswer(i->{name.set(i.getArgument(0));return null;}).when(p).playerListName(any());
        when(p.playerListFooter()).thenAnswer(i->footer.get());doAnswer(i->{footer.set(i.getArgument(0));return null;}).when(p).sendPlayerListFooter(any(Component.class));
        var yaml=SettingsTest.yaml();yaml.set("sidebar.enabled",false);var settings=Settings.read(yaml);
        var record=new PlayerRecord(p.getUniqueId(),"Alex",0,null,false,true,false);Map<String,Component> values=new HashMap<>();Templates.KEYS.forEach(key->values.put(key,Component.text("value")));
        var displays=new Displays(null,Logger.getAnonymousLogger());displays.update(p,record,settings,values);assertEquals("Credits: MK/108e",TemplatesTest.plain(footer.get()));
        displays.update(p,record,settings,values);displays.remove(p);assertEquals(Component.text("Prior footer"),footer.get());
        displays.update(p,record,settings,values);Component external=Component.text("Other plugin");footer.set(external);displays.update(p,record,settings,values);displays.remove(p);assertEquals(external,footer.get());
    }
}
