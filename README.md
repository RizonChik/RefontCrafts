# RefontCrafts

<div align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.8.8--26.2-green?style=for-the-badge" alt="Minecraft Version">
  <img src="https://img.shields.io/badge/Java-8+-orange?style=for-the-badge" alt="Java Version">
  <img src="https://img.shields.io/github/v/release/rizonchik/RefontCrafts?style=for-the-badge" alt="Release">
  <img src="https://img.shields.io/github/downloads/rizonchik/RefontCrafts/total?style=for-the-badge" alt="Downloads">
</div>

Редактор кастомных крафтов для Bukkit, Spigot и Paper 1.8.8–26.2 в одном JAR.

По умолчанию интерфейс на английском, но есть `ru`, `vi` и `zh_cn`. Старые серверы получают совместимые материалы, GUI и legacy-цвета автоматически.

- Красивые GUI для верстака (строгие/зеркальные формы) и наковальни (с ценой XP)
- Поддержка кастом‑предметов и зелий (BasePotionData)
- Анти‑дюп в редакторах: предпросмотрные вещи можно переставлять внутри GUI, но нельзя унести; возвращаются только реальные предметы игрока
- `/crafts` показывает все кастомные рецепты и отдельно считает, сколько раз каждый из них доступен сейчас
- Авто‑обновление config.yml: новые ключи добавляются сами, твои значения не затираются
- Логи плагина в чат игрокам с правом `refontcrafts.admin`
- Хранилище рецептов: SQLite/MySQL, миграции и снепшоты, async‑сохранение
- Тонкий релизный JAR: JDBC-библиотеки безопасно докачиваются при первом запуске и больше не раздувают файл плагина

В каждом слоте рецепта можно указать нужное количество предметов: 1, 2, 3 или целый стак. При крафте плагин проверяет и списывает именно сохранённое количество.

---

## Требования

- Bukkit/Spigot/Paper: 1.8.8–26.2
- Плагин собран под Java 8; Java для запуска выбирается по требованиям ядра сервера
- Доступ к HTTPS на первом запуске для загрузки JDBC-библиотек с Maven Central
- Опционально: AdvancedEnchantments (влияет на режим наковальни)

На новых Paper будет предупреждение о legacy-плагине без `api-version`. Это ожидаемо: современный `api-version` сделал бы тот же JAR несовместимым с 1.8.8.

---

## Установка

1. Помести `RefontCrafts-*.jar` в `plugins/`
2. Перезапусти сервер
3. (Опционально) Установи AdvancedEnchantments, если нужен “клик‑режим” анвиля

Плагин сам создаст/обновит `config.yml`, базу данных и каталог `plugins/RefontCrafts/libraries`. При SQLite скачиваются только `sqlite-jdbc` и `slf4j-api`; MySQL Connector загружается только при выборе MySQL или миграции. После первой успешной загрузки сервер может запускаться без интернета.

---

## Быстрый старт

- `/rcrafts` — главное меню
- `/rcrafts recipe` — редактор верстака (строгая выкладка 3×3)
- `/rcrafts anvil` — редактор наковальни (можно задать стоимость XP)
- `/rcrafts view workbench|anvil [page]` — браузер рецептов
- `/crafts` — просмотр всех кастомных крафтов с количеством, доступным игроку сейчас
- `/rcrafts reload` — перезагрузка конфига и рецептов

В редакторах:
- “Сохранить” — записывает рецепт в БД и регистрирует
- “Очистить” — возвращает в инвентарь только реальные вещи (не предпросмотр)
- “Редактировать” подставляет призрачные копии сохранённого рецепта. Их можно переставлять и повторно сохранять, но нельзя вынести в инвентарь или выбросить. При закрытии они исчезают; вложенные игроком реальные предметы возвращаются.

---

## Команды

