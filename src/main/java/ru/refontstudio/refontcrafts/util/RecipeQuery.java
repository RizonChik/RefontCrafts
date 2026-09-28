package ru.refontstudio.refontcrafts.util;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.refontstudio.refontcrafts.RefontCrafts;

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
        return YouerCompat.signature(item, plugin, mode);
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
}
