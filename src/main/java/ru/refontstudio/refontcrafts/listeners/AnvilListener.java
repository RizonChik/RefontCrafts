package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.util.ItemUtil;

import java.util.*;

public class AnvilListener implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;

    private final Map<Long, List<AnvilRecipe>> index = new HashMap<>();
    private int indexedCount = -1;

    private final NamespacedKey RESULT_MARK;

    public AnvilListener(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.RESULT_MARK = new NamespacedKey(plugin, "rc_anvil_recipe_id");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareAnvilEvent e) {
        ItemStack a = e.getInventory().getItem(0);
        ItemStack b = e.getInventory().getItem(1);
        if (isAir(a) || isAir(b)) return;

        ensureIndex();
        List<AnvilRecipe> candidates = index.getOrDefault(key(a.getType(), b.getType()), Collections.<AnvilRecipe>emptyList());
        if (candidates.isEmpty()) return;

        Player viewer = null;
        for (HumanEntity he : e.getViewers()) {
            if (he instanceof Player) { viewer = (Player) he; break; }
        }

        for (AnvilRecipe r : candidates) {
            if (!matches(a, r.left) || !matches(b, r.right)) continue;

            int needA = Math.max(1, r.left.getAmount());
            int needB = Math.max(1, r.right.getAmount());
            if (a.getAmount() < needA || b.getAmount() < needB) continue;

            ItemStack preview = r.result.clone();
            int perSet = Math.max(1, preview.getAmount());
            preview.setAmount(perSet);

            ItemMeta im = preview.getItemMeta();
            if (im != null) {
                im.getPersistentDataContainer().set(RESULT_MARK, PersistentDataType.STRING, r.id);
                preview.setItemMeta(im);
            }

            e.setResult(preview);

            int cost = Math.max(0, r.cost);
            boolean creativeIgnores = plugin.getConfig().getBoolean("settings.anvil.creative_ignores_xp", true);
            boolean opsIgnore = plugin.getConfig().getBoolean("settings.anvil.ops_ignore_xp", false);
            if (viewer != null) {
                if (creativeIgnores && viewer.getGameMode() == GameMode.CREATIVE) cost = 0;
                else if (opsIgnore && viewer.isOp()) cost = 0;
            }
            pushRepairCost(e.getInventory(), cost);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        int raw = e.getRawSlot();

        if (raw == 0 || raw == 1) {
            if (e.getClick() == ClickType.SWAP_OFFHAND) {
                e.setCancelled(true);
            } else {
                e.setCancelled(false);
            }
            return;
        }

        if (raw != 2) return;
        handleResultSlot(e);
    }

    private void handleResultSlot(InventoryClickEvent e) {
        AnvilInventory inv = (AnvilInventory) e.getInventory();
        Player p = (Player) e.getWhoClicked();

        ItemStack resultSlot = inv.getItem(2);
        if (isAir(resultSlot)) return;

        ItemMeta rm = resultSlot.getItemMeta();
        if (rm == null) return;

        String rid = rm.getPersistentDataContainer().get(RESULT_MARK, PersistentDataType.STRING);
        if (rid == null || rid.isEmpty()) return;

        ItemStack a = inv.getItem(0);
        ItemStack b = inv.getItem(1);
        if (isAir(a) || isAir(b)) return;

        ensureIndex();
        List<AnvilRecipe> candidates = index.getOrDefault(key(a.getType(), b.getType()), Collections.<AnvilRecipe>emptyList());
        if (candidates.isEmpty()) return;

        AnvilRecipe match = null;
        for (AnvilRecipe r : candidates) {
            if (!r.id.equals(rid)) continue;
            if (!matches(a, r.left) || !matches(b, r.right)) continue;
            match = r;
            break;
        }
        if (match == null) return;

        int needA = Math.max(1, match.left.getAmount());
        int needB = Math.max(1, match.right.getAmount());
        int haveA = a.getAmount();
        int haveB = b.getAmount();
        int setsByItems = Math.min(haveA / needA, haveB / needB);
        if (setsByItems <= 0) return;

        int perSet = Math.max(1, match.result.getAmount());
        int maxStack = Math.max(1, match.result.getMaxStackSize());
        int previewSets = Math.max(1, resultSlot.getAmount() / perSet);

        boolean creativeIgnores = plugin.getConfig().getBoolean("settings.anvil.creative_ignores_xp", true);
        boolean opsIgnore = plugin.getConfig().getBoolean("settings.anvil.ops_ignore_xp", false);
        boolean ignoreXp = (creativeIgnores && p.getGameMode() == GameMode.CREATIVE) || (opsIgnore && p.isOp());

        int setsByXP = match.cost > 0 ? (ignoreXp ? setsByItems : (p.getLevel() / match.cost)) : setsByItems;

        boolean shift = e.isShiftClick();
        boolean numberKey = (e.getClick() == ClickType.NUMBER_KEY);
        int setsCap = Math.min(setsByItems, setsByXP);

        int setsWanted;
        if (shift) {
            int invCap = inventoryCapacityFor(p, match.result);
            if (invCap <= 0) shift = false;
        }
        setsWanted = shift ? setsCap : Math.min(previewSets, setsCap);

        if (setsWanted <= 0) {
            e.setCancelled(true);
            if (!ignoreXp && match.cost > 0 && p.getLevel() < match.cost) {
                p.sendMessage(plugin.msg("not_enough_levels", "cost", String.valueOf(match.cost)));
                pushRepairCost(inv, match.cost);
            }
            return;
        }

        int desiredItems = perSet * setsWanted;
        int acceptedItems = 0;
        ItemStack base = match.result.clone();

        e.setCancelled(true);

        if (numberKey) {
            int hotbar = e.getHotbarButton();
            if (hotbar >= 0) {
                ItemStack slot = p.getInventory().getItem(hotbar);
                if (slot == null || slot.getType() == Material.AIR) {
                    int put = Math.min(desiredItems, maxStack);
                    ItemStack toSet = base.clone(); toSet.setAmount(put);
                    p.getInventory().setItem(hotbar, toSet);
                    acceptedItems += put;
                } else if (slot.isSimilar(base)) {
                    int can = Math.max(0, maxStack - slot.getAmount());
                    int put = Math.min(desiredItems, can);
                    if (put > 0) {
                        slot.setAmount(slot.getAmount() + put);
                        p.getInventory().setItem(hotbar, slot);
                        acceptedItems += put;
                    }
                }
                int rest = desiredItems - acceptedItems;
                if (rest > 0) acceptedItems += addToInvOrDrop(p, base, rest);
            }
        } else if (shift) {
            acceptedItems += addToInvOrDrop(p, base, desiredItems);
        } else {
            ItemStack cursor = e.getCursor();
            int canCursor = 0;
            if (cursor == null || cursor.getType() == Material.AIR) canCursor = maxStack;
            else if (cursor.isSimilar(base)) canCursor = Math.max(0, maxStack - cursor.getAmount());
            int putOnCursor = Math.min(desiredItems, canCursor);
            if (putOnCursor > 0) {
                if (cursor == null || cursor.getType() == Material.AIR) {
                    ItemStack toSet = base.clone(); toSet.setAmount(putOnCursor);
                    p.setItemOnCursor(toSet);
                } else {
                    cursor.setAmount(cursor.getAmount() + putOnCursor);
                    p.setItemOnCursor(cursor);
                }
            }
            acceptedItems += putOnCursor;
            int rest = desiredItems - putOnCursor;
            if (rest > 0) acceptedItems += addToInvOrDrop(p, base, rest);
        }

        int setsCrafted = acceptedItems / perSet;
        if (setsCrafted <= 0) return;

        int spendA = setsCrafted * needA;
        int spendB = setsCrafted * needB;
        int spendXP = match.cost * setsCrafted;

        ItemStack a2 = a.clone();
        ItemStack b2 = b.clone();
        a2.setAmount(a2.getAmount() - spendA);
        b2.setAmount(b2.getAmount() - spendB);
        inv.setItem(0, a2.getAmount() <= 0 ? null : a2);
        inv.setItem(1, b2.getAmount() <= 0 ? null : b2);

        if (spendXP > 0 && !ignoreXp) p.setLevel(Math.max(0, p.getLevel() - spendXP));

        inv.setItem(2, null);
        pushRepairCost(inv, 0);
        p.updateInventory();

        String sName = plugin.getConfig().getString("settings.sounds.anvil_success.name", "BLOCK_ANVIL_USE");
        float vol = (float) plugin.getConfig().getDouble("settings.sounds.anvil_success.volume", 1.0);
        float pit = (float) plugin.getConfig().getDouble("settings.sounds.anvil_success.pitch", 1.0);
        p.playSound(p.getLocation(), safeSound(sName, Sound.BLOCK_ANVIL_USE), vol, pit);
    }

    private int inventoryCapacityFor(Player p, ItemStack sample) {
        int cap = 0;
        int max = sample.getMaxStackSize();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType() == Material.AIR) cap += max;
            else if (it.isSimilar(sample)) cap += Math.max(0, max - it.getAmount());
        }
        return cap;
    }

    private int addToInvOrDrop(Player p, ItemStack base, int amount) {
        Map<Integer, ItemStack> rem = p.getInventory().addItem(ItemUtil.cloneWithAmount(base, amount));
        if (rem.isEmpty()) return amount;
        int back = 0;
        for (ItemStack it : rem.values()) if (it != null) back += it.getAmount();
        int accepted = Math.max(0, amount - back);
        if (back > 0) p.getWorld().dropItemNaturally(p.getLocation(), ItemUtil.cloneWithAmount(base, back));
        return accepted;
    }

    private void pushRepairCost(AnvilInventory inv, int cost) {
        try { inv.setRepairCost(cost); } catch (Throwable ignored) {}
        for (HumanEntity he : inv.getViewers()) {
            if (he instanceof Player) {
                Player pl = (Player) he;
                try { pl.getOpenInventory().setProperty(InventoryView.Property.REPAIR_COST, cost); } catch (Throwable ignored) {}
                try { pl.setWindowProperty(InventoryView.Property.REPAIR_COST, cost); } catch (Throwable ignored) {}
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() {
                    for (HumanEntity he : inv.getViewers()) {
                        if (he instanceof Player) {
                            Player pl = (Player) he;
                            try { pl.getOpenInventory().setProperty(InventoryView.Property.REPAIR_COST, cost); } catch (Throwable ignored) {}
                            try { pl.setWindowProperty(InventoryView.Property.REPAIR_COST, cost); } catch (Throwable ignored) {}
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private Sound safeSound(String name, Sound def) {
        try { return Sound.valueOf(name); } catch (Throwable ignored) { return def; }
    }

    private boolean matches(ItemStack actual, ItemStack recipe) {
        return ItemUtil.matchesIngredient(actual, ItemUtil.cloneWithAmount(recipe, actual.getAmount()), plugin.exactMeta());
    }

    private boolean isAir(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private void ensureIndex() {
        int cur = storage.revision();
        if (cur == indexedCount) return;
        index.clear();
        for (AnvilRecipe r : storage.getAnvilRecipes()) {
            long k = key(r.left.getType(), r.right.getType());
            List<AnvilRecipe> list = index.get(k);
            if (list == null) {
                list = new ArrayList<>();
                index.put(k, list);
            }
            list.add(r);
        }
        indexedCount = cur;
    }

    private long key(Material left, Material right) {
        return (((long) left.ordinal()) << 32) | (right.ordinal() & 0xffffffffL);
    }
}
