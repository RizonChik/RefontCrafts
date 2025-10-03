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

import java.util.*;

public class RecipeEditorMenu implements Listener {
    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final NamespacedKey GHOST;

    private static final int[] ING = {10,11,12,19,20,21,28,29,30};
    private static final int RES = 25;
    private static final int SAVE = 49;
    private static final int CLEAR = 50;
    private static final int EXIT = 53;

    public RecipeEditorMenu(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.GHOST = new NamespacedKey(plugin, "rc_ghost");
    }

    public void openEditor(Player p) {
        Inventory inv = Bukkit.createInventory(p, 54, plugin.titleRecipe());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, ItemUtil.named(Material.GRAY_STAINED_GLASS_PANE, " "));
        for (int s : ING) inv.setItem(s, null);
        inv.setItem(RES, null);
        inv.setItem(SAVE, ItemUtil.named(Material.LIME_WOOL, "§aСохранить", "§7Сохранить рецепт"));
        inv.setItem(CLEAR, ItemUtil.named(Material.YELLOW_WOOL, "§eОчистить", "§7Убрать все предметы"));
        inv.setItem(EXIT, ItemUtil.named(Material.BARRIER, "§cВыход", "§7Вернуть вещи и закрыть"));
        p.openInventory(inv);
    }

    public void openEditorForEdit(Player p, String id, List<ItemStack> grid9, ItemStack result, boolean shaped) {
        Inventory inv = Bukkit.createInventory(p, 54, plugin.titleRecipe());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, ItemUtil.named(Material.GRAY_STAINED_GLASS_PANE, " "));
        for (int i = 0; i < ING.length; i++) {
            ItemStack it = i < grid9.size() ? grid9.get(i) : null;
            ItemStack put = it == null ? null : it.clone();
            if (put != null && put.getType() != Material.AIR) markGhost(put);
            inv.setItem(ING[i], put);
        }
        ItemStack out = result == null ? null : result.clone();
        if (out != null && out.getType() != Material.AIR) markGhost(out);
        inv.setItem(RES, out);
        inv.setItem(SAVE, ItemUtil.named(Material.LIME_WOOL, "§aСохранить", "§7Пересохранить рецепт"));
        inv.setItem(CLEAR, ItemUtil.named(Material.YELLOW_WOOL, "§eОчистить", "§7Убрать все предметы"));
        inv.setItem(EXIT, ItemUtil.named(Material.BARRIER, "§cВыход", "§7Вернуть вещи и закрыть"));
        p.openInventory(inv);
    }

    private boolean isEditorTitle(String title) {
        return Text.plain(title).equals(Text.plain(plugin.titleRecipe()));
    }

    private boolean isRecipeSlot(int s) {
        if (s == RES) return true;
        for (int x : ING) if (x == s) return true;
        return false;
    }
    private boolean isControlSlot(int s) { return s == SAVE || s == CLEAR || s == EXIT; }

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
            if (slot == SAVE) {
                List<ItemStack> grid = new ArrayList<>(9);
                for (int s1 : ING) {
                    ItemStack it = top.getItem(s1);
                    if (it == null || it.getType() == Material.AIR) grid.add(new ItemStack(Material.AIR));
                    else { ItemStack one = unghost(it.clone()); one.setAmount(1); grid.add(one); }
                }
                ItemStack res = top.getItem(RES);
                if (res == null || res.getType() == Material.AIR) {
                    p.sendMessage(Text.color(plugin.prefix() + plugin.msg("recipe_fill_both")));
                    return;
                }
                String id = storage.saveShapedRecipe(grid, unghost(res.clone()));
                p.sendMessage(Text.color(plugin.prefix() + plugin.msg("saved_recipe", "id", id)));
                return;
            }
            if (slot == CLEAR) {
                for (int s : ING) returnIfReal(p, top.getItem(s));
                returnIfReal(p, top.getItem(RES));
                for (int s : ING) top.setItem(s, null);
                top.setItem(RES, null);
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
        HumanEntity he = e.getPlayer();
        Inventory inv = e.getInventory();
        for (int s : ING) returnIfReal(he, inv.getItem(s));
        returnIfReal(he, inv.getItem(RES));
        cleanupGhostEverywhere((Player) he);
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
        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "rc_ghost"), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
    }

    private boolean isGhost(ItemStack it) {
        if (it == null) return false;
        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        Byte b = m.getPersistentDataContainer().get(new NamespacedKey(plugin, "rc_ghost"), PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private ItemStack unghost(ItemStack it) {
        if (it == null) return null;
        ItemMeta m = it.getItemMeta();
        if (m != null) m.getPersistentDataContainer().remove(new NamespacedKey(plugin, "rc_ghost"));
        it.setItemMeta(m);
        return it;
    }
}