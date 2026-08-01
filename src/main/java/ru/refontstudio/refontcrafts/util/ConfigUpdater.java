package ru.refontstudio.refontcrafts.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.refontstudio.refontcrafts.RefontCrafts;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class ConfigUpdater {
    private final RefontCrafts plugin;

    public ConfigUpdater(RefontCrafts plugin) {
        this.plugin = plugin;
    }

    public void writePretty() {
        writePretty(true);
    }

    public void writePretty(boolean backupExisting) {
        try {
            File cfgFile = new File(plugin.getDataFolder(), "config.yml");
            plugin.saveDefaultConfig();
            InputStream in = plugin.getResource("config.yml");
            if (in == null) return;

            List<String> tpl = readLines(in);
            FileConfiguration cfg = plugin.getConfig();
            List<String> out = mergeTemplateWithValues(tpl, cfg);
            if (backupExisting) {
                backup(cfgFile);
            }
            writeLines(cfgFile, out);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to update config.yml: " + ex.getMessage());
        }
    }

    private List<String> mergeTemplateWithValues(List<String> template, FileConfiguration cfg) {
        List<String> out = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        int i = 0;
        while (i < template.size()) {
            String line = template.get(i);
            String trim = line.trim();
            if (trim.isEmpty() || trim.startsWith("#")) {
                out.add(localizeComment(line));
                i++;
                continue;
            }

            Key k = parseKeyLine(line);
            if (k == null) {
                out.add(line);
                i++;
                continue;
            }

            while (path.size() > k.level) path.removeLast();
            if (k.section) {
                path.addLast(k.key);
                String full = join(path);
                Object val = cfg.get(full);
                if (val instanceof List) {
                    out.add(line);
                    int listIndent = k.indent + 2;
                    List<?> list = (List<?>) val;
                    for (Object o : list) {
                        out.add(spaces(listIndent) + "- " + yamlScalar(o));
                    }
                    int j = i + 1;
                    while (j < template.size()) {
                        String nl = template.get(j);
                        String nt = nl.trim();
                        if (nt.isEmpty() || nt.startsWith("#")) {
                            j++;
                            continue;
                        }
                        int ni = indentOf(nl);
                        if (ni > k.indent) {
                            j++;
                            continue;
                        }
                        break;
                    }
                    i = j;
                    continue;
                }
                out.add(line);
                i++;
                continue;
            }

            String full = buildPathForLeaf(path, k.level, k.key);
            Object val = cfg.get(full);
            if (val == null || val instanceof ConfigurationSection) {
                out.add(line);
            } else {
                out.add(spaces(k.indent) + k.key + ": " + yamlScalar(translateValue(full, val)));
            }
            i++;
        }
        return out;
    }

    private String localizeComment(String line) {
        int indent = indentOf(line);
        String body = line.substring(indent).trim();
        if (!body.startsWith("#")) return line;
        String translated = translateComment(body);
        if (translated == null || translated.isEmpty()) return line;
        return spaces(indent) + translated;
    }

    private String translateComment(String comment) {
        String locale = currentLocale();
        if ("ru".equals(locale)) return translateRu(comment);
        if ("vi".equals(locale)) return translateVi(comment);
        if ("zh_cn".equals(locale)) return translateZh(comment);
        return comment;
    }

    private String translateRu(String comment) {
        String translated;
        if ((translated = tr(comment, "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========")) != null) return translated;
        if ((translated = tr(comment, "# Author: https://t.me/orythix", "# Автор: https://t.me/orythix", "# Tác giả: https://t.me/orythix", "# 作者: https://t.me/orythix")) != null) return translated;
        if ((translated = tr(comment, "# This is the plugin config. Here you can adjust the appearance,", "# Это конфиг плагина. Здесь можно настроить внешний вид,", "# Đây là file cấu hình của plugin. Tại đây có thể chỉnh giao diện,", "# 这是插件配置。你可以在这里调整外观,")) != null) return translated;
        if ((translated = tr(comment, "# the database, and recipe behavior (workbench/anvil).", "# базу данных и поведение рецептов (верстак/наковальня).", "# cơ sở dữ liệu và hành vi công thức (bàn chế tạo/đe).", "# 数据库和配方行为（工作台/铁砧）。")) != null) return translated;
        if ((translated = tr(comment, "# Interface language: en, ru, vi, zh_cn", "# Язык интерфейса: en, ru, vi, zh_cn", "# Ngôn ngữ giao diện: en, ru, vi, zh_cn", "# 界面语言: en, ru, vi, zh_cn")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for workbench recipes", "# Заголовок GUI для рецептов верстака", "# Tiêu đề GUI cho công thức bàn chế tạo", "# 工作台配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for anvil recipes", "# Заголовок GUI для рецептов наковальни", "# Tiêu đề GUI cho công thức đe", "# 铁砧配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for workbench recipes (view menu)", "# Заголовок браузера рецептов верстака (меню просмотра)", "# Tiêu đề trình duyệt công thức bàn chế tạo (menu xem)", "# 工作台配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for anvil recipes (view menu)", "# Заголовок браузера рецептов наковальни (меню просмотра)", "# Tiêu đề trình duyệt công thức đe (menu xem)", "# 铁砧配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Exact item matching:", "# Точное сравнение предметов:", "# So sánh vật phẩm chính xác:", "# 精确物品匹配：")) != null) return translated;
        if ((translated = tr(comment, "# true  - compare isSimilar (name, lore, enchants, NBT, etc.)", "# true  - сравниваем isSimilar (имя, лор, чары, NBT и т.д.)", "# true  - so sánh isSimilar (tên, lore, phù phép, NBT, v.v.)", "# true  - 比较 isSimilar（名称、Lore、附魔、NBT 等）")) != null) return translated;
        if ((translated = tr(comment, "# false - compare stable metadata but ignore unique technical NBT.", "# false - сравниваем стабильные метаданные, но игнорируем уникальные служебные NBT.", "# false - so sánh metadata ổn định nhưng bỏ qua NBT kỹ thuật duy nhất.", "# false - 比较稳定元数据，但忽略唯一的技术 NBT。")) != null) return translated;
        if ((translated = tr(comment, "# Workbenches: strict 3x3 layout (the recipe works only with exact placement).", "# Верстаки: строго соблюдать выкладку 3x3 (рецепт сработает только при точном расположении).", "# Bàn chế tạo: giữ đúng bố cục 3x3 (công thức chỉ chạy khi đặt đúng vị trí).", "# 工作台：严格遵守 3x3 布局（只有精确位置才会生效）。")) != null) return translated;
        if ((translated = tr(comment, "# Limit of the visible craft preview/result stack. 126 keeps the client display working.", "# Лимит видимого крафта/превью результата. 126 нормально отображается в клиенте.", "# Giới hạn số lượng xem trước/kết quả hiển thị. 126 hiển thị нормально trong client.", "# 可见合成预览/结果堆叠上限。126 在客户端显示正常。")) != null) return translated;
        if ((translated = tr(comment, "# Allow horizontal mirror of the pattern. Default: no.", "# Разрешить горизонтальное отражение шаблона. По умолчанию: нет.", "# Cho phép lật ngang mẫu. Mặc định: không.", "# 允许水平镜像图案。默认：否。")) != null) return translated;
        if ((translated = tr(comment, "# If enabled, ABC will also match as CBA on the same row.", "# Если включено, ABC также будет совпадать как CBA в той же строке.", "# Nếu bật, ABC cũng khớp với CBA trên cùng một hàng.", "# 启用后，ABC 也会在同一行匹配为 CBA。")) != null) return translated;
        if ((translated = tr(comment, "# Return items to player when closing the GUI without saving", "# Возвращать предметы игроку при закрытии GUI без сохранения", "# Trả предмет cho người chơi khi đóng GUI mà không lưu", "# 关闭 GUI 且未保存时，将物品返还给玩家")) != null) return translated;
        if ((translated = tr(comment, "# Default cost for new anvil recipes (in levels)", "# Базовая стоимость новых рецептов на наковальне (в уровнях)", "# Chi phí mặc định cho công thức đe mới (tính theo cấp)", "# 新铁砧配方的默认等级消耗")) != null) return translated;
        if ((translated = tr(comment, "# Anvil mode:", "# Режим наковальни:", "# Chế độ đe:", "# 铁砧模式：")) != null) return translated;
        if ((translated = tr(comment, "# auto  - if AdvancedEnchantments is installed, use click-book mode; otherwise normal anvil", "# auto  - если установлен AdvancedEnchantments, используется режим клика по книге; иначе обычная наковальня", "# auto  - nếu có AdvancedEnchantments, dùng chế độ bấm sách; nếu không thì đe thường", "# auto  - 如果安装了 AdvancedEnchantments，则使用点书模式；否则使用普通铁砧")) != null) return translated;
        if ((translated = tr(comment, "# anvil - always use the anvil", "# anvil - всегда использовать наковальню", "# anvil - luôn dùng đe", "# anvil - 始终使用铁砧")) != null) return translated;
        if ((translated = tr(comment, "# click - always use inventory clicking", "# click - всегда использовать клики по инвентарю", "# click - luôn dùng thao tác click trong inventory", "# click - 始终使用背包点击")) != null) return translated;
        if ((translated = tr(comment, "# Database backend: sqlite is local .db file; mysql is external MySQL/MariaDB server.", "# База данных: sqlite - локальный файл .db; mysql - внешний сервер MySQL/MariaDB.", "# Cơ sở dữ liệu: sqlite là file .db local; mysql là máy chủ MySQL/MariaDB bên ngoài.", "# 数据库：sqlite 是本地 .db 文件；mysql 是外部 MySQL/MariaDB 服务器。")) != null) return translated;
        if ((translated = tr(comment, "# Recipes and items are stored with metadata and can be moved between servers.", "# Рецепты и предметы хранятся с метаданными и могут переноситься между серверами.", "# Công thức và vật phẩm được lưu kèm metadata và có thể chuyển giữa các server.", "# 配方和物品会随元数据一起保存，可在服务器之间迁移。")) != null) return translated;
        if ((translated = tr(comment, "# Database file name in the plugin folder", "# Имя файла базы данных в папке плагина", "# Tên file cơ sở dữ liệu trong thư mục plugin", "# 插件文件夹中的数据库文件名")) != null) return translated;
        if ((translated = tr(comment, "# MySQL host (host:port is allowed)", "# Хост MySQL (можно host:port)", "# Host MySQL (có thể dùng host:port)", "# MySQL 主机（可使用 host:port）")) != null) return translated;
        if ((translated = tr(comment, "# MySQL port", "# Порт MySQL", "# Cổng MySQL", "# MySQL 端口")) != null) return translated;
        if ((translated = tr(comment, "# Database name", "# Имя базы данных", "# Tên cơ sở dữ liệu", "# 数据库名称")) != null) return translated;
        if ((translated = tr(comment, "# Database user and password", "# Пользователь и пароль базы данных", "# Người dùng và mật khẩu cơ sở dữ liệu", "# 数据库用户和密码")) != null) return translated;
        if ((translated = tr(comment, "# Use SSL", "# Использовать SSL", "# Dùng SSL", "# 使用 SSL")) != null) return translated;
        if ((translated = tr(comment, "# Additional connection parameters (optional)", "# Дополнительные параметры подключения (необязательно)", "# Tham số kết nối bổ sung (tùy chọn)", "# 额外连接参数（可选）")) != null) return translated;
        if ((translated = tr(comment, "# Save new recipes asynchronously (less lag).", "# Сохранять новые рецепты асинхронно (меньше лагов).", "# Lưu công thức mới bất đồng bộ (ít lag hơn).", "# 新配方异步保存（减少卡顿）。")) != null) return translated;
        return comment;
    }

    private String translateVi(String comment) {
        String translated;
        if ((translated = tr(comment, "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========")) != null) return translated;
        if ((translated = tr(comment, "# Author: https://t.me/orythix", "# Автор: https://t.me/orythix", "# Tác giả: https://t.me/orythix", "# 作者: https://t.me/orythix")) != null) return translated;
        if ((translated = tr(comment, "# This is the plugin config. Here you can adjust the appearance,", "# Это конфиг плагина. Здесь можно настроить внешний вид,", "# Đây là file cấu hình của plugin. Tại đây có thể chỉnh giao diện,", "# 这是插件配置。你可以在这里调整外观,")) != null) return translated;
        if ((translated = tr(comment, "# the database, and recipe behavior (workbench/anvil).", "# базу данных и поведение рецептов (верстак/наковальня).", "# cơ sở dữ liệu và hành vi công thức (bàn chế tạo/đe).", "# 数据库和配方行为（工作台/铁砧）。")) != null) return translated;
        if ((translated = tr(comment, "# Interface language: en, ru, vi, zh_cn", "# Язык интерфейса: en, ru, vi, zh_cn", "# Ngôn ngữ giao diện: en, ru, vi, zh_cn", "# 界面语言: en, ru, vi, zh_cn")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for workbench recipes", "# Заголовок GUI для рецептов верстака", "# Tiêu đề GUI cho công thức bàn chế tạo", "# 工作台配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for anvil recipes", "# Заголовок GUI для рецептов наковальни", "# Tiêu đề GUI cho công thức đe", "# 铁砧配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for workbench recipes (view menu)", "# Заголовок браузера рецептов верстака (меню просмотра)", "# Tiêu đề trình duyệt công thức bàn chế tạo (menu xem)", "# 工作台配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for anvil recipes (view menu)", "# Заголовок браузера рецептов наковальни (меню просмотра)", "# Tiêu đề trình duyệt công thức đe (menu xem)", "# 铁砧配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Exact item matching:", "# Точное сравнение предметов:", "# So sánh vật phẩm chính xác:", "# 精确物品匹配：")) != null) return translated;
        if ((translated = tr(comment, "# true  - compare isSimilar (name, lore, enchants, NBT, etc.)", "# true  - сравниваем isSimilar (имя, лор, чары, NBT и т.д.)", "# true  - so sánh isSimilar (tên, lore, phù phép, NBT, v.v.)", "# true  - 比较 isSimilar（名称、Lore、附魔、NBT 等）")) != null) return translated;
        if ((translated = tr(comment, "# false - compare stable metadata but ignore unique technical NBT.", "# false - сравниваем стабильные метаданные, но игнорируем уникальные служебные NBT.", "# false - so sánh metadata ổn định nhưng bỏ qua NBT kỹ thuật duy nhất.", "# false - 比较稳定元数据，但忽略唯一的技术 NBT。")) != null) return translated;
        if ((translated = tr(comment, "# Workbenches: strict 3x3 layout (the recipe works only with exact placement).", "# Верстаки: строго соблюдать выкладку 3x3 (рецепт сработает только при точном расположении).", "# Bàn chế tạo: giữ đúng bố cục 3x3 (công thức chỉ chạy khi đặt đúng vị trí).", "# 工作台：严格遵守 3x3 布局（只有精确位置才会生效）。")) != null) return translated;
        if ((translated = tr(comment, "# Limit of the visible craft preview/result stack. 126 keeps the client display working.", "# Лимит видимого крафта/превью результата. 126 нормально отображается в клиенте.", "# Giới hạn số lượng xem trước/kết quả hiển thị. 126 hiển thị нормально trong client.", "# 可见合成预览/结果堆叠上限。126 在客户端显示正常。")) != null) return translated;
        if ((translated = tr(comment, "# Allow horizontal mirror of the pattern. Default: no.", "# Разрешить горизонтальное отражение шаблона. По умолчанию: нет.", "# Cho phép lật ngang mẫu. Mặc định: không.", "# 允许水平镜像图案。默认：否。")) != null) return translated;
        if ((translated = tr(comment, "# If enabled, ABC will also match as CBA on the same row.", "# Если включено, ABC также будет совпадать как CBA в той же строке.", "# Nếu bật, ABC cũng khớp với CBA trên cùng một hàng.", "# 启用后，ABC 也会在同一行匹配为 CBA。")) != null) return translated;
        if ((translated = tr(comment, "# Return items to player when closing the GUI without saving", "# Возвращать предметы игроку при закрытии GUI без сохранения", "# Trả предмет cho người chơi khi đóng GUI mà không lưu", "# 关闭 GUI 且未保存时，将物品返还给玩家")) != null) return translated;
        if ((translated = tr(comment, "# Default cost for new anvil recipes (in levels)", "# Базовая стоимость новых рецептов на наковальне (в уровнях)", "# Chi phí mặc định cho công thức đe mới (tính theo cấp)", "# 新铁砧配方的默认等级消耗")) != null) return translated;
        if ((translated = tr(comment, "# Anvil mode:", "# Режим наковальни:", "# Chế độ đe:", "# 铁砧模式：")) != null) return translated;
        if ((translated = tr(comment, "# auto  - if AdvancedEnchantments is installed, use click-book mode; otherwise normal anvil", "# auto  - если установлен AdvancedEnchantments, используется режим клика по книге; иначе обычная наковальня", "# auto  - nếu có AdvancedEnchantments, dùng chế độ bấm sách; nếu không thì đe thường", "# auto  - 如果安装了 AdvancedEnchantments，则使用点书模式；否则使用普通铁砧")) != null) return translated;
        if ((translated = tr(comment, "# anvil - always use the anvil", "# anvil - всегда использовать наковальню", "# anvil - luôn dùng đe", "# anvil - 始终使用铁砧")) != null) return translated;
        if ((translated = tr(comment, "# click - always use inventory clicking", "# click - всегда использовать клики по инвентарю", "# click - luôn dùng thao tác click trong inventory", "# click - 始终使用背包点击")) != null) return translated;
        if ((translated = tr(comment, "# Database backend: sqlite is local .db file; mysql is external MySQL/MariaDB server.", "# База данных: sqlite - локальный файл .db; mysql - внешний сервер MySQL/MariaDB.", "# Cơ sở dữ liệu: sqlite là file .db local; mysql là máy chủ MySQL/MariaDB bên ngoài.", "# 数据库：sqlite 是本地 .db 文件；mysql 是外部 MySQL/MariaDB 服务器。")) != null) return translated;
        if ((translated = tr(comment, "# Recipes and items are stored with metadata and can be moved between servers.", "# Рецепты и предметы хранятся с метаданными и могут переноситься между серверами.", "# Công thức và vật phẩm được lưu kèm metadata và có thể chuyển giữa các server.", "# 配方和物品会随元数据一起保存，可在服务器之间迁移。")) != null) return translated;
        if ((translated = tr(comment, "# Database file name in the plugin folder", "# Имя файла базы данных в папке плагина", "# Tên file cơ sở dữ liệu trong thư mục plugin", "# 插件文件夹中的数据库文件名")) != null) return translated;
        if ((translated = tr(comment, "# MySQL host (host:port is allowed)", "# Хост MySQL (можно host:port)", "# Host MySQL (có thể dùng host:port)", "# MySQL 主机（可使用 host:port）")) != null) return translated;
        if ((translated = tr(comment, "# MySQL port", "# Порт MySQL", "# Cổng MySQL", "# MySQL 端口")) != null) return translated;
        if ((translated = tr(comment, "# Database name", "# Имя базы данных", "# Tên cơ sở dữ liệu", "# 数据库名称")) != null) return translated;
        if ((translated = tr(comment, "# Database user and password", "# Пользователь и пароль базы данных", "# Người dùng và mật khẩu cơ sở dữ liệu", "# 数据库用户和密码")) != null) return translated;
        if ((translated = tr(comment, "# Use SSL", "# Использовать SSL", "# Dùng SSL", "# 使用 SSL")) != null) return translated;
        if ((translated = tr(comment, "# Additional connection parameters (optional)", "# Дополнительные параметры подключения (необязательно)", "# Tham số kết nối bổ sung (tùy chọn)", "# 额外连接参数（可选）")) != null) return translated;
        if ((translated = tr(comment, "# Save new recipes asynchronously (less lag).", "# Сохранять новые рецепты асинхронно (меньше лагов).", "# Lưu công thức mới bất đồng bộ (ít lag hơn).", "# 新配方异步保存（减少卡顿）。")) != null) return translated;
        return comment;
    }

    private String translateZh(String comment) {
        String translated;
        if ((translated = tr(comment, "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========", "# ========== RefontCrafts ==========")) != null) return translated;
        if ((translated = tr(comment, "# Author: https://t.me/orythix", "# Автор: https://t.me/orythix", "# Tác giả: https://t.me/orythix", "# 作者: https://t.me/orythix")) != null) return translated;
        if ((translated = tr(comment, "# This is the plugin config. Here you can adjust the appearance,", "# Это конфиг плагина. Здесь можно настроить внешний вид,", "# Đây là file cấu hình của plugin. Tại đây có thể chỉnh giao diện,", "# 这是插件配置。你可以在这里调整外观,")) != null) return translated;
        if ((translated = tr(comment, "# the database, and recipe behavior (workbench/anvil).", "# базу данных и поведение рецептов (верстак/наковальня).", "# cơ sở dữ liệu và hành vi công thức (bàn chế tạo/đe).", "# 数据库和配方行为（工作台/铁砧）。")) != null) return translated;
        if ((translated = tr(comment, "# Interface language: en, ru, vi, zh_cn", "# Язык интерфейса: en, ru, vi, zh_cn", "# Ngôn ngữ giao diện: en, ru, vi, zh_cn", "# 界面语言: en, ru, vi, zh_cn")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for workbench recipes", "# Заголовок GUI для рецептов верстака", "# Tiêu đề GUI cho công thức bàn chế tạo", "# 工作台配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# GUI title for anvil recipes", "# Заголовок GUI для рецептов наковальни", "# Tiêu đề GUI cho công thức đe", "# 铁砧配方 GUI 标题")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for workbench recipes (view menu)", "# Заголовок браузера рецептов верстака (меню просмотра)", "# Tiêu đề trình duyệt công thức bàn chế tạo (menu xem)", "# 工作台配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Browser title for anvil recipes (view menu)", "# Заголовок браузера рецептов наковальни (меню просмотра)", "# Tiêu đề trình duyệt công thức đe (menu xem)", "# 铁砧配方浏览器标题（查看菜单）")) != null) return translated;
        if ((translated = tr(comment, "# Exact item matching:", "# Точное сравнение предметов:", "# So sánh vật phẩm chính xác:", "# 精确物品匹配：")) != null) return translated;
        if ((translated = tr(comment, "# true  - compare isSimilar (name, lore, enchants, NBT, etc.)", "# true  - сравниваем isSimilar (имя, лор, чары, NBT и т.д.)", "# true  - so sánh isSimilar (tên, lore, phù phép, NBT, v.v.)", "# true  - 比较 isSimilar（名称、Lore、附魔、NBT 等）")) != null) return translated;
        if ((translated = tr(comment, "# false - compare stable metadata but ignore unique technical NBT.", "# false - сравниваем стабильные метаданные, но игнорируем уникальные служебные NBT.", "# false - so sánh metadata ổn định nhưng bỏ qua NBT kỹ thuật duy nhất.", "# false - 比较稳定元数据，但忽略唯一的技术 NBT。")) != null) return translated;
        if ((translated = tr(comment, "# Workbenches: strict 3x3 layout (the recipe works only with exact placement).", "# Верстаки: строго соблюдать выкладку 3x3 (рецепт сработает только при точном расположении).", "# Bàn chế tạo: giữ đúng bố cục 3x3 (công thức chỉ chạy khi đặt đúng vị trí).", "# 工作台：严格遵守 3x3 布局（只有精确位置才会生效）。")) != null) return translated;
        if ((translated = tr(comment, "# Limit of the visible craft preview/result stack. 126 keeps the client display working.", "# Лимит видимого крафта/превью результата. 126 нормально отображается в клиенте.", "# Giới hạn số lượng xem trước/kết quả hiển thị. 126 hiển thị нормально trong client.", "# 可见合成预览/结果堆叠上限。126 在客户端显示正常。")) != null) return translated;
        if ((translated = tr(comment, "# Allow horizontal mirror of the pattern. Default: no.", "# Разрешить горизонтальное отражение шаблона. По умолчанию: нет.", "# Cho phép lật ngang mẫu. Mặc định: không.", "# 允许水平镜像图案。默认：否。")) != null) return translated;
        if ((translated = tr(comment, "# If enabled, ABC will also match as CBA on the same row.", "# Если включено, ABC также будет совпадать как CBA в той же строке.", "# Nếu bật, ABC cũng khớp với CBA trên cùng một hàng.", "# 启用后，ABC 也会在同一行匹配为 CBA。")) != null) return translated;
        if ((translated = tr(comment, "# Return items to player when closing the GUI without saving", "# Возвращать предметы игроку при закрытии GUI без сохранения", "# Trả предмет cho người chơi khi đóng GUI mà không lưu", "# 关闭 GUI 且未保存时，将物品返还给玩家")) != null) return translated;
        if ((translated = tr(comment, "# Default cost for new anvil recipes (in levels)", "# Базовая стоимость новых рецептов на наковальне (в уровнях)", "# Chi phí mặc định cho công thức đe mới (tính theo cấp)", "# 新铁砧配方的默认等级消耗")) != null) return translated;
        if ((translated = tr(comment, "# Anvil mode:", "# Режим наковальни:", "# Chế độ đe:", "# 铁砧模式：")) != null) return translated;
        if ((translated = tr(comment, "# auto  - if AdvancedEnchantments is installed, use click-book mode; otherwise normal anvil", "# auto  - если установлен AdvancedEnchantments, используется режим клика по книге; иначе обычная наковальня", "# auto  - nếu có AdvancedEnchantments, dùng chế độ bấm sách; nếu không thì đe thường", "# auto  - 如果安装了 AdvancedEnchantments，则使用点书模式；否则使用普通铁砧")) != null) return translated;
        if ((translated = tr(comment, "# anvil - always use the anvil", "# anvil - всегда использовать наковальню", "# anvil - luôn dùng đe", "# anvil - 始终使用铁砧")) != null) return translated;
        if ((translated = tr(comment, "# click - always use inventory clicking", "# click - всегда использовать клики по инвентарю", "# click - luôn dùng thao tác click trong inventory", "# click - 始终使用背包点击")) != null) return translated;
        if ((translated = tr(comment, "# Database backend: sqlite is local .db file; mysql is external MySQL/MariaDB server.", "# База данных: sqlite - локальный файл .db; mysql - внешний сервер MySQL/MariaDB.", "# Cơ sở dữ liệu: sqlite là file .db local; mysql là máy chủ MySQL/MariaDB bên ngoài.", "# 数据库：sqlite 是本地 .db 文件；mysql 是外部 MySQL/MariaDB 服务器。")) != null) return translated;
        if ((translated = tr(comment, "# Recipes and items are stored with metadata and can be moved between servers.", "# Рецепты и предметы хранятся с метаданными и могут переноситься между серверами.", "# Công thức và vật phẩm được lưu kèm metadata và có thể chuyển giữa các server.", "# 配方和物品会随元数据一起保存，可在服务器之间迁移。")) != null) return translated;
        if ((translated = tr(comment, "# Database file name in the plugin folder", "# Имя файла базы данных в папке плагина", "# Tên file cơ sở dữ liệu trong thư mục plugin", "# 插件文件夹中的数据库文件名")) != null) return translated;
        if ((translated = tr(comment, "# MySQL host (host:port is allowed)", "# Хост MySQL (можно host:port)", "# Host MySQL (có thể dùng host:port)", "# MySQL 主机（可使用 host:port）")) != null) return translated;
        if ((translated = tr(comment, "# MySQL port", "# Порт MySQL", "# Cổng MySQL", "# MySQL 端口")) != null) return translated;
        if ((translated = tr(comment, "# Database name", "# Имя базы данных", "# Tên cơ sở dữ liệu", "# 数据库名称")) != null) return translated;
        if ((translated = tr(comment, "# Database user and password", "# Пользователь и пароль базы данных", "# Người dùng và mật khẩu cơ sở dữ liệu", "# 数据库用户和密码")) != null) return translated;
        if ((translated = tr(comment, "# Use SSL", "# Использовать SSL", "# Dùng SSL", "# 使用 SSL")) != null) return translated;
        if ((translated = tr(comment, "# Additional connection parameters (optional)", "# Дополнительные параметры подключения (необязательно)", "# Tham số kết nối bổ sung (tùy chọn)", "# 额外连接参数（可选）")) != null) return translated;
        if ((translated = tr(comment, "# Save new recipes asynchronously (less lag).", "# Сохранять новые рецепты асинхронно (меньше лагов).", "# Lưu công thức mới bất đồng bộ (ít lag hơn).", "# 新配方异步保存（减少卡顿）。")) != null) return translated;
        return comment;
    }

    private String tr(String comment, String en, String ru, String vi, String zh) {
        if (!comment.equals(en)) return null;
        String locale = currentLocale();
        if ("ru".equals(locale)) return ru;
        if ("vi".equals(locale)) return vi;
        if ("zh_cn".equals(locale)) return zh;
        return en;
    }

    private Object translateValue(String path, Object value) {
        if (!(value instanceof String)) return value;
        if ("settings.prefix".equals(path) && isLegacyPrefix((String) value)) {
            return RefontCrafts.DEFAULT_PREFIX;
        }
        if ("settings.titles.recipe".equals(path)) return plugin.tr("titles.recipe", (String) value);
        if ("settings.titles.anvil".equals(path)) return plugin.tr("titles.anvil", (String) value);
        if ("settings.titles.browser_workbench".equals(path)) return plugin.tr("titles.browser_workbench", (String) value);
        if ("settings.titles.browser_anvil".equals(path)) return plugin.tr("titles.browser_anvil", (String) value);
        return value;
    }

    private boolean isLegacyPrefix(String value) {
        return "&7[&aRefontCrafts&7] ".equals(value)
                || "&a[RefontCrafts] ".equals(value);
    }

    private String currentLocale() {
        if (plugin.language() != null) return plugin.language().locale();
        String raw = plugin.getConfig().getString("settings.language", "en");
        if (raw == null) return "en";
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (s.startsWith("ru")) return "ru";
        if (s.startsWith("vi")) return "vi";
        if (s.startsWith("zh")) return "zh_cn";
        return "en";
    }

    private static class Key {
        final int indent;
        final int level;
        final String key;
        final boolean section;

        Key(int indent, int level, String key, boolean section) {
            this.indent = indent;
            this.level = level;
            this.key = key;
            this.section = section;
        }
    }

    private Key parseKeyLine(String line) {
        int indent = indentOf(line);
        int level = indent / 2;
        String body = line.substring(indent);
        int idx = body.indexOf(':');
        if (idx <= 0) return null;
        String k = body.substring(0, idx).trim();
        if (!k.matches("[A-Za-z0-9_]+")) return null;
        String after = body.substring(idx + 1);
        boolean section = after.trim().isEmpty();
        boolean listItem = body.trim().startsWith("- ");
        if (listItem) return null;
        return new Key(indent, level, k, section);
    }

    private int indentOf(String s) {
        int c = 0;
        while (c < s.length() && s.charAt(c) == ' ') c++;
        return c;
    }

    private String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    private String join(Deque<String> path) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : path) {
            if (!first) sb.append('.');
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }

    private String buildPathForLeaf(Deque<String> path, int level, String leaf) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String p : path) {
            if (i >= level) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(p);
            i++;
        }
        if (sb.length() > 0) sb.append('.');
        sb.append(leaf);
        return sb.toString();
    }

    private String yamlScalar(Object v) {
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(yamlScalar(l.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        String s = String.valueOf(v);
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }

    private List<String> readLines(InputStream in) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = br.readLine()) != null) out.add(ln);
        }
        return out;
    }

    private void writeLines(File f, List<String> lines) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                bw.write(line);
                bw.write("\n");
            }
        }
    }

    private void backup(File src) {
        try {
            File dst = new File(src.getParentFile(), "config.yml.bak");
            try (FileInputStream is = new FileInputStream(src); FileOutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
            }
        } catch (Exception ignored) {
        }
    }
}
