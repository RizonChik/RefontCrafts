package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.util.ItemUtil;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class AnvilClickListener implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private static final Set<Material> BOOKS = EnumSet.of(Material.BOOK, Material.ENCHANTED_BOOK, Material.WRITTEN_BOOK, Material.WRITABLE_BOOK);

    public AnvilClickListener(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory().getType() != InventoryType.PLAYER) return;
        if (e.getClick() != ClickType.RIGHT && e.getClick() != ClickType.SHIFT_RIGHT) return;

        ItemStack cursor = e.getCursor();
        ItemStack target = e.getCurrentItem();
        if (isAir(cursor) || isAir(target)) return;

        boolean cursorBook = isBook(cursor.getType());
        boolean targetBook = isBook(target.getType());
        if (cursorBook == targetBook) return;

        AnvilRecipe matched = null;
        boolean baseIsClicked = false;
        int needA = 0;
        int needB = 0;

        for (AnvilRecipe r : storage.getAnvilRecipes()) {
            if (cursorBook) {
                if (matches(target, r.left) && matches(cursor, r.right)) {
                    matched = r;
                    baseIsClicked = true;
                    needA = Math.max(1, r.left.getAmount());
                    needB = Math.max(1, r.right.getAmount());
                    break;
                }
            } else {
                if (matches(cursor, r.left) && matches(target, r.right)) {
                    matched = r;
                    baseIsClicked = false;
                    needA = Math.max(1, r.left.getAmount());
                    needB = Math.max(1, r.right.getAmount());
                    break;
                }
            }
        }
        if (matched == null) return;

        int haveA = baseIsClicked ? target.getAmount() : cursor.getAmount();
        int haveB = baseIsClicked ? cursor.getAmount() : target.getAmount();
        if (haveA < needA || haveB < needB) return;

        Player p = (Player) e.getWhoClicked();
        int cost = Math.max(0, matched.cost);
        if (cost > 0 && p.getGameMode() != GameMode.CREATIVE && p.getLevel() < cost) {
            p.sendMessage(plugin.msg("not_enough_levels", "cost", String.valueOf(cost)));
            return;
        }

        e.setCancelled(true);

        int perSet = Math.max(1, matched.result.getAmount());
        ItemStack result = matched.result.clone();
        result.setAmount(perSet);

        if (baseIsClicked) {
            Inventory inv = e.getClickedInventory();
            int slot = e.getSlot();

            int leftAfter = target.getAmount() - needA;
            int rightAfter = cursor.getAmount() - needB;

            inv.setItem(slot, result);

            if (leftAfter > 0) giveOrDrop(p, ItemUtil.cloneWithAmount(target, leftAfter));
            if (rightAfter > 0) {
                ItemStack c = cursor.clone();
                c.setAmount(rightAfter);
                p.setItemOnCursor(c);
            } else {
                p.setItemOnCursor(null);
            }
        } else {
            int rightAfter = target.getAmount() - needB;
            int leftAfter = cursor.getAmount() - needA;

            if (leftAfter > 0) giveOrDrop(p, ItemUtil.cloneWithAmount(cursor, leftAfter));
            if (rightAfter > 0) {
                ItemStack t = target.clone();
                t.setAmount(rightAfter);
                e.getClickedInventory().setItem(e.getSlot(), t);
            } else {
                e.getClickedInventory().setItem(e.getSlot(), null);
            }
            p.setItemOnCursor(result);
        }

        if (cost > 0 && p.getGameMode() != GameMode.CREATIVE) p.setLevel(Math.max(0, p.getLevel() - cost));
        p.updateInventory();
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
    }

    private boolean matches(ItemStack actual, ItemStack recipe) {
        return ItemUtil.matchesIngredient(actual, ItemUtil.cloneWithAmount(recipe, actual.getAmount()), plugin.exactMeta());
    }

    private boolean isBook(Material m) {
        return BOOKS.contains(m);
    }

    private boolean isAir(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        if (!left.isEmpty()) for (ItemStack r : left.values()) if (r != null) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }
}
