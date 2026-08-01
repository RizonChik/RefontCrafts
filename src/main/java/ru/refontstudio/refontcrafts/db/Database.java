package ru.refontstudio.refontcrafts.db;

import org.bukkit.configuration.file.FileConfiguration;
import ru.refontstudio.refontcrafts.RefontCrafts;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;

public class Database {
    private final RefontCrafts plugin;
    private final String configuredType;
    private String activeType;
    private String url;
    private String user;
    private String pass;

    public Database(RefontCrafts plugin) {
        this(plugin, readString(plugin.getConfig(), "type", "sqlite"));
    }

    public Database(RefontCrafts plugin, String forcedType) {
        this.plugin = plugin;
        this.configuredType = normalizeType(forcedType);
        this.activeType = this.configuredType;
        if ("mysql".equals(this.configuredType) && plugin.forceSqliteFailover()) {
            buildFailoverSqlite();
        } else {
            buildForType(this.activeType);
        }
    }

    public static Database ofType(RefontCrafts plugin, String type) {
        return new Database(plugin, type);
    }

    private void buildForType(String type) {
        FileConfiguration config = plugin.getConfig();
        if ("mysql".equalsIgnoreCase(type)) {
            String hostRaw = readString(config, "mysql.host", "127.0.0.1");
            int configuredPort = readInt(config, "mysql.port", 3306);
            String host = hostRaw;
            int port = configuredPort;
            if (hostRaw != null && hostRaw.contains(":")) {
                String[] parts = hostRaw.split(":", 2);
                host = parts[0].trim();
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (Throwable ignored) {
                    port = configuredPort;
                }
            }
            String database = readString(config, "mysql.database", "refontcrafts");
            boolean useSsl = readBoolean(config, "mysql.use_ssl", false);
            String params = readString(config, "mysql.params", "useUnicode=true&characterEncoding=utf8");
            String query = "useSSL=" + useSsl + (params == null || params.trim().isEmpty() ? "" : "&" + params);
            this.url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + query;
            this.user = readString(config, "mysql.user", "root");
            this.pass = readString(config, "mysql.password", "");
        } else {
            String fileName = readString(config, "sqlite.file", "data.db");
            File file = new File(plugin.getDataFolder(), fileName);
            this.url = "jdbc:sqlite:" + file.getAbsolutePath();
            this.user = null;
            this.pass = null;
        }
    }

    private void buildFailoverSqlite() {
        File file = new File(plugin.getDataFolder(), "failover.db");
        this.url = "jdbc:sqlite:" + file.getAbsolutePath();
        this.user = null;
        this.pass = null;
        this.activeType = "sqlite";
    }

    public String getType() {
        return configuredType;
    }

    public String getActiveType() {
        return activeType;
    }

    public boolean isFailoverActive() {
        return "mysql".equalsIgnoreCase(configuredType) && !"mysql".equalsIgnoreCase(activeType);
    }

