package ru.refontstudio.refontcrafts.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.util.ItemUtil;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorkbenchListener implements Listener {
    private static final int DEFAULT_RESULT_PREVIEW_LIMIT = 126;

    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<NamespacedKey, List<ItemStack>> reqCache = new ConcurrentHashMap<>();
    private final Set<UUID> customPreviewPlayers = ConcurrentHashMap.newKeySet();

    public WorkbenchListener(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;

        NamespacedKey key = null;
        if (e.getRecipe() instanceof ShapedRecipe) key = ((ShapedRecipe) e.getRecipe()).getKey();
        if (e.getRecipe() instanceof ShapelessRecipe) key = ((ShapelessRecipe) e.getRecipe()).getKey();
        if (key == null || !key.getNamespace().equalsIgnoreCase(plugin.getName())) return;

        CraftingInventory inv = e.getInventory();
        ItemStack[] matrix = inv.getMatrix();
        if (matrix == null || matrix.length == 0) return;

        if (e.getRecipe() instanceof ShapedRecipe) {
            ShapedRecipe sr = (ShapedRecipe) e.getRecipe();
            List<ItemStack> req9 = getShapedRequirements(key, sr);
            if (req9.isEmpty()) return;

            int possible = computeSetsPossibleShaped(req9, matrix, plugin.exactMeta());
            if (possible <= 0) { inv.setResult(null); return; }

            ItemStack base = sr.getResult().clone();
            setPreviewResult(inv, previewResult(base, possible));
            return;
        }

        if (e.getRecipe() instanceof ShapelessRecipe) {
            ShapelessRecipe sr = (ShapelessRecipe) e.getRecipe();
            List<ItemStack> req = getShapelessRequirements(key, sr);
            if (req.isEmpty()) return;

            int possible = computeSetsPossibleShapeless(req, matrix, plugin.exactMeta());
            if (possible <= 0) { inv.setResult(null); return; }

            ItemStack base = sr.getResult().clone();
            setPreviewResult(inv, previewResult(base, possible));
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (e.getRecipe() == null) return;

        NamespacedKey key = null;
        if (e.getRecipe() instanceof ShapedRecipe) key = ((ShapedRecipe) e.getRecipe()).getKey();
        if (e.getRecipe() instanceof ShapelessRecipe) key = ((ShapelessRecipe) e.getRecipe()).getKey();
        if (key == null || !key.getNamespace().equalsIgnoreCase(plugin.getName())) return;
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player p = (Player) e.getWhoClicked();
        CraftingInventory inv = e.getInventory();
        ItemStack[] matrix = inv.getMatrix();
        if (matrix == null || matrix.length == 0) return;

        if (e.getRecipe() instanceof ShapedRecipe) {
            ShapedRecipe sr = (ShapedRecipe) e.getRecipe();
            List<ItemStack> req9 = getShapedRequirements(key, sr);
            if (req9.isEmpty()) return;

            int possible = computeSetsPossibleShaped(req9, matrix, plugin.exactMeta());
            if (possible <= 0) { e.setCancelled(true); inv.setResult(null); p.updateInventory(); return; }

            ItemStack base = sr.getResult().clone();
            int perSet = Math.max(1, base.getAmount());
            int stackLimit = previewLimit();

            boolean shift = e.isShiftClick();
            int setsWanted;
            if (shift) {
                int cap = capacityForItem(p.getInventory(), base);
                int byInv = Math.max(0, cap / perSet);
                setsWanted = Math.min(possible, byInv);
                if (setsWanted <= 0) { e.setCancelled(true); p.sendMessage(plugin.msg("no_inventory_space")); return; }
            } else {
                setsWanted = previewSetsFor(possible, perSet);
            }

            int[] left = simulateConsumeShaped(req9, matrix, setsWanted);
            if (left == null) { e.setCancelled(true); inv.setResult(null); p.updateInventory(); return; }

            int totalItems = perSet * setsWanted;

            e.setCancelled(true);

            if (shift) {
                int accepted = addToInventory(p.getInventory(), base, totalItems);
                if (accepted < totalItems) {
                    int setsAccepted = accepted / perSet;
                    left = simulateConsumeShaped(req9, matrix, setsAccepted);
                    if (left == null || setsAccepted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                    totalItems = accepted;
                }
            } else {
                ItemStack cursor = p.getItemOnCursor();
                int canOnCursor;
                if (cursor == null || cursor.getType() == Material.AIR) canOnCursor = stackLimit;
                else if (cursor.isSimilar(base)) canOnCursor = Math.max(0, stackLimit - cursor.getAmount());
                else canOnCursor = 0;
                int totalCapacity = canOnCursor + capacityForItem(p.getInventory(), base);
                setsWanted = Math.min(setsWanted, Math.max(0, totalCapacity / perSet));
                if (setsWanted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                totalItems = perSet * setsWanted;
                left = simulateConsumeShaped(req9, matrix, setsWanted);
                if (left == null) { inv.setResult(null); p.updateInventory(); return; }
                int putOnCursor = Math.min(totalItems, canOnCursor);
                if (putOnCursor > 0) {
                    if (cursor == null || cursor.getType() == Material.AIR) {
                        ItemStack toSet = base.clone(); toSet.setAmount(putOnCursor);
                        p.setItemOnCursor(toSet);
                    } else {
                        cursor.setAmount(cursor.getAmount() + putOnCursor);
                        p.setItemOnCursor(cursor);
                    }
                }
                int rest = totalItems - putOnCursor;
                if (rest > 0) {
                    int acceptedRest = addToInventory(p.getInventory(), base, rest);
                    if (acceptedRest < rest) {
                        int accepted = acceptedRest + putOnCursor;
                        int setsAccepted = accepted / perSet;
                        left = simulateConsumeShaped(req9, matrix, setsAccepted);
                        if (left == null || setsAccepted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                        totalItems = accepted;
                    }
                }
            }

            ItemStack[] newMatrix = new ItemStack[matrix.length];
            for (int i = 0; i < matrix.length; i++) {
                int leftAmt = (i < left.length ? left[i] : 0);
                if (leftAmt <= 0) newMatrix[i] = null;
                else if (matrix[i] != null && matrix[i].getType() != Material.AIR) {
                    ItemStack c = matrix[i].clone(); c.setAmount(leftAmt); newMatrix[i] = c;
                } else newMatrix[i] = null;
            }
            inv.setMatrix(newMatrix);
            inv.setResult(null);
            p.updateInventory();

            String sName = plugin.getConfig().getString("settings.sounds.workbench_success.name", "UI_STONECUTTER_TAKE_RESULT");
            float vol = (float) plugin.getConfig().getDouble("settings.sounds.workbench_success.volume", 1.0);
            float pit = (float) plugin.getConfig().getDouble("settings.sounds.workbench_success.pitch", 1.05);
            p.playSound(p.getLocation(), safeSound(sName, Sound.UI_STONECUTTER_TAKE_RESULT), vol, pit);
            return;
        }

        if (e.getRecipe() instanceof ShapelessRecipe) {
            ShapelessRecipe sr = (ShapelessRecipe) e.getRecipe();
            List<ItemStack> req = getShapelessRequirements(key, sr);
            if (req.isEmpty()) return;

            int possible = computeSetsPossibleShapeless(req, matrix, plugin.exactMeta());
            if (possible <= 0) { e.setCancelled(true); inv.setResult(null); p.updateInventory(); return; }

            ItemStack base = sr.getResult().clone();
            int perSet = Math.max(1, base.getAmount());
            int stackLimit = previewLimit();

            boolean shift = e.isShiftClick();
            int setsWanted;
            if (shift) {
                int cap = capacityForItem(p.getInventory(), base);
                int byInv = Math.max(0, cap / perSet);
                setsWanted = Math.min(possible, byInv);
                if (setsWanted <= 0) { e.setCancelled(true); p.sendMessage(plugin.msg("no_inventory_space")); return; }
            } else {
                setsWanted = previewSetsFor(possible, perSet);
            }

            int[] left = simulateConsumeShapeless(req, matrix, plugin.exactMeta(), setsWanted);
            if (left == null) { e.setCancelled(true); inv.setResult(null); p.updateInventory(); return; }

            int totalItems = perSet * setsWanted;

            e.setCancelled(true);

            if (shift) {
                int accepted = addToInventory(p.getInventory(), base, totalItems);
                if (accepted < totalItems) {
                    int setsAccepted = accepted / perSet;
                    left = simulateConsumeShapeless(req, matrix, plugin.exactMeta(), setsAccepted);
                    if (left == null || setsAccepted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                    totalItems = accepted;
                }
            } else {
                ItemStack cursor = p.getItemOnCursor();
                int canOnCursor;
                if (cursor == null || cursor.getType() == Material.AIR) canOnCursor = stackLimit;
                else if (cursor.isSimilar(base)) canOnCursor = Math.max(0, stackLimit - cursor.getAmount());
                else canOnCursor = 0;
                int totalCapacity = canOnCursor + capacityForItem(p.getInventory(), base);
                setsWanted = Math.min(setsWanted, Math.max(0, totalCapacity / perSet));
                if (setsWanted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                totalItems = perSet * setsWanted;
                left = simulateConsumeShapeless(req, matrix, plugin.exactMeta(), setsWanted);
                if (left == null) { inv.setResult(null); p.updateInventory(); return; }
                int putOnCursor = Math.min(totalItems, canOnCursor);
                if (putOnCursor > 0) {
                    if (cursor == null || cursor.getType() == Material.AIR) {
                        ItemStack toSet = base.clone(); toSet.setAmount(putOnCursor);
                        p.setItemOnCursor(toSet);
                    } else {
                        cursor.setAmount(cursor.getAmount() + putOnCursor);
                        p.setItemOnCursor(cursor);
                    }
                }
                int rest = totalItems - putOnCursor;
                if (rest > 0) {
                    int acceptedRest = addToInventory(p.getInventory(), base, rest);
                    if (acceptedRest < rest) {
                        int accepted = acceptedRest + putOnCursor;
                        int setsAccepted = accepted / perSet;
                        left = simulateConsumeShapeless(req, matrix, plugin.exactMeta(), setsAccepted);
                        if (left == null || setsAccepted <= 0) { p.sendMessage(plugin.msg("no_inventory_space")); return; }
                        totalItems = accepted;
                    }
                }
            }

            ItemStack[] newMatrix = new ItemStack[matrix.length];
            for (int i = 0; i < matrix.length; i++) {
                if (left[i] <= 0) newMatrix[i] = null;
                else { ItemStack c = matrix[i].clone(); c.setAmount(left[i]); newMatrix[i] = c; }
            }
            inv.setMatrix(newMatrix);
            inv.setResult(null);
            p.updateInventory();

            String sName = plugin.getConfig().getString("settings.sounds.workbench_success.name", "UI_STONECUTTER_TAKE_RESULT");
            float vol = (float) plugin.getConfig().getDouble("settings.sounds.workbench_success.volume", 1.0);
            float pit = (float) plugin.getConfig().getDouble("settings.sounds.workbench_success.pitch", 1.05);
            p.playSound(p.getLocation(), safeSound(sName, Sound.UI_STONECUTTER_TAKE_RESULT), vol, pit);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWorkbenchInputClickRefresh(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        InventoryType t = e.getView().getTopInventory().getType();
        if (t != InventoryType.WORKBENCH && t != InventoryType.CRAFTING) return;
        if (!isMatrixRawSlot(t, e.getRawSlot())) return;
        scheduleCraftPreviewRefresh((Player) e.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWorkbenchInputDragRefresh(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        InventoryType t = e.getView().getTopInventory().getType();
        if (t != InventoryType.WORKBENCH && t != InventoryType.CRAFTING) return;
        for (Integer raw : e.getRawSlots()) {
            if (raw != null && isMatrixRawSlot(t, raw)) {
                scheduleCraftPreviewRefresh((Player) e.getWhoClicked());
                return;
            }
        }
    }

    @EventHandler
    public void onWorkbenchClose(InventoryCloseEvent e) {
        customPreviewPlayers.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        customPreviewPlayers.remove(e.getPlayer().getUniqueId());
    }

    private boolean isMatrixRawSlot(InventoryType t, int raw) {
        return (t == InventoryType.WORKBENCH && raw >= 1 && raw <= 9)
                || (t == InventoryType.CRAFTING && raw >= 1 && raw <= 4);
    }

    private void scheduleCraftPreviewRefresh(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(p.getOpenInventory().getTopInventory() instanceof CraftingInventory)) return;
            CraftingInventory inv = (CraftingInventory) p.getOpenInventory().getTopInventory();
            ItemStack preview = findCustomPreview(inv);
            UUID id = p.getUniqueId();
            if (preview != null) {
                setPreviewResult(inv, preview);
                customPreviewPlayers.add(id);
                p.updateInventory();
                return;
            }
            if (customPreviewPlayers.remove(id) && isCustomResult(inv.getResult())) {
                inv.setResult(null);
                p.updateInventory();
            }
        });
    }

    private ItemStack findCustomPreview(CraftingInventory inv) {
        ItemStack[] matrix = inv.getMatrix();
        if (matrix == null || matrix.length == 0) return null;
        boolean exact = plugin.exactMeta();
        for (RecipeStorage.WorkbenchRecipe r : storage.getWorkbenchRecipes()) {
            if (r.shaped && plugin.workbenchStrictShape()) {
                if (matrix.length < 9 || r.ingredients.size() != 9) continue;
                List<ItemStack> req9 = normalizeGrid(r.ingredients);
                int possible = computeSetsPossibleShaped(req9, matrix, exact);
                if (possible <= 0 && plugin.workbenchAllowMirror()) possible = computeSetsPossibleShaped(mirrorGrid(req9), matrix, exact);
                if (possible > 0) return previewResult(r.result, possible);
            } else {
                List<ItemStack> req = normalizeShapeless(r.ingredients);
                if (req.isEmpty()) continue;
                int possible = computeSetsPossibleShapeless(req, matrix, exact);
                if (possible > 0) return previewResult(r.result, possible);
            }
        }
        return null;
    }

    private List<ItemStack> normalizeGrid(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack it = i < src.size() ? src.get(i) : null;
            if (it == null || it.getType() == Material.AIR) out.add(new ItemStack(Material.AIR));
            else out.add(cloneWithAmount(it, 1));
        }
        return out;
    }

    private List<ItemStack> mirrorGrid(List<ItemStack> req9) {
        List<ItemStack> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            int src = (i / 3) * 3 + (2 - (i % 3));
            ItemStack it = req9.get(src);
            out.add(it == null ? new ItemStack(Material.AIR) : it.clone());
        }
        return out;
    }

    private List<ItemStack> normalizeShapeless(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : src) {
            if (it == null || it.getType() == Material.AIR) continue;
            out.add(cloneWithAmount(it, 1));
        }
        return out;
    }

    private ItemStack previewResult(ItemStack result, int possible) {
        ItemStack base = result.clone();
        int perSet = Math.max(1, base.getAmount());
        int previewSets = previewSetsFor(possible, perSet);
        base.setAmount(perSet * previewSets);
        return base;
    }

    private int previewSetsFor(int possible, int perSet) {
        return Math.min(possible, Math.max(1, previewLimit() / Math.max(1, perSet)));
    }

    private int previewLimit() {
        return Math.max(1, plugin.getConfig().getInt("settings.workbench_preview_limit", DEFAULT_RESULT_PREVIEW_LIMIT));
    }

    private void setPreviewResult(CraftingInventory inv, ItemStack item) {
        try { inv.setMaxStackSize(previewLimit()); } catch (Throwable ignored) {}
        inv.setResult(item);
    }

    private boolean isCustomResult(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        for (RecipeStorage.WorkbenchRecipe r : storage.getWorkbenchRecipes()) {
            if (ItemUtil.matchesIngredient(item, r.result, plugin.exactMeta())) return true;
        }
        return false;
    }

    private Sound safeSound(String name, Sound def) {
        try { return Sound.valueOf(name); } catch (Throwable ignored) { return def; }
    }

    private List<ItemStack> getShapelessRequirementsCached(ShapelessRecipe sr) {
        NamespacedKey k = sr.getKey();
        if (k == null) return extractShapeless(sr);
        if (reqCache.size() > 1024) reqCache.clear();
        return reqCache.computeIfAbsent(k, kk -> extractShapeless(sr));
    }

    private List<ItemStack> getShapelessRequirements(NamespacedKey key, ShapelessRecipe sr) {
        List<ItemStack> stored = getStoredShapeless(key);
        if (!stored.isEmpty()) return stored;
        return getShapelessRequirementsCached(sr);
    }

    private List<ItemStack> getStoredShapeless(NamespacedKey key) {
        if (key == null) return Collections.emptyList();
        String raw = key.getKey();
        if (!raw.startsWith("shapeless_")) return Collections.emptyList();
        String id = raw.substring("shapeless_".length());

        RecipeStorage.WorkbenchRecipe recipe = storage.getWorkbenchRecipe(id);
        if (recipe == null || recipe.shaped) return Collections.emptyList();

        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : recipe.ingredients) {
            if (it == null || it.getType() == Material.AIR) continue;
            ItemStack one = it.clone();
            one.setAmount(1);
            out.add(one);
        }
        return out;
    }

    private List<ItemStack> extractShapeless(ShapelessRecipe sr) {
        List<ItemStack> req = new ArrayList<>();
        try {
            List<ItemStack> ing = sr.getIngredientList();
            if (ing != null) {
                for (ItemStack it : ing) {
                    if (it == null || it.getType() == Material.AIR) continue;
                    ItemStack one = it.clone();
                    one.setAmount(1);
                    req.add(one);
                }
                if (!req.isEmpty()) return req;
            }
        } catch (Throwable ignored) {}
        try {
            List<RecipeChoice> choices = sr.getChoiceList();
            if (choices != null) {
                for (RecipeChoice ch : choices) {
                    if (ch instanceof RecipeChoice.ExactChoice) {
                        List<ItemStack> list = ((RecipeChoice.ExactChoice) ch).getChoices();
                        if (!list.isEmpty()) {
                            ItemStack one = list.get(0).clone();
                            one.setAmount(1);
                            req.add(one);
                        }
                    } else if (ch instanceof RecipeChoice.MaterialChoice) {
                        List<Material> mats = ((RecipeChoice.MaterialChoice) ch).getChoices();
                        if (!mats.isEmpty()) req.add(new ItemStack(mats.get(0), 1));
                    }
                }
            }
        } catch (Throwable ignored) {}
        return req;
    }

    private List<ItemStack> getShapedRequirementsCached(ShapedRecipe sr) {
        NamespacedKey k = sr.getKey();
        if (k == null) return extractShaped(sr);
        if (reqCache.size() > 1024) reqCache.clear();
        return reqCache.computeIfAbsent(k, kk -> extractShaped(sr));
    }

    private List<ItemStack> getShapedRequirements(NamespacedKey key, ShapedRecipe sr) {
        List<ItemStack> stored = getStoredGrid(key);
        if (!stored.isEmpty()) return stored;
        return getShapedRequirementsCached(sr);
    }

    private List<ItemStack> getStoredGrid(NamespacedKey key) {
        if (key == null) return Collections.emptyList();
        String raw = key.getKey();
        boolean mirror = raw.startsWith("shaped_") && raw.endsWith("_m");
        String id = null;
        if (mirror) id = raw.substring("shaped_".length(), raw.length() - 2);
        else if (raw.startsWith("shaped_")) id = raw.substring("shaped_".length());
        if (id == null) return Collections.emptyList();

        RecipeStorage.WorkbenchRecipe recipe = storage.getWorkbenchRecipe(id);
        if (recipe == null || !recipe.shaped || recipe.ingredients.size() != 9) return Collections.emptyList();

        List<ItemStack> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            int src = mirror ? (i / 3) * 3 + (2 - (i % 3)) : i;
            ItemStack it = recipe.ingredients.get(src);
            out.add(it == null ? new ItemStack(Material.AIR) : it.clone());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> extractShaped(ShapedRecipe sr) {
        List<ItemStack> req9 = new ArrayList<>(9);
        String[] shape = sr.getShape();
        Map<Character, RecipeChoice> choiceMap = null;
        Map<Character, ItemStack> legacyMap = null;

        try {
            Method m = ShapedRecipe.class.getMethod("getChoiceMap");
            Object obj = m.invoke(sr);
            if (obj instanceof Map) choiceMap = (Map<Character, RecipeChoice>) obj;
        } catch (Throwable ignored) {}

        if (choiceMap == null) {
            try {
                Method m2 = ShapedRecipe.class.getMethod("getIngredientMap");
                Object obj2 = m2.invoke(sr);
                if (obj2 instanceof Map) legacyMap = (Map<Character, ItemStack>) obj2;
            } catch (Throwable ignored) {}
        }

        for (int r = 0; r < 3; r++) {
            String row = (shape != null && r < shape.length) ? shape[r] : "";
            for (int c = 0; c < 3; c++) {
                char ch = (c < row.length()) ? row.charAt(c) : ' ';
                ItemStack need = null;
                if (ch == ' ') need = new ItemStack(Material.AIR);
                else if (choiceMap != null) {
                    RecipeChoice chs = choiceMap.get(ch);
                    if (chs instanceof RecipeChoice.ExactChoice) {
                        List<ItemStack> list = ((RecipeChoice.ExactChoice) chs).getChoices();
                        if (list != null && !list.isEmpty()) need = list.get(0).clone();
                    } else if (chs instanceof RecipeChoice.MaterialChoice) {
                        List<Material> mats = ((RecipeChoice.MaterialChoice) chs).getChoices();
                        if (mats != null && !mats.isEmpty()) need = new ItemStack(mats.get(0), 1);
                    }
                } else if (legacyMap != null) {
                    ItemStack got = legacyMap.get(ch);
                    if (got != null) { need = got.clone(); need.setAmount(1); }
                }
                if (need == null) need = new ItemStack(Material.AIR);
                req9.add(need);
            }
        }
        return req9;
    }

    private int computeSetsPossibleShapeless(List<ItemStack> req, ItemStack[] matrix, boolean exact) {
        int[] left = new int[matrix.length];
        ItemStack[] items = new ItemStack[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            ItemStack it = matrix[i];
            items[i] = it;
            left[i] = (it == null || it.getType() == Material.AIR) ? 0 : it.getAmount();
        }
        int sets = 0;
        while (true) {
            boolean ok = true;
            for (ItemStack need : req) {
                int idx = findMatchIndex(need, items, left, exact);
                if (idx == -1) { ok = false; break; }
                left[idx]--;
            }
            if (!ok) break;
            sets++;
        }
        return sets;
    }

    private int[] simulateConsumeShapeless(List<ItemStack> req, ItemStack[] matrix, boolean exact, int sets) {
        int[] left = new int[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            ItemStack it = matrix[i];
            left[i] = (it == null || it.getType() == Material.AIR) ? 0 : it.getAmount();
        }
        for (int s = 0; s < sets; s++) {
            for (ItemStack need : req) {
                int idx = findMatchIndex(need, matrix, left, exact);
                if (idx == -1) return null;
                left[idx]--;
            }
        }
        return left;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onWorkbenchInputShift(InventoryClickEvent e) {
        InventoryType t = e.getInventory().getType();
        if (t != InventoryType.WORKBENCH && t != InventoryType.CRAFTING) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        int raw = e.getRawSlot();
        int topSize = e.getView().getTopInventory().getSize();

        boolean isMatrixSlot = (t == InventoryType.WORKBENCH && raw >= 1 && raw <= 9)
                || (t == InventoryType.CRAFTING && raw >= 1 && raw <= 4);
        if (!isMatrixSlot) return;

        if (e.isShiftClick()) {
            Player p = (Player) e.getWhoClicked();
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType() == Material.AIR) return;
            e.setCancelled(true);
            Map<Integer, ItemStack> rem = p.getInventory().addItem(cur.clone());
            int back = 0; for (ItemStack r : rem.values()) if (r != null) back += r.getAmount();
            if (back <= 0) {
                e.getInventory().setItem(raw, null);
            } else {
                ItemStack rest = cur.clone(); rest.setAmount(back);
                e.getInventory().setItem(raw, rest);
            }
            p.updateInventory();
        }
    }

    private int computeSetsPossibleShaped(List<ItemStack> req9, ItemStack[] matrix, boolean exact) {
        int possible = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack need = req9.get(i);
            if (need == null || need.getType() == Material.AIR) {
                if (i < matrix.length) {
                    ItemStack m = matrix[i];
                    if (m != null && m.getType() != Material.AIR) return 0;
                }
                continue;
            }
            ItemStack have = i < matrix.length ? matrix[i] : null;
            if (have == null || have.getType() == Material.AIR) return 0;
            if (!ItemUtil.matchesIngredient(have, need, exact)) return 0;
            int can = Math.max(0, have.getAmount() / Math.max(1, need.getAmount()));
            possible = Math.min(possible, can);
        }
        if (possible == Integer.MAX_VALUE) possible = 0;
        return possible;
    }

    private int[] simulateConsumeShaped(List<ItemStack> req9, ItemStack[] matrix, int sets) {
        int[] left = new int[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            ItemStack it = matrix[i];
            left[i] = (it == null || it.getType() == Material.AIR) ? 0 : it.getAmount();
        }
        for (int i = 0; i < 9; i++) {
            ItemStack need = req9.get(i);
            if (need == null || need.getType() == Material.AIR) continue;
            int needAmt = Math.max(1, need.getAmount()) * sets;
            if (i >= matrix.length) return null;
            if (left[i] < needAmt) return null;
            left[i] -= needAmt;
        }
        return left;
    }

    private int findMatchIndex(ItemStack need, ItemStack[] items, int[] left, boolean exact) {
        for (int i = 0; i < items.length; i++) {
            if (left[i] <= 0) continue;
            ItemStack have = items[i];
            if (have == null || have.getType() == Material.AIR) continue;
            if (!ItemUtil.matchesIngredient(have, need, exact)) continue;
            return i;
        }
        return -1;
    }

    private int capacityForItem(PlayerInventory inv, ItemStack sample) {
        int cap = 0;
        ItemStack[] cont = inv.getStorageContents();
        int max = previewLimit();
        for (ItemStack it : cont) {
            if (it == null || it.getType() == Material.AIR) cap += max;
            else if (it.isSimilar(sample)) cap += Math.max(0, max - it.getAmount());
        }
        return cap;
    }

    private int addToInventory(PlayerInventory inv, ItemStack sample, int amount) {
        int left = Math.max(0, amount);
        int limit = previewLimit();
        ItemStack[] cont = inv.getStorageContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (it == null || it.getType() == Material.AIR || !it.isSimilar(sample)) continue;
            int add = Math.min(left, Math.max(0, limit - it.getAmount()));
            if (add <= 0) continue;
            ItemStack updated = it.clone();
            updated.setAmount(it.getAmount() + add);
            cont[i] = updated;
            left -= add;
        }
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (it != null && it.getType() != Material.AIR) continue;
            int add = Math.min(left, limit);
            cont[i] = cloneWithAmount(sample, add);
            left -= add;
        }
        inv.setStorageContents(cont);
        return amount - left;
    }

    private ItemStack cloneWithAmount(ItemStack it, int amount) {
        ItemStack c = it.clone();
        c.setAmount(amount);
        return c;
    }
}