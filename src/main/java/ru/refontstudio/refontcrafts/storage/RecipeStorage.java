package ru.refontstudio.refontcrafts.storage;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class RecipeStorage {
    private final RefontCrafts plugin;
    private final Database db;
    private final Map<String, AnvilRecipe> anvil = new LinkedHashMap<>();
    private final Map<String, WorkbenchRecipe> workbench = new LinkedHashMap<>();
    private final ExecutorService ioExecutor;
    private final AtomicLong idSequence = new AtomicLong(System.currentTimeMillis());
    private volatile boolean closed = false;

    public RecipeStorage(RefontCrafts plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RefontCrafts-Storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void shutdown() {
        closed = true;
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
                ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private boolean alive() { return !closed && plugin.isEnabled(); }

    public int shapelessCount() { return workbench.size(); }
    public int anvilCount() { return anvil.size(); }
    public Collection<AnvilRecipe> getAnvilRecipes() { return anvil.values(); }
    public Collection<WorkbenchRecipe> getWorkbenchRecipes() { return workbench.values(); }
    public WorkbenchRecipe getWorkbenchRecipe(String id) { return workbench.get(id); }
    public AnvilRecipe getAnvilRecipe(String id) { return anvil.get(id); }

    public void loadAllAsync(Runnable onDone) {
        if (!alive()) return;
        runAsync(() -> {
            if (!alive()) return;

            boolean ready = db.ensureReadyWithRetry(3, 1000);
            if (!ready) db.activateFailoverSqlite();
            try { db.init(); } catch (Throwable ignored) {}

            autoMigrateIfDbTypeChanged();
            bootstrapFromConfigIfNeeded();

            List<LoadedWorkbench> wbList = new ArrayList<>();
            List<AnvilRecipe> anvilList = new ArrayList<>();

            try (Connection cn = db.getConnection();
                 PreparedStatement ps = cn.prepareStatement("SELECT id,result FROM shapeless_recipes ORDER BY created_at ASC");
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
                    if (res == null || res.getType() == Material.AIR) continue;

                    boolean shaped = raw.size() == 9;
                    if (shaped) {
                        List<ItemStack> payload = new ArrayList<>(9);
                        for (int i = 0; i < 9; i++) {
                            ItemStack it = i < raw.size() ? raw.get(i) : new ItemStack(Material.AIR);
                            payload.add(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount())));
                        }
                        wbList.add(new LoadedWorkbench(id, payload, ItemUtil.cloneWithAmount(res, Math.max(1, res.getAmount())), true));
                    } else {
                        List<ItemStack> trimmed = new ArrayList<>();
                        for (ItemStack it : raw) if (it != null && it.getType() != Material.AIR) trimmed.add(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount())));
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
                    if (left != null && right != null && result != null && left.getType() != Material.AIR && right.getType() != Material.AIR && result.getType() != Material.AIR) {
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
                    if (onDone != null) onDone.run();
                });
            } catch (IllegalPluginAccessException ignored) {}
        });
    }

    public void autoMigrateIfDbTypeChanged() {
        FileConfiguration conf = plugin.getConfig();
        String curr = db.getActiveType();

        YamlConfiguration state = loadState();
        String prev = state.getString("database.last_type",
                conf.getString("settings.database.last_type", conf.getString("database.last_type", null)));

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
                    try (Connection dcn = dst.getConnection();
                         PreparedStatement ins = dcn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
                        ins.setString(1, id);
                        ins.setString(2, result);
                        ins.setLong(3, created);
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
                String rid = nextId("s_") + "_" + id;
                long now = System.currentTimeMillis();
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
                    ins.setString(1, rid);
                    ins.setString(2, resStr);
                    ins.setLong(3, now);
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
                String aid = nextId("a_") + "_" + id;
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
        String id = nextId("s_");
        List<ItemStack> copy = normalizeTo9(matrix9);
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true));
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
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
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public String saveShapelessRecipe(List<ItemStack> ingredients, ItemStack result) {
        String id = nextId("s_");
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack it : ingredients) copy.add(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount())));
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false));
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
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
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public String saveAnvilRecipe(ItemStack left, ItemStack right, ItemStack result, int cost) {
        String id = nextId("a_");
        anvil.put(id, new AnvilRecipe(id, left.clone(), right.clone(), result.clone(), cost));
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertAnvil(id, left, right, result, cost);
            if (!ok) {
                String line = "A;" + id + ";" + ItemCodec.formatString(left) + ";" + ItemCodec.formatString(right) + ";" + ItemCodec.formatString(result) + ";" + cost;
                BackupUtil.appendPending(plugin, line);
            }
        };
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public boolean deleteWorkbenchRecipe(String id) {
        unregisterById(id);
        WorkbenchRecipe removed = workbench.remove(id);
        if (removed == null) return false;

        Runnable task = () -> {
            if (!deleteWorkbenchFromDatabase(id)) {
                plugin.getLogger().warning("Could not delete workbench recipe from database: " + id);
            }
        };
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        if (async && alive()) runAsync(task); else task.run();
        return true;
    }

    public boolean deleteAnvilRecipe(String id) {
        AnvilRecipe removed = anvil.remove(id);
        if (removed == null) return false;

        Runnable task = () -> {
            if (!deleteAnvilFromDatabase(id)) {
                plugin.getLogger().warning("Could not delete anvil recipe from database: " + id);
            }
        };
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        if (async && alive()) runAsync(task); else task.run();
        return true;
    }

    private boolean deleteWorkbenchFromDatabase(String id) {
        // Memory state is updated synchronously by the public delete method.
        // The recipe was already removed from the in-memory index.
        if (id == null || id.trim().isEmpty()) return false;
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
        if (id == null || id.trim().isEmpty()) return false;
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
             PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
            ins.setString(1, id);
            ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
            ins.setLong(3, System.currentTimeMillis());
            ins.executeUpdate();
            for (int i = 0; i < 9; i++) {
                try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    insI.setString(1, id);
                    insI.setInt(2, i);
                    insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(matrix9.get(i), Math.max(1, matrix9.get(i).getAmount()))));
                    insI.executeUpdate();
                }
            }
            return true;
        } catch (Throwable first) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType())) {
                db.activateFailoverSqlite();
                try { db.init(); } catch (Throwable ignored) {}
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
                    ins.setLong(3, System.currentTimeMillis());
                    ins.executeUpdate();
                    for (int i = 0; i < 9; i++) {
                        try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                            insI.setString(1, id);
                            insI.setInt(2, i);
                            insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(matrix9.get(i), Math.max(1, matrix9.get(i).getAmount()))));
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
             PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
            ins.setString(1, id);
            ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
            ins.setLong(3, System.currentTimeMillis());
            ins.executeUpdate();
            int ord = 0;
            for (ItemStack it : copy) {
                try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    insI.setString(1, id);
                    insI.setInt(2, ord++);
                    insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount()))));
                    insI.executeUpdate();
                }
            }
            return true;
        } catch (Throwable first) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType())) {
                db.activateFailoverSqlite();
                try { db.init(); } catch (Throwable ignored) {}
                try (Connection cn = db.getConnection();
                     PreparedStatement ins = cn.prepareStatement("INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
                    ins.setString(1, id);
                    ins.setString(2, ItemCodec.formatString(ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount()))));
                    ins.setLong(3, System.currentTimeMillis());
                    ins.executeUpdate();
                    int ord = 0;
                    for (ItemStack it : copy) {
                        try (PreparedStatement insI = cn.prepareStatement("INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                            insI.setString(1, id);
                            insI.setInt(2, ord++);
                            insI.setString(3, ItemCodec.formatString(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount()))));
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
        // Matched and consumed by WorkbenchListener for cross-version support.
    }

    public void unregisterAllShapeless() {
        // No native Bukkit recipes are registered.
    }

    private void unregisterById(String id) {
        // No native Bukkit recipes are registered.
    }

    private String nextId(String prefix) {
        while (true) {
            long current = idSequence.get();
            long candidate = Math.max(System.currentTimeMillis(), current + 1L);
            if (idSequence.compareAndSet(current, candidate)) return prefix + candidate;
        }
    }

    private List<ItemStack> normalizeTo9(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            ItemStack it = (src != null && i < src.size()) ? src.get(i) : null;
            if (it == null) it = new ItemStack(Material.AIR);
            out.add(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount())));
        }
        return out;
    }

    private void runAsync(Runnable task) {
        if (!alive()) return;
        try {
            ioExecutor.execute(() -> {
                if (!alive()) return;
                try {
                    task.run();
                } catch (Throwable error) {
                    if (alive()) plugin.getLogger().log(Level.SEVERE, "Asynchronous storage task failed", error);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
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
