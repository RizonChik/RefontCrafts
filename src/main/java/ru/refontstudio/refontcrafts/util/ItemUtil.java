package ru.refontstudio.refontcrafts.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class ItemUtil {
    public static ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        im.setDisplayName(Text.color(name));
        if (lore != null && lore.length > 0) im.setLore(colorLines(Arrays.asList(lore)));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(im);
        return it;
    }
    public static List<String> colorLines(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) lines.set(i, Text.color(lines.get(i)));
        return lines;
    }
    public static boolean similarType(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() == Material.AIR || b.getType() == Material.AIR) return false;
        return a.getType() == b.getType();
    }
    public static boolean similarExact(ItemStack a, ItemStack b) {
        if (!similarType(a, b)) return false;
        ItemStack aa = cloneWithAmount(a, 1);
        ItemStack bb = cloneWithAmount(b, 1);

        String sigA = fullItemSignature(aa);
        String sigB = fullItemSignature(bb);
        if (sigA != null && sigB != null) return sigA.equals(sigB);

        return aa.isSimilar(bb);
    }
    public static boolean matchesIngredient(ItemStack actual, ItemStack recipe, boolean exact) {
        if (exact) return similarExact(actual, recipe);
        if (actual == null || recipe == null) return false;
        if (isPotion(actual.getType()) && isPotion(recipe.getType())) return potionBaseEquals(actual, recipe);
        return similarType(actual, recipe);
    }
    public static ItemStack cloneWithAmount(ItemStack it, int amount) {
        ItemStack c = it.clone();
        c.setAmount(amount);
        return c;
    }
    private static boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }
    private static boolean potionBaseEquals(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (!isPotion(a.getType()) || !isPotion(b.getType())) return false;
        if (a.getType() != b.getType()) return false;
        ItemMeta am = a.getItemMeta();
        ItemMeta bm = b.getItemMeta();
        if (!(am instanceof PotionMeta) || !(bm instanceof PotionMeta)) return false;

        Object av = potionBaseValue((PotionMeta) am);
        Object bv = potionBaseValue((PotionMeta) bm);
        return av != null && av.equals(bv);
    }
    private static Object potionBaseValue(PotionMeta meta) {
        Object data = invokeNoArg(meta, "getBasePotionData");
        if (data != null) {
            Object type = invokeNoArg(data, "getType");
            Object extended = invokeNoArg(data, "isExtended");
            Object upgraded = invokeNoArg(data, "isUpgraded");
            return String.valueOf(type) + "|" + extended + "|" + upgraded;
        }

        Object type = invokeNoArg(meta, "getBasePotionType");
        return type == null ? null : String.valueOf(type);
    }
    private static Object invokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static String fullItemSignature(ItemStack item) {
        String nms = nmsSignature(item);
        if (nms != null) return nms;

        String bytes = bukkitBytesSignature(item);
        if (bytes != null) return bytes;

        return bukkitObjectSignature(item);
    }
    private static String bukkitBytesSignature(ItemStack item) {
        try {
            Method m = ItemStack.class.getMethod("serializeAsBytes");
            byte[] data = (byte[]) m.invoke(item);
            return "BYTES:" + Base64.getEncoder().encodeToString(data);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static String bukkitObjectSignature(ItemStack item) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos);
            out.writeObject(item);
            out.close();
            return "OBJ:" + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static String nmsSignature(ItemStack item) {
        try {
            String pkg = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftItemStack = Class.forName(pkg + ".inventory.CraftItemStack");
            Object nms = craftItemStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            if (nms == null) return null;

            String version = pkg.substring(pkg.lastIndexOf('.') + 1);
            Class<?> tagClass = Class.forName("net.minecraft.server." + version + ".NBTTagCompound");
            Object tag = tagClass.getDeclaredConstructor().newInstance();
            Object saved = nms.getClass().getMethod("save", tagClass).invoke(nms, tag);
            return "NMS:" + String.valueOf(saved);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
