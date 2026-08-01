package ru.refontstudio.refontcrafts.util;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.refontstudio.refontcrafts.RefontCrafts;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class LanguageManager {
    private final RefontCrafts plugin;
    private YamlConfiguration english;
    private YamlConfiguration selected;
    private String locale;

    public LanguageManager(RefontCrafts plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.locale = normalize(plugin.getConfig().getString("settings.language", "en"));
        ensureMessageFiles();
        this.english = load("messages/en.yml");
        this.selected = "en".equals(locale) ? english : load("messages/" + locale + ".yml");
        if (this.selected == null) this.selected = english;
    }

    public String locale() {
        return locale;
    }

    public String text(String key, String fallback, String... placeholders) {
        String value = read(selected, key);
        if (value == null) value = read(english, key);
        if (value == null) value = fallback;
        if (value == null) value = "";
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
            }
        }
        return Text.color(value);
    }

    private YamlConfiguration load(String path) {
        YamlConfiguration bundled = loadBundled(path);
        File file = new File(plugin.getDataFolder(), path);
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                YamlConfiguration external = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                external.setDefaults(bundled);
                return external;
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load external language file " + path + ": " + ex.getMessage());
            }
        }
        return bundled;
    }

    private YamlConfiguration loadBundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                plugin.getLogger().warning("Language resource not found: " + path);
                return new YamlConfiguration();
            }
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return cfg;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load language resource " + path + ": " + ex.getMessage());
            return new YamlConfiguration();
        }
    }

    private void ensureMessageFiles() {
        for (String language : new String[]{"en", "ru", "vi", "zh_cn"}) {
            String path = "messages/" + language + ".yml";
            File file = new File(plugin.getDataFolder(), path);
            if (file.isFile()) continue;
            try {
                plugin.saveResource(path, false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Failed to create language file " + path + ": " + ex.getMessage());
            }
        }
    }

    private String read(YamlConfiguration cfg, String key) {
        if (cfg == null) return null;
        String value = cfg.getString(key);
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private String normalize(String raw) {
        if (raw == null) return "en";
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (s.startsWith("ru")) return "ru";
        if (s.startsWith("vi")) return "vi";
        if (s.startsWith("zh")) return "zh_cn";
        return "en";
    }
}
