package ru.refontstudio.refontcrafts.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ItemUtil {
    private ItemUtil() {
    }

    public static ItemStack named(Material material, String name, String... lore) {
        return named(new ItemStack(material), name, lore);
    }

    public static ItemStack named(ItemStack template, String name, String... lore) {
        ItemStack item = template == null ? new ItemStack(Material.STONE) : template.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(Text.color(name));
        if (lore != null && lore.length > 0) {
            meta.setLore(colorLines(new ArrayList<String>(Arrays.asList(lore))));
        }
        Compat.hideStandardFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    public static List<String> colorLines(List<String> lines) {
        if (lines == null) return new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) lines.set(i, Text.color(lines.get(i)));
        return lines;
    }

    public static boolean similarType(ItemStack first, ItemStack second) {
        if (Compat.isAir(first) || Compat.isAir(second)) return false;
        return first.getType() == second.getType();
    }

    public static boolean similarExact(ItemStack first, ItemStack second) {
        if (!similarType(first, second)) return false;
        ItemStack a = first.clone();
        ItemStack b = second.clone();
        a.setAmount(1);
        b.setAmount(1);
        return a.isSimilar(b);
    }

    public static ItemStack cloneWithAmount(ItemStack item, int amount) {
        if (item == null) return new ItemStack(Material.AIR);
        ItemStack copy = item.clone();
        copy.setAmount(Math.max(0, amount));
        return copy;
    }
}
