package ru.refontstudio.refontcrafts.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class RefontCraftsCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int SLOT_RECIPE = 10;
    private static final int SLOT_ANVIL = 12;
    private static final int SLOT_BROWSE = 14;
    private static final int SLOT_CRAFTS = 16;
    private static final int SLOT_CLOSE = 22;

    private final RefontCrafts plugin;

    public RefontCraftsCommand(RefontCrafts plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.msg("only_player"));
            return true;
        }

        Player player = (Player) sender;
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("crafts".equals(name)) {
            if (!player.hasPermission("refontcrafts.crafts")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.craftMenu().open(player, parsePage(args, 0));
            return true;
        }

        if (args.length == 0) {
            if (!player.hasPermission("refontcrafts.use")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("crafts".equals(sub)) {
            if (!player.hasPermission("refontcrafts.crafts")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.craftMenu().open(player, parsePage(args, 1));
            return true;
        }

        if ("recipe".equals(sub)) {
            if (!player.hasPermission("refontcrafts.create.workbench")
                    && !player.hasPermission("refontcrafts.recipe")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.recipeMenu().openEditor(player);
            return true;
        }

        if ("anvil".equals(sub)) {
            if (!player.hasPermission("refontcrafts.create.anvil")
                    && !player.hasPermission("refontcrafts.anvil")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.anvilMenu().openEditor(player);
            return true;
        }

        if ("view".equals(sub) || "browse".equals(sub) || "list".equals(sub)) {
            if (!player.hasPermission("refontcrafts.view")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            if (args.length >= 2 && "anvil".equalsIgnoreCase(args[1])) {
                plugin.browserMenu().openAnvil(player, parsePage(args, 2));
            } else {
                plugin.browserMenu().openWorkbench(player, parsePage(args, 2));
            }
            return true;
        }

        if ("reload".equals(sub)) {
            if (!player.hasPermission("refontcrafts.reload")) {
                player.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.reloadAll();
            player.sendMessage(plugin.msg("reloaded"));
            return true;
        }

        openMainMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("crafts".equals(name)) {
            return args.length <= 1 ? Arrays.asList("1", "2", "3") : new ArrayList<String>();
        }
        if (args.length == 1) {
            return Arrays.asList("crafts", "view", "browse", "list", "recipe", "anvil", "reload");
        }
        if (args.length == 2 && ("view".equalsIgnoreCase(args[0]) || "browse".equalsIgnoreCase(args[0]) || "list".equalsIgnoreCase(args[0]))) {
            return Arrays.asList("workbench", "anvil");
        }
        return new ArrayList<String>();
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(player, 27, plugin.titleMainMenu());
        ItemStack filler = ItemUtil.named(Compat.grayPane(), " ");
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler.clone());

        inventory.setItem(SLOT_RECIPE, ItemUtil.named(
                Compat.craftingTable(),
                plugin.tr("gui.main.recipe.name", "&bWorkbench editor"),
                plugin.tr("gui.main.recipe.lore", "&7Open the workbench recipe editor")));
        inventory.setItem(SLOT_ANVIL, ItemUtil.named(
                Material.ANVIL,
                plugin.tr("gui.main.anvil.name", "&dAnvil editor"),
                plugin.tr("gui.main.anvil.lore", "&7Open the anvil recipe editor")));
        inventory.setItem(SLOT_BROWSE, ItemUtil.named(
                Material.BOOK,
                plugin.tr("gui.main.browse.name", "&eBrowse recipes"),
                plugin.tr("gui.main.browse.lore", "&7View all custom recipes")));
        inventory.setItem(SLOT_CRAFTS, ItemUtil.named(
                Compat.knowledgeBook(),
                plugin.tr("gui.main.crafts.name", "&aAll crafts"),
                plugin.tr("gui.main.crafts.lore", "&7Show all recipes and current availability")));
        inventory.setItem(SLOT_CLOSE, ItemUtil.named(
                Material.BARRIER,
                plugin.tr("gui.main.close.name", "&cClose"),
                plugin.tr("gui.main.close.lore", "&7Close this menu")));

        player.openInventory(inventory);
    }

    @EventHandler
    public void onMainClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!Text.plain(event.getView().getTitle()).equals(Text.plain(plugin.titleMainMenu()))) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        if (slot == SLOT_RECIPE) {
            if (!player.hasPermission("refontcrafts.create.workbench")
                    && !player.hasPermission("refontcrafts.recipe")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    plugin.recipeMenu().openEditor(player);
                }
            });
            return;
        }
        if (slot == SLOT_ANVIL) {
            if (!player.hasPermission("refontcrafts.create.anvil")
                    && !player.hasPermission("refontcrafts.anvil")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    plugin.anvilMenu().openEditor(player);
                }
            });
            return;
        }
        if (slot == SLOT_BROWSE) {
            if (!player.hasPermission("refontcrafts.view")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    plugin.browserMenu().openWorkbench(player, 1);
                }
            });
            return;
        }
        if (slot == SLOT_CRAFTS) {
            if (!player.hasPermission("refontcrafts.crafts")) {
                player.sendMessage(plugin.msg("no_permission"));
                return;
            }
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    plugin.craftMenu().open(player, 1);
                }
            });
            return;
        }
        if (slot == SLOT_CLOSE) {
            runNextTick(player, new Runnable() {
                @Override
                public void run() {
                    player.closeInventory();
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

    private int parsePage(String[] args, int index) {
        if (args == null || args.length <= index) return 1;
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