- `/rcrafts` — главное меню
- `/rcrafts view workbench [page]` — просмотр рецептов верстака
- `/rcrafts view anvil [page]` — просмотр рецептов наковальни
- `/crafts` — просмотр всех крафтов и текущей доступности
- `/rcrafts recipe` — редактор рецептов верстака
- `/rcrafts anvil` — редактор рецептов наковальни
- `/rcrafts reload` — перезагрузка конфига и рецептов

Алиасы: `/rc`, `/refontcrafts`

---

## Права

- `refontcrafts.use` — доступ к плагину (default: true)
- `refontcrafts.crafts` — просмотр всех крафтов и текущей доступности (`/crafts`, default: true)
- `refontcrafts.recipe` — создание, редактирование и удаление рецептов верстака (default: op)
- `refontcrafts.anvil` — создание, редактирование и удаление рецептов наковальни (default: op)
- `refontcrafts.browse` — открытие браузера всех рецептов; само по себе не даёт права менять или удалять их (default: op)
- `refontcrafts.reload` — перезагрузка (default: op)
- `refontcrafts.admin` — админ + логи плагина в чат (default: op)

---

## Особенности и поведение

- Верстак:
  - Строгие формы 3×3 по сохранённой выкладке (настраивается)
  - Зеркалирование формы по горизонтали (опционально)
- Сопоставление предметов:
  - `exact_meta_match: true` — сравнение `isSimilar` (имя, лор, чары, NBT)
  - `false` — сверяются Material, data/durability, имя, lore, модель, чары, unbreakable и данные зелья; уникальные служебные NBT игнорируются
- Наковальня:
  - SHIFT‑клик делает мультикрафт с учётом XP и свободного места
  - “Клик‑режим” (с AE) — рецепты через правый клик в инвентаре
- Анти‑дюп:
  - Предпросмотрные вещи из “Редактировать” нельзя забрать и они не возвращаются
  - Возвращаются только реальные вещи игрока
- База:
  - SQLite по умолчанию (`plugins/RefontCrafts/data.db`)
  - MySQL поддерживается; при недоступности включается SQLite failover (`failover.db`)
  - JDBC-файлы скачиваются только по HTTPS и принимаются только при совпадении встроенного SHA-256
  - Повреждённая или подменённая библиотека автоматически удаляется и скачивается заново
  - Снапшоты рецептов на диск
  - Сохранение выполняется собственным последовательным I/O-потоком с корректным завершением при остановке сервера

---

## AdvancedEnchantments

Режим наковальни задаётся `settings.anvil_mode`:
- `auto` (по умолчанию): если установлен AE — включается “клик‑режим”, иначе обычная наковальня
- `anvil`: всегда через наковальню
- `click`: всегда “клик‑режим”

Если хочешь складывать зелья именно в наковальне — выстави:
```yaml
settings:
  anvil_mode: anvil
```

---

## Конфигурация

Плагин сам добавляет недостающие ключи из шаблона в `config.yml` (твои значения не трогает), создаёт `config.yml.bak`.

В шаблоне сейчас есть:
- `settings.language` для языка интерфейса (`en`, `ru`, `vi`, `zh_cn`)
- все названия, подсказки и сообщения вынесены в `messages/en.yml`, `messages/ru.yml`, `messages/vi.yml`, `messages/zh_cn.yml`
- `settings.exact_meta_match: false` по умолчанию
- `settings.workbench_preview_limit: 126` для предпросмотра результата и батч‑крафта

