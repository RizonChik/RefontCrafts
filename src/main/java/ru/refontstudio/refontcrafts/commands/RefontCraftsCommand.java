package ru.refontstudio.refontcrafts.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RefontCraftsCommand implements CommandExecutor, TabCompleter, Listener {
    private final RefontCrafts plugin;
    private final NamespacedKey MAIN_KEY;
    private final String MAIN_TITLE = Text.color("§bВыбор раздела");

    public RefontCraftsCommand(RefontCrafts plugin) {
        this.plugin = plugin;
        this.MAIN_KEY = new NamespacedKey(plugin, "rc_main_menu");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player)) {
            s.sendMessage(plugin.msg("only_player"));
            return true;
        }
        Player p = (Player) s;
        if (args.length == 0) {
            openMainMenu(p);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("recipe")) {
            if (!p.hasPermission("refontcrafts.admin")) {
                p.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.recipeMenu().openEditor(p);
            return true;
        }
        if (sub.equals("anvil")) {
            if (!p.hasPermission("refontcrafts.admin")) {
                p.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.anvilMenu().openEditor(p);
            return true;
        }
        if (sub.equals("view") || sub.equals("browse") || sub.equals("list")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("anvil")) {
                int page = 1;
                if (args.length >= 3) try { page = Math.max(1, Integer.parseInt(args[2])); } catch (Throwable ignored) {}
                plugin.browserMenu().openAnvil(p, page);
            } else {
                int page = 1;
                if (args.length >= 3) try { page = Math.max(1, Integer.parseInt(args[2])); } catch (Throwable ignored) {}
                plugin.browserMenu().openWorkbench(p, page);
            }
            return true;
        }
        if (sub.equals("reload")) {
            if (!p.hasPermission("refontcrafts.admin")) {
                p.sendMessage(plugin.msg("no_permission"));
                return true;
            }
            plugin.reloadAll();
            p.sendMessage(plugin.msg("reloaded"));
            return true;
        }
        openMainMenu(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("view","browse","list","recipe","anvil","reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("browse") || args[0].equalsIgnoreCase("list"))) return Arrays.asList("workbench","anvil");
        return new ArrayList<>();
    }

    private void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(p, 27, MAIN_TITLE);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, ItemUtil.named(Material.GRAY_STAINED_GLASS_PANE, " "));
        ItemStack wb = ItemUtil.named(Material.CRAFTING_TABLE, Text.color("§bВерстак"), Text.color("&7Открыть рецепты верстака"));
        ItemMeta wbm = wb.getItemMeta();
        wbm.getPersistentDataContainer().set(MAIN_KEY, PersistentDataType.STRING, "wb");
        wb.setItemMeta(wbm);
        ItemStack anv = ItemUtil.named(Material.ANVIL, Text.color("§dНаковальня"), Text.color("&7Открыть рецепты наковальни"));
        ItemMeta anm = anv.getItemMeta();
        anm.getPersistentDataContainer().set(MAIN_KEY, PersistentDataType.STRING, "anv");
        anv.setItemMeta(anm);
        ItemStack close = ItemUtil.named(Material.BARRIER, Text.color("&cЗакрыть"));
        ItemMeta clm = close.getItemMeta();
        clm.getPersistentDataContainer().set(MAIN_KEY, PersistentDataType.STRING, "close");
        close.setItemMeta(clm);
        inv.setItem(11, wb);
        inv.setItem(13, close);
        inv.setItem(15, anv);
        p.openInventory(inv);
    }

    @EventHandler
    public void onMainClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!Text.plain(e.getView().getTitle()).equals(Text.plain(MAIN_TITLE))) return;
        if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) return;
        e.setCancelled(true);
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR) return;
        ItemMeta im = it.getItemMeta();
        if (im == null) return;
        String act = im.getPersistentDataContainer().get(MAIN_KEY, PersistentDataType.STRING);
        if (act == null) return;
        Player p = (Player) e.getWhoClicked();
        if (act.equals("wb")) {
            plugin.browserMenu().openWorkbench(p, 1);
            return;
        }
        if (act.equals("anv")) {
            plugin.browserMenu().openAnvil(p, 1);
            return;
        }
        if (act.equals("close")) {
            p.closeInventory();
        }
    }
}
