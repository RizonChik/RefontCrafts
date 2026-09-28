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
import ru.refontstudio.refontcrafts.util.Compat;

import java.io.File;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private volatile boolean ready = false;

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
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Storage tasks did not finish within 30 seconds; check the database before restarting.");
                ioExecutor.shutdownNow();
                ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            plugin.getLogger().severe("Storage shutdown interrupted; some recipe writes may not be committed.");
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private boolean alive() { return !closed && plugin.isEnabled(); }
    public boolean isReady() { return ready && alive(); }

    public int shapelessCount() { return workbench.size(); }
    public int anvilCount() { return anvil.size(); }
    public Collection<AnvilRecipe> getAnvilRecipes() { return anvil.values(); }
    public Collection<WorkbenchRecipe> getWorkbenchRecipes() { return workbench.values(); }
    public WorkbenchRecipe getWorkbenchRecipe(String id) { return workbench.get(id); }
    public AnvilRecipe getAnvilRecipe(String id) { return anvil.get(id); }

    public void loadAllAsync(Runnable onDone) {
        if (!alive()) return;
        ready = false;
        runAsync(() -> {
            if (!alive()) return;

            boolean dbAvailable = db.ensureReadyWithRetry(3, 1000);
            if (!dbAvailable && !db.activateFailoverSqlite()) return;
            if (!db.init()) return;

            autoMigrateIfDbTypeChanged();
            bootstrapFromConfigIfNeeded();
            recoverPendingRecipes();

            List<RawWorkbench> wbList = new ArrayList<RawWorkbench>();
            List<RawAnvil> anvilList = new ArrayList<RawAnvil>();
            final boolean[] loadSucceeded = {true};

            try (Connection cn = db.getConnection();
                 PreparedStatement ps = cn.prepareStatement("SELECT id,result FROM shapeless_recipes ORDER BY created_at ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    List<String> ingredients = new ArrayList<String>();
                    try (PreparedStatement pi = cn.prepareStatement(
                            "SELECT ord,item FROM shapeless_ingredients WHERE recipe_id=? ORDER BY ord ASC")) {
                        pi.setString(1, id);
                        try (ResultSet ri = pi.executeQuery()) {
                            while (ri.next()) ingredients.add(ri.getString(2));
                        }
                    }
                    wbList.add(new RawWorkbench(id, ingredients, rs.getString(2)));
                }
            } catch (Exception error) {
                loadSucceeded[0] = false;
                plugin.getLogger().log(Level.SEVERE, "Could not load workbench recipes", error);
            }

            try (Connection cn = db.getConnection();
                 PreparedStatement ps = cn.prepareStatement(
                         "SELECT id,left_item,right_item,result,cost FROM anvil_recipes ORDER BY created_at ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anvilList.add(new RawAnvil(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getInt(5)));
                }
            } catch (Exception error) {
                loadSucceeded[0] = false;
                plugin.getLogger().log(Level.SEVERE, "Could not load anvil recipes", error);
            }

            if (!loadSucceeded[0]) return;

            List<String> snapS = new ArrayList<String>();
            for (RawWorkbench recipe : wbList) {
                String line = "S;" + recipe.id + ";" + recipe.result + ";" + String.join(",", recipe.ingredients);
                if (recipe.ingredients.size() == 9) line += ";SHAPED";
                snapS.add(line);
            }
            List<String> snapA = new ArrayList<String>();
            for (RawAnvil recipe : anvilList) {
                snapA.add("A;" + recipe.id + ";" + recipe.left + ";" + recipe.right + ";"
                        + recipe.result + ";" + recipe.cost);
            }
            BackupUtil.writeSnapshot(plugin, snapS, snapA, db.getActiveType());

            if (!alive()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!alive()) return;
                    unregisterAllShapeless();
                    anvil.clear();
                    workbench.clear();
                    for (RawWorkbench recipe : wbList) {
                        try {
                            ItemStack result = ItemCodec.parseString(recipe.result);
                            if (Compat.isAir(result)) throw new IllegalStateException("Result could not be decoded");
                            List<ItemStack> ingredients = new ArrayList<ItemStack>();
                            boolean shaped = recipe.ingredients.size() == 9;
                            for (String encoded : recipe.ingredients) {
                                ItemStack item = ItemCodec.parseString(encoded);
                                if (item == null) throw new IllegalStateException("Ingredient could not be decoded");
                                if (shaped || !Compat.isAir(item)) ingredients.add(item);
                            }
                            if (ingredients.isEmpty()) throw new IllegalStateException("No ingredients");
                            registerWorkbench(recipe.id, ingredients, result, shaped);
                            workbench.put(recipe.id, new WorkbenchRecipe(recipe.id, ingredients, result, shaped));
                        } catch (Exception error) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Workbench recipe " + recipe.id + " remains in database but could not be loaded", error);
                        }
                    }
                    for (RawAnvil recipe : anvilList) {
                        try {
                            ItemStack left = ItemCodec.parseString(recipe.left);
                            ItemStack right = ItemCodec.parseString(recipe.right);
                            ItemStack result = ItemCodec.parseString(recipe.result);
                            if (Compat.isAir(left) || Compat.isAir(right) || Compat.isAir(result)) {
                                throw new IllegalStateException("Anvil item could not be decoded");
                            }
                            anvil.put(recipe.id, new AnvilRecipe(recipe.id, left, right, result, recipe.cost));
                        } catch (Exception error) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Anvil recipe " + recipe.id + " remains in database but could not be loaded", error);
                        }
                    }
                    ready = true;
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

        if (db.isFailoverActive() && "mysql".equalsIgnoreCase(prev)) {
            // The primary MySQL database is unavailable; keep it untouched until it returns.
            state.set("database.last_type", curr);
            saveState(state);
            return;
        }
        List<Database> sources = new ArrayList<Database>();
        if ("sqlite".equalsIgnoreCase(prev) && "mysql".equalsIgnoreCase(curr)) {
            Database normal = Database.ofType(plugin, "sqlite");
            Database failover = Database.ofFailoverSqlite(plugin);
            if (!isDbEmpty(normal)) sources.add(normal);
            if (!isDbEmpty(failover)) sources.add(failover);
        } else {
            sources.add(Database.ofType(plugin, prev));
        }
        try {
            for (Database source : sources) migrateAll(source, db);
            state.set("database.last_type", curr);
            saveState(state);
            runSync(() -> ChatLog.send(plugin.prefix() + "&aMigrated recipes from &f" + prev + " &7→ &f" + curr + "&a."));
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Recipe database migration failed; original database remains untouched", error);
        }
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
        try (Connection source = src.getConnection();
             Connection target = dst.getConnection()) {
            target.setAutoCommit(false);
            try {
                try (PreparedStatement read = source.prepareStatement(
                        "SELECT id,result,created_at FROM shapeless_recipes");
                     ResultSet rows = read.executeQuery();
                     PreparedStatement exists = target.prepareStatement(
                        "SELECT 1 FROM shapeless_recipes WHERE id=?");
                     PreparedStatement insert = target.prepareStatement(
                        "INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)")) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        if (exists(exists, id)) continue;
                        insert.setString(1, id);
                        insert.setString(2, rows.getString(2));
                        insert.setLong(3, rows.getLong(3));
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement read = source.prepareStatement(
                        "SELECT recipe_id,ord,item FROM shapeless_ingredients");
                     ResultSet rows = read.executeQuery();
                     PreparedStatement exists = target.prepareStatement(
                        "SELECT 1 FROM shapeless_ingredients WHERE recipe_id=? AND ord=?");
                     PreparedStatement insert = target.prepareStatement(
                        "INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        int ord = rows.getInt(2);
                        exists.setString(1, id);
                        exists.setInt(2, ord);
                        try (ResultSet found = exists.executeQuery()) {
                            if (found.next()) continue;
                        }
                        insert.setString(1, id);
                        insert.setInt(2, ord);
                        insert.setString(3, rows.getString(3));
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement read = source.prepareStatement(
                        "SELECT id,left_item,right_item,result,cost,created_at FROM anvil_recipes");
                     ResultSet rows = read.executeQuery();
                     PreparedStatement exists = target.prepareStatement(
                        "SELECT 1 FROM anvil_recipes WHERE id=?");
                     PreparedStatement insert = target.prepareStatement(
                        "INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        if (exists(exists, id)) continue;
                        insert.setString(1, id);
                        insert.setString(2, rows.getString(2));
                        insert.setString(3, rows.getString(3));
                        insert.setString(4, rows.getString(4));
                        insert.setInt(5, rows.getInt(5));
                        insert.setLong(6, rows.getLong(6));
                        insert.executeUpdate();
                    }
                }
                target.commit();
            } catch (Exception error) {
                target.rollback();
                throw error;
            }
        }
    }

    private boolean exists(PreparedStatement query, String id) throws Exception {
        query.setString(1, id);
        try (ResultSet rows = query.executeQuery()) {
            return rows.next();
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

    private void recoverPendingRecipes() {
        File directory = new File(plugin.getDataFolder(), "backups");
        File[] pending = directory.listFiles((dir, name) -> name.startsWith("pending-") && name.endsWith(".txt"));
        if (pending == null) return;
        Arrays.sort(pending, Comparator.comparing(File::getName));
        for (File file : pending) {
            boolean complete = true;
            int restored = 0;
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        if (line.startsWith("S;")) {
                            String[] parts = line.split(";", -1);
                            if (parts.length < 4 || !validRecipeId(parts[1])) throw new IllegalArgumentException("Invalid workbench recovery record");
                            List<String> ingredients = Arrays.asList(parts[3].split(",", -1));
                            if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("Invalid ingredient count");
                            insertWorkbench(parts[1], ingredients, parts[2], true);
                        } else if (line.startsWith("A;")) {
                            String[] parts = line.split(";", -1);
                            if (parts.length != 6 || !validRecipeId(parts[1])) throw new IllegalArgumentException("Invalid anvil recovery record");
                            insertAnvil(parts[1], parts[2], parts[3], parts[4], Integer.parseInt(parts[5]), true);
                        } else {
                            throw new IllegalArgumentException("Unknown recovery record");
                        }
                        restored++;
                    } catch (Exception error) {
                        complete = false;
                        plugin.getLogger().log(Level.SEVERE, "Could not recover a recipe from " + file.getName(), error);
                    }
                }
            } catch (Exception error) {
                complete = false;
                plugin.getLogger().log(Level.SEVERE, "Could not read pending recipes from " + file.getName(), error);
            }
            if (complete) {
                try {
                    Files.move(file.toPath(), new File(directory,
                            file.getName() + ".recovered-" + System.currentTimeMillis()).toPath());
                    if (restored > 0) plugin.getLogger().info("Recovered " + restored + " recipes from " + file.getName());
                } catch (Exception error) {
                    plugin.getLogger().log(Level.WARNING, "Recovered recipes but could not archive " + file.getName(), error);
                }
            }
        }
    }

    private boolean validRecipeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    public String saveShapedRecipe(List<ItemStack> matrix9, ItemStack result) {
        if (!isReady()) throw new IllegalStateException("Recipe storage has not finished loading");
        String id = nextId("s_");
        List<ItemStack> copy = normalizeTo9(matrix9);
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), true));
        List<String> encoded = encodeItems(copy);
        String encodedResult = ItemCodec.formatString(result);
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertWorkbench(id, encoded, encodedResult);
            if (!ok) {
                BackupUtil.appendPending(plugin, "S;" + id + ";" + encodedResult + ";" + String.join(",", encoded) + ";SHAPED");
                plugin.getLogger().severe("Workbench recipe " + id + " was not saved to database; see backups/pending-*.txt");
            }
        };
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public String saveShapelessRecipe(List<ItemStack> ingredients, ItemStack result) {
        if (!isReady()) throw new IllegalStateException("Recipe storage has not finished loading");
        String id = nextId("s_");
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack it : ingredients) copy.add(ItemUtil.cloneWithAmount(it, Math.max(1, it.getAmount())));
        registerWorkbench(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false);
        workbench.put(id, new WorkbenchRecipe(id, copy, ItemUtil.cloneWithAmount(result, Math.max(1, result.getAmount())), false));
        List<String> encoded = encodeItems(copy);
        String encodedResult = ItemCodec.formatString(result);
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertWorkbench(id, encoded, encodedResult);
            if (!ok) {
                BackupUtil.appendPending(plugin, "S;" + id + ";" + encodedResult + ";" + String.join(",", encoded));
                plugin.getLogger().severe("Workbench recipe " + id + " was not saved to database; see backups/pending-*.txt");
            }
        };
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public String saveAnvilRecipe(ItemStack left, ItemStack right, ItemStack result, int cost) {
        if (!isReady()) throw new IllegalStateException("Recipe storage has not finished loading");
        String id = nextId("a_");
        anvil.put(id, new AnvilRecipe(id, left.clone(), right.clone(), result.clone(), cost));
        String encodedLeft = ItemCodec.formatString(left);
        String encodedRight = ItemCodec.formatString(right);
        String encodedResult = ItemCodec.formatString(result);
        boolean async = plugin.getConfig().getBoolean("settings.database.async_save", true);
        Runnable task = () -> {
            boolean ok = tryInsertAnvil(id, encodedLeft, encodedRight, encodedResult, cost);
            if (!ok) {
                String line = "A;" + id + ";" + encodedLeft + ";" + encodedRight + ";" + encodedResult + ";" + cost;
                BackupUtil.appendPending(plugin, line);
                plugin.getLogger().severe("Anvil recipe " + id + " was not saved to database; see backups/pending-*.txt");
            }
        };
        if (async && alive()) runAsync(task); else task.run();
        return id;
    }

    public boolean deleteWorkbenchRecipe(String id) {
        if (!isReady()) return false;
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
        if (!isReady()) return false;
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
            cn.setAutoCommit(false);
            d1.setString(1, id);
            d1.executeUpdate();
            d2.setString(1, id);
            d2.executeUpdate();
            cn.commit();
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete workbench recipe " + id, t);
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
            plugin.getLogger().log(Level.SEVERE, "Failed to delete anvil recipe " + id, t);
            return false;
        }
    }

    private List<String> encodeItems(List<ItemStack> items) {
        List<String> encoded = new ArrayList<String>(items.size());
        for (ItemStack item : items) encoded.add(ItemCodec.formatString(item));
        return encoded;
    }

    private boolean tryInsertWorkbench(String id, List<String> ingredients, String result) {
        try {
            insertWorkbench(id, ingredients, result, false);
            return true;
        } catch (Throwable primary) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType()) && db.activateFailoverSqlite()) {
                try {
                    insertWorkbench(id, ingredients, result, false);
                    recordFailoverLocation();
                    return true;
                } catch (Throwable fallback) {
                    primary.addSuppressed(fallback);
                }
            }
            plugin.getLogger().log(Level.SEVERE, "Failed to persist workbench recipe " + id, primary);
            return false;
        }
    }

    private void insertWorkbench(String id, List<String> ingredients, String result, boolean replace) throws Exception {
        try (Connection cn = db.getConnection()) {
            cn.setAutoCommit(false);
            try {
                if (replace) {
                    try (PreparedStatement removeIngredients = cn.prepareStatement(
                            "DELETE FROM shapeless_ingredients WHERE recipe_id=?");
                         PreparedStatement removeRecipe = cn.prepareStatement(
                            "DELETE FROM shapeless_recipes WHERE id=?")) {
                        removeIngredients.setString(1, id);
                        removeIngredients.executeUpdate();
                        removeRecipe.setString(1, id);
                        removeRecipe.executeUpdate();
                    }
                }
                try (PreparedStatement recipe = cn.prepareStatement(
                        "INSERT INTO shapeless_recipes(id,result,created_at) VALUES(?,?,?)");
                     PreparedStatement ingredient = cn.prepareStatement(
                        "INSERT INTO shapeless_ingredients(recipe_id,ord,item) VALUES(?,?,?)")) {
                    recipe.setString(1, id);
                    recipe.setString(2, result);
                    recipe.setLong(3, System.currentTimeMillis());
                    recipe.executeUpdate();
                    for (int i = 0; i < ingredients.size(); i++) {
                        ingredient.setString(1, id);
                        ingredient.setInt(2, i);
                        ingredient.setString(3, ingredients.get(i));
                        ingredient.executeUpdate();
                    }
                }
                cn.commit();
            } catch (Exception error) {
                cn.rollback();
                throw error;
            }
        }
    }

    private boolean tryInsertAnvil(String id, String left, String right, String result, int cost) {
        try {
            insertAnvil(id, left, right, result, cost, false);
            return true;
        } catch (Throwable primary) {
            if (!"sqlite".equalsIgnoreCase(db.getActiveType()) && db.activateFailoverSqlite()) {
                try {
                    insertAnvil(id, left, right, result, cost, false);
                    recordFailoverLocation();
                    return true;
                } catch (Throwable fallback) {
                    primary.addSuppressed(fallback);
                }
            }
            plugin.getLogger().log(Level.SEVERE, "Failed to persist anvil recipe " + id, primary);
            return false;
        }
    }

    private void insertAnvil(String id, String left, String right, String result, int cost, boolean replace) throws Exception {
        try (Connection cn = db.getConnection()) {
            cn.setAutoCommit(false);
            try {
                if (replace) {
                    try (PreparedStatement remove = cn.prepareStatement("DELETE FROM anvil_recipes WHERE id=?")) {
                        remove.setString(1, id);
                        remove.executeUpdate();
                    }
                }
                try (PreparedStatement recipe = cn.prepareStatement(
                        "INSERT INTO anvil_recipes(id,left_item,right_item,result,cost,created_at) VALUES(?,?,?,?,?,?)")) {
                    recipe.setString(1, id);
                    recipe.setString(2, left);
                    recipe.setString(3, right);
                    recipe.setString(4, result);
                    recipe.setInt(5, cost);
                    recipe.setLong(6, System.currentTimeMillis());
                    recipe.executeUpdate();
                }
                cn.commit();
            } catch (Exception error) {
                cn.rollback();
                throw error;
            }
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
                try {
                    task.run();
                } catch (Throwable error) {
                    plugin.getLogger().log(Level.SEVERE, "Asynchronous storage task failed", error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            plugin.getLogger().warning("Storage task arrived during shutdown; persisting it synchronously");
            task.run();
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
        try {
            s.save(stateFile());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not save recipe database location in state.yml", error);
        }
    }

    private void recordFailoverLocation() {
        YamlConfiguration state = loadState();
        state.set("database.last_type", "sqlite");
        saveState(state);
    }

    private static class RawWorkbench {
        final String id;
        final List<String> ingredients;
        final String result;
        RawWorkbench(String id, List<String> ingredients, String result) {
            this.id = id;
            this.ingredients = ingredients;
            this.result = result;
        }
    }

    private static class RawAnvil {
        final String id;
        final String left;
        final String right;
        final String result;
        final int cost;
        RawAnvil(String id, String left, String right, String result, int cost) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.result = result;
            this.cost = cost;
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
