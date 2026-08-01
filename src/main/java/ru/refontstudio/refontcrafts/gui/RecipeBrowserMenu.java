package ru.refontstudio.refontcrafts.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.WorkbenchRecipe;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RecipeBrowserMenu implements Listener {
    private static final int[] VIEW_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<UUID, Integer> pages = new HashMap<UUID, Integer>();
    private final Map<UUID, Map<Integer, EntryRef>> entries = new HashMap<UUID, Map<Integer, EntryRef>>();

    public RecipeBrowserMenu(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void openWorkbench(Player player, int page) {
        List<WorkbenchRecipe> recipes = new ArrayList<WorkbenchRecipe>(storage.getWorkbenchRecipes());
        Collections.sort(recipes, new Comparator<WorkbenchRecipe>() {
            @Override
            public int compare(WorkbenchRecipe first, WorkbenchRecipe second) {
                return first.id.compareTo(second.id);
            }
        });

        int safePage = normalizePage(page, recipes.size());
        pages.put(player.getUniqueId(), safePage);
        Map<Integer, EntryRef> refs = new HashMap<Integer, EntryRef>();
        entries.put(player.getUniqueId(), refs);

        Inventory inventory = createBase(plugin.titleBrowseWorkbench());
        int from = (safePage - 1) * VIEW_SLOTS.length;
        int to = Math.min(from + VIEW_SLOTS.length, recipes.size());
        int shown = 0;
        for (int i = from; i < to; i++) {
            WorkbenchRecipe recipe = recipes.get(i);
            ItemStack icon = recipe.result.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<String>();
                lore.add(plugin.tr("gui.browser.workbench.type", "&8Type: &fWorkbench"));
                lore.add(plugin.tr("gui.browser.workbench.shape.text", "&8Form: %shape%",
                        "shape", recipe.shaped
                                ? plugin.tr("gui.browser.workbench.shape.shaped", "&aShaped")
                                : plugin.tr("gui.browser.workbench.shape.shapeless", "&eShapeless")));
                lore.add(plugin.tr("gui.browser.workbench.id", "&8ID: &7%id%", "id", recipe.id));
                lore.add(" ");
                if (canEditWorkbench(player)) {
                    lore.add(plugin.tr("gui.browser.workbench.edit", "&7Left click: &aedit"));
                } else {
                    lore.add(plugin.tr("gui.crafts.preview", "&7Left click to preview"));
                }
                lore.add(plugin.tr("gui.browser.workbench.delete", "&7Right click: &cdelete"));
                meta.setLore(ItemUtil.colorLines(lore));
                icon.setItemMeta(meta);
            }
            int slot = VIEW_SLOTS[shown++];
            inventory.setItem(slot, icon);
            refs.put(slot, new EntryRef("wb", recipe.id));
        }
        player.openInventory(inventory);
    }

    public void openAnvil(Player player, int page) {
        List<AnvilRecipe> recipes = new ArrayList<AnvilRecipe>(storage.getAnvilRecipes());
        Collections.sort(recipes, new Comparator<AnvilRecipe>() {
            @Override
            public int compare(AnvilRecipe first, AnvilRecipe second) {
                return first.id.compareTo(second.id);
            }
        });

        int safePage = normalizePage(page, recipes.size());
        pages.put(player.getUniqueId(), safePage);
        Map<Integer, EntryRef> refs = new HashMap<Integer, EntryRef>();
        entries.put(player.getUniqueId(), refs);

        Inventory inventory = createBase(plugin.titleBrowseAnvil());
        int from = (safePage - 1) * VIEW_SLOTS.length;
        int to = Math.min(from + VIEW_SLOTS.length, recipes.size());
        int shown = 0;
        for (int i = from; i < to; i++) {
            AnvilRecipe recipe = recipes.get(i);
            ItemStack icon = recipe.result.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<String>();
                lore.add(plugin.tr("gui.browser.anvil.type", "&8Type: &fAnvil"));
                lore.add(plugin.tr("gui.browser.anvil.id", "&8ID: &7%id%", "id", recipe.id));
                lore.add(plugin.tr("gui.browser.anvil.cost", "&8Cost: &f%cost%", "cost", String.valueOf(recipe.cost)));
                lore.add(" ");
                lore.add(plugin.tr("gui.browser.anvil.left", "&7Left input: &f%item%", "item", recipe.left.getType().name()));
                lore.add(plugin.tr("gui.browser.anvil.right", "&7Right input: &f%item%", "item", recipe.right.getType().name()));
                lore.add(" ");
                lore.add(plugin.tr("gui.crafts.preview", "&7Left click to preview"));
                lore.add(plugin.tr("gui.browser.anvil.edit", "&7Shift+left click: &aedit"));
                lore.add(plugin.tr("gui.browser.anvil.delete", "&7Right click: &cdelete"));
                meta.setLore(ItemUtil.colorLines(lore));
                icon.setItemMeta(meta);
            }
            int slot = VIEW_SLOTS[shown++];
            inventory.setItem(slot, icon);
            refs.put(slot, new EntryRef("anv", recipe.id));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        boolean workbench = Text.plain(title).equals(Text.plain(plugin.titleBrowseWorkbench()));
        boolean anvil = Text.plain(title).equals(Text.plain(plugin.titleBrowseAnvil()));
        if (!workbench && !anvil) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int page = page(player);

        if (slot == PREVIOUS_SLOT) {
            final int targetPage = page - 1;
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    if (workbench) openWorkbench(player, targetPage);
                    else openAnvil(player, targetPage);
                }
            });
            return;
        }
        if (slot == NEXT_SLOT) {
            final int targetPage = page + 1;
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    if (workbench) openWorkbench(player, targetPage);
                    else openAnvil(player, targetPage);
                }
            });
            return;
        }
        if (slot == CLOSE_SLOT) {
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    player.closeInventory();
                }
            });
            return;
        }

        Map<Integer, EntryRef> refs = entries.get(player.getUniqueId());
        EntryRef ref = refs == null ? null : refs.get(slot);
        if (ref == null) return;

        if ("wb".equals(ref.type)) {
            WorkbenchRecipe recipe = storage.getWorkbenchRecipe(ref.id);
            if (recipe == null) return;
            if (!player.hasPermission("refontcrafts.view")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            if (event.isLeftClick()) {
                if (canEditWorkbench(player)) {
                    runNextTick(player, new Runnable() {
                        @Override
                        public void run() {
                            plugin.recipeMenu().openEditorForEdit(
                                    player, recipe.id, recipe.ingredients, recipe.result, recipe.shaped);
                        }
                    });
                } else {
                    runNextTick(player, new Runnable() {
                        @Override
                        public void run() {
                            plugin.craftMenu().openWorkbenchPreview(player, recipe);
                        }
                    });
                }
            } else if (event.isRightClick()) {
                if (!player.hasPermission("refontcrafts.delete.workbench")
                        && !player.hasPermission("refontcrafts.recipe")) {
                    player.sendMessage(plugin.msg("no_permission"));
                    return;
                }
                storage.deleteWorkbenchRecipe(recipe.id);
                runNextTick(player, new Runnable() {
                    @Override
                    public void run() {
                        openWorkbench(player, page);
                    }
                });
            }
            return;
        }

        AnvilRecipe recipe = storage.getAnvilRecipe(ref.id);
        if (recipe == null) return;
        if (!player.hasPermission("refontcrafts.view")) {
            player.sendMessage(plugin.msg("no_permission"));
            return;
        }
        if (event.isLeftClick()) {
            if (event.isShiftClick() && (player.hasPermission("refontcrafts.edit.anvil")
                    || player.hasPermission("refontcrafts.anvil"))) {
                runNextTick(player, new Runnable() {
                    @Override
                    public void run() {
                        plugin.anvilMenu().openEditorForEdit(
                                player, recipe.left, recipe.right, recipe.result, recipe.cost, recipe.id);
                    }
                });
            } else {
                runNextTick(player, new Runnable() {
                    @Override
                    public void run() {
                        plugin.craftMenu().openAnvilPreview(player, recipe);
                    }
                });
            }
        } else if (event.isRightClick()) {
            if (!player.hasPermission("refontcrafts.delete.anvil")
                    && !player.hasPermission("refontcrafts.anvil")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            storage.deleteAnvilRecipe(recipe.id);
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    openAnvil(player, page);
                }
            });
        }
    }

    private void runNextTick(final Player player, final Runnable action) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) action.run();
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (!Text.plain(title).equals(Text.plain(plugin.titleBrowseWorkbench()))
                && !Text.plain(title).equals(Text.plain(plugin.titleBrowseAnvil()))) return;
        UUID id = event.getPlayer().getUniqueId();
        pages.remove(id);
        entries.remove(id);
    }

    private Inventory createBase(String title) {
        Inventory inventory = Bukkit.createInventory(null, 54, title);
        ItemStack filler = ItemUtil.named(Compat.blackPane(), " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler.clone());
        inventory.setItem(PREVIOUS_SLOT, ItemUtil.named(
                Material.ARROW,
                plugin.tr("gui.nav.prev.name", "&e← Previous"),
                plugin.tr("gui.nav.prev.lore", "&7Go to the previous page")));
        inventory.setItem(CLOSE_SLOT, ItemUtil.named(
                Material.BARRIER,
                plugin.tr("gui.nav.close.name", "&cClose"),
                plugin.tr("gui.nav.close.lore", "&7Close this menu")));
        inventory.setItem(NEXT_SLOT, ItemUtil.named(
                Material.ARROW,
                plugin.tr("gui.nav.next.name", "&eNext →"),
                plugin.tr("gui.nav.next.lore", "&7Go to the next page")));
        return inventory;
    }

    private int normalizePage(int requested, int size) {
        int pagesCount = Math.max(1, (size + VIEW_SLOTS.length - 1) / VIEW_SLOTS.length);
        return Math.max(1, Math.min(requested, pagesCount));
    }

    private int page(Player player) {
        Integer value = pages.get(player.getUniqueId());
        return value == null ? 1 : value.intValue();
    }

    private boolean canEditWorkbench(Player player) {
        return player.hasPermission("refontcrafts.edit.workbench")
                || player.hasPermission("refontcrafts.recipe");
    }

    private static final class EntryRef {
        private final String type;
        private final String id;

        private EntryRef(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }
}
