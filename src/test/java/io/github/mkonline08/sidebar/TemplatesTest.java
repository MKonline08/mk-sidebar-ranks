package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplatesTest {
    @Test void supportsEveryOriginalPlaceholderAndPreservesLiteralPlayerText() {
        Map<String,Component> values=new HashMap<>(); Templates.KEYS.forEach(k->values.put(k,Component.text("value")));
        for(String key:Templates.KEYS) { Templates.validate("%"+key+"%"); assertEquals("value",plain(Templates.render("%"+key+"%",values))); }
        values.put("player_displayname",Component.text("<red>literal</red>"));
        assertEquals("<red>literal</red>",plain(Templates.render("%player_displayname%",values)));
    }
    @Test void preservesRankColorAndOuterTemplateStyle() {
        Rank owner=new Rank("owner","OWNER","red",true);
        Component rendered=Templates.render("<white>Rank: %player_rank%</white>",Map.of("player_rank",owner.label()));
        assertEquals("Rank: OWNER",plain(rendered)); assertEquals(NamedTextColor.RED,owner.label().color());
        assertTrue(rendered.toString().contains("red"));
    }
    @Test void rejectsUnknownTokensAndBrokenMiniMessage() {
        assertThrows(IllegalArgumentException.class,()->Templates.validate("%player_typo%"));
        assertThrows(RuntimeException.class,()->Templates.validate("<red>broken</blue>"));
    }
    static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }
}
