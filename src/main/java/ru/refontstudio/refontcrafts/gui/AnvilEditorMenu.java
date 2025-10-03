package ru.refontstudio.refontcrafts.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AnvilEditorMenu implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<Player, Integer> costs = new HashMap<>();
    private final Map<UUID, String> editId = new HashMap<>();
    private final NamespacedKey GHOST;

    private static final int LEFT = 10;
    private static final int RIGHT = 12;
    private static final int OUT = 16;
    private static final int MINUS = 20;
    private static final int COST = 22;
    private static final int PLUS = 24;
    private static final int SAVE = 39;
    private static final int CLEAR = 40;
    private static final int EXIT = 44;

    public AnvilEditorMenu(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.GHOST = new NamespacedKey(plugin, "rc_ghost");
    }

    public void openEditor(Player p) {
        Inventory inv = Bukkit.createInventory(p, 54, plugin.titleAnvil());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, ItemUtil.named(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(LEFT, null);
        inv.setItem(RIGHT, null);
        inv.setItem(OUT, null);
        inv.setItem(MINUS, ItemUtil.named(Material.REDSTONE, "&c- Стоимость", "&7Уменьшить на 1"));
        inv.setItem(PLUS, ItemUtil.named(Material.EMERALD, "&a+ Стоимость", "&7Увеличить на 1"));
        inv.setItem(COST, ItemUtil.named(Material.EXPERIENCE_BOTTLE, "&eСтоимость: &f" + plugin.defaultAnvilCost(), "&7Уровни при крафте"));
        inv.setItem(SAVE, ItemUtil.named(Material.LIME_WOOL, "&aСохранить", "&7Сохранить рецепт наковальни"));
        inv.setItem(CLEAR, ItemUtil.named(Material.YELLOW_WOOL, "&eОчистить", "&7Убрать все предметы"));
        inv.setItem(EXIT, ItemUtil.named(Material.BARRIER, "&cВыход", "&7Вернуть вещи и закрыть"));
        costs.put(p, plugin.defaultAnvilCost());
        editId.remove(p.getUniqueId());
        p.openInventory(inv);
    }

    public void openEditorForEdit(Player p, ItemStack left, ItemStack right, ItemStack out, int cost, String id) {
        Inventory inv = Bukkit.createInventory(p, 54, plugin.titleAnvil());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, ItemUtil.named(Material.GRAY_STAINED_GLASS_PANE, " "));
        ItemStack l = left == null ? null : left.clone();
        ItemStack r = right == null ? null : right.clone();
        ItemStack o = out == null ? null : out.clone();
        if (l != null && l.getType() != Material.AIR) markGhost(l);
        if (r != null && r.getType() != Material.AIR) markGhost(r);
        if (o != null && o.getType() != Material.AIR) markGhost(o);
        inv.setItem(LEFT, l);
        inv.setItem(RIGHT, r);
        inv.setItem(OUT, o);
        inv.setItem(MINUS, ItemUtil.named(Material.REDSTONE, "&c- Стоимость", "&7Уменьшить на 1"));
        inv.setItem(PLUS, ItemUtil.named(Material.EMERALD, "&a+ Стоимость", "&7Увеличить на 1"));
        inv.setItem(COST, ItemUtil.named(Material.EXPERIENCE_BOTTLE, "&eСтоимость: &f" + cost, "&7Уровни при крафте"));
        inv.setItem(SAVE, ItemUtil.named(Material.LIME_WOOL, "&aСохранить", "&7Пересохранить рецепт"));
        inv.setItem(CLEAR, ItemUtil.named(Material.YELLOW_WOOL, "&eОчистить", "&7Убрать все предметы"));
        inv.setItem(EXIT, ItemUtil.named(Material.BARRIER, "&cВыход", "&7Вернуть вещи и закрыть"));
        costs.put(p, cost);
        editId.put(p.getUniqueId(), id);
        p.openInventory(inv);
    }

    private boolean isEditorTitle(String title) {
        return Text.plain(title).equals(Text.plain(plugin.titleAnvil()));
    }

    private boolean isRecipeSlot(int s) { return s == LEFT || s == RIGHT || s == OUT; }
    private boolean isControlSlot(int s) { return s == MINUS || s == PLUS || s == COST || s == SAVE || s == CLEAR || s == EXIT; }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!isEditorTitle(e.getView().getTitle())) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getView().getTopInventory();
        int slot = e.getRawSlot();
        if (slot >= top.getSize()) return;

        if (isControlSlot(slot)) {
            e.setCancelled(true);
            if (slot == MINUS || slot == PLUS) {
                int cur = costs.getOrDefault(p, plugin.defaultAnvilCost());
                cur = slot == MINUS ? Math.max(0, cur - 1) : Math.min(99, cur + 1);
                costs.put(p, cur);
                top.setItem(COST, ItemUtil.named(Material.EXPERIENCE_BOTTLE, "&eСтоимость: &f" + cur, "&7Уровни при крафте"));
                return;
            }
            if (slot == SAVE) {
                ItemStack L = top.getItem(LEFT);
                ItemStack R = top.getItem(RIGHT);
                ItemStack O = top.getItem(OUT);
                if (L == null || L.getType() == Material.AIR || R == null || R.getType() == Material.AIR || O == null || O.getType() == Material.AIR) {
                    p.sendMessage(Text.color(plugin.prefix() + plugin.msg("anvil_fill_both")));
                    return;
                }
                ItemStack left = unghost(L.clone());
                ItemStack right = unghost(R.clone());
                ItemStack out = unghost(O.clone());
                int cost = costs.getOrDefault(p, plugin.defaultAnvilCost());
                String old = editId.remove(p.getUniqueId());
                if (old != null) storage.deleteAnvilRecipe(old);
                String id = storage.saveAnvilRecipe(left, right, out, cost);
                p.sendMessage(Text.color(plugin.prefix() + plugin.msg("saved_anvil", "id", id, "cost", String.valueOf(cost))));
                return;
            }
            if (slot == CLEAR) {
                returnIfReal(p, top.getItem(LEFT));
                returnIfReal(p, top.getItem(RIGHT));
                returnIfReal(p, top.getItem(OUT));
                top.setItem(LEFT, null);
                top.setItem(RIGHT, null);
                top.setItem(OUT, null);
                return;
            }
            if (slot == EXIT) {
                p.closeInventory();
            }
            return;
        }

        if (!isRecipeSlot(slot)) e.setCancelled(true);
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        if (!isEditorTitle(e.getView().getTitle())) return;
        int top = e.getView().getTopInventory().getSize();
        for (Integer s : e.getRawSlots()) if (s < top && isControlSlot(s)) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void close(InventoryCloseEvent e) {
        if (!isEditorTitle(e.getView().getTitle())) return;
        Player p = (Player) e.getPlayer();
        returnIfReal(p, e.getInventory().getItem(LEFT));
        returnIfReal(p, e.getInventory().getItem(RIGHT));
        returnIfReal(p, e.getInventory().getItem(OUT));
        cleanupGhostEverywhere(p);
        costs.remove(p);
        editId.remove(p.getUniqueId());
    }

    private void returnIfReal(HumanEntity p, ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return;
        if (isGhost(it)) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(it.clone());
        for (ItemStack r : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }

    private void cleanupGhostEverywhere(Player p) {
        if (isGhost(p.getItemOnCursor())) p.setItemOnCursor(null);
        ItemStack[] cont = p.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < cont.length; i++) {
            ItemStack it = cont[i];
            if (isGhost(it)) { cont[i] = null; changed = true; }
        }
        if (changed) p.getInventory().setContents(cont);
    }

    private void markGhost(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return;
        m.getPersistentDataContainer().set(GHOST, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
    }

    private boolean isGhost(ItemStack it) {
        if (it == null) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        Byte b = m.getPersistentDataContainer().get(GHOST, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private ItemStack unghost(ItemStack it) {
        if (it == null) return null;
        ItemMeta m = it.getItemMeta();
        if (m != null) m.getPersistentDataContainer().remove(GHOST);
        it.setItemMeta(m);
        return it;
    }
}