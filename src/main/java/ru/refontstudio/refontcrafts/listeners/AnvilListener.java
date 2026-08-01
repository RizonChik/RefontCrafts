package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.RecipeQuery;
import ru.refontstudio.refontcrafts.util.RecipeQuery.MatchMode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Manual anvil implementation that avoids PrepareAnvilEvent and PDC for Bukkit 1.8.8 compatibility. */
public class AnvilListener implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<UUID, ActiveResult> activeResults = new HashMap<UUID, ActiveResult>();

    public AnvilListener(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        if (event.getInventory().getType() != InventoryType.ANVIL) return;
        scheduleRefresh((Player) event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof AnvilInventory) || top.getType() != InventoryType.ANVIL) return;

        Player player = (Player) event.getWhoClicked();
        AnvilInventory inventory = (AnvilInventory) top;
        if (event.getRawSlot() == 2) {
            Match match = findMatch(inventory.getItem(0), inventory.getItem(1), player);
            if (match != null) {
                event.setCancelled(true);
                if (event.isLeftClick() || event.isRightClick() || event.isShiftClick()) {
                    takeResult(player, inventory, match, event.isShiftClick());
                }
            }
        }
        scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top instanceof AnvilInventory) || top.getType() != InventoryType.ANVIL) return;
        scheduleRefresh((Player) event.getWhoClicked());
    }

    private void takeResult(Player player, AnvilInventory inventory, Match match, boolean shift) {
        ItemStack left = inventory.getItem(0);
        ItemStack right = inventory.getItem(1);
        Match fresh = findMatch(left, right, player);
        if (fresh == null || !fresh.recipe.id.equals(match.recipe.id)) return;

        int perSet = Math.max(1, fresh.recipe.result.getAmount());
        int sets;
        if (shift) {
            int capacity = capacity(player, fresh.recipe.result);
            sets = Math.min(fresh.possibleSets, capacity / perSet);
        } else {
            ItemStack cursor = player.getItemOnCursor();
            int free;
            if (Compat.isAir(cursor)) free = 127;
            else if (cursor.isSimilar(fresh.recipe.result)) free = Math.max(0, 127 - cursor.getAmount());
            else free = 0;
            sets = Math.min(previewSets(fresh.possibleSets, perSet), free / perSet);
        }
        if (sets <= 0) {
            player.sendMessage(plugin.msg("no_inventory_space"));
            return;
        }

        int cost = Math.max(0, fresh.recipe.cost) * sets;
        if (cost > player.getLevel()) {
            player.sendMessage(plugin.msg("not_enough_levels", "cost", String.valueOf(cost)));
            return;
        }

        int total = perSet * sets;
        ItemStack output = fresh.recipe.result.clone();
        output.setAmount(total);
        if (shift) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(output);
            if (!leftovers.isEmpty()) {
                for (ItemStack item : leftovers.values()) {
                    if (!Compat.isAir(item)) player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
        } else {
            ItemStack cursor = player.getItemOnCursor();
            if (Compat.isAir(cursor)) player.setItemOnCursor(output);
            else {
                cursor.setAmount(cursor.getAmount() + total);
                player.setItemOnCursor(cursor);
            }
        }

        inventory.setItem(0, subtract(left, Math.max(1, fresh.recipe.left.getAmount()) * sets));
        inventory.setItem(1, subtract(right, Math.max(1, fresh.recipe.right.getAmount()) * sets));
        inventory.setItem(2, null);
        Compat.setAnvilRepairCost(inventory, 0);
        if (cost > 0) player.setLevel(Math.max(0, player.getLevel() - cost));
        activeResults.remove(player.getUniqueId());
        player.updateInventory();
    }

    private void refresh(Player player) {
        if (player == null || !player.isOnline()) return;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top instanceof AnvilInventory) || top.getType() != InventoryType.ANVIL) return;
        AnvilInventory inventory = (AnvilInventory) top;
        Match match = findMatch(inventory.getItem(0), inventory.getItem(1), player);
        if (match == null) {
            ActiveResult old = activeResults.remove(player.getUniqueId());
            ItemStack current = inventory.getItem(2);
            if (old != null && !Compat.isAir(current) && current.isSimilar(old.preview)) {
                inventory.setItem(2, null);
                Compat.setAnvilRepairCost(inventory, 0);
                player.updateInventory();
            }
            return;
        }

        ItemStack result = match.recipe.result.clone();
        int perSet = Math.max(1, result.getAmount());
        int sets = previewSets(match.possibleSets, perSet);
        result.setAmount(Compat.clientSafeAmount(perSet * sets));
        inventory.setItem(2, result);
        Compat.setAnvilRepairCost(inventory, Math.max(0, match.recipe.cost * sets));
        activeResults.put(player.getUniqueId(), new ActiveResult(match.recipe.id, result.clone()));
        player.updateInventory();
    }

    private Match findMatch(ItemStack left, ItemStack right, Player player) {
        if (Compat.isAir(left) || Compat.isAir(right)) return null;
        for (AnvilRecipe recipe : storage.getAnvilRecipes()) {
            if (!matches(left, recipe.left) || !matches(right, recipe.right)) continue;
            int possible = Math.min(
                    left.getAmount() / Math.max(1, recipe.left.getAmount()),
                    right.getAmount() / Math.max(1, recipe.right.getAmount()));
            if (possible > 0) return new Match(recipe, possible);
        }
        return null;
    }

    private boolean matches(ItemStack actual, ItemStack required) {
        if (plugin.exactMeta()) {
            ItemStack first = actual.clone();
            ItemStack second = required.clone();
            first.setAmount(1);
            second.setAmount(1);
            return first.isSimilar(second);
        }
        String first = RecipeQuery.signature(actual, plugin, MatchMode.ANVIL);
        String second = RecipeQuery.signature(required, plugin, MatchMode.ANVIL);
        return first != null && first.equals(second);
    }

    private int previewSets(int possible, int perSet) {
        int limit = Math.max(1, Math.min(127, plugin.craftPreviewLimit()));
        return Math.max(1, Math.min(possible, Math.max(1, limit / Math.max(1, perSet))));
    }

    private int capacity(Player player, ItemStack sample) {
        int capacity = 0;
        int max = Math.max(1, sample.getMaxStackSize());
        for (ItemStack item : Compat.storageContents(player.getInventory())) {
            if (Compat.isAir(item)) capacity += max;
            else if (item.isSimilar(sample)) capacity += Math.max(0, max - item.getAmount());
        }
        return capacity;
    }

    private ItemStack subtract(ItemStack item, int amount) {
        if (Compat.isAir(item) || item.getAmount() <= amount) return null;
        ItemStack result = item.clone();
        result.setAmount(item.getAmount() - amount);
        return result;
    }

    private void scheduleRefresh(final Player player) {
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                refresh(player);
            }
        });
    }

    private static final class Match {
        private final AnvilRecipe recipe;
        private final int possibleSets;

        private Match(AnvilRecipe recipe, int possibleSets) {
            this.recipe = recipe;
            this.possibleSets = possibleSets;
        }
    }

    private static final class ActiveResult {
        private final String recipeId;
        private final ItemStack preview;

        private ActiveResult(String recipeId, ItemStack preview) {
            this.recipeId = recipeId;
            this.preview = preview;
        }
    }
}
