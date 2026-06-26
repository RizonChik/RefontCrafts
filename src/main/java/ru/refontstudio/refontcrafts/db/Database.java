package ru.refontstudio.refontcrafts.db;

import org.bukkit.configuration.file.FileConfiguration;
import ru.refontstudio.refontcrafts.RefontCrafts;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Database {
    private final RefontCrafts plugin;
    private String configuredType;
    private String activeType;
    private String url;
    private String user;
    private String pass;

    public Database(RefontCrafts plugin) {
        this(plugin, plugin.getConfig().getString("database.type", "sqlite"));
    }

    public Database(RefontCrafts plugin, String forcedType) {
        this.plugin = plugin;
        this.configuredType = forcedType == null ? "sqlite" : forcedType.toLowerCase();
        buildForType(this.configuredType);
        this.activeType = this.configuredType;
    }

    public static Database ofType(RefontCrafts plugin, String type) {
        return new Database(plugin, type);
    }

    private void buildForType(String type) {
        FileConfiguration c = plugin.getConfig();
        if ("mysql".equalsIgnoreCase(type)) {
            String hostRaw = c.getString("database.mysql.host", "127.0.0.1");
            int portCfg = c.getInt("database.mysql.port", 3306);
            String host = hostRaw;
            int port = portCfg;
            if (hostRaw != null && hostRaw.contains(":")) {
                String[] parts = hostRaw.split(":");
                host = parts[0].trim();
                try { port = Integer.parseInt(parts[1].trim()); } catch (Throwable ignored) { port = portCfg; }
            }
            String db = c.getString("database.mysql.database", "refontcrafts");
            boolean useSSL = c.getBoolean("database.mysql.use_ssl", false);
            String params = c.getString("database.mysql.params", "useUnicode=true&characterEncoding=utf8");
            String qp = "useSSL=" + useSSL + (params == null || params.isEmpty() ? "" : "&" + params);
            this.url = "jdbc:mysql://" + host + ":" + port + "/" + db + "?" + qp;
            this.user = c.getString("database.mysql.user", "root");
            this.pass = c.getString("database.mysql.password", "");
        } else {
            String file = c.getString("database.sqlite.file", "data.db");
            File f = new File(plugin.getDataFolder(), file);
            this.url = "jdbc:sqlite:" + f.getAbsolutePath();
            this.user = null;
            this.pass = null;
        }
    }

    private void buildFailoverSqlite() {
        File f = new File(plugin.getDataFolder(), "failover.db");
        this.url = "jdbc:sqlite:" + f.getAbsolutePath();
        this.user = null;
        this.pass = null;
        this.activeType = "sqlite";
    }

    public String getType() { return configuredType; }
    public String getActiveType() { return activeType; }
    public boolean isFailoverActive() { return !"mysql".equalsIgnoreCase(configuredType) ? false : !"mysql".equalsIgnoreCase(activeType); }

    public Connection getConnection() throws java.sql.SQLException {
        if (user == null) return DriverManager.getConnection(url);
        return DriverManager.getConnection(url, user, pass);
    }

    public boolean ensureReadyWithRetry(int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            try (Connection cn = getConnection()) { return true; }
            catch (Throwable ignored) {}
            try { Thread.sleep(Math.max(0, delayMs)); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    public boolean activateFailoverSqlite() {
        if ("sqlite".equalsIgnoreCase(activeType)) return true;
        buildFailoverSqlite();
        try { init(); } catch (Throwable ignored) {}
        return true;
    }

    public void init() {
        try (Connection cn = getConnection(); Statement st = cn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS shapeless_recipes (" +
                    "id VARCHAR(64) PRIMARY KEY," +
                    "result TEXT NOT NULL," +
                    "shaped INT NOT NULL DEFAULT 0," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS shapeless_ingredients (" +
                    "recipe_id VARCHAR(64) NOT NULL," +
                    "ord INT NOT NULL," +
                    "item TEXT NOT NULL," +
                    "PRIMARY KEY (recipe_id, ord)" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS anvil_recipes (" +
                    "id VARCHAR(64) PRIMARY KEY," +
                    "left_item TEXT NOT NULL," +
                    "right_item TEXT NOT NULL," +
                    "result TEXT NOT NULL," +
                    "cost INT NOT NULL," +
                    "created_at BIGINT NOT NULL" +
                    ")");
        } catch (Throwable ignored) {}

        try { upgradeSchemaIfNeeded(); } catch (Throwable ignored) {}
    }

    private void upgradeSchemaIfNeeded() throws Exception {
        if ("mysql".equalsIgnoreCase(activeType)) {
            try (Connection cn = getConnection(); Statement st = cn.createStatement()) {
                try { st.executeUpdate("ALTER TABLE shapeless_recipes MODIFY result TEXT NOT NULL"); } catch (Throwable ignored) {}
                boolean hadShaped = columnExists(cn, "shapeless_recipes", "shaped");
                if (!hadShaped) {
                    try { st.executeUpdate("ALTER TABLE shapeless_recipes ADD COLUMN shaped INT NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
                    try { st.executeUpdate("UPDATE shapeless_recipes SET shaped=1 WHERE id IN (SELECT recipe_id FROM shapeless_ingredients GROUP BY recipe_id HAVING COUNT(*) = 9)"); } catch (Throwable ignored) {}
                }
                try { st.executeUpdate("ALTER TABLE shapeless_ingredients MODIFY item TEXT NOT NULL"); } catch (Throwable ignored) {}
                try { st.executeUpdate("ALTER TABLE anvil_recipes MODIFY left_item TEXT NOT NULL, MODIFY right_item TEXT NOT NULL, MODIFY result TEXT NOT NULL"); } catch (Throwable ignored) {}
            }
            return;
        }

        try (Connection cn = getConnection(); Statement st = cn.createStatement()) {
            if (sqliteNeedsRebuild(cn, "shapeless_recipes", "result")) {
                st.executeUpdate("ALTER TABLE shapeless_recipes RENAME TO shapeless_recipes_v1");
                st.executeUpdate("CREATE TABLE shapeless_recipes (id VARCHAR(64) PRIMARY KEY, result TEXT NOT NULL, shaped INT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL)");
                st.executeUpdate("INSERT INTO shapeless_recipes (id,result,shaped,created_at) SELECT id,result,0,created_at FROM shapeless_recipes_v1");
                st.executeUpdate("DROP TABLE shapeless_recipes_v1");
            }
            boolean hadShaped = columnExists(cn, "shapeless_recipes", "shaped");
            if (!hadShaped) {
                try { st.executeUpdate("ALTER TABLE shapeless_recipes ADD COLUMN shaped INT NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
                try { st.executeUpdate("UPDATE shapeless_recipes SET shaped=1 WHERE id IN (SELECT recipe_id FROM shapeless_ingredients GROUP BY recipe_id HAVING COUNT(*) = 9)"); } catch (Throwable ignored) {}
            }
            if (sqliteNeedsRebuild(cn, "shapeless_ingredients", "item")) {
                st.executeUpdate("ALTER TABLE shapeless_ingredients RENAME TO shapeless_ingredients_v1");
                st.executeUpdate("CREATE TABLE shapeless_ingredients (recipe_id VARCHAR(64) NOT NULL, ord INT NOT NULL, item TEXT NOT NULL, PRIMARY KEY (recipe_id, ord))");
                st.executeUpdate("INSERT INTO shapeless_ingredients (recipe_id,ord,item) SELECT recipe_id,ord,item FROM shapeless_ingredients_v1");
                st.executeUpdate("DROP TABLE shapeless_ingredients_v1");
            }
            boolean needAnvil = sqliteNeedsRebuild(cn, "anvil_recipes", "left_item")
                    || sqliteNeedsRebuild(cn, "anvil_recipes", "right_item")
                    || sqliteNeedsRebuild(cn, "anvil_recipes", "result");
            if (needAnvil) {
                st.executeUpdate("ALTER TABLE anvil_recipes RENAME TO anvil_recipes_v1");
                st.executeUpdate("CREATE TABLE anvil_recipes (id VARCHAR(64) PRIMARY KEY, left_item TEXT NOT NULL, right_item TEXT NOT NULL, result TEXT NOT NULL, cost INT NOT NULL, created_at BIGINT NOT NULL)");
                st.executeUpdate("INSERT INTO anvil_recipes (id,left_item,right_item,result,cost,created_at) SELECT id,left_item,right_item,result,cost,created_at FROM anvil_recipes_v1");
                st.executeUpdate("DROP TABLE anvil_recipes_v1");
            }
        }
    }

    private boolean sqliteNeedsRebuild(Connection cn, String table, String column) {
        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (rs.next()) {
                String name = rs.getString("name");
                String type = rs.getString("type");
                if (name != null && name.equalsIgnoreCase(column)) {
                    if (type == null) return true;
                    return !type.equalsIgnoreCase("TEXT");
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean columnExists(Connection cn, String table, String column) {
        try (Statement st = cn.createStatement();
             ResultSet ignored = st.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1=0")) {
            return true;
        } catch (Throwable ignored) {}

        try (ResultSet rs = cn.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) return true;
        } catch (Throwable ignored) {}

        try (Statement st = cn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null && name.equalsIgnoreCase(column)) return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
