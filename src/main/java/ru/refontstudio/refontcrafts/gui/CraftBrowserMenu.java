package ru.refontstudio.refontcrafts.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.AnvilRecipe;
import ru.refontstudio.refontcrafts.storage.RecipeStorage.WorkbenchRecipe;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.RecipeQuery;
import ru.refontstudio.refontcrafts.util.RecipeQuery.MatchMode;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CraftBrowserMenu implements Listener {
    private static final int[] VIEW_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int INFO_SLOT = 4;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<UUID, Integer> pages = new HashMap<UUID, Integer>();
    private final Map<UUID, Map<Integer, EntryRef>> visibleEntries = new HashMap<UUID, Map<Integer, EntryRef>>();

    public CraftBrowserMenu(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void open(Player player, int page) {
        if (player == null) return;
        List<CraftEntry> entries = buildEntries(player);
        int totalPages = Math.max(1, (entries.size() + VIEW_SLOTS.length - 1) / VIEW_SLOTS.length);
        int safePage = Math.max(1, Math.min(page, totalPages));
        pages.put(player.getUniqueId(), safePage);

        Map<Integer, EntryRef> refs = new HashMap<Integer, EntryRef>();
        visibleEntries.put(player.getUniqueId(), refs);
        Inventory inventory = baseInventory(plugin.titleCrafts());
        inventory.setItem(INFO_SLOT, summaryItem(safePage, totalPages, entries.size()));

        int from = (safePage - 1) * VIEW_SLOTS.length;
        int to = Math.min(from + VIEW_SLOTS.length, entries.size());
        int shown = 0;
        for (int i = from; i < to; i++) {
            CraftEntry entry = entries.get(i);
            ItemStack icon = entry.result.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<String>();
                if (entry.workbench != null) {
                    lore.add(plugin.tr("gui.crafts.type.workbench", "&8Type: &fWorkbench"));
                    lore.add(plugin.tr("gui.crafts.available", "&8Available now: &f%count%", "count", String.valueOf(entry.availableSets)));
                    lore.add(plugin.tr("gui.crafts.shape.text", "&8Form: &f%shape%", "shape",
                            entry.workbench.shaped
                                    ? plugin.tr("gui.crafts.shape.shaped", "Shaped")
                                    : plugin.tr("gui.crafts.shape.shapeless", "Shapeless")));
                    lore.add(plugin.tr("gui.crafts.how", "&8How to get: &f%ingredients%", "ingredients", describeWorkbench(entry.workbench)));
                } else {
                    lore.add(plugin.tr("gui.crafts.type.anvil", "&8Type: &fAnvil"));
                    lore.add(plugin.tr("gui.crafts.available", "&8Available now: &f%count%", "count", String.valueOf(entry.availableSets)));
                    lore.add(plugin.tr("gui.crafts.cost", "&8Cost: &f%cost%", "cost", String.valueOf(entry.anvil.cost)));
                    lore.add(plugin.tr("gui.crafts.how", "&8How to get: &f%ingredients%", "ingredients", describeAnvil(entry.anvil)));
                }
                lore.add(plugin.tr("gui.crafts.id", "&8ID: &7%id%", "id", entry.id));
                lore.add(" ");
                lore.add(plugin.tr("gui.crafts.preview", "&7Left click to preview"));
                meta.setLore(ItemUtil.colorLines(lore));
                icon.setItemMeta(meta);
            }
            int slot = VIEW_SLOTS[shown++];
            inventory.setItem(slot, icon);
            refs.put(slot, new EntryRef(entry.id, entry.workbench != null ? "wb" : "anv"));
        }

        if (entries.isEmpty()) inventory.setItem(22, emptyItem());
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        boolean list = Text.plain(title).equals(Text.plain(plugin.titleCrafts()));
        boolean preview = Text.plain(title).equals(Text.plain(plugin.titleCraftPreview()));
        if (!list && !preview) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();
        int page = page(player);
        if (slot == CLOSE_SLOT) {
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    player.closeInventory();
                }
            });
            return;
        }
        if (preview && slot == PREVIOUS_SLOT) {
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    open(player, page);
                }
            });
            return;
        }
        if (!list) return;
        if (slot == PREVIOUS_SLOT) {
            final int targetPage = page - 1;
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    open(player, targetPage);
                }
            });
            return;
        }
        if (slot == NEXT_SLOT) {
            final int targetPage = page + 1;
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    open(player, targetPage);
                }
            });
            return;
        }

        Map<Integer, EntryRef> refs = visibleEntries.get(player.getUniqueId());
        EntryRef ref = refs == null ? null : refs.get(slot);
        if (ref == null) return;
        CraftEntry entry = findEntry(player, ref.id, ref.type);
        if (entry != null) {
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    openPreview(player, entry);
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
    public void onQuit(PlayerQuitEvent event) {
        clearState(event.getPlayer().getUniqueId());
    }

    private void clearState(UUID id) {
        pages.remove(id);
        visibleEntries.remove(id);
    }

    private void openPreview(Player player, CraftEntry entry) {
        Inventory inventory = baseInventory(plugin.titleCraftPreview());
        inventory.setItem(NEXT_SLOT, ItemUtil.named(Compat.blackPane(), " "));
        if (entry.workbench != null) fillWorkbenchPreview(inventory, entry);
        else fillAnvilPreview(inventory, entry);
        inventory.setItem(PREVIOUS_SLOT, ItemUtil.named(
                Material.ARROW,
                plugin.tr("gui.nav.back.name", "&e← Back"),
                plugin.tr("gui.nav.back.lore", "&7Return to the list")));
        player.openInventory(inventory);
    }

    public void openWorkbenchPreview(Player player, WorkbenchRecipe recipe) {
        if (player == null || recipe == null) return;
        openPreview(player, CraftEntry.workbench(recipe, 0));
    }

    public void openAnvilPreview(Player player, AnvilRecipe recipe) {
        if (player == null || recipe == null) return;
        openPreview(player, CraftEntry.anvil(recipe, 0));
    }

    private void fillWorkbenchPreview(Inventory inventory, CraftEntry entry) {
        WorkbenchRecipe recipe = entry.workbench;
        List<String> lore = new ArrayList<String>();
        lore.add(plugin.tr("gui.preview.workbench.type", "&8Type: &fWorkbench"));
        lore.add(plugin.tr("gui.preview.workbench.form", "&8Form: &f%shape%", "shape",
                recipe.shaped ? plugin.tr("gui.crafts.shape.shaped", "Shaped") : plugin.tr("gui.crafts.shape.shapeless", "Shapeless")));
        lore.add(plugin.tr("gui.preview.workbench.available", "&8Available now: &f%count%", "count", String.valueOf(entry.availableSets)));
        lore.add(plugin.tr("gui.preview.workbench.result", "&8Result per craft: &f%amount%", "amount", String.valueOf(Math.max(1, recipe.result.getAmount()))));
        lore.add(plugin.tr("gui.preview.workbench.id", "&8ID: &7%id%", "id", entry.id));
        inventory.setItem(INFO_SLOT, ItemUtil.named(Compat.knowledgeBook(), plugin.tr("gui.preview.info.name", "&bCraft preview"), lore.toArray(new String[lore.size()])));

        int[] slots = {10, 11, 12, 19, 20, 21, 28, 29, 30};
        for (int slot : slots) inventory.setItem(slot, null);
        int index = 0;
        for (ItemStack ingredient : recipe.ingredients) {
            if (Compat.isAir(ingredient)) {
                if (recipe.shaped) index++;
                continue;
            }
            if (index >= slots.length) break;
            inventory.setItem(slots[index++], RecipeQuery.cloneOne(ingredient));
        }
        inventory.setItem(24, recipe.result.clone());
    }

    private void fillAnvilPreview(Inventory inventory, CraftEntry entry) {
        AnvilRecipe recipe = entry.anvil;
        List<String> lore = new ArrayList<String>();
        lore.add(plugin.tr("gui.preview.anvil.type", "&8Type: &fAnvil"));
        lore.add(plugin.tr("gui.preview.anvil.available", "&8Available now: &f%count%", "count", String.valueOf(entry.availableSets)));
        lore.add(plugin.tr("gui.preview.anvil.cost", "&8Cost: &f%cost%", "cost", String.valueOf(recipe.cost)));
        lore.add(plugin.tr("gui.preview.anvil.id", "&8ID: &7%id%", "id", entry.id));
        inventory.setItem(INFO_SLOT, ItemUtil.named(Compat.experienceBottle(), plugin.tr("gui.preview.info.name", "&bCraft preview"), lore.toArray(new String[lore.size()])));
        inventory.setItem(20, RecipeQuery.cloneOne(recipe.left));
        inventory.setItem(22, RecipeQuery.cloneOne(recipe.right));
        inventory.setItem(24, recipe.result.clone());
    }

    private List<CraftEntry> buildEntries(Player player) {
        List<CraftEntry> entries = new ArrayList<CraftEntry>();
        Map<String, Integer> workbenchCounts = RecipeQuery.inventoryCounts(player.getInventory(), plugin, MatchMode.WORKBENCH);
        Map<String, Integer> anvilCounts = plugin.exactMeta()
                ? workbenchCounts
                : RecipeQuery.inventoryCounts(player.getInventory(), plugin, MatchMode.ANVIL);

        for (WorkbenchRecipe recipe : storage.getWorkbenchRecipes()) {
            int sets = RecipeQuery.craftSets(workbenchCounts,
                    RecipeQuery.requirementCounts(recipe.ingredients, plugin, MatchMode.WORKBENCH));
            entries.add(CraftEntry.workbench(recipe, sets));
        }
        for (AnvilRecipe recipe : storage.getAnvilRecipes()) {
            Map<String, Integer> requirements = new LinkedHashMap<String, Integer>();
            merge(requirements, RecipeQuery.signature(recipe.left, plugin, MatchMode.ANVIL), Math.max(1, recipe.left.getAmount()));
            merge(requirements, RecipeQuery.signature(recipe.right, plugin, MatchMode.ANVIL), Math.max(1, recipe.right.getAmount()));
            int sets = RecipeQuery.craftSets(anvilCounts, requirements);
            if (recipe.cost > 0) sets = Math.min(sets, player.getLevel() / recipe.cost);
            entries.add(CraftEntry.anvil(recipe, sets));
        }

        Collections.sort(entries, new Comparator<CraftEntry>() {
            @Override
            public int compare(CraftEntry first, CraftEntry second) {
                int kind = first.kindOrder() - second.kindOrder();
                if (kind != 0) return kind;
                int available = second.availableSets - first.availableSets;
                if (available != 0) return available;
                return first.id.compareTo(second.id);
            }
        });
        return entries;
    }

    private CraftEntry findEntry(Player player, String id, String type) {
        for (CraftEntry entry : buildEntries(player)) {
            if (entry.id.equals(id) && entry.type().equals(type)) return entry;
        }
        return null;
    }

    private Inventory baseInventory(String title) {
        Inventory inventory = Bukkit.createInventory(null, 54, title);
        ItemStack filler = ItemUtil.named(Compat.blackPane(), " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler.clone());
        inventory.setItem(PREVIOUS_SLOT, ItemUtil.named(Material.ARROW, plugin.tr("gui.nav.prev.name", "&e← Previous"), plugin.tr("gui.nav.prev.lore", "&7Go to the previous page")));
        inventory.setItem(CLOSE_SLOT, ItemUtil.named(Material.BARRIER, plugin.tr("gui.nav.close.name", "&cClose"), plugin.tr("gui.nav.close.lore", "&7Close this menu")));
        inventory.setItem(NEXT_SLOT, ItemUtil.named(Material.ARROW, plugin.tr("gui.nav.next.name", "&eNext →"), plugin.tr("gui.nav.next.lore", "&7Go to the next page")));
        return inventory;
    }

    private ItemStack summaryItem(int page, int totalPages, int totalEntries) {
        List<String> lore = new ArrayList<String>();
        lore.add(plugin.tr("gui.crafts.summary.page", "&7Page: &f%page%&7/&f%pages%", "page", String.valueOf(page), "pages", String.valueOf(totalPages)));
        lore.add(plugin.tr("gui.crafts.summary.total", "&7Visible crafts: &f%count%", "count", String.valueOf(totalEntries)));
        lore.add(plugin.tr("gui.crafts.summary.order", "&7Order: &fAnvil first, Workbench second"));
        return ItemUtil.named(Compat.knowledgeBook(), plugin.tr("gui.crafts.summary.name", "&bAll crafts"), lore.toArray(new String[lore.size()]));
    }

    private ItemStack emptyItem() {
        return ItemUtil.named(Material.BARRIER,
                plugin.tr("gui.crafts.empty.name", "&cNo custom recipes"),
                plugin.tr("gui.crafts.empty.lore", "&7No workbench or anvil recipes have been created yet."));
    }

    private String describeWorkbench(WorkbenchRecipe recipe) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (ItemStack item : recipe.ingredients) {
            if (!Compat.isAir(item)) merge(counts, itemLabel(item), Math.max(1, item.getAmount()));
        }
        if (counts.isEmpty()) return plugin.tr("gui.crafts.ingredients.empty", "Nothing");
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) parts.add(entry.getValue() + "x " + entry.getKey());
        return join(parts, ", ");
    }

    private String describeAnvil(AnvilRecipe recipe) {
        return itemLabel(recipe.left) + " x" + Math.max(1, recipe.left.getAmount())
                + " + " + itemLabel(recipe.right) + " x" + Math.max(1, recipe.right.getAmount());
    }

    private Map<String, Integer> materialInventoryCounts(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (ItemStack item : Compat.storageContents(player.getInventory())) {
            if (!Compat.isAir(item)) merge(counts, item.getType().name(), item.getAmount());
        }
        return counts;
    }

    private Map<String, Integer> materialRequirementCounts(Iterable<ItemStack> items) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (items == null) return counts;
        for (ItemStack item : items) {
            if (!Compat.isAir(item)) merge(counts, item.getType().name(), Math.max(1, item.getAmount()));
        }
        return counts;
    }

    private String itemLabel(ItemStack item) {
        if (Compat.isAir(item)) return "AIR";
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? Text.plain(meta.getDisplayName()) : item.getType().name();
    }

    private void merge(Map<String, Integer> map, String key, int amount) {
        if (key == null || amount <= 0) return;
        Integer current = map.get(key);
        map.put(key, (current == null ? 0 : current.intValue()) + amount);
    }

    private int page(Player player) {
        Integer page = pages.get(player.getUniqueId());
        return page == null ? 1 : page.intValue();
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class EntryRef {
        private final String id;
        private final String type;

        private EntryRef(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }

    private static final class CraftEntry {
        private final String id;
        private final WorkbenchRecipe workbench;
        private final AnvilRecipe anvil;
        private final int availableSets;
        private final ItemStack result;

        private CraftEntry(String id, WorkbenchRecipe workbench, AnvilRecipe anvil, int availableSets, ItemStack result) {
            this.id = id;
            this.workbench = workbench;
            this.anvil = anvil;
            this.availableSets = availableSets;
            this.result = result;
        }

        private static CraftEntry workbench(WorkbenchRecipe recipe, int sets) {
            return new CraftEntry(recipe.id, recipe, null, sets, recipe.result.clone());
        }

        private static CraftEntry anvil(AnvilRecipe recipe, int sets) {
            return new CraftEntry(recipe.id, null, recipe, sets, recipe.result.clone());
        }

        private int kindOrder() {
            return workbench == null ? 0 : 1;
        }

        private String type() {
            return workbench == null ? "anv" : "wb";
        }
    }
}
