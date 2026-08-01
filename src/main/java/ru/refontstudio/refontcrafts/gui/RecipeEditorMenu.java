package ru.refontstudio.refontcrafts.gui;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RecipeEditorMenu implements Listener {
    private static final int[] INGREDIENT_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT_SLOT = 25;
    private static final int HELP_SLOT = 45;
    private static final int SAVE_SLOT = 49;
    private static final int CLEAR_SLOT = 50;
    private static final int EXIT_SLOT = 53;

    private final RefontCrafts plugin;
    private final RecipeStorage storage;
    private final Map<UUID, EditorSession> sessions = new HashMap<UUID, EditorSession>();

    public RecipeEditorMenu(RefontCrafts plugin, RecipeStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void openEditor(Player player) {
        Inventory inventory = createBaseInventory(false);
        EditorSession session = new EditorSession(null, inventory);
        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), session);
    }

    public void openEditorForEdit(Player player, String recipeId, List<ItemStack> grid, ItemStack result, boolean shaped) {
        Inventory inventory = createBaseInventory(true);
        EditorSession session = new EditorSession(recipeId, inventory);

        for (int i = 0; i < INGREDIENT_SLOTS.length; i++) {
            ItemStack item = grid != null && i < grid.size() ? grid.get(i) : null;
            if (Compat.isAir(item)) continue;
            inventory.setItem(INGREDIENT_SLOTS[i], item.clone());
            session.ghostSlots.add(INGREDIENT_SLOTS[i]);
        }
        if (!Compat.isAir(result)) {
            inventory.setItem(RESULT_SLOT, result.clone());
            session.ghostSlots.add(RESULT_SLOT);
        }

        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), session);
    }

    private Inventory createBaseInventory(boolean editing) {
        Inventory inventory = Bukkit.createInventory(null, 54, plugin.titleRecipe());
        ItemStack filler = ItemUtil.named(Compat.grayPane(), " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler.clone());
        for (int slot : INGREDIENT_SLOTS) inventory.clear(slot);
        inventory.clear(RESULT_SLOT);
        inventory.setItem(HELP_SLOT, ItemUtil.named(
                Material.BOOK,
                plugin.tr("gui.editor.help.name", "&bShift controls"),
                plugin.tr("gui.editor.help.inputs", "&7Shift+left: input slots"),
                plugin.tr("gui.editor.help.result", "&7Shift+right or Shift on target: result"),
                plugin.tr("gui.editor.help.number", "&7Hover a slot and press 1-9: hotbar swap"),
                plugin.tr("gui.editor.help.remove", "&7Shift inside editor: return/remove")));
        inventory.setItem(SAVE_SLOT, ItemUtil.named(
                Compat.limeWool(),
                plugin.tr("gui.editor.save.name", "&aSave"),
                plugin.tr(editing ? "gui.editor.save.edit_lore" : "gui.editor.save.lore",
                        editing ? "&7Resave the recipe" : "&7Save the recipe")));
        inventory.setItem(CLEAR_SLOT, ItemUtil.named(
                Compat.yellowWool(),
                plugin.tr("gui.editor.clear.name", "&eClear"),
                plugin.tr("gui.editor.clear.lore", "&7Remove all items")));
        inventory.setItem(EXIT_SLOT, ItemUtil.named(
                Material.BARRIER,
                plugin.tr("gui.editor.exit.name", "&cExit"),
                plugin.tr("gui.editor.exit.lore", "&7Return your items and close")));
        return inventory;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!isEditorTitle(event.getView().getTitle())) return;

        Player player = (Player) event.getWhoClicked();
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            event.setCancelled(true);
            closeNextTick(player);
            return;
        }
        Inventory top = session.inventory;
        int rawSlot = event.getRawSlot();

        if (rawSlot < 0) {
            event.setCancelled(true);
            return;
        }

        // Lower inventory stays usable. Shift+left fills inputs; Shift+right fills the result slot.
        if (rawSlot >= top.getSize()) {
            if (session.pending) {
                event.setCancelled(true);
                return;
            }
            if (isCreative(player)) return;
            if (session.ghostCursor || isUnsafeClick(event)) {
                event.setCancelled(true);
                return;
            }
            if (event.isShiftClick()) {
                event.setCancelled(true);
                final Inventory sourceInventory = event.getClickedInventory();
                final int sourceSlot = event.getSlot();
                final boolean intoResult = isShiftRight(event);
                queueEdit(player, top, session, new Runnable() {
                    @Override
                    public void run() {
                        ItemStack source = sourceInventory == null ? null : sourceInventory.getItem(sourceSlot);
                        ItemStack remaining = intoResult
                                ? shiftStackIntoResult(player, top, source, session)
                                : shiftStackIntoInputs(top, source, session);
                        if (sourceInventory != null) sourceInventory.setItem(sourceSlot, remaining);
                    }
                });
            }
            return;
        }

        int slot = rawSlot;
        if (slot == SAVE_SLOT) {
            event.setCancelled(true);
            if (!session.pending) {
                queueEdit(player, top, session, new Runnable() {
                    @Override
                    public void run() {
                        save(player, top, session);
                    }
                });
            }
            return;
        }
        if (slot == CLEAR_SLOT) {
            event.setCancelled(true);
            if (!session.pending) {
                queueEdit(player, top, session, new Runnable() {
                    @Override
                    public void run() {
                        clear(player, top, session);
                    }
                });
            }
            return;
        }
        if (slot == EXIT_SLOT) {
            event.setCancelled(true);
            closeNextTick(player);
            return;
        }
        if (!isEditableSlot(slot)) {
            event.setCancelled(true);
            return;
        }

        if (session.pending) {
            event.setCancelled(true);
            return;
        }

        if (isCreative(player)) {
            session.ghostSlots.remove(slot);
            session.ghostCursor = false;
            return;
        }

        if (session.ghostCursor || isUnsafeClick(event)) {
            event.setCancelled(true);
            return;
        }

        if (!session.ghostSlots.contains(slot)) {
            return;
        }

        event.setCancelled(true);
        final int editableSlot = slot;
        if (isNumberKey(event)) {
            final int hotbarButton = event.getHotbarButton();
            queueEdit(player, top, session, new Runnable() {
                @Override
                public void run() {
                    swapWithHotbar(player, top, editableSlot, session, hotbarButton);
                }
            });
            return;
        }
        if (event.isShiftClick()) {
            queueEdit(player, top, session, new Runnable() {
                @Override
                public void run() {
                    shiftOutOfEditor(player, top, editableSlot, session);
                }
            });
            return;
        }

        final ItemStack cursor = cloneOrNull(event.getCursor());
        final boolean rightClick = event.isRightClick();
        queueEdit(player, top, session, new Runnable() {
            @Override
            public void run() {
                if (Compat.isAir(cursor)) {
                    top.setItem(editableSlot, null);
                    session.ghostSlots.remove(editableSlot);
                } else {
                    moveEditableItem(player, top, editableSlot, session, cursor, rightClick);
                }
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!isEditorTitle(event.getView().getTitle())) return;

        Player player = (Player) event.getWhoClicked();
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        if (session.pending) {
            event.setCancelled(true);
            return;
        }
        if (isCreative(player)) {
            for (Integer rawSlot : event.getRawSlots()) {
                if (rawSlot != null && rawSlot.intValue() >= 0 && rawSlot.intValue() < event.getView().getTopInventory().getSize()) {
                    session.ghostSlots.remove(rawSlot.intValue());
                }
            }
            session.ghostCursor = false;
            return;
        }
        if (session.ghostCursor) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot != null && rawSlot.intValue() >= 0 && rawSlot.intValue() < topSize) {
                int slot = rawSlot.intValue();
                if (!isEditableSlot(slot) || session.ghostSlots.contains(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!isEditorTitle(event.getView().getTitle())) return;
        UUID id = event.getPlayer().getUniqueId();
        EditorSession session = sessions.remove(id);
        if (session == null) return;
        if (!session.completed) settleItems(event.getPlayer(), session.inventory, session);
        clearGhostCursor(event.getPlayer(), session);
    }

    private void save(Player player, Inventory inventory, EditorSession session) {
        if (!player.hasPermission("refontcrafts.recipe")) {
            player.sendMessage(plugin.msg("no_permission"));
            return;
        }

        List<ItemStack> grid = new ArrayList<ItemStack>(9);
        boolean hasIngredient = false;
        for (int slot : INGREDIENT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (Compat.isAir(item)) {
                grid.add(new ItemStack(Material.AIR));
                continue;
            }
            ItemStack ingredient = item.clone();
            ingredient.setAmount(Math.max(1, item.getAmount()));
            grid.add(ingredient);
            hasIngredient = true;
        }

        ItemStack result = inventory.getItem(RESULT_SLOT);
        if (!hasIngredient || Compat.isAir(result)) {
            player.sendMessage(plugin.prefix() + plugin.msg("recipe_fill_both"));
            return;
        }

        String oldId = session.recipeId;
        if (oldId != null && !storage.deleteWorkbenchRecipe(oldId)) {
            player.sendMessage(plugin.prefix() + plugin.tr("messages.recipe_update_failed",
                    "&cCould not replace the old recipe. Nothing was changed."));
            return;
        }

        String newId = storage.saveShapedRecipe(grid, result.clone());
        player.sendMessage(plugin.prefix() + plugin.msg("saved_recipe", "id", newId));

        settleItems(player, inventory, session);
        session.completed = true;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) return;
                player.closeInventory();
                openEditor(player);
            }
        });
    }

    private void clear(Player player, Inventory inventory, EditorSession session) {
        settleItems(player, inventory, session);
    }

    private ItemStack shiftStackIntoInputs(Inventory inventory, ItemStack source, EditorSession session) {
        if (Compat.isAir(source)) return source;
        ItemStack remaining = source.clone();

        for (int slot : INGREDIENT_SLOTS) {
            ItemStack current = inventory.getItem(slot);
            if (Compat.isAir(current) || session.ghostSlots.contains(slot) || !current.isSimilar(remaining)) continue;
            int free = Math.max(0, current.getType().getMaxStackSize() - current.getAmount());
            if (free <= 0) continue;
            int moved = Math.min(free, remaining.getAmount());
            current.setAmount(current.getAmount() + moved);
            inventory.setItem(slot, current);
            remaining.setAmount(remaining.getAmount() - moved);
            if (remaining.getAmount() <= 0) return null;
        }

        for (int slot : INGREDIENT_SLOTS) {
            if (!Compat.isAir(inventory.getItem(slot))) continue;
            inventory.setItem(slot, remaining.clone());
            session.ghostSlots.remove(slot);
            return null;
        }
        return remaining;
    }

    private ItemStack shiftStackIntoResult(Player player, Inventory inventory, ItemStack source, EditorSession session) {
        if (Compat.isAir(source)) return source;
        ItemStack current = inventory.getItem(RESULT_SLOT);
        boolean currentGhost = session.ghostSlots.contains(RESULT_SLOT);

        if (Compat.isAir(current) || currentGhost) {
            inventory.setItem(RESULT_SLOT, source.clone());
            session.ghostSlots.remove(RESULT_SLOT);
            return null;
        }

        if (current.isSimilar(source)) {
            int free = Math.max(0, current.getType().getMaxStackSize() - current.getAmount());
            int moved = Math.min(free, source.getAmount());
            if (moved <= 0) return source;
            current.setAmount(current.getAmount() + moved);
            inventory.setItem(RESULT_SLOT, current);
            if (moved >= source.getAmount()) return null;
            ItemStack remaining = source.clone();
            remaining.setAmount(source.getAmount() - moved);
            return remaining;
        }

        returnItem(player, current);
        inventory.setItem(RESULT_SLOT, source.clone());
        session.ghostSlots.remove(RESULT_SLOT);
        return null;
    }

    private void shiftOutOfEditor(Player player, Inventory inventory, int slot, EditorSession session) {
        ItemStack current = inventory.getItem(slot);
        if (Compat.isAir(current)) return;

        if (session.ghostSlots.contains(slot)) {
            inventory.setItem(slot, null);
            session.ghostSlots.remove(slot);
            return;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(current.clone());
        int remainingAmount = 0;
        for (ItemStack leftover : leftovers.values()) {
            if (!Compat.isAir(leftover)) remainingAmount += leftover.getAmount();
        }
        if (remainingAmount <= 0) {
            inventory.setItem(slot, null);
        } else {
            ItemStack remaining = current.clone();
            remaining.setAmount(remainingAmount);
            inventory.setItem(slot, remaining);
            player.sendMessage(plugin.msg("no_inventory_space"));
        }
    }

    /**
     * Vanilla-like editor movement with ghost ownership tracking:
     * left click moves/merges a stack, right click moves one item or splits a stack.
     */
    private void moveEditableItem(Player player, Inventory inventory, int slot, EditorSession session,
                                  ItemStack eventCursor, boolean rightClick) {
        ItemStack current = inventory.getItem(slot);
        ItemStack cursor = eventCursor == null ? player.getItemOnCursor() : eventCursor;
        boolean currentGhost = session.ghostSlots.contains(slot);
        boolean cursorGhost = session.ghostCursor;

        if (Compat.isAir(cursor)) {
            if (Compat.isAir(current)) return;
            int taken = rightClick ? (current.getAmount() + 1) / 2 : current.getAmount();
            ItemStack picked = current.clone();
            picked.setAmount(taken);
            player.setItemOnCursor(picked);
            session.ghostCursor = currentGhost;

            int left = current.getAmount() - taken;
            if (left <= 0) {
                inventory.setItem(slot, null);
                session.ghostSlots.remove(slot);
            } else {
                current.setAmount(left);
                inventory.setItem(slot, current);
            }
            return;
        }

        if (cursorGhost) {
            if (Compat.isAir(current)) {
                int moved = rightClick ? 1 : cursor.getAmount();
                ItemStack placed = cursor.clone();
                placed.setAmount(moved);
                inventory.setItem(slot, placed);
                setGhost(session, slot, true);
                setCursorRemainder(player, cursor, moved);
                session.ghostCursor = cursor.getAmount() > moved;
                return;
            }
            if (currentGhost && current.isSimilar(cursor)) {
                int free = Math.max(0, current.getType().getMaxStackSize() - current.getAmount());
                int moved = Math.min(rightClick ? 1 : cursor.getAmount(), free);
                if (moved <= 0) return;
                current.setAmount(current.getAmount() + moved);
                inventory.setItem(slot, current);
                setCursorRemainder(player, cursor, moved);
                session.ghostCursor = cursor.getAmount() > moved;
                return;
            }

            inventory.setItem(slot, cursor.clone());
            setGhost(session, slot, true);
            player.setItemOnCursor(current.clone());
            session.ghostCursor = currentGhost;
            return;
        }

        if (Compat.isAir(current) || currentGhost) {
            int moved = rightClick ? 1 : cursor.getAmount();
            ItemStack placed = cursor.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            session.ghostSlots.remove(slot);
            setCursorRemainder(player, cursor, moved);
            session.ghostCursor = false;
            return;
        }

        if (current.isSimilar(cursor)) {
            int free = Math.max(0, current.getType().getMaxStackSize() - current.getAmount());
            int moved = Math.min(rightClick ? 1 : cursor.getAmount(), free);
            if (moved <= 0) return;
            current.setAmount(current.getAmount() + moved);
            inventory.setItem(slot, current);
            setCursorRemainder(player, cursor, moved);
            return;
        }

        inventory.setItem(slot, cursor.clone());
        session.ghostSlots.remove(slot);
        player.setItemOnCursor(current.clone());
        session.ghostCursor = false;
    }

    private void swapWithHotbar(Player player, Inventory inventory, int slot, EditorSession session, int hotbarButton) {
        if (hotbarButton < 0 || hotbarButton > 8) return;

        ItemStack hotbar = player.getInventory().getItem(hotbarButton);
        ItemStack current = inventory.getItem(slot);
        boolean currentGhost = session.ghostSlots.contains(slot);

        inventory.setItem(slot, Compat.isAir(hotbar) ? null : hotbar.clone());
        session.ghostSlots.remove(slot);

        if (currentGhost || Compat.isAir(current)) {
            player.getInventory().setItem(hotbarButton, null);
        } else {
            player.getInventory().setItem(hotbarButton, current.clone());
        }
    }

    private void setCursorRemainder(Player player, ItemStack source, int used) {
        int remainingAmount = source.getAmount() - used;
        if (remainingAmount <= 0) {
            player.setItemOnCursor(null);
            return;
        }
        ItemStack remaining = source.clone();
        remaining.setAmount(remainingAmount);
        player.setItemOnCursor(remaining);
    }

    private void queueEdit(final Player player, final Inventory inventory,
                           final EditorSession session, final Runnable action) {
        session.pending = true;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    if (player.isOnline()
                            && sessions.get(player.getUniqueId()) == session
                            && isEditorTitle(player.getOpenInventory().getTitle())) {
                        action.run();
                        player.updateInventory();
                    }
                } finally {
                    session.pending = false;
                }
            }
        });
    }

    private void closeNextTick(final Player player) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) player.closeInventory();
            }
        });
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return Compat.isAir(item) ? null : item.clone();
    }

    private void settleItems(HumanEntity entity, Inventory inventory, EditorSession session) {
        for (int slot : INGREDIENT_SLOTS) settleSlot(entity, inventory, slot, session);
        settleSlot(entity, inventory, RESULT_SLOT, session);
        settleCursor(entity, session);
        session.ghostSlots.clear();
    }

    private void settleSlot(HumanEntity entity, Inventory inventory, int slot, EditorSession session) {
        ItemStack item = inventory.getItem(slot);
        if (!session.ghostSlots.contains(slot)) returnItem(entity, item);
        inventory.setItem(slot, null);
    }

    private void clearGhostCursor(HumanEntity entity, EditorSession session) {
        entity.setItemOnCursor(null);
        session.ghostCursor = false;
    }

    private void settleCursor(HumanEntity entity, EditorSession session) {
        ItemStack cursor = entity.getItemOnCursor();
        if (!session.ghostCursor) returnItem(entity, cursor);
        clearGhostCursor(entity, session);
    }

    private void setGhost(EditorSession session, int slot, boolean ghost) {
        if (ghost) session.ghostSlots.add(slot);
        else session.ghostSlots.remove(slot);
    }

    private boolean isNumberKey(InventoryClickEvent event) {
        return "NUMBER_KEY".equals(event.getClick().name());
    }

    private boolean isShiftRight(InventoryClickEvent event) {
        String click = event.getClick().name();
        return "SHIFT_RIGHT".equals(click) || (event.isShiftClick() && event.isRightClick());
    }

    private boolean isUnsafeClick(InventoryClickEvent event) {
        String click = event.getClick().name();
        return "DOUBLE_CLICK".equals(click)
                || "DROP".equals(click)
                || "CONTROL_DROP".equals(click)
                || "SWAP_OFFHAND".equals(click)
                || "MIDDLE".equals(click)
                || "WINDOW_BORDER_LEFT".equals(click)
                || "WINDOW_BORDER_RIGHT".equals(click);
    }

    private boolean isEditableSlot(int slot) {
        if (slot == RESULT_SLOT) return true;
        for (int ingredient : INGREDIENT_SLOTS) if (ingredient == slot) return true;
        return false;
    }

    private boolean isCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE;
    }

    private boolean isEditorTitle(String title) {
        return Text.plain(title).equals(Text.plain(plugin.titleRecipe()));
    }

    private void returnItem(HumanEntity entity, ItemStack item) {
        if (Compat.isAir(item)) return;
        Map<Integer, ItemStack> remaining = entity.getInventory().addItem(item.clone());
        if (!remaining.isEmpty() && entity instanceof Player) {
            ((Player) entity).sendMessage(plugin.msg("no_inventory_space"));
        }
        for (ItemStack leftover : remaining.values()) {
            if (!Compat.isAir(leftover)) entity.getWorld().dropItemNaturally(entity.getLocation(), leftover);
        }
    }

    private static final class EditorSession {
        private final String recipeId;
        private final Inventory inventory;
        private final Set<Integer> ghostSlots = new HashSet<Integer>();
        private boolean ghostCursor;
        private boolean completed;
        private boolean pending;

        private EditorSession(String recipeId, Inventory inventory) {
            this.recipeId = recipeId;
            this.inventory = inventory;
        }
    }
}
