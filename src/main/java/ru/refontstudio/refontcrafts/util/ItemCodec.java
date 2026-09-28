package ru.refontstudio.refontcrafts.util;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public class ItemCodec {
    public static ItemStack parseString(String value) {
        return YouerItemCodec.parseString(value);
    }

    public static String formatString(ItemStack item) {
        return YouerItemCodec.formatString(item);
    }

    public static ItemStack parseSection(ConfigurationSection section) {
        if (section == null) return new ItemStack(Material.AIR);
        String type = section.getString("type", "AIR");
        int amount = Math.max(1, section.getInt("amount", 1));
        Material material;
        try {
            material = Material.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            material = null;
        }
        if (Compat.isAir(material)) return new ItemStack(Material.AIR);
        return new ItemStack(material, amount);
    }
}
