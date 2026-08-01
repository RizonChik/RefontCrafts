package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.WorkbenchRecipe;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.RecipeQuery;
import ru.refontstudio.refontcrafts.util.RecipeQuery.MatchMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-version workbench implementation. It intentionally does not register Bukkit recipes,
 * because keyed recipes and RecipeChoice do not exist on 1.8.8.
 */
public final class WorkbenchListener implements Listener {
    private final RefontCrafts plugin;
    private final Set<UUID> pendingRefresh = new HashSet<UUID>();

    public WorkbenchListener(RefontCrafts plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        Match match = findMatch(inventory.getMatrix());
        if (match == null) return;
        inventory.setResult(preview(match));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof CraftingInventory)) return;
        if (!isCraftingType(top.getType())) return;

        Player player = (Player) event.getWhoClicked();
        CraftingInventory inventory = (CraftingInventory) top;

        if (event.getRawSlot() == 0) {
            Match match = findMatch(inventory.getMatrix());
            if (match == null) return;
            event.setCancelled(true);
            if (!event.isLeftClick() && !event.isRightClick() && !event.isShiftClick()) return;
            craft(player, inventory, match, event.isShiftClick());
        }

        scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof CraftingInventory) || !isCraftingType(top.getType())) return;
        scheduleRefresh((Player) event.getWhoClicked());
    }

    private void craft(Player player, CraftingInventory inventory, Match original, boolean shift) {
        ItemStack[] matrix = inventory.getMatrix();
        Match match = findMatch(matrix);
        if (match == null || !match.recipe.id.equals(original.recipe.id)) {
            inventory.setResult(null);
            player.updateInventory();
            return;
        }

        ItemStack base = match.recipe.result.clone();
        int perSet = Math.max(1, base.getAmount());
        int sets;

        if (shift) {
            int capacity = capacityForItem(player.getInventory(), base);
            sets = Math.min(match.possibleSets, capacity / perSet);
            if (sets <= 0) {
                player.sendMessage(plugin.msg("no_inventory_space"));
                return;
            }
        } else {
            int previewSets = previewSets(match.possibleSets, perSet);
            ItemStack cursor = player.getItemOnCursor();
            int free;
            if (Compat.isAir(cursor)) {
                free = 127;
            } else if (cursor.isSimilar(base)) {
                free = Math.max(0, 127 - cursor.getAmount());
            } else {
                free = 0;
            }
            sets = Math.min(previewSets, free / perSet);
            if (sets <= 0) {
                player.sendMessage(plugin.msg("no_inventory_space"));
                return;
            }
        }

        ItemStack[] consumed = consume(match, matrix, sets);
        if (consumed == null) {
            inventory.setResult(null);
            player.updateInventory();
            return;
        }

        int total = perSet * sets;
        if (shift) {
            ItemStack output = base.clone();
            output.setAmount(total);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(output);
            if (!leftovers.isEmpty()) {
                // Capacity is calculated immediately before addItem, so this is only a defensive guard.
                for (ItemStack leftover : leftovers.values()) {
                    if (!Compat.isAir(leftover)) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        } else {
            ItemStack cursor = player.getItemOnCursor();
            if (Compat.isAir(cursor)) {
                ItemStack output = base.clone();
                output.setAmount(total);
                player.setItemOnCursor(output);
            } else {
                cursor.setAmount(cursor.getAmount() + total);
                player.setItemOnCursor(cursor);
            }
        }

        inventory.setMatrix(consumed);
        inventory.setResult(null);
        player.updateInventory();
    }

    private ItemStack preview(Match match) {
        ItemStack result = match.recipe.result.clone();
        int perSet = Math.max(1, result.getAmount());
        int sets = previewSets(match.possibleSets, perSet);
        result.setAmount(Compat.clientSafeAmount(perSet * sets));
        return result;
    }

    private int previewSets(int possible, int perSet) {
        int limit = Math.max(1, Math.min(127, plugin.craftPreviewLimit()));
        int byLimit = Math.max(1, limit / Math.max(1, perSet));
        return Math.max(1, Math.min(possible, byLimit));
    }

    private Match findMatch(ItemStack[] matrix) {
        if (matrix == null || plugin.storage() == null) return null;
        for (WorkbenchRecipe recipe : plugin.storage().getWorkbenchRecipes()) {
            if (recipe == null || recipe.result == null || recipe.ingredients == null) continue;
            if (recipe.shaped && recipe.ingredients.size() == 9 && plugin.workbenchStrictShape()) {
                int normal = possibleShaped(recipe.ingredients, matrix, false);
                if (normal > 0) return new Match(recipe, normal, false, true);
                if (plugin.workbenchAllowMirror()) {
                    int mirrored = possibleShaped(recipe.ingredients, matrix, true);
                    if (mirrored > 0) return new Match(recipe, mirrored, true, true);
                }
            } else {
                List<ItemStack> compact = compact(recipe.ingredients);
                int possible = possibleShapeless(compact, matrix);
                if (possible > 0) return new Match(recipe, possible, false, false);
            }
        }
        return null;
    }

    private int possibleShaped(List<ItemStack> requirements, ItemStack[] matrix, boolean mirrored) {
        if (matrix.length < 9) return 0;
        int possible = Integer.MAX_VALUE;
        boolean hasIngredient = false;
        for (int recipeIndex = 0; recipeIndex < 9; recipeIndex++) {
            int matrixIndex = mirrored ? mirrorIndex(recipeIndex) : recipeIndex;
            ItemStack need = requirements.get(recipeIndex);
            ItemStack have = matrix[matrixIndex];
            if (Compat.isAir(need)) {
                if (!Compat.isAir(have)) return 0;
                continue;
            }
            hasIngredient = true;
            if (Compat.isAir(have) || !matches(have, need)) return 0;
            int needAmount = Math.max(1, need.getAmount());
            possible = Math.min(possible, have.getAmount() / needAmount);
        }
        return !hasIngredient || possible == Integer.MAX_VALUE ? 0 : Math.max(0, possible);
    }

    private int possibleShapeless(List<ItemStack> requirements, ItemStack[] matrix) {
        if (requirements.isEmpty()) return 0;
        int[] left = amounts(matrix);
        int sets = 0;
        while (true) {
            int[] trial = left.clone();
            boolean success = true;
            for (ItemStack need : requirements) {
                int index = findMatchingSlot(need, matrix, trial);
                if (index < 0) {
                    success = false;
                    break;
                }
                trial[index] -= Math.max(1, need.getAmount());
            }
            if (!success) break;
            left = trial;
            sets++;
        }
        return sets;
    }

    private ItemStack[] consume(Match match, ItemStack[] matrix, int sets) {
        int[] left = amounts(matrix);
        if (match.shaped) {
            for (int recipeIndex = 0; recipeIndex < 9; recipeIndex++) {
                ItemStack need = match.recipe.ingredients.get(recipeIndex);
                if (Compat.isAir(need)) continue;
                int matrixIndex = match.mirrored ? mirrorIndex(recipeIndex) : recipeIndex;
                int amount = Math.max(1, need.getAmount()) * sets;
                if (matrixIndex >= left.length || left[matrixIndex] < amount) return null;
                left[matrixIndex] -= amount;
            }
        } else {
            List<ItemStack> requirements = compact(match.recipe.ingredients);
            for (int set = 0; set < sets; set++) {
                for (ItemStack need : requirements) {
                    int index = findMatchingSlot(need, matrix, left);
                    if (index < 0) return null;
                    left[index] -= Math.max(1, need.getAmount());
                }
            }
        }

        ItemStack[] result = new ItemStack[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            if (left[i] <= 0 || Compat.isAir(matrix[i])) {
                result[i] = null;
            } else {
                result[i] = matrix[i].clone();
                result[i].setAmount(left[i]);
            }
        }
        return result;
    }

    private int findMatchingSlot(ItemStack need, ItemStack[] matrix, int[] left) {
        int needAmount = Math.max(1, need.getAmount());
        for (int i = 0; i < matrix.length; i++) {
            if (left[i] < needAmount || Compat.isAir(matrix[i])) continue;
            if (matches(matrix[i], need)) return i;
        }
        return -1;
    }

    private boolean matches(ItemStack actual, ItemStack required) {
        if (plugin.exactMeta()) {
            ItemStack first = actual.clone();
            ItemStack second = required.clone();
            first.setAmount(1);
            second.setAmount(1);
            return first.isSimilar(second);
        }
        String first = RecipeQuery.signature(actual, plugin, MatchMode.WORKBENCH);
        String second = RecipeQuery.signature(required, plugin, MatchMode.WORKBENCH);
        return first != null && first.equals(second);
    }

    private List<ItemStack> compact(List<ItemStack> source) {
        List<ItemStack> result = new ArrayList<ItemStack>();
        if (source == null) return result;
        for (ItemStack item : source) {
            if (!Compat.isAir(item)) result.add(item.clone());
        }
        return result;
    }

    private int[] amounts(ItemStack[] matrix) {
        int[] amounts = new int[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            amounts[i] = Compat.isAir(matrix[i]) ? 0 : matrix[i].getAmount();
        }
        return amounts;
    }

    private int capacityForItem(PlayerInventory inventory, ItemStack sample) {
        int capacity = 0;
        int max = Math.max(1, sample.getMaxStackSize());
        for (ItemStack item : Compat.storageContents(inventory)) {
            if (Compat.isAir(item)) {
                capacity += max;
            } else if (item.isSimilar(sample)) {
                capacity += Math.max(0, max - item.getAmount());
            }
        }
        return capacity;
    }

    private int mirrorIndex(int index) {
        int row = index / 3;
        int column = index % 3;
        return row * 3 + (2 - column);
    }

    private boolean isCraftingType(InventoryType type) {
        return type == InventoryType.WORKBENCH || type == InventoryType.CRAFTING;
    }

    private void scheduleRefresh(final Player player) {
        if (player == null || !pendingRefresh.add(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                pendingRefresh.remove(player.getUniqueId());
                if (!player.isOnline()) return;
                Inventory top = player.getOpenInventory().getTopInventory();
                if (!(top instanceof CraftingInventory) || !isCraftingType(top.getType())) return;
                CraftingInventory crafting = (CraftingInventory) top;
                Match match = findMatch(crafting.getMatrix());
                if (match != null) crafting.setResult(preview(match));
                player.updateInventory();
            }
        });
    }

    private static final class Match {
        private final WorkbenchRecipe recipe;
        private final int possibleSets;
        private final boolean mirrored;
        private final boolean shaped;

        private Match(WorkbenchRecipe recipe, int possibleSets, boolean mirrored, boolean shaped) {
            this.recipe = recipe;
            this.possibleSets = possibleSets;
            this.mirrored = mirrored;
            this.shaped = shaped;
        }
    }
}
