# RefontCrafts — Documentation

**RefontCrafts** is a GUI-based Minecraft server plugin for custom crafting in crafting tables and anvils.

It is designed for survival, RPG, roleplay, economy and custom servers that need flexible recipes, permission checks and support for custom items.

## Features

- GUI editor for crafting table recipes.
- GUI editor for anvil recipes.
- Strict 3x3 shaped recipes and shapeless recipes.
- Recipe browser for regular players without OP.
- Support for custom items, potions, CustomModelData, names, lore, enchantments and PDC data.
- Softer item matching with `exact_meta_match: false`.
- Strict item order for anvil recipes.
- Automatic result preview updates on left click, right click and drag placement.
- Crafting table preview and result stacks up to `126`.
- SQLite by default, optional MySQL support.

## Requirements

| Item | Value |
| --- | --- |
| Server software | Bukkit / Spigot / Paper |
| Java | Java 8+ for older servers; use the correct Java version for newer Minecraft versions |
| Database | SQLite or MySQL |
| Main command | `/rcrafts` |
| Aliases | `/rc`, `/refontcrafts` |

## Installation

1. Download the latest `.jar` from Releases or Modrinth.
2. Put the file into the `plugins/` folder.
3. Restart your server.
4. Open the main menu:

```text
/rcrafts
```

The plugin will create its configuration and database on first startup.

## Commands

| Command | Description |
| --- | --- |
| `/rcrafts` | Main menu |
| `/rcrafts view workbench [page]` | View crafting table recipes |
| `/rcrafts view anvil [page]` | View anvil recipes |
| `/rcrafts recipe` | Create a crafting table recipe |
| `/rcrafts anvil` | Create an anvil recipe |
| `/rcrafts reload` | Reload configuration and recipes |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `refontcrafts.use` | `true` | Basic access to `/rcrafts` |
| `refontcrafts.view` | `true` | View recipes |
| `refontcrafts.create.workbench` | `op` | Create crafting table recipes |
| `refontcrafts.create.anvil` | `op` | Create anvil recipes |
| `refontcrafts.edit.workbench` | `op` | Edit crafting table recipes |
| `refontcrafts.edit.anvil` | `op` | Edit anvil recipes |
| `refontcrafts.delete.workbench` | `op` | Delete crafting table recipes |
| `refontcrafts.delete.anvil` | `op` | Delete anvil recipes |
| `refontcrafts.reload` | `op` | Reload the plugin |
| `refontcrafts.notify` | `op` | Internal notifications |
| `refontcrafts.admin` | `op` | Full access |

## Important settings

```yaml
settings:
  exact_meta_match: false
  workbench_strict_shape: true
  workbench_preview_limit: 126

  anvil:
    strict_order: true
```

### `exact_meta_match`

- `true` — strict item comparison.
- `false` — compares important visual and logical item data while ignoring unique technical NBT tags.

`false` is recommended for servers that use shops, Brewery, ExecutableItems or other custom item plugins.

### `workbench_strict_shape`

When enabled, shaped crafting table recipes only work when items are placed in the exact 3x3 slots.

### `workbench_preview_limit`

Controls the visual result amount in the crafting table. `126` is used as a stable limit for overstack result display.

### `anvil.strict_order`

When enabled, left and right anvil slots are treated as different slots. Recipe `A + B` will not work as `B + A`.

## FAQ

### Why is the result preview limited to 126?

This is a practical stable limit for client-side overstack result rendering. Higher values may make the result slot display incorrectly.

### Can players only view recipes?

Yes. Give them only these permissions:

```text
refontcrafts.use
refontcrafts.view
```

### Where should bugs be reported?

Use GitHub Issues: https://github.com/RizonChik/RefontCrafts/issues

## Build from source

```bash
mvn clean package
```

The compiled `.jar` will be created in the `target/` folder.
