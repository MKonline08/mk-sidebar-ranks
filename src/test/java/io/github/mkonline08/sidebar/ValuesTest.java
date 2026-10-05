package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValuesTest {
    @Test void formattingCoversPingBoundariesCompassAndUptime() {
        assertEquals(NamedTextColor.GREEN,Values.pingColor(99));assertEquals(NamedTextColor.YELLOW,Values.pingColor(100));assertEquals(NamedTextColor.YELLOW,Values.pingColor(199));assertEquals(NamedTextColor.RED,Values.pingColor(200));
        assertEquals("S",Values.direction(0));assertEquals("W",Values.direction(90));assertEquals("N",Values.direction(180));assertEquals("E",Values.direction(-90));assertEquals("SE",Values.direction(-45));assertEquals("S",Values.direction(360));
        assertEquals("1d 2h 3m 4s",Values.duration(93_784_000));assertEquals("0m 0s",Values.duration(0));assertEquals("18.0",Values.decimal(18));
    }
}
