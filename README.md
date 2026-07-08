# RefontCrafts

<div align="center">

![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--1.21.x-4caf50?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-8%2B%20%2F%2021-f39c12?style=for-the-badge)
![Core](https://img.shields.io/badge/Bukkit%20%7C%20Spigot%20%7C%20Paper-blue?style=for-the-badge)
![Release](https://img.shields.io/github/v/release/RizonChik/RefontCrafts?style=for-the-badge)

**GUI-плагин для кастомных крафтов в верстаке и наковальне.**

[Скачать последнюю версию](https://github.com/RizonChik/RefontCrafts/releases/latest)

</div>

---

## Возможности

- GUI-редактор рецептов верстака: строгая выкладка 3x3 и бесформенные рецепты.
- GUI-редактор рецептов наковальни с настраиваемой стоимостью опыта.
- Просмотр сохранённых рецептов игроками без OP.
- Поддержка кастомных предметов, зелий, CustomModelData, названий, лора, чар и стабильных PDC-данных.
- Более мягкое сравнение предметов при `exact_meta_match: false`: уникальные служебные NBT не ломают одинаковые предметы из магазинов и сторонних плагинов.
- Строгий порядок предметов в наковальне: левый и правый слот не взаимозаменяемы.
- Автообновление визуального результата крафта при ЛКМ, ПКМ и drag-раскладке.
- Preview и выдача результата верстака стаками до `126`.
- SQLite по умолчанию, MySQL опционально.
- Асинхронное сохранение рецептов и автообновление `config.yml`.

---

## Поддержка

| Параметр | Значение |
| --- | --- |
| Minecraft | `1.16.5 - 1.21.x` |
| Ядра | `Bukkit`, `Spigot`, `Paper` |
| Java | `8+` для старых серверов, `21` для новых 1.21.x сборок |
| База данных | `SQLite`, `MySQL` |

---

## Установка

1. Скачай `RefontCrafts-1.0.8.jar` из [Releases](https://github.com/RizonChik/RefontCrafts/releases/latest).
2. Закинь jar в папку `plugins/`.
3. Перезапусти сервер.
4. Открой `/rcrafts`.

Плагин сам создаст `config.yml` и локальную базу `data.db`.

---

## Команды

| Команда | Описание |
| --- | --- |
| `/rcrafts` | Главное меню |
| `/rcrafts view workbench [page]` | Просмотр рецептов верстака |
| `/rcrafts view anvil [page]` | Просмотр рецептов наковальни |
| `/rcrafts recipe` | Создание рецепта верстака |
| `/rcrafts anvil` | Создание рецепта наковальни |
| `/rcrafts reload` | Перезагрузка конфига и рецептов |

Алиасы: `/rc`, `/refontcrafts`

---

## Права

| Право | По умолчанию | Описание |
| --- | --- | --- |
| `refontcrafts.use` | `true` | Базовый доступ к команде `/rcrafts` |
| `refontcrafts.view` | `true` | Просмотр рецептов верстака и наковальни |
| `refontcrafts.create.workbench` | `op` | Создание рецептов верстака |
| `refontcrafts.create.anvil` | `op` | Создание рецептов наковальни |
| `refontcrafts.edit.workbench` | `op` | Редактирование рецептов верстака |
| `refontcrafts.edit.anvil` | `op` | Редактирование рецептов наковальни |
| `refontcrafts.delete.workbench` | `op` | Удаление рецептов верстака |
| `refontcrafts.delete.anvil` | `op` | Удаление рецептов наковальни |
| `refontcrafts.reload` | `op` | Перезагрузка конфига и рецептов |
| `refontcrafts.notify` | `op` | Служебные уведомления плагина |
| `refontcrafts.admin` | `op` | Полный доступ ко всему |

---

## Важные настройки

```yaml
settings:
  exact_meta_match: false
  workbench_strict_shape: true
  workbench_preview_limit: 126

  anvil:
    strict_order: true
```

### `exact_meta_match`

- `true` — строгое сравнение через `isSimilar`: имя, лор, чары, NBT и другие метаданные.
- `false` — сравнение по визуально важным данным без уникальных служебных NBT. Рекомендуется для серверов с кастомными предметами из магазинов, Brewery, ExecutableItems и похожих плагинов.

### `workbench_strict_shape`

Если включено, рецепт верстака сработает только при точной выкладке предметов по слотам 3x3.

### `workbench_preview_limit`

Лимит визуального количества результата в верстаке. На клиенте стабильно показывает до `126`; больше 126 результат может пропадать.

### `anvil.strict_order`

Если включено, левый и правый слоты наковальни считаются разными. Рецепт `A + B` не сработает как `B + A`.

---

## Пример конфига

```yaml
settings:
  exact_meta_match: false
  workbench_strict_shape: true
  workbench_allow_mirror: false
  workbench_preview_limit: 126
  take_back_on_close: true
  default_anvil_cost: 0
  anvil_mode: anvil

  anvil:
    strict_order: true
    creative_ignores_xp: true
    ops_ignore_xp: true
    stack_preview_result: false

database:
  type: sqlite

  sqlite:
    file: data.db

  mysql:
    host: 127.0.0.1
    port: 3306
    database: refontcrafts
    user: root
    password: ''
    use_ssl: false
    params: 'useUnicode=true&characterEncoding=utf8'

  bootstrap_from_config: true
  async_save: true
```

---

## Частые вопросы

### Почему результат показывает максимум 126?

Это рабочий предел для клиентского отображения overstack-результата. Значения выше могут ломать слот результата: предмет визуально пропадает или клиент начинает вести себя нестабильно.

### Почему одинаковые предметы иногда не совпадали?

Некоторые плагины добавляют уникальные служебные NBT к каждому предмету. При `exact_meta_match: false` RefontCrafts сравнивает визуально и логически важные данные, но игнорирует уникальные технические метки, которые мешают крафту.

### Можно ли разрешить игрокам только смотреть рецепты?

Да. Достаточно прав:

```text
refontcrafts.use
refontcrafts.view
```

Они включены для всех игроков по умолчанию.

---

## Сборка

```bash
mvn clean package
```

Готовый jar будет в `target/`.

---

## Поддержка

Автор: [@orythix](https://t.me/orythix)

Баги и предложения можно оставлять в Issues.