package ru.refontstudio.refontcrafts.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;

public class ItemCodec {
    private static final String PREFIX = "B64:";

    public static ItemStack parseString(String s) {
        if (s == null || s.trim().isEmpty()) return new ItemStack(Material.AIR);
        String t = s.trim();
        if (t.startsWith(PREFIX)) {
            try {
                byte[] data = Base64.getDecoder().decode(t.substring(PREFIX.length()));
                ByteArrayInputStream bis = new ByteArrayInputStream(data);
                BukkitObjectInputStream in = new BukkitObjectInputStream(bis);
                Object obj = in.readObject();
                in.close();
                if (obj instanceof ItemStack) return (ItemStack) obj;
            } catch (Throwable ignored) {}
        }
        String[] p = t.split("[: ]");
        String name = p[0].trim().toUpperCase(Locale.ROOT);
        int amount = 1;
        if (p.length > 1) {
            try { amount = Math.max(1, Integer.parseInt(p[1])); } catch (Throwable ignored) {}
        }
        Material m;
        try { m = Material.valueOf(name); } catch (IllegalArgumentException ex) { m = null; }
        if (Compat.isAir(m)) return new ItemStack(Material.AIR);
        return new ItemStack(m, amount);
    }

    public static String formatString(ItemStack it) {
        if (Compat.isAir(it)) return "AIR:1";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos);
            out.writeObject(it);
            out.close();
            return PREFIX + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable ignored) {}
        return it.getType().name() + ":" + Math.max(1, it.getAmount());
    }

    public static ItemStack parseSection(ConfigurationSection s) {
        if (s == null) return new ItemStack(Material.AIR);
        String type = s.getString("type", "AIR");
        int amount = Math.max(1, s.getInt("amount", 1));
        Material m;
        try { m = Material.valueOf(type.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { m = null; }
        if (Compat.isAir(m)) return new ItemStack(Material.AIR);
        return new ItemStack(m, amount);
    }
}