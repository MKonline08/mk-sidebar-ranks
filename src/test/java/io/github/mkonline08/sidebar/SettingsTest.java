package io.github.mkonline08.sidebar;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    static YamlConfiguration yaml() throws Exception {
        YamlConfiguration yaml=new YamlConfiguration();
        try(var stream=SettingsTest.class.getResourceAsStream("/config.yml")) { yaml.load(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8)); }
        return yaml;
    }
    static Settings defaults() throws Exception { return Settings.read(yaml()); }
    @Test void defaultConfigurationHasExpectedSettings() throws Exception {
        Settings s=defaults(); assertEquals("My Server",s.serverName()); assertEquals(86_400_000,s.promotionMillis()); assertTrue(s.tabEnabled()); assertTrue(s.sidebarEnabled()); assertTrue(s.importExisting()); assertTrue(s.ranks().get("owner").bold());
    }
    @Test void rejectsInvalidThresholdsAndBooleanStrings() throws Exception {
        for(Object value:List.of(0,-1,Double.NaN,Double.POSITIVE_INFINITY,1_000_001,"oops")) { var yaml=yaml();yaml.set("promotion.hours",value); assertThrows(IllegalArgumentException.class,()->Settings.read(yaml)); }
        var yaml=yaml();yaml.set("tab.enabled","true");assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
    }
    @Test void rejectsTooManyLinesAndNonTextLines() throws Exception {
        var yaml=yaml(); yaml.set("sidebar.lines",Collections.nCopies(16,"line")); assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
        yaml.set("sidebar.lines",List.of(123));assertThrows(IllegalArgumentException.class,()->Settings.read(yaml));
    }
    @Test void rejectsMissingRanksColorsAndUnknownPlaceholders() throws Exception {
        var yaml=yaml();yaml.set("ranks.owner",null);var missingYaml=yaml;assertThrows(IllegalArgumentException.class,()->Settings.read(missingYaml));
        yaml=yaml();yaml.set("ranks.owner.color","rainbow"); var colorYaml=yaml;assertThrows(IllegalArgumentException.class,()->Settings.read(colorYaml));
        yaml=yaml();yaml.set("tab.format","%player_typo%");var typoYaml=yaml;assertThrows(IllegalArgumentException.class,()->Settings.read(typoYaml));
    }
    @Test void allowsBlankAndDuplicateLinesAndFractionalHours() throws Exception {
        var yaml=yaml(); yaml.set("sidebar.lines",List.of("","","same","same"));yaml.set("promotion.hours",0.001);assertEquals(3600,Settings.read(yaml).promotionMillis());
    }
    @Test void rankValidationRejectsUnsafeIdsAndAcceptsHex() {
        assertThrows(IllegalArgumentException.class,()->new Rank("bad.id","Bad","red",false)); assertThrows(IllegalArgumentException.class,()->new Rank("bad","\n","red",false));
        assertEquals("#ff5555",new Rank("vip","VIP","#ff5555",false).color());
    }
}
