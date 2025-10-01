package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
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
        List<AnvilRecipe> candidates = index.getOrDefault(key(a.getType(), b.getType()), Collections.emptyList());
        if (candidates.isEmpty()) return;

        for (AnvilRecipe r : candidates) {
            if (!matches(a, r.left) || !matches(b, r.right)) continue;

            int needA = Math.max(1, r.left.getAmount());
            int needB = Math.max(1, r.right.getAmount());
            if (a.getAmount() < needA || b.getAmount() < needB) continue;

            int setsPossible = Math.min(a.getAmount() / needA, b.getAmount() / needB);
            if (setsPossible <= 0) continue;

            ItemStack base = r.result.clone();
            int perSet = Math.max(1, base.getAmount());
            int maxStack = Math.max(1, base.getMaxStackSize());
            int previewSets = Math.max(1, Math.min(setsPossible, Math.max(1, maxStack / perSet)));

            ItemStack preview = base.clone();
            preview.setAmount(perSet * previewSets);

            ItemMeta im = preview.getItemMeta();
            if (im != null) {
                im.getPersistentDataContainer().set(RESULT_MARK, PersistentDataType.STRING, r.id);
                preview.setItemMeta(im);
            }

            e.setResult(preview);
            e.getInventory().setRepairCost(Math.max(0, r.cost * previewSets));
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTake(InventoryClickEvent e) {
        if (e.getInventory().getType() != InventoryType.ANVIL) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getRawSlot() != 2) return;

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
        List<AnvilRecipe> candidates = index.getOrDefault(key(a.getType(), b.getType()), Collections.emptyList());
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

        int setsByXP = match.cost > 0 ? (p.getLevel() / match.cost) : setsByItems;

        boolean shift = e.isShiftClick();
        boolean numberKey = (e.getClick() == ClickType.NUMBER_KEY);
        int setsCap = Math.min(setsByItems, setsByXP);
        int setsWanted = shift ? setsCap : Math.min(previewSets, setsCap);

        if (setsWanted <= 0) {
            if (match.cost > 0 && p.getLevel() < match.cost) {
                p.sendMessage(plugin.msg("not_enough_levels", "cost", String.valueOf(match.cost)));
            }
            e.setCancelled(true);
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
                    ItemStack toSet = base.clone();
                    toSet.setAmount(put);
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
                if (rest > 0) {
                    Map<Integer, ItemStack> rem = p.getInventory().addItem(ItemUtil.cloneWithAmount(base, rest));
                    if (!rem.isEmpty()) {
                        int back = 0;
                        for (ItemStack it : rem.values()) if (it != null) back += it.getAmount();
                        acceptedItems += Math.max(0, rest - back);
                    }
                }
            }
        } else if (shift) {
            Map<Integer, ItemStack> rem = p.getInventory().addItem(ItemUtil.cloneWithAmount(base, desiredItems));
            if (rem.isEmpty()) {
                acceptedItems = desiredItems;
            } else {
                int back = 0;
                for (ItemStack it : rem.values()) if (it != null) back += it.getAmount();
                acceptedItems = Math.max(0, desiredItems - back);
            }
        } else {
            ItemStack cursor = e.getCursor();
            int canCursor = 0;
            if (cursor == null || cursor.getType() == Material.AIR) {
                canCursor = maxStack;
            } else if (cursor.isSimilar(base)) {
                canCursor = Math.max(0, maxStack - cursor.getAmount());
            }
            int putOnCursor = Math.min(desiredItems, canCursor);
            if (putOnCursor > 0) {
                if (cursor == null || cursor.getType() == Material.AIR) {
                    ItemStack toSet = base.clone();
                    toSet.setAmount(putOnCursor);
                    p.setItemOnCursor(toSet);
                } else {
                    cursor.setAmount(cursor.getAmount() + putOnCursor);
                    p.setItemOnCursor(cursor);
                }
            }
            acceptedItems += putOnCursor;

            int rest = desiredItems - putOnCursor;
            if (rest > 0) {
                Map<Integer, ItemStack> rem = p.getInventory().addItem(ItemUtil.cloneWithAmount(base, rest));
                if (!rem.isEmpty()) {
                    int back = 0;
                    for (ItemStack it : rem.values()) if (it != null) back += it.getAmount();
                    acceptedItems += Math.max(0, rest - back);
                }
            }
        }

        int setsCrafted = acceptedItems / perSet;
        if (setsCrafted <= 0) {
            p.sendMessage(plugin.msg("no_inventory_space"));
            return;
        }

        int spendA = setsCrafted * needA;
        int spendB = setsCrafted * needB;
        int spendXP = match.cost * setsCrafted;

        ItemStack a2 = a.clone();
        ItemStack b2 = b.clone();
        a2.setAmount(a2.getAmount() - spendA);
        b2.setAmount(b2.getAmount() - spendB);
        inv.setItem(0, a2.getAmount() <= 0 ? null : a2);
        inv.setItem(1, b2.getAmount() <= 0 ? null : b2);

        if (spendXP > 0) p.setLevel(Math.max(0, p.getLevel() - spendXP));

        inv.setItem(2, null);
        inv.setRepairCost(0);
        p.updateInventory();
    }

    private boolean matches(ItemStack actual, ItemStack recipe) {
        if (plugin.exactMeta()) return ItemUtil.similarExact(actual, ItemUtil.cloneWithAmount(recipe, actual.getAmount()));
        if (isPotion(actual.getType()) && isPotion(recipe.getType())) return potionBaseEquals(actual, recipe);
        return ItemUtil.similarType(actual, recipe);
    }

    private boolean potionBaseEquals(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (!isPotion(a.getType()) || !isPotion(b.getType())) return false;
        if (a.getType() != b.getType()) return false;
        ItemMeta am = a.getItemMeta();
        ItemMeta bm = b.getItemMeta();
        if (!(am instanceof PotionMeta) || !(bm instanceof PotionMeta)) return false;
        PotionMeta ap = (PotionMeta) am;
        PotionMeta bp = (PotionMeta) bm;
        if (ap.getBasePotionData() == null || bp.getBasePotionData() == null) return false;
        return ap.getBasePotionData().getType() == bp.getBasePotionData().getType()
                && ap.getBasePotionData().isExtended() == bp.getBasePotionData().isExtended()
                && ap.getBasePotionData().isUpgraded() == bp.getBasePotionData().isUpgraded();
    }

    private boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private boolean isAir(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private void ensureIndex() {
        int cur = storage.getAnvilRecipes().size();
        if (cur == indexedCount) return;
        index.clear();
        for (AnvilRecipe r : storage.getAnvilRecipes()) {
            long k = key(r.left.getType(), r.right.getType());
            index.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        indexedCount = cur;
    }

    private long key(Material left, Material right) {
        return (((long) left.ordinal()) << 32) | (right.ordinal() & 0xffffffffL);
    }
}