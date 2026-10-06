package io.github.mkonline08.sidebar;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    @Test void deniesRankAndReloadAdministrationWithoutPermission() {
        MKSidebarPlugin plugin=mock(MKSidebarPlugin.class);CommandSender sender=mock(CommandSender.class);Command rank=mock(Command.class);when(rank.getName()).thenReturn("mkrank");
        Commands commands=new Commands(plugin);commands.onCommand(sender,rank,"mkrank",new String[]{"set","Alex","owner"});verify(plugin,never()).assign(anyString(),anyString());
        commands.onCommand(sender,rank,"mkrank",new String[]{"testsound"});verify(plugin,never()).testSound();
        Command sidebar=mock(Command.class);when(sidebar.getName()).thenReturn("mksb");commands.onCommand(sender,sidebar,"mksb",new String[]{"reload"});verifyNoInteractions(plugin);
        assertTrue(commands.onTabComplete(sender,rank,"mkrank",new String[]{""}).isEmpty());
    }
    @Test void permitsSelfRankWithoutAdministrationAndProtectsSoundTesting() {
        MKSidebarPlugin plugin=mock(MKSidebarPlugin.class);Player p=mock(Player.class);when(p.hasPermission("mksidebar.rank")).thenReturn(true);Command rank=mock(Command.class);when(rank.getName()).thenReturn("rank");Commands commands=new Commands(plugin);
        commands.onCommand(p,rank,"rank",new String[]{});verify(plugin,times(1)).showRank(p);commands.onCommand(p,rank,"rank",new String[]{"Alex"});verify(plugin,times(1)).showRank(p);
        when(p.hasPermission("mksidebar.rank")).thenReturn(false);commands.onCommand(p,rank,"rank",new String[]{});verify(plugin,times(1)).showRank(p);
        CommandSender console=mock(CommandSender.class);commands.onCommand(console,rank,"rank",new String[]{});verify(plugin,times(1)).showRank(any());
        when(console.hasPermission("mksidebar.admin")).thenReturn(true);Command admin=mock(Command.class);when(admin.getName()).thenReturn("mkrank");when(plugin.testSound()).thenReturn(3);
        commands.onCommand(console,admin,"mkrank",new String[]{"testsound"});verify(plugin,times(1)).testSound();commands.onCommand(console,admin,"mkrank",new String[]{"testsound","Alex"});verify(plugin,times(1)).testSound();
        assertEquals(java.util.List.of("testsound"),commands.onTabComplete(console,admin,"mkrank",new String[]{"test"}));assertTrue(commands.onTabComplete(p,rank,"rank",new String[]{""}).isEmpty());
    }
    @Test void permitsPersonalToggleAndRejectsInvalidCommandShapes() {
        MKSidebarPlugin plugin=mock(MKSidebarPlugin.class);Player p=mock(Player.class);when(p.hasPermission("mksidebar.toggle")).thenReturn(true);Command sidebar=mock(Command.class);when(sidebar.getName()).thenReturn("mksb");
        Commands commands=new Commands(plugin);commands.onCommand(p,sidebar,"mksb",new String[]{"toggle"});verify(plugin).toggle(p);
        CommandSender admin=mock(CommandSender.class);when(admin.hasPermission("mksidebar.admin")).thenReturn(true);Command rank=mock(Command.class);when(rank.getName()).thenReturn("mkrank");
        commands.onCommand(admin,rank,"mkrank",new String[]{"set","Alex"});verify(plugin,never()).assign(anyString(),anyString());
    }
}
