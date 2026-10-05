package io.github.mkonline08.sidebar;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.file.YamlConfiguration;

public record PromotionSound(boolean enabled, String key, float volume, float pitch) {
    static PromotionSound read(YamlConfiguration yaml) {
        if (yaml.contains("promotion.sound.enabled") && !yaml.isBoolean("promotion.sound.enabled")) throw new IllegalArgumentException("promotion.sound.enabled must be true or false.");
        if(yaml.contains("promotion.sound.key") && !yaml.isString("promotion.sound.key")) throw new IllegalArgumentException("promotion.sound.key must be a Minecraft sound key.");
        String key = yaml.getString("promotion.sound.key", "minecraft:ui.toast.challenge_complete");
        try { Key.key(key); } catch(net.kyori.adventure.key.InvalidKeyException e) { throw new IllegalArgumentException("promotion.sound.key must be a valid key such as minecraft:ui.toast.challenge_complete.",e); }
        double volume = yaml.getDouble("promotion.sound.volume", 0.7), pitch = yaml.getDouble("promotion.sound.pitch", 1.0);
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) throw new IllegalArgumentException("promotion.sound.volume must be between 0 and 1.");
        if (!Double.isFinite(pitch) || pitch < 0.5 || pitch > 2) throw new IllegalArgumentException("promotion.sound.pitch must be between 0.5 and 2.");
        for (String field : new String[]{"volume", "pitch"}) if (yaml.contains("promotion.sound."+field) && !(yaml.get("promotion.sound."+field) instanceof Number)) throw new IllegalArgumentException("promotion.sound."+field+" must be a number.");
        return new PromotionSound(yaml.getBoolean("promotion.sound.enabled", true), key, (float)volume, (float)pitch);
    }
    public Sound sound() { return Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch); }
}
