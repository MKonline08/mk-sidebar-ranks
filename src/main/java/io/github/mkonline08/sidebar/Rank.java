package io.github.mkonline08.sidebar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public record Rank(String id, String name, String color, boolean bold) {
    public Rank {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("Rank IDs must use lowercase letters, numbers, and underscores (1–32 characters).");
        if (name == null || name.isBlank() || name.length() > 48 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Rank names must contain 1–48 printable characters.");
        parseColor(color);
    }
    public static TextColor parseColor(String color) {
        if (color == null) throw new IllegalArgumentException("A rank color is required.");
        TextColor result = color.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(color) : NamedTextColor.NAMES.value(color);
        if (result == null) throw new IllegalArgumentException("Use a named color such as red or a hex color such as #ff5555.");
        return result;
    }
    public Component label() { return Component.text(name, parseColor(color)).decoration(TextDecoration.BOLD, bold); }
    public Component badge() { return Component.text("[" + name + "]", parseColor(color)).decoration(TextDecoration.BOLD, bold); }
}