    public Connection getConnection() throws java.sql.SQLException {
        Driver driver = "mysql".equalsIgnoreCase(activeType)
                ? plugin.libraries().mysqlDriver()
                : plugin.libraries().sqliteDriver();
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
            properties.setProperty("password", pass == null ? "" : pass);
        }
        Connection connection = driver.connect(url, properties);
        if (connection == null) throw new java.sql.SQLException("JDBC driver rejected URL: " + url);
        return connection;
    }

    public boolean ensureReadyWithRetry(int attempts, long delayMs) {
        Throwable last = null;
        int count = Math.max(1, attempts);
        for (int i = 0; i < count; i++) {
            try (Connection ignored = getConnection()) {
                return true;
            } catch (Throwable error) {
                last = error;
            }
            if (i + 1 < count) {
                try {
                    Thread.sleep(Math.max(0L, delayMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) {
            plugin.getLogger().log(Level.WARNING,
                    "Database connection failed for " + activeType + ": " + safeMessage(last), last);
        }
        return false;
    }

    public boolean activateFailoverSqlite() {
        if (!"sqlite".equalsIgnoreCase(activeType)) buildFailoverSqlite();
        return init();
    }

    public boolean init() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS shapeless_recipes ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "result TEXT NOT NULL,"
                    + "created_at BIGINT NOT NULL"
                    + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS shapeless_ingredients ("
                    + "recipe_id VARCHAR(64) NOT NULL,"
                    + "ord INT NOT NULL,"
                    + "item TEXT NOT NULL,"
                    + "PRIMARY KEY (recipe_id, ord)"
                    + ")");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS anvil_recipes ("
                    + "id VARCHAR(64) PRIMARY KEY,"
                    + "left_item TEXT NOT NULL,"
                    + "right_item TEXT NOT NULL,"
                    + "result TEXT NOT NULL,"
                    + "cost INT NOT NULL,"
                    + "created_at BIGINT NOT NULL"
                    + ")");
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not initialize " + activeType + " database: " + safeMessage(error), error);
            return false;
        }

        try {
            upgradeSchemaIfNeeded();
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not complete database schema upgrade: " + safeMessage(error), error);
        }
        return true;
    }

    private void upgradeSchemaIfNeeded() throws Exception {
        if ("mysql".equalsIgnoreCase(activeType)) {
            try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
                try {
                    statement.executeUpdate("ALTER TABLE shapeless_recipes MODIFY result TEXT NOT NULL");
                } catch (Throwable ignored) {
                }
                try {
                    statement.executeUpdate("ALTER TABLE shapeless_ingredients MODIFY item TEXT NOT NULL");
                } catch (Throwable ignored) {
                }
                try {
                    statement.executeUpdate("ALTER TABLE anvil_recipes MODIFY left_item TEXT NOT NULL, MODIFY right_item TEXT NOT NULL, MODIFY result TEXT NOT NULL");
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            if (sqliteNeedsRebuild(connection, "shapeless_recipes", "result")) {
                statement.executeUpdate("ALTER TABLE shapeless_recipes RENAME TO shapeless_recipes_v1");
                statement.executeUpdate("CREATE TABLE shapeless_recipes (id VARCHAR(64) PRIMARY KEY, result TEXT NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("INSERT INTO shapeless_recipes (id,result,created_at) SELECT id,result,created_at FROM shapeless_recipes_v1");
                statement.executeUpdate("DROP TABLE shapeless_recipes_v1");
            }
            if (sqliteNeedsRebuild(connection, "shapeless_ingredients", "item")) {
                statement.executeUpdate("ALTER TABLE shapeless_ingredients RENAME TO shapeless_ingredients_v1");
                statement.executeUpdate("CREATE TABLE shapeless_ingredients (recipe_id VARCHAR(64) NOT NULL, ord INT NOT NULL, item TEXT NOT NULL, PRIMARY KEY (recipe_id, ord))");
                statement.executeUpdate("INSERT INTO shapeless_ingredients (recipe_id,ord,item) SELECT recipe_id,ord,item FROM shapeless_ingredients_v1");
                statement.executeUpdate("DROP TABLE shapeless_ingredients_v1");
            }
            boolean rebuildAnvil = sqliteNeedsRebuild(connection, "anvil_recipes", "left_item")
                    || sqliteNeedsRebuild(connection, "anvil_recipes", "right_item")
                    || sqliteNeedsRebuild(connection, "anvil_recipes", "result");
            if (rebuildAnvil) {
                statement.executeUpdate("ALTER TABLE anvil_recipes RENAME TO anvil_recipes_v1");
                statement.executeUpdate("CREATE TABLE anvil_recipes (id VARCHAR(64) PRIMARY KEY, left_item TEXT NOT NULL, right_item TEXT NOT NULL, result TEXT NOT NULL, cost INT NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("INSERT INTO anvil_recipes (id,left_item,right_item,result,cost,created_at) SELECT id,left_item,right_item,result,cost,created_at FROM anvil_recipes_v1");
                statement.executeUpdate("DROP TABLE anvil_recipes_v1");
            }
        }
    }

    private boolean sqliteNeedsRebuild(Connection connection, String table, String column) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                String name = result.getString("name");
                String type = result.getString("type");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return type == null || !type.equalsIgnoreCase("TEXT");
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String normalizeType(String value) {
        if (value == null) return "sqlite";
        return "mysql".equals(value.trim().toLowerCase(Locale.ROOT)) ? "mysql" : "sqlite";
    }

    private static String readString(FileConfiguration config, String suffix, String fallback) {
        String current = "settings.database." + suffix;
        if (config.contains(current)) return config.getString(current, fallback);
        return config.getString("database." + suffix, fallback);
    }

    private static int readInt(FileConfiguration config, String suffix, int fallback) {
        String current = "settings.database." + suffix;
        if (config.contains(current)) return config.getInt(current, fallback);
        return config.getInt("database." + suffix, fallback);
    }

    private static boolean readBoolean(FileConfiguration config, String suffix, boolean fallback) {
        String current = "settings.database." + suffix;
        if (config.contains(current)) return config.getBoolean(current, fallback);
        return config.getBoolean("database." + suffix, fallback);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
