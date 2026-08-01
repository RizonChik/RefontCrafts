package ru.refontstudio.refontcrafts;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.refontstudio.refontcrafts.commands.RefontCraftsCommand;
import ru.refontstudio.refontcrafts.db.Database;
import ru.refontstudio.refontcrafts.gui.AnvilEditorMenu;
import ru.refontstudio.refontcrafts.gui.CraftBrowserMenu;
import ru.refontstudio.refontcrafts.gui.RecipeBrowserMenu;
import ru.refontstudio.refontcrafts.gui.RecipeEditorMenu;
import ru.refontstudio.refontcrafts.listeners.AnvilClickListener;
import ru.refontstudio.refontcrafts.listeners.AnvilListener;
import ru.refontstudio.refontcrafts.listeners.WorkbenchListener;
import ru.refontstudio.refontcrafts.libs.LibraryManager;
import ru.refontstudio.refontcrafts.storage.RecipeStorage;
import ru.refontstudio.refontcrafts.util.ChatLog;
import ru.refontstudio.refontcrafts.util.ChatLogger;
import ru.refontstudio.refontcrafts.util.Compat;
import ru.refontstudio.refontcrafts.util.ConfigUpdater;
import ru.refontstudio.refontcrafts.util.LanguageManager;
import ru.refontstudio.refontcrafts.util.Text;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Handler;
import java.util.logging.Level;

public final class RefontCrafts extends JavaPlugin {
    public static final String DEFAULT_PREFIX = "§x§2§5§A§F§F§1R§x§2§2§A§8§F§2e§x§1§E§A§1§F§4f§x§1§B§9§B§F§5o§x§1§8§9§4§F§6n§x§1§4§8§D§F§7t§x§1§1§8§6§F§9C§x§0§D§7§F§F§Ar§x§0§A§7§8§F§Ba§x§0§7§7§2§F§Cf§x§0§3§6§B§F§Et§x§0§0§6§4§F§Fs &8»&7 ";
    private static RefontCrafts instance;

    private Database database;
    private RecipeStorage storage;
    private RecipeEditorMenu recipeMenu;
    private AnvilEditorMenu anvilMenu;
    private RecipeBrowserMenu browserMenu;
    private CraftBrowserMenu craftMenu;
    private LanguageManager language;
    private LibraryManager libraries;
    private ChatLogger chatLogger;
    private FileConfiguration utf8Config;
    private boolean clickAnvilMode;
    private boolean forceSqliteFailover;

    public static RefontCrafts getInstance() {
        return instance;
    }

    public Database database() {
        return database;
    }

    public RecipeStorage storage() {
        return storage;
    }

    public RecipeEditorMenu recipeMenu() {
        return recipeMenu;
    }

    public AnvilEditorMenu anvilMenu() {
        return anvilMenu;
    }

    public RecipeBrowserMenu browserMenu() {
        return browserMenu;
    }

    public CraftBrowserMenu craftMenu() {
        return craftMenu;
    }

    public LanguageManager language() {
        return language;
    }

    public LibraryManager libraries() {
        return libraries;
    }

    public boolean clickAnvilMode() {
        return clickAnvilMode;
    }

    public boolean forceSqliteFailover() {
        return forceSqliteFailover;
    }

