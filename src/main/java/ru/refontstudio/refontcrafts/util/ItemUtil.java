package ru.refontstudio.refontcrafts.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return similarVisual(actual, recipe);
    }
    public static boolean similarVisual(ItemStack a, ItemStack b) {
        if (!similarType(a, b)) return false;
        if (isPotion(a.getType()) && isPotion(b.getType()) && !potionBaseEquals(a, b)) return false;
        if (a.hasItemMeta() != b.hasItemMeta()) return false;
        if (!a.hasItemMeta()) return true;

        ItemMeta am = a.getItemMeta();
        ItemMeta bm = b.getItemMeta();
        if (am == null || bm == null) return am == bm;
        if (am.hasDisplayName() != bm.hasDisplayName()) return false;
        if (am.hasDisplayName() && !Objects.equals(am.getDisplayName(), bm.getDisplayName())) return false;
        if (am.hasLore() != bm.hasLore()) return false;
        if (am.hasLore() && !Objects.equals(am.getLore(), bm.getLore())) return false;
        if (am.hasCustomModelData() != bm.hasCustomModelData()) return false;
        if (am.hasCustomModelData() && am.getCustomModelData() != bm.getCustomModelData()) return false;
        if (am.isUnbreakable() != bm.isUnbreakable()) return false;
        if (!Objects.equals(am.getEnchants(), bm.getEnchants())) return false;
        if (!Objects.equals(am.getItemFlags(), bm.getItemFlags())) return false;
        if (!Objects.equals(invokeNoArg(am, "getAttributeModifiers"), invokeNoArg(bm, "getAttributeModifiers"))) return false;
        if (!sameOptionalValue(am, bm, "hasDamage", "getDamage")) return false;
        if (!sameOptionalValue(am, bm, "hasRepairCost", "getRepairCost")) return false;
        if (!sameOptionalValue(am, bm, "hasItemName", "getItemName")) return false;
        if (!sameOptionalValue(am, bm, "hasLocalizedName", "getLocalizedName")) return false;
        if (!Objects.equals(skullOwnerValue(am), skullOwnerValue(bm))) return false;
        return Objects.equals(pdcValues(am), pdcValues(bm));
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
    private static boolean sameOptionalValue(ItemMeta a, ItemMeta b, String hasMethod, String getMethod) {
        Object ah = invokeNoArg(a, hasMethod);
        Object bh = invokeNoArg(b, hasMethod);
        if (ah instanceof Boolean || bh instanceof Boolean) {
            if (!Objects.equals(ah, bh)) return false;
            return !Boolean.TRUE.equals(ah) || Objects.equals(invokeNoArg(a, getMethod), invokeNoArg(b, getMethod));
        }
        return Objects.equals(invokeNoArg(a, getMethod), invokeNoArg(b, getMethod));
    }
    private static Object skullOwnerValue(ItemMeta meta) {
        Object profile = firstNotNull(
                invokeNoArg(meta, "getPlayerProfile"),
                invokeNoArg(meta, "getOwnerProfile"),
                invokeNoArg(meta, "getOwningPlayer"),
                invokeNoArg(meta, "getOwner")
        );
        if (profile == null) return null;

        Object id = firstNotNull(invokeNoArg(profile, "getId"), invokeNoArg(profile, "getUniqueId"));
        Object name = invokeNoArg(profile, "getName");
        Object properties = invokeNoArg(profile, "getProperties");
        if (id != null || name != null || properties != null) {
            return String.valueOf(id) + "|" + String.valueOf(name) + "|" + String.valueOf(properties);
        }
        return String.valueOf(profile);
    }
    private static Object firstNotNull(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }
    private static Map<String, String> pdcValues(ItemMeta meta) {
        Map<String, String> out = new HashMap<>();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            String name = key.toString();
            if (ignoredPdcKey(name)) continue;
            out.put(name, pdcValue(pdc, key));
        }
        return out;
    }
    private static boolean ignoredPdcKey(String key) {
        return "executableitems:ei-disablestack".equals(key) || "refontcrafts:rc_ghost".equals(key);
    }
    private static String pdcValue(PersistentDataContainer pdc, NamespacedKey key) {
        if (pdc.has(key, PersistentDataType.STRING)) return "STRING:" + pdc.get(key, PersistentDataType.STRING);
        if (pdc.has(key, PersistentDataType.INTEGER)) return "INTEGER:" + pdc.get(key, PersistentDataType.INTEGER);
        if (pdc.has(key, PersistentDataType.LONG)) return "LONG:" + pdc.get(key, PersistentDataType.LONG);
        if (pdc.has(key, PersistentDataType.DOUBLE)) return "DOUBLE:" + pdc.get(key, PersistentDataType.DOUBLE);
        if (pdc.has(key, PersistentDataType.FLOAT)) return "FLOAT:" + pdc.get(key, PersistentDataType.FLOAT);
        if (pdc.has(key, PersistentDataType.SHORT)) return "SHORT:" + pdc.get(key, PersistentDataType.SHORT);
        if (pdc.has(key, PersistentDataType.BYTE)) return "BYTE:" + pdc.get(key, PersistentDataType.BYTE);
        if (pdc.has(key, PersistentDataType.BYTE_ARRAY)) return "BYTE_ARRAY:" + Arrays.toString(pdc.get(key, PersistentDataType.BYTE_ARRAY));
        if (pdc.has(key, PersistentDataType.INTEGER_ARRAY)) return "INTEGER_ARRAY:" + Arrays.toString(pdc.get(key, PersistentDataType.INTEGER_ARRAY));
        if (pdc.has(key, PersistentDataType.LONG_ARRAY)) return "LONG_ARRAY:" + Arrays.toString(pdc.get(key, PersistentDataType.LONG_ARRAY));
        return "PRESENT";
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