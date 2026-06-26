package ru.refontstudio.refontcrafts.storage;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.IllegalPluginAccessException;
import ru.refontstudio.refontcrafts.RefontCrafts;
import ru.refontstudio.refontcrafts.db.Database;
import ru.refontstudio.refontcrafts.util.BackupUtil;
import ru.refontstudio.refontcrafts.util.ItemCodec;
import ru.refontstudio.refontcrafts.util.ItemUtil;
import ru.refontstudio.refontcrafts.util.ChatLog;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Consumer;

public class RecipeStorage {
    private final RefontCrafts plugin;
    private final Database db;
    private final Map<String, NamespacedKey> shapelessKeys = new LinkedHashMap<>();
    private final Map<String, AnvilRecipe> anvil = new LinkedHashMap<>();
    private final Map<String, WorkbenchRecipe> workbench = new LinkedHashMap<>();
    private volatile boolean closed = false;
    private int revision = 0;

    public RecipeStorage(RefontCrafts plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void shutdown() { closed = true; }
    private boolean alive() { return !closed && plugin.isEnabled(); }

    public int shapelessCount() { return workbench.size(); }
    public int anvilCount() { return anvil.size(); }
    public int revision() { return revision; }
    public Collection<AnvilRecipe> getAnvilRecipes() { return anvil.values(); }
    public Collection<WorkbenchRecipe> getWorkbenchRecipes() { return workbench.values(); }
    public WorkbenchRecipe getWorkbenchRecipe(String id) { return workbench.get(id); }
    public AnvilRecipe getAnvilRecipe(String id) { return anvil.get(id); }

    public void loadAllAsync(Runnable onDone) {
        if (!alive()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!alive()) return;

            boolean ready = db.ensureReadyWithRetry(3, 1000);
            if (!ready) db.activateFailoverSqlite();
            try { db.init(); } catch (Throwable ignored) {}

            autoMigrateIfDbTypeChanged();
            bootstrapFromConfigIfNeeded();

            List<LoadedWorkbench> wbList = new ArrayList<>();
            List<AnvilRecipe> anvilList = new ArrayList<>();

            try (Connection cn = db.getConnection();
                 PreparedStatement ps = cn.prepareStatement("SELECT id,result,shaped FROM shapeless_recipes ORDER BY created_at ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String resStr = rs.getString(2);

                    List<ItemStack> raw = new ArrayList<>();
                    try (PreparedStatement pi = cn.prepareStatement("SELECT ord,item FROM shapeless_ingredients WHERE recipe_id=? ORDER BY ord ASC")) {
                        pi.setString(1, id);
                        try (ResultSet ri = pi.executeQuery()) {
                            while (ri.next()) {
                                ItemStack is = ItemCodec.parseString(ri.getString(2));
                                if (is == null) is = new ItemStack(Material.AIR);
                                raw.add(ItemUtil.cloneWithAmount(is, Math.max(1, is.getAmount())));
                            }
                        }
                    }

                    ItemStack res = ItemCodec.parseString(resStr);
                    if (res == null || res.getType().isAir()) continue;

                    boolean shaped = rs.getInt(3) != 0;
                    if (shaped) {
                        List<ItemStack> payload = new ArrayList<>(9);
                        for (int i = 0; i < 9; i++) {
                            ItemStack it = i < raw.size() ? raw.get(i) : new ItemStack(Material.AIR);
                            payload.add(ItemUtil.cloneWithAmount(it, 1));
                        }
                        wbList.add(new LoadedWorkbench(id, payload, ItemUtil.cloneWithAmount(res, Math.max(1, res.getAmount())), true));
                    } else {
                        List<ItemStack> trimmed = new ArrayList<>();
                        for (ItemStack it : raw) if (it != null && !it.getType().isAir()) trimmed.add(ItemUtil.cloneWithAmount(it, 1));
                        if (!trimmed.isEmpty()) wbList.add(new LoadedWorkbench(id, trimmed, ItemUtil.cloneWithAmount(res, Math.max(1, res.getAmount())), false));
                    }
                }
            } catch (Throwable t) {
                runSync(() -> ChatLog.send(plugin.prefix() + "&cDB error: load workbench: &f" + t.getMessage()));
            }

            try (Connection cn = db.getConnection();
                 PreparedStatement ps = cn.prepareStatement("SELECT id,left_item,right_item,result,cost FROM anvil_recipes ORDER BY created_at ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    ItemStack left = ItemCodec.parseString(rs.getString(2));
                    ItemStack right = ItemCodec.parseString(rs.getString(3));
                    ItemStack result = ItemCodec.parseString(rs.getString(4));
                    int cost = rs.getInt(5);
                    if (left != null && right != null && result != null && !left.getType().isAir() && !right.getType().isAir() && !result.getType().isAir()) {
                        anvilList.add(new AnvilRecipe(id, left, right, result, cost));
                    }
                }
            } catch (Throwable t) {
                runSync(() -> ChatLog.send(plugin.prefix() + "&cDB error: load anvil: &f" + t.getMessage()));
            }

            List<String> snapS = new ArrayList<>();
            for (LoadedWorkbench s : wbList) {
                StringBuilder sb = new StringBuilder();
                sb.append("S;").append(s.id).append(";").append(ItemCodec.formatString(s.result)).append(";");
                List<String> items = new ArrayList<>();
                for (ItemStack it : s.ingredients) items.add(ItemCodec.formatString(it));
                sb.append(String.join(",", items));
                if (s.shaped) sb.append(";SHAPED");
                snapS.add(sb.toString());
            }
            List<String> snapA = new ArrayList<>();
            for (AnvilRecipe a : anvilList) {
                String line = "A;" + a.id + ";" + ItemCodec.formatString(a.left) + ";" + ItemCodec.formatString(a.right) + ";" + ItemCodec.formatString(a.result) + ";" + a.cost;
                snapA.add(line);
            }
            BackupUtil.writeSnapshot(plugin, snapS, snapA, db.getActiveType());

            if (!alive()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!alive()) return;
                    unregisterAllShapeless();
                    anvil.clear();
                    workbench.clear();
                    for (LoadedWorkbench s : wbList) {
                        registerWorkbench(s.id, s.ingredients, s.result, s.shaped);
                        workbench.put(s.id, new WorkbenchRecipe(s.id, s.ingredients, s.result, s.shaped));
                    }
                    for (AnvilRecipe a : anvilList) anvil.put(a.id, a);
                    revision++;
                    if (onDone != null) onDone.run();
                });
            } catch (IllegalPluginAccessException ignored) {}
        });
    }

    public void autoMigrateIfDbTypeChanged() {
        FileConfiguration conf = plugin.getConfig();
        String curr = db.getActiveType();

        YamlConfiguration state = loadState();
        String prev = state.getString("database.last_type", conf.getString("database.last_type", null));

        if (prev == null || prev.isEmpty()) {
            state.set("database.last_type", curr);
            saveState(state);
            return;
        }
        if (prev.equalsIgnoreCase(curr)) return;

        Database src = Database.ofType(plugin, prev);
        if (isDbEmpty(db)) {
            try {
                migrateAll(src, db);
                runSync(() -> ChatLog.send(plugin.prefix() + "&aMigrated recipes from &f" + prev + " &7→ &f" + curr + "&a."));
            } catch (Throwable t) {
                runSync(() -> ChatLog.send(plugin.prefix() + "&cMigration error from &f" + prev + " &cto &f" + curr + "&c: &f" + t.getMessage()));
            }
        }
        state.set("database.last_type", curr);
        saveState(state);
    }

    private boolean isDbEmpty(Database target) {
        boolean empty = true;
        try (Connection cn = target.getConnection();
             PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM shapeless_recipes LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) empty = false;
        } catch (Throwable ignored) {}
        if (empty) {
            try (Connection cn = target.getConnection();
                 PreparedStatement ps = cn.prepareStatement("SELECT 1 FROM anvil_recipes LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) empty = false;
            } catch (Throwable ignored) {}
        }
        return empty;
    }

    private void migrateAll(Database src, Database dst) throws Exception {
        try (Connection scn = src.getConnection()) {
            try (PreparedStatement ps = scn.prepareStatement("SELECT id,result,created_at FROM shapeless_recipes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String result = rs.getString("result");
                    long created = rs.getLong("created_at");
                    int shaped = recipeShapedFromSource(scn, id);
                    try (Connection dcn = dst.getConnection();
                         PreparedStatement ins = dcn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
                        ins.setString(1, id);
                        ins.setString(2, result);
                        ins.setInt(3, shaped);
                        ins.setLong(4, created);
                        ins.executeUpdate();
                    }
                    try (PreparedStatement pi = scn.prepareStatement("SELECT ord,item FROM shapeless_ingredients WHERE recipe_id=? ORDER BY ord ASC")) {
                        pi.setString(1, id);
                        try (ResultSet ri = pi.executeQuery()) {
                            int ord = 0;
                            while (ri.next()) {
                                String item = ri.getString("item");
                                try (Connection dcn = dst.getConnection();
                                     PreparedStatement insI = dcn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                                    insI.setString(1, id);
                                    insI.setInt(2, ord++);
                                    insI.setString(3, item);
                                    insI.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }

            try (PreparedStatement ps = scn.prepareStatement("SELECT id,left_item,right_item,result,cost,created_at FROM anvil_recipes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try (Connection dcn = dst.getConnection();
                         PreparedStatement ins = dcn.prepareStatement("INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
                        ins.setString(1, rs.getString("id"));
                        ins.setString(2, rs.getString("left_item"));
                        ins.setString(3, rs.getString("right_item"));
                        ins.setString(4, rs.getString("result"));
                        ins.setInt(5, rs.getInt("cost"));
                        ins.setLong(6, rs.getLong("created_at"));
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    private int recipeShapedFromSource(Connection cn, String id) {
        try (PreparedStatement ps = cn.prepareStatement("SELECT shaped FROM shapeless_recipes WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) != 0 ? 1 : 0;
            }
        } catch (Throwable ignored) {}

        try (PreparedStatement ps = cn.prepareStatement("SELECT COUNT(*) FROM shapeless_ingredients WHERE recipe_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) == 9 ? 1 : 0;
            }
        } catch (Throwable ignored) {}

        return 0;
    }

    public void bootstrapFromConfigIfNeeded() {
        FileConfiguration c = plugin.getConfig();
        if (!isDbEmpty(db)) return;

        ConfigurationSection s = c.getConfigurationSection("recipes.shapeless");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                String base = "recipes.shapeless." + id;
                List<String> list = c.getStringList(base + ".ingredients");
                String resStr = c.getString(base + ".result");
                if (list == null || list.isEmpty() || resStr == null) continue;
                String rid = "s_" + System.currentTimeMillis() + "_" + id;
                long now = System.currentTimeMillis();
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
                    ins.setString(1, rid);
                    ins.setString(2, resStr);
                    ins.setInt(3, 0);
                    ins.setLong(4, now);
                    ins.executeUpdate();
                    int ord = 0;
                    for (String it : list) {
                        try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                            insI.setString(1, rid);
                            insI.setInt(2, ord++);
                            insI.setString(3, it);
                            insI.executeUpdate();
                        }
                    }
                } catch (Throwable t) {
                    runSync(() -> ChatLog.send(plugin.prefix() + "&cDB error: bootstrap shapeless &f" + id + "&c: &f" + t.getMessage()));
                }
            }
        }
        ConfigurationSection a = c.getConfigurationSection("recipes.anvil");
        if (a != null) {
            for (String id : a.getKeys(false)) {
                String base = "recipes.anvil." + id;
                String left = c.getString(base + ".left");
                String right = c.getString(base + ".right");
                String result = c.getString(base + ".result");
                int cost = c.getInt(base + ".cost", plugin.defaultAnvilCost());
                if (left == null || right == null || result == null) continue;
                String aid = "a_" + System.currentTimeMillis() + "_" + id;
                long now = System.currentTimeMillis();
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
                    ins.setString(1, aid);
                    ins.setString(2, left);
                    ins.setString(3, right);
                    ins.setString(4, result);
                    ins.setInt(5, cost);
                    ins.setLong(6, now);
                    ins.executeUpdate();
                } catch (Throwable t) {
                    runSync(() -> ChatLog.send(plugin.prefix() + "&cDB error: bootstrap anvil &f" + id + "&c: &f" + t.getMessage()));
                }
            }
        }
    }

    public String saveShapedRecipe(List<ItemStack> matrix9, ItemStack result) {
        String id = "s_" + UUID.randomUUID().toString().replace("-", "");
        List<ItemStack> copy = normalizeTo9(matrix9);
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true));
        revision++;
        boolean async = plugin.getConfig().getBoolean("database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertWorkbench(id, copy, result);
            if (!ok) {
                StringBuilder sb = new StringBuilder();
                List<String> items = new ArrayList<>();
                for (ItemStack it : copy) items.add(ItemCodec.formatString(it));
                sb.append("S;").append(id).append(";").append(ItemCodec.formatString(result)).append(";").append(String.join(",", items)).append(";SHAPED");
                BackupUtil.appendPending(plugin, sb.toString());
            }
        };
        if (async && alive()) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task); else task.run();
        return id;
    }

    public String saveShapelessRecipe(List<ItemStack> ingredients, ItemStack result) {
        String id = "s_" + UUID.randomUUID().toString().replace("-", "");
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack it : ingredients) copy.add(ItemUtil.cloneWithAmount(it, 1));
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false));
        revision++;
        boolean async = plugin.getConfig().getBoolean("database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertShapeless(id, copy, result);
            if (!ok) {
                StringBuilder sb = new StringBuilder();
                List<String> items = new ArrayList<>();
                for (ItemStack it : copy) items.add(ItemCodec.formatString(it));
                sb.append("S;").append(id).append(";").append(ItemCodec.formatString(result)).append(";").append(String.join(",", items));
                BackupUtil.appendPending(plugin, sb.toString());
            }
        };
        if (async && alive()) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task); else task.run();
        return id;
    }

    public String saveAnvilRecipe(ItemStack left, ItemStack right, ItemStack result, int cost) {
        String id = "a_" + UUID.randomUUID().toString().replace("-", "");
        anvil.put(id, new AnvilRecipe(id, left.clone(), right.clone(), result.clone(), cost));
        revision++;
        boolean async = plugin.getConfig().getBoolean("database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertAnvil(id, left, right, result, cost);
            if (!ok) {
                String line = "A;" + id + ";" + ItemCodec.formatString(left) + ";" + ItemCodec.formatString(right) + ";" + ItemCodec.formatString(result) + ";" + cost;
                BackupUtil.appendPending(plugin, line);
            }
        };
        if (async && alive()) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task); else task.run();
        return id;
    }

    public boolean deleteWorkbenchRecipe(String id) {
        boolean ok = deleteWorkbenchFromDatabase(id);
        if (ok) {
            unregisterById(id);
            workbench.remove(id);
            revision++;
        }
        return ok;
    }

    public boolean deleteAnvilRecipe(String id) {
        boolean ok = deleteAnvilFromDatabase(id);
        if (ok) {
            anvil.remove(id);
            revision++;
        }
        return ok;
    }

    public void deleteWorkbenchRecipeAsync(String id, Consumer<Boolean> callback) {
        if (!alive()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = deleteWorkbenchFromDatabase(id);
            runSync(() -> {
                if (ok) {
                    unregisterById(id);
                    workbench.remove(id);
                    revision++;
                }
                if (callback != null) callback.accept(ok);
            });
        });
    }

    public void deleteAnvilRecipeAsync(String id, Consumer<Boolean> callback) {
        if (!alive()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = deleteAnvilFromDatabase(id);
            runSync(() -> {
                if (ok) {
                    anvil.remove(id);
                    revision++;
                }
                if (callback != null) callback.accept(ok);
            });
        });
    }

    private boolean deleteWorkbenchFromDatabase(String id) {
        try (Connection cn = db.getConnection();
             PreparedStatement d1 = cn.prepareStatement("DELETE FROM shapeless_ingredients WHERE recipe_id=?");
             PreparedStatement d2 = cn.prepareStatement("DELETE FROM shapeless_recipes WHERE id=?")) {
            d1.setString(1, id);
            d1.executeUpdate();
            d2.setString(1, id);
            d2.executeUpdate();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean deleteAnvilFromDatabase(String id) {
        try (Connection cn = db.getConnection();
             PreparedStatement d = cn.prepareStatement("DELETE FROM anvil_recipes WHERE id=?")) {
            d.setString(1, id);
            d.executeUpdate();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tryInsertWorkbench(String id, List<ItemStack> matrix9, ItemStack result) {
        try (Connection cn = db.getConnection();
             PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
            ins.setString(1, id);
            ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
            ins.setInt(3, 1);
            ins.setLong(4, System.currentTimeMillis());
            ins.executeUpdate();
            for (int i = 0; i < 9; i++) {
                try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    insI.setString(1, id);
                    insI.setInt(2, i);
                    insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(matrix9.get(i), 1)));
                    insI.executeUpdate();
                }
            }
            return true;
        } catch (Throwable first) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType())) {
                db.activateFailoverSqlite();
                try { db.init(); } catch (Throwable ignored) {}
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
                    ins.setInt(3, 1);
                    ins.setLong(4, System.currentTimeMillis());
                    ins.executeUpdate();
                    for (int i = 0; i < 9; i++) {
                        try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                            insI.setString(1, id);
                            insI.setInt(2, i);
                            insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(matrix9.get(i), 1)));
                            insI.executeUpdate();
                        }
                    }
                    return true;
                } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private boolean tryInsertShapeless(String id, List<ItemStack> copy, ItemStack result) {
        try (Connection cn = db.getConnection();
             PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
            ins.setString(1, id);
            ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
            ins.setInt(3, 0);
            ins.setLong(4, System.currentTimeMillis());
            ins.executeUpdate();
            int ord = 0;
            for (ItemStack it : copy) {
                try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    insI.setString(1, id);
                    insI.setInt(2, ord++);
                    insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(it, 1)));
                    insI.executeUpdate();
                }
            }
            return true;
        } catch (Throwable first) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType())) {
                db.activateFailoverSqlite();
                try { db.init(); } catch (Throwable ignored) {}
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,shaped,created_at) VALUES(?,?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
                    ins.setInt(3, 0);
                    ins.setLong(4, System.currentTimeMillis());
                    ins.executeUpdate();
                    int ord = 0;
                    for (ItemStack it : copy) {
                        try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                            insI.setString(1, id);
                            insI.setInt(2, ord++);
                            insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(it, 1)));
                            insI.executeUpdate();
                        }
                    }
                    return true;
                } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private boolean tryInsertAnvil(String id, ItemStack left, ItemStack right, ItemStack result, int cost) {
        try (Connection cn = db.getConnection();
             PreparedStatement ins = cn.prepareStatement("INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
            ins.setString(1, id);
            ins.setString(2, ItemCodec.formatString(left));
            ins.setString(3, ItemCodec.formatString(right));
            ins.setString(4, ItemCodec.formatString(result));
            ins.setInt(5, cost);
            ins.setLong(6, System.currentTimeMillis());
            ins.executeUpdate();
            return true;
        } catch (Throwable first) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType())) {
                db.activateFailoverSqlite();
                try { db.init(); } catch (Throwable ignored) {}
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, ItemCodec.formatString(left));
                    ins.setString(3, ItemCodec.formatString(right));
                    ins.setString(4, ItemCodec.formatString(result));
                    ins.setInt(5, cost);
                    ins.setLong(6, System.currentTimeMillis());
                    ins.executeUpdate();
                    return true;
                } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private void registerWorkbench(String id, List<ItemStack> ingredients, ItemStack result, boolean shaped) {
        if (!alive()) return;
        if (shaped && ingredients.size() == 9 && plugin.workbenchStrictShape()) {
            registerShaped(id, ingredients, result);
        } else {
            registerShapeless(id, ingredients, result);
        }
    }

    private void registerShapeless(String id, List<ItemStack> ingredients, ItemStack result) {
        NamespacedKey key = new NamespacedKey(plugin, "shapeless_" + id);
        try { Bukkit.removeRecipe(key); } catch (Throwable ignored) {}
        ShapelessRecipe r = new ShapelessRecipe(key, result.clone());
        boolean exact = plugin.exactMeta();
        int added = 0;
        for (ItemStack it : ingredients) {
            if (it == null || it.getType() == Material.AIR) continue;
            if (added >= 9) break;
            if (exact) {
                ItemStack one = it.clone();
                one.setAmount(1);
                r.addIngredient(new RecipeChoice.ExactChoice(one));
            } else {
                r.addIngredient(it.getType());
            }
            added++;
        }
        try { Bukkit.addRecipe(r); } catch (IllegalStateException ignored) {}
        shapelessKeys.put(id, key);
    }

    private void registerShaped(String id, List<ItemStack> grid9, ItemStack result) {
        NamespacedKey key = new NamespacedKey(plugin, "shaped_" + id);
        try { Bukkit.removeRecipe(key); } catch (Throwable ignored) {}
        ShapedRecipe shaped = new ShapedRecipe(key, result.clone());

        char[][] cells = new char[3][3];
        for (int r = 0; r < 3; r++) Arrays.fill(cells[r], ' ');
        Map<Character, RecipeChoice> map = new LinkedHashMap<>();
        boolean exact = plugin.exactMeta();
        char next = 'A';

        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            ItemStack it = (i < grid9.size() ? grid9.get(i) : null);
            if (it == null || it.getType() == Material.AIR) {
                cells[row][col] = ' ';
                continue;
            }
            char ch = next++;
            cells[row][col] = ch;
            if (exact) {
                ItemStack one = it.clone();
                one.setAmount(1);
                map.put(ch, new RecipeChoice.ExactChoice(one));
            } else {
                map.put(ch, new RecipeChoice.MaterialChoice(it.getType()));
            }
        }

        String s1 = new String(cells[0]);
        String s2 = new String(cells[1]);
        String s3 = new String(cells[2]);
        shaped.shape(s1, s2, s3);
        for (Map.Entry<Character, RecipeChoice> e : map.entrySet()) shaped.setIngredient(e.getKey(), e.getValue());
        try { Bukkit.addRecipe(shaped); } catch (IllegalStateException ignored) {}
        shapelessKeys.put(id, key);

        if (plugin.workbenchAllowMirror()) {
            NamespacedKey keyM = new NamespacedKey(plugin, "shaped_" + id + "_m");
            try { Bukkit.removeRecipe(keyM); } catch (Throwable ignored) {}
            ShapedRecipe mir = new ShapedRecipe(keyM, result.clone());
            String m1 = new StringBuilder(s1).reverse().toString();
            String m2 = new StringBuilder(s2).reverse().toString();
            String m3 = new StringBuilder(s3).reverse().toString();
            mir.shape(m1, m2, m3);
            for (Map.Entry<Character, RecipeChoice> e : map.entrySet()) mir.setIngredient(e.getKey(), e.getValue());
            try { Bukkit.addRecipe(mir); } catch (IllegalStateException ignored) {}
            shapelessKeys.put(id + "_m", keyM);
        }
    }

    public void unregisterAllShapeless() {
        for (NamespacedKey k : shapelessKeys.values()) {
            try { Bukkit.removeRecipe(k); } catch (Throwable ignored) {}
        }
        shapelessKeys.clear();
    }

    private void unregisterById(String id) {
        NamespacedKey k1 = shapelessKeys.remove(id);
        if (k1 != null) { try { Bukkit.removeRecipe(k1); } catch (Throwable ignored) {} }
        NamespacedKey k2 = shapelessKeys.remove(id + "_m");
        if (k2 != null) { try { Bukkit.removeRecipe(k2); } catch (Throwable ignored) {} }
    }

    private List<ItemStack> normalizeTo9(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack it = (src != null && i < src.size()) ? src.get(i) : null;
            if (it == null) it = new ItemStack(Material.AIR);
            out.add(ItemUtil.cloneWithAmount(it, 1));
        }
        return out;
    }

    private void runSync(Runnable r) {
        if (!alive()) return;
        try { plugin.getServer().getScheduler().runTask(plugin, r); } catch (IllegalPluginAccessException ignored) {}
    }

    private File stateFile() {
        return new File(plugin.getDataFolder(), "state.yml");
    }

    private YamlConfiguration loadState() {
        File f = stateFile();
        return YamlConfiguration.loadConfiguration(f);
    }

    private void saveState(YamlConfiguration s) {
        try { s.save(stateFile()); } catch (Throwable ignored) {}
    }

    private static class LoadedWorkbench {
        final String id;
        final List<ItemStack> ingredients;
        final ItemStack result;
        final boolean shaped;
        LoadedWorkbench(String id, List<ItemStack> ingredients, ItemStack result, boolean shaped) {
            this.id = id;
            this.ingredients = ingredients;
            this.result = result;
            this.shaped = shaped;
        }
    }

    public static class WorkbenchRecipe {
        public final String id;
        public final List<ItemStack> ingredients;
        public final ItemStack result;
        public final boolean shaped;
        public WorkbenchRecipe(String id, List<ItemStack> ingredients, ItemStack result, boolean shaped) {
            this.id = id;
            this.ingredients = ingredients;
            this.result = result;
            this.shaped = shaped;
        }
    }

    public static class AnvilRecipe {
        public final String id;
        public final ItemStack left;
        public final ItemStack right;
        public final ItemStack result;
        public final int cost;
        public AnvilRecipe(String id, ItemStack left, ItemStack right, ItemStack result, int cost) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.result = result;
            this.cost = cost;
        }
    }
}
