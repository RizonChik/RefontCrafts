package ru.refontstudio.refontcrafts.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import ru.refontstudio.refontcrafts.RefontCrafts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeQuery {
    public enum MatchMode {
        WORKBENCH,
        ANVIL
    }

    private RecipeQuery() {
    }

    public static Map<String, Integer> inventoryCounts(PlayerInventory inventory, RefontCrafts plugin, MatchMode mode) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (inventory == null) return counts;
        for (ItemStack stack : Compat.storageContents(inventory)) {
            add(counts, signature(stack, plugin, mode), stack == null ? 0 : stack.getAmount());
        }
        return counts;
    }

    public static Map<String, Integer> requirementCounts(Iterable<ItemStack> items, RefontCrafts plugin, MatchMode mode) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (items == null) return counts;
        for (ItemStack stack : items) {
            if (Compat.isAir(stack)) continue;
            add(counts, signature(stack, plugin, mode), Math.max(1, stack.getAmount()));
        }
        return counts;
    }

    public static int craftSets(Map<String, Integer> inventory, Map<String, Integer> requirements) {
        if (requirements == null || requirements.isEmpty()) return 0;
        int sets = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : requirements.entrySet()) {
            if (entry.getKey() == null) return 0;
            Integer value = inventory == null ? null : inventory.get(entry.getKey());
            int have = value == null ? 0 : value;
            int need = Math.max(1, entry.getValue() == null ? 1 : entry.getValue());
            sets = Math.min(sets, have / need);
        }
        return sets == Integer.MAX_VALUE ? 0 : Math.max(0, sets);
    }

    public static String signature(ItemStack item, RefontCrafts plugin, MatchMode mode) {
        if (Compat.isAir(item)) return null;

        if (plugin.exactMeta()) {
            ItemStack copy = item.clone();
            copy.setAmount(1);
            return ItemCodec.formatString(copy);
        }

        StringBuilder out = new StringBuilder(item.getType().name());
        out.append("|data=").append(item.getDurability());
        if (Compat.materialNameContains(item, "POTION")) {
            out.append("|potion=").append(Compat.potionSignature(item));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) out.append("|name=").append(escape(meta.getDisplayName()));
            if (meta.hasLore() && meta.getLore() != null) {
                out.append("|lore=");
                for (String line : meta.getLore()) out.append(escape(line)).append('\u001f');
            }
            Integer customModelData = Compat.customModelData(meta);
            if (customModelData != null) out.append("|cmd=").append(customModelData.intValue());
            if (Compat.isUnbreakable(meta)) out.append("|unbreakable=true");

            Map<Enchantment, Integer> enchants = new LinkedHashMap<Enchantment, Integer>();
            if (meta.getEnchants() != null) enchants.putAll(meta.getEnchants());
            if (meta instanceof EnchantmentStorageMeta) {
                Map<Enchantment, Integer> stored = ((EnchantmentStorageMeta) meta).getStoredEnchants();
                if (stored != null) enchants.putAll(stored);
            }
            if (!enchants.isEmpty()) {
                List<Map.Entry<Enchantment, Integer>> sorted = new ArrayList<Map.Entry<Enchantment, Integer>>(enchants.entrySet());
                Collections.sort(sorted, new Comparator<Map.Entry<Enchantment, Integer>>() {
                    @Override
                    public int compare(Map.Entry<Enchantment, Integer> first, Map.Entry<Enchantment, Integer> second) {
                        return enchantName(first.getKey()).compareTo(enchantName(second.getKey()));
                    }
                });
                out.append("|ench=");
                for (Map.Entry<Enchantment, Integer> entry : sorted) {
                    out.append(enchantName(entry.getKey())).append(':').append(entry.getValue()).append(',');
                }
            }
        }

        return out.toString();
    }

    public static ItemStack cloneOne(ItemStack item) {
        if (item == null) return null;
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    private static void add(Map<String, Integer> map, String key, int amount) {
        if (key == null || amount <= 0) return;
        Integer current = map.get(key);
        map.put(key, (current == null ? 0 : current) + amount);
    }

    private static String enchantName(Enchantment enchantment) {
        if (enchantment == null) return "unknown";
        try {
            String name = enchantment.getName();
            if (name != null) return name;
        } catch (Throwable ignored) {
        }
        return enchantment.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\u001f", "\\u001f");
    }
}
