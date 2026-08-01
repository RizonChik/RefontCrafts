package ru.refontstudio.refontcrafts.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Binary compatibility helpers. This class is compiled against Bukkit 1.8.8 and
 * accesses newer API only through reflection.
 */
public final class Compat {
    private static final int[] VERSION = detectVersion();

    private Compat() {
    }

    public static int minecraftMajor() {
        return VERSION[0];
    }

    public static int minecraftMinor() {
        return VERSION[1];
    }

    public static boolean isAtLeast(int major, int minor) {
        return VERSION[0] > major || (VERSION[0] == major && VERSION[1] >= minor);
    }

    public static boolean supportsHexColors() {
        return isAtLeast(1, 16) || VERSION[0] >= 16;
    }

    public static boolean isAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    public static boolean isAir(Material material) {
        return material == null || material == Material.AIR;
    }

    public static Material material(String modernName, String legacyName) {
        Material modern = match(modernName);
        if (modern != null) return modern;
        Material legacy = match(legacyName);
        return legacy == null ? Material.STONE : legacy;
    }

    public static ItemStack item(String modernName, String legacyName, int legacyData) {
        Material modern = match(modernName);
        if (modern != null) return new ItemStack(modern, 1);

        Material legacy = match(legacyName);
        if (legacy == null) return new ItemStack(Material.STONE, 1);
        return new ItemStack(legacy, 1, (short) Math.max(0, legacyData));
    }

    public static ItemStack grayPane() {
        return item("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE", 7);
    }

    public static ItemStack blackPane() {
        return item("BLACK_STAINED_GLASS_PANE", "STAINED_GLASS_PANE", 15);
    }

    public static ItemStack limeWool() {
        return item("LIME_WOOL", "WOOL", 5);
    }

    public static ItemStack yellowWool() {
        return item("YELLOW_WOOL", "WOOL", 4);
    }

    public static ItemStack knowledgeBook() {
        return item("KNOWLEDGE_BOOK", "BOOK", 0);
    }

    public static ItemStack experienceBottle() {
        return item("EXPERIENCE_BOTTLE", "EXP_BOTTLE", 0);
    }

    public static Material craftingTable() {
        return material("CRAFTING_TABLE", "WORKBENCH");
    }

    public static ItemStack[] storageContents(PlayerInventory inventory) {
        if (inventory == null) return new ItemStack[0];
        try {
            Method method = inventory.getClass().getMethod("getStorageContents");
            Object value = method.invoke(inventory);
            if (value instanceof ItemStack[]) return (ItemStack[]) value;
        } catch (Throwable ignored) {
        }
        ItemStack[] contents = inventory.getContents();
        return contents == null ? new ItemStack[0] : contents;
    }

    public static Integer customModelData(ItemMeta meta) {
        if (meta == null) return null;
        try {
            Method has = meta.getClass().getMethod("hasCustomModelData");
            Object present = has.invoke(meta);
            if (!(present instanceof Boolean) || !((Boolean) present)) return null;
            Method get = meta.getClass().getMethod("getCustomModelData");
            Object value = get.invoke(meta);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isUnbreakable(ItemMeta meta) {
        if (meta == null) return false;
        try {
            Method method = meta.getClass().getMethod("isUnbreakable");
            Object value = method.invoke(meta);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Throwable ignored) {
        }
        try {
            Method spigotMethod = meta.getClass().getMethod("spigot");
            Object spigot = spigotMethod.invoke(meta);
            Method method = spigot.getClass().getMethod("isUnbreakable");
            Object value = method.invoke(spigot);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String potionSignature(ItemStack item) {
        if (isAir(item)) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                Method getType = meta.getClass().getMethod("getBasePotionType");
                Object type = getType.invoke(meta);
                if (type != null) return "type=" + String.valueOf(type);
            } catch (Throwable ignored) {
            }
            try {
                Method getData = meta.getClass().getMethod("getBasePotionData");
                Object data = getData.invoke(meta);
                if (data != null) {
                    Method getType = data.getClass().getMethod("getType");
                    Method isExtended = data.getClass().getMethod("isExtended");
                    Method isUpgraded = data.getClass().getMethod("isUpgraded");
                    return "type=" + String.valueOf(getType.invoke(data))
                            + ",extended=" + String.valueOf(isExtended.invoke(data))
                            + ",upgraded=" + String.valueOf(isUpgraded.invoke(data));
                }
            } catch (Throwable ignored) {
            }
        }
        return "durability=" + item.getDurability();
    }

    public static void setAnvilRepairCost(Object inventory, int cost) {
        if (inventory == null) return;
        try {
            Method method = inventory.getClass().getMethod("setRepairCost", int.class);
            method.invoke(inventory, Math.max(0, cost));
        } catch (Throwable ignored) {
        }
    }

    public static void hideStandardFlags(ItemMeta meta) {
        if (meta == null) return;
        try {
            Class<?> flagClass = Class.forName("org.bukkit.inventory.ItemFlag");
            List<Object> values = new ArrayList<Object>();
            addEnum(values, flagClass, "HIDE_ATTRIBUTES");
            addEnum(values, flagClass, "HIDE_ENCHANTS");
            addEnum(values, flagClass, "HIDE_UNBREAKABLE");
            addEnum(values, flagClass, "HIDE_POTION_EFFECTS");
            if (values.isEmpty()) return;

            Object flags = Array.newInstance(flagClass, values.size());
            for (int i = 0; i < values.size(); i++) Array.set(flags, i, values.get(i));
            Method addFlags = meta.getClass().getMethod("addItemFlags", flags.getClass());
            addFlags.invoke(meta, flags);
        } catch (Throwable ignored) {
        }
    }

    public static String inventoryTitle(String title) {
        String value = title == null ? "" : title;
        if (isAtLeast(1, 14) || VERSION[0] >= 14) return value;
        if (value.length() <= 32) return value;
        String cut = value.substring(0, 32);
        if (cut.endsWith("§")) cut = cut.substring(0, cut.length() - 1);
        return cut;
    }

    public static int clientSafeAmount(int amount) {
        return Math.max(1, Math.min(127, amount));
    }

    public static boolean materialNameContains(ItemStack item, String token) {
        if (isAir(item) || token == null) return false;
        return item.getType().name().toUpperCase(Locale.ROOT).contains(token.toUpperCase(Locale.ROOT));
    }

    private static Material match(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try {
            Material material = Material.matchMaterial(name);
            if (material != null) return material;
        } catch (Throwable ignored) {
        }
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addEnum(List<Object> values, Class<?> enumClass, String name) {
        try {
            values.add(Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name));
        } catch (Throwable ignored) {
        }
    }

    private static int[] detectVersion() {
        String raw;
        try {
            raw = Bukkit.getBukkitVersion();
        } catch (Throwable ignored) {
            raw = "1.8";
        }
        if (raw == null) raw = "1.8";
        String base = raw.split("-")[0];
        String[] parts = base.split("\\.");
        int major = parse(parts, 0, 1);
        int minor = parse(parts, 1, 8);
        return new int[]{major, minor};
    }

    private static int parse(String[] parts, int index, int fallback) {
        if (parts == null || index >= parts.length) return fallback;
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