    @Override
    public void onEnable() {
        instance = this;
        boolean configExisted = new File(getDataFolder(), "config.yml").isFile();
        saveDefaultConfig();
        reloadConfig();

        language = new LanguageManager(this);
        new ConfigUpdater(this).writePretty(configExisted);

        if (!prepareRuntimeLibraries()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().setUseParentHandlers(true);
        for (Handler handler : getLogger().getHandlers()) {
            if (handler instanceof ChatLogger) getLogger().removeHandler(handler);
        }
        chatLogger = new ChatLogger();
        getLogger().addHandler(chatLogger);

        database = new Database(this);
        storage = new RecipeStorage(this, database);
        recipeMenu = new RecipeEditorMenu(this, storage);
        anvilMenu = new AnvilEditorMenu(this, storage);
        browserMenu = new RecipeBrowserMenu(this, storage);
        craftMenu = new CraftBrowserMenu(this, storage);

        String mode = getConfig().getString("settings.anvil_mode", "auto").toLowerCase();
        boolean aePresent = Bukkit.getPluginManager().getPlugin("AdvancedEnchantments") != null;
        clickAnvilMode = mode.equals("click") || (mode.equals("auto") && aePresent);

        Bukkit.getPluginManager().registerEvents(recipeMenu, this);
        Bukkit.getPluginManager().registerEvents(anvilMenu, this);
        Bukkit.getPluginManager().registerEvents(browserMenu, this);
        Bukkit.getPluginManager().registerEvents(craftMenu, this);

        if (clickAnvilMode) {
            Bukkit.getPluginManager().registerEvents(new AnvilClickListener(this, storage), this);
        } else {
            Bukkit.getPluginManager().registerEvents(new AnvilListener(this, storage), this);
        }
        Bukkit.getPluginManager().registerEvents(new WorkbenchListener(this), this);

        RefontCraftsCommand cmd = new RefontCraftsCommand(this);
        if (getCommand("rcrafts") != null) {
            getCommand("rcrafts").setExecutor(cmd);
            getCommand("rcrafts").setTabCompleter(cmd);
        }
        if (getCommand("crafts") != null) {
            getCommand("crafts").setExecutor(cmd);
            getCommand("crafts").setTabCompleter(cmd);
        }

        storage.loadAllAsync(() -> ChatLog.send(prefix() + msg("loaded_recipes", "shapeless", String.valueOf(storage.shapelessCount()), "anvil", String.valueOf(storage.anvilCount()))));
    }

    private boolean prepareRuntimeLibraries() {
        libraries = new LibraryManager(this);
        String configured = getConfig().getString(
                "settings.database.type", getConfig().getString("database.type", "sqlite"));
        String backend = configured == null ? "sqlite" : configured.trim().toLowerCase();

        try {
            libraries.prepare(backend);
            return true;
        } catch (java.sql.SQLException primaryError) {
            if ("mysql".equals(backend)) {
                getLogger().log(Level.WARNING,
                        "MySQL JDBC library is unavailable; preparing SQLite failover instead.", primaryError);
                try {
                    libraries.prepare("sqlite");
                    forceSqliteFailover = true;
                    return true;
                } catch (java.sql.SQLException sqliteError) {
                    primaryError.addSuppressed(sqliteError);
                }
            }

            getLogger().log(Level.SEVERE,
                    "Required JDBC libraries could not be prepared. Check internet access or place the verified JAR files in "
                            + libraries.directory().getAbsolutePath(), primaryError);
            return false;
        }
    }

    @Override
    public void onDisable() {
        if (chatLogger != null) {
            getLogger().removeHandler(chatLogger);
            chatLogger.close();
            chatLogger = null;
        }
        if (storage != null) {
            storage.shutdown();
            storage.unregisterAllShapeless();
        }
        if (libraries != null) libraries.close();
    }

    public String prefix() {
        return Text.color(getConfig().getString("settings.prefix", DEFAULT_PREFIX));
    }

    public String titleMainMenu() {
        return Compat.inventoryTitle(tr("titles.main", "RefontCrafts"));
    }

    public String titleRecipe() {
        return Compat.inventoryTitle(tr("titles.recipe", "Recipe Editor"));
    }

    public String titleAnvil() {
        return Compat.inventoryTitle(tr("titles.anvil", "Anvil Editor"));
    }

    public String titleBrowseWorkbench() {
        return Compat.inventoryTitle(tr("titles.browser_workbench", "Workbench Recipes"));
    }

    public String titleBrowseAnvil() {
        return Compat.inventoryTitle(tr("titles.browser_anvil", "Anvil Recipes"));
    }

    public String titleCrafts() {
        return Compat.inventoryTitle(tr("titles.crafts", "Craft Browser"));
    }

    public String titleCraftPreview() {
        return Compat.inventoryTitle(tr("titles.craft_preview", "Craft Preview"));
    }

    public String titleConfirmDelete() {
        return Compat.inventoryTitle(tr("titles.confirm_delete", "Confirm Delete"));
    }

    public String tr(String key, String fallback, String... placeholders) {
        if (language == null) {
            return Text.color(fallback);
        }
        return language.text(key, fallback, placeholders);
    }

    public boolean exactMeta() {
        return getConfig().getBoolean("settings.exact_meta_match", false);
    }

    public int defaultAnvilCost() {
        return getConfig().getInt("settings.default_anvil_cost", 0);
    }

    public boolean workbenchStrictShape() {
        return getConfig().getBoolean("settings.workbench_strict_shape", true);
    }

    public boolean workbenchAllowMirror() {
        return getConfig().getBoolean("settings.workbench_allow_mirror", false);
    }

    public int craftPreviewLimit() {
        return getConfig().getInt("settings.workbench_preview_limit", 126);
    }

    public String msg(String key) {
        return tr("messages." + key, "");
    }

    public String msg(String key, String... ph) {
        return tr("messages." + key, "", ph);
    }

    @Override
    public FileConfiguration getConfig() {
        if (utf8Config == null) reloadConfig();
        return utf8Config;
    }

    @Override
    public void reloadConfig() {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration loaded = new YamlConfiguration();

        if (file.isFile()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                loaded.load(reader);
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Could not load config.yml as UTF-8", ex);
            }
        }

        InputStream defaultsStream = getResource("config.yml");
        if (defaultsStream != null) {
            try (Reader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Could not load default config.yml as UTF-8", ex);
            }
        }
        utf8Config = loaded;
    }

    @Override
    public void saveConfig() {
        File file = new File(getDataFolder(), "config.yml");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            getLogger().warning("Could not create plugin data folder");
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(getConfig().saveToString());
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not save config.yml as UTF-8", ex);
        }
    }

    public void reloadAll() {
        reloadConfig();
        if (language != null) {
            language.reload();
        }
        new ConfigUpdater(this).writePretty();
        storage.unregisterAllShapeless();
        storage.loadAllAsync(() -> {
        });
    }
}
