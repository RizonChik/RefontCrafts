package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.RecipeQuery;
import ru.refontstudio.refontcrafts.util.RecipeQuery.MatchMode;

import java.util.Map;

/**
 * Inventory right-click mode used when AdvancedEnchantments integration is active.
 * One side must be a book, matching the behavior of earlier RefontCrafts builds.
 */
public final class AnvilClickListener implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;

    public AnvilClickListener(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() != InventoryType.PLAYER) return;
        if (!event.isRightClick()) return;

        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        if (Compat.isAir(cursor) || Compat.isAir(clicked)) return;

        boolean cursorBook = Compat.materialNameContains(cursor, "BOOK");
        boolean clickedBook = Compat.materialNameContains(clicked, "BOOK");
        if (cursorBook == clickedBook) return;

        ClickMatch match = findMatch(cursor, clicked);
        if (match == null) return;

        Player player = (Player) event.getWhoClicked();
        int cost = Math.max(0, match.recipe.cost);
        if (cost > player.getLevel()) {
            player.sendMessage(plugin.msg("not_enough_levels", "cost", String.valueOf(cost)));
            return;
        }

        event.setCancelled(true);
        ItemStack result = match.recipe.result.clone();
        result.setAmount(Compat.clientSafeAmount(Math.max(1, result.getAmount())));

        if (match.clickedIsLeft) {
            int leftAfter = clicked.getAmount() - Math.max(1, match.recipe.left.getAmount());
            int rightAfter = cursor.getAmount() - Math.max(1, match.recipe.right.getAmount());
            event.getClickedInventory().setItem(event.getSlot(), result);
            if (leftAfter > 0) giveOrDrop(player, withAmount(clicked, leftAfter));
            player.setItemOnCursor(rightAfter > 0 ? withAmount(cursor, rightAfter) : null);
        } else {
            int leftAfter = cursor.getAmount() - Math.max(1, match.recipe.left.getAmount());
            int rightAfter = clicked.getAmount() - Math.max(1, match.recipe.right.getAmount());
            event.getClickedInventory().setItem(event.getSlot(), rightAfter > 0 ? withAmount(clicked, rightAfter) : null);
            if (leftAfter > 0) giveOrDrop(player, withAmount(cursor, leftAfter));
            player.setItemOnCursor(result);
        }

        if (cost > 0) player.setLevel(Math.max(0, player.getLevel() - cost));
        player.updateInventory();
    }

    private ClickMatch findMatch(ItemStack cursor, ItemStack clicked) {
        for (AnvilRecipe recipe : storage.getAnvilRecipes()) {
            if (matches(clicked, recipe.left) && matches(cursor, recipe.right)
                    && clicked.getAmount() >= Math.max(1, recipe.left.getAmount())
                    && cursor.getAmount() >= Math.max(1, recipe.right.getAmount())) {
                return new ClickMatch(recipe, true);
            }
            if (matches(cursor, recipe.left) && matches(clicked, recipe.right)
                    && cursor.getAmount() >= Math.max(1, recipe.left.getAmount())
                    && clicked.getAmount() >= Math.max(1, recipe.right.getAmount())) {
                return new ClickMatch(recipe, false);
            }
        }
        return null;
    }

    private boolean matches(ItemStack actual, ItemStack required) {
        if (Compat.isAir(actual) || Compat.isAir(required)) return false;
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

    private ItemStack withAmount(ItemStack source, int amount) {
        ItemStack result = source.clone();
        result.setAmount(Math.max(1, amount));
        return result;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        if (Compat.isAir(item)) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            if (!Compat.isAir(leftover)) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private static final class ClickMatch {
        private final AnvilRecipe recipe;
        private final boolean clickedIsLeft;

        private ClickMatch(AnvilRecipe recipe, boolean clickedIsLeft) {
            this.recipe = recipe;
            this.clickedIsLeft = clickedIsLeft;
        }
    }
}
