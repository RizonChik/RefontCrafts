# RefontCrafts — 文档

**RefontCrafts** 是一个用于 Minecraft 服务器的 GUI 插件，可以在工作台和铁砧中创建自定义合成配方。

它适合 survival、RPG、roleplay、economy 和 custom 类型服务器，适用于需要灵活配方、权限控制和自定义物品支持的项目。

## 功能

- 工作台配方 GUI 编辑器。
- 铁砧配方 GUI 编辑器。
- 支持严格 3x3 有序配方和无序配方。
- 普通玩家无需 OP 也可以查看已保存的配方。
- 支持自定义物品、药水、CustomModelData、名称、lore、附魔和 PDC 数据。
- 使用 `exact_meta_match: false` 时支持更宽松的物品比较。
- 铁砧配方可以启用严格左右槽位顺序。
- 左键、右键和拖动物品时自动更新结果预览。
- 工作台结果预览和发放数量最高可到 `126`。
- 默认 SQLite，可选 MySQL。

## 要求

| 项目 | 值 |
| --- | --- |
| 服务端 | Bukkit / Spigot / Paper |
| Java | 旧版本服务器可使用 Java 8+；新版本 Minecraft 请使用对应 Java 版本 |
| 数据库 | SQLite 或 MySQL |
| 主命令 | `/rcrafts` |
| 别名 | `/rc`, `/refontcrafts` |

## 安装

1. 从 Releases 或 Modrinth 下载最新 `.jar` 文件。
2. 将文件放入 `plugins/` 文件夹。
3. 重启服务器。
4. 打开主菜单：

```text
/rcrafts
```

插件会在首次启动时自动创建配置文件和数据库。

## 命令

| 命令 | 说明 |
| --- | --- |
| `/rcrafts` | 主菜单 |
| `/rcrafts view workbench [page]` | 查看工作台配方 |
| `/rcrafts view anvil [page]` | 查看铁砧配方 |
| `/rcrafts recipe` | 创建工作台配方 |
| `/rcrafts anvil` | 创建铁砧配方 |
| `/rcrafts reload` | 重新加载配置和配方 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `refontcrafts.use` | `true` | 使用 `/rcrafts` 的基础权限 |
| `refontcrafts.view` | `true` | 查看配方 |
| `refontcrafts.create.workbench` | `op` | 创建工作台配方 |
| `refontcrafts.create.anvil` | `op` | 创建铁砧配方 |
| `refontcrafts.edit.workbench` | `op` | 编辑工作台配方 |
| `refontcrafts.edit.anvil` | `op` | 编辑铁砧配方 |
| `refontcrafts.delete.workbench` | `op` | 删除工作台配方 |
| `refontcrafts.delete.anvil` | `op` | 删除铁砧配方 |
| `refontcrafts.reload` | `op` | 重新加载插件 |
| `refontcrafts.notify` | `op` | 插件内部通知 |
| `refontcrafts.admin` | `op` | 完整权限 |

## 重要配置

```yaml
settings:
  exact_meta_match: false
  workbench_strict_shape: true
  workbench_preview_limit: 126

  anvil:
    strict_order: true
```

### `exact_meta_match`

- `true` — 严格比较物品。
- `false` — 比较重要的显示和逻辑数据，同时忽略唯一的技术性 NBT 标签。

如果服务器使用商店、Brewery、ExecutableItems 或其他自定义物品插件，建议使用 `false`。

### `workbench_strict_shape`

启用后，工作台有序配方必须按准确的 3x3 槽位摆放才能生效。

### `workbench_preview_limit`

控制工作台结果预览数量。`126` 是 overstack 结果显示的稳定限制。

### `anvil.strict_order`

启用后，铁砧左槽和右槽会被视为不同槽位。配方 `A + B` 不会作为 `B + A` 生效。

## FAQ

### 为什么结果预览限制为 126？

这是客户端稳定显示 overstack 结果的实际限制。更高的数值可能导致结果槽显示异常。

### 可以只允许玩家查看配方吗？

可以。只需要给予：

```text
refontcrafts.use
refontcrafts.view
```

### 在哪里报告问题？

使用 GitHub Issues: https://github.com/RizonChik/RefontCrafts/issues

## 从源码构建

```bash
mvn clean package
```

编译后的 `.jar` 会生成在 `target/` 文件夹中。