Пример полного конфига:
```yaml
# ========== RefontCrafts ==========
# Автор: https://t.me/orythix

settings:
  prefix: "§x§2§5§A§F§F§1R§x§2§2§A§8§F§2e§x§1§E§A§1§F§4f§x§1§B§9§B§F§5o§x§1§8§9§4§F§6n§x§1§4§8§D§F§7t§x§1§1§8§6§F§9C§x§0§D§7§F§F§Ar§x§0§A§7§8§F§Ba§x§0§7§7§2§F§Cf§x§0§3§6§B§F§Et§x§0§0§6§4§F§Fs &8»&7 "
  language: en

  titles:
    recipe: "§x§2§5§A§F§F§1С§x§2§3§A§A§F§2о§x§2§0§A§5§F§3з§x§1§E§A§0§F§4д§x§1§B§9§B§F§5а§x§1§9§9§6§F§6н§x§1§6§9§1§F§7и§x§1§4§8§C§F§8е §x§0§F§8§2§F§9р§x§0§C§7§D§F§Aе§x§0§A§7§8§F§Bц§x§0§7§7§3§F§Cе§x§0§5§6§E§F§Dп§x§0§2§6§9§F§Eт§x§0§0§6§4§F§Fа"
    anvil:  "§x§2§5§A§F§F§1Р§x§2§3§A§B§F§2е§x§2§1§A§7§F§3ц§x§1§F§A§3§F§3е§x§1§D§9§E§F§4п§x§1§B§9§A§F§5т §x§1§7§9§2§F§6в §x§1§3§8§A§F§8н§x§1§0§8§5§F§9а§x§0§E§8§1§F§Aк§x§0§C§7§D§F§Aо§x§0§A§7§9§F§Bв§x§0§8§7§5§F§Cа§x§0§6§7§1§F§Dл§x§0§4§6§C§F§Dь§x§0§2§6§8§F§Eн§x§0§0§6§4§F§Fю"
    browser_workbench: "§bРецепты Верстака"
    browser_anvil: "§dРецепты Наковальни"

  exact_meta_match: false
  workbench_strict_shape: true
  workbench_allow_mirror: false
  take_back_on_close: true
  default_anvil_cost: 0
  workbench_preview_limit: 126
  anvil_mode: auto

  database:
    type: sqlite

    sqlite:
      file: data.db

    mysql:
      host: 127.0.0.1
      port: 3306
      database: refontcrafts
      user: root
      password: ""
      use_ssl: false
      params: "useUnicode=true&characterEncoding=utf8"

    async_save: true

# Сообщения находятся в папке messages/ и выбираются через settings.language.

# Примеры автозагрузки (если БД пустая)
recipes:
  shapeless:
    demo_trident:
      ingredients:
        - "BONE:1"
        - "IRON_INGOT:1"
        - "STICK:1"
      result: "TRIDENT:1"
  anvil:
    demo:
      left:   "EMERALD:1"
      right:  "EMERALD:1"
      result: "SEA_LANTERN:1"
      cost: 1
```

---

## Частые вопросы

- “Зелья не складываются в наковальне с AE”
  - Поставь `settings.anvil_mode: anvil`
- “Не могу забрать предмет из ‘Редактировать’”
  - Это призрачная копия сохранённого рецепта. Её можно двигать только внутри редактора и использовать при повторном сохранении; вынести её нельзя. Свои вложенные предметы вернутся при закрытии.
- “SHIFT‑крафт не весь выдал”
  - Выдача частичная с учётом XP и свободного места. Остатки дропаются у игрока, показывается `no_inventory_space`
- “Сервер не имеет доступа в интернет”
  - Один раз положи точные версии JDBC-файлов вручную в `plugins/RefontCrafts/libraries`. Плагин всё равно проверит их SHA-256 и отклонит неверные файлы.

---

## Сборка

- Maven: `mvn clean package`
- Исходники компилируются против Spigot API 1.8.8 с target Java 8
- JDBC-зависимости имеют scope `provided` и не входят в релизный JAR
- Собственный `LibraryManager` нужен потому, что Bukkit 1.8.8 не поддерживает секцию `libraries` в `plugin.yml`
- Версии, Maven Central URL и SHA-256 зафиксированы в коде; произвольные URL из конфига не принимаются



## Поддержка

Автор: https://t.me/orythix  
Баг‑репорты и предложения — Issues/PR в репозитории.
