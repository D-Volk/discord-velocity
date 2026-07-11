# Discord Velocity

A [Velocity](https://papermc.io/software/velocity) plugin that bridges the proxy with a Discord server: player event relay, bidirectional chat, console log streaming, command execution from Discord, and playtime statistics.

## Features

- **Bidirectional chat channel**: player messages go to Discord; Discord messages reach all players on the proxy. Player display includes their LuckPerms prefix/suffix (if installed).
- **Player events**: join / leave, server switch, executed commands (optional).
- **Proxy state messages**: startup and shutdown.
- **Console channel**: streams all Velocity logs (batched ~1.5 s to avoid rate limits). Plain messages in the channel are executed as proxy console commands with ✅ / ❌ reactions.
- **ANSI escape stripping** from logs before sending.
- **Presence bot**: single status, list rotation with interval, or schedule-based (`HH:mm`). Activity types: `PLAYING / LISTENING / WATCHING / STREAMING / COMPETING / CUSTOM`. Supports `%online%` / `%max%`.
- **Online status** (`ONLINE / IDLE / DND / INVISIBLE`): automatic by player count or manual; each rotation entry can override it.
- **Slash command `/commands`** — lists all proxy commands (console channel only).
- **Slash command `/players`** — lists online players per server; supports server autocomplete.
- **Slash command `/time`** — playtime per server for a player with optional period filter (requires Statify).
- **`/discord-velocity` (`/dv`)** in Velocity: `info` and `reload` without restarting.
- **Versioned YAML configs**: on version mismatch the old file is backed up and a fresh one is created.

## Requirements

- Java **21+**
- Gradle **8.10**
- Velocity **3.5.0+**
- Discord application → Bot with **MESSAGE CONTENT INTENT** and **SERVER MEMBERS INTENT** enabled
- Enable **Developer Mode** in Discord (Settings → Advanced) to copy channel / guild IDs
- [LuckPerms](https://luckperms.net/) *(optional)* — prefix/suffix display in chat
- [Statify Paper 1.0.0](statify-paper-1.0.0.jar) + MySQL/MariaDB *(optional)* — `/time` command

## Installation

1. Download or build `discord-velocity-<version>.jar`.
2. Place it in Velocity's `plugins/` directory.
3. Start the proxy — `plugins/discord-velocity/config.yml` and `messages.yml` are created.
4. Fill in `config.yml`:
   - `discord.token` — bot token
   - `discord.guild-id` — guild ID (recommended; otherwise slash commands take up to 1 h to appear globally)
   - `discord.chat-channel-id`, `discord.console-channel-id` — channel IDs
   - `statify.*` — DB credentials and server list *(only needed for `/time`)*
5. Restart the proxy.

## Build from source

```bash
gradle wrapper --gradle-version 8.10
./gradlew clean shadowJar
```

Output: `build/libs/discord-velocity-<version>.jar` (fat-jar with JDA, SnakeYAML, MariaDB JDBC shaded).

## Commands

### Velocity

| Command                       | Description                                               |
| ----------------------------- | --------------------------------------------------------- |
| `/discord-velocity info`      | Plugin version, bot status, channels, current presence.   |
| `/discord-velocity reload`    | Reload `config.yml` and `messages.yml` without restart.   |
| `/dv ...`                     | Alias.                                                    |

- From the proxy console or the Discord console channel — no permission required.
- From an in-game server — requires **`discordvelocity.admin`** (LuckPerms: `lp user <name> permission set discordvelocity.admin true`).
- `reload` applies all `config.yml` changes except `discord.*` and `console.flush-interval-ms` (a `[warn]` is emitted for those).

### Discord slash commands

| Command      | Where          | Description                                                              |
| ------------ | -------------- | ------------------------------------------------------------------------ |
| `/commands`  | console channel | Lists all proxy commands (ephemeral reply).                             |
| `/players`   | any channel    | Online players per server; optional `server` parameter with autocomplete.|
| `/time`      | any channel    | Playtime per server for a player. Requires `statify.enabled: true`.      |

Any plain message in the console channel is executed as a proxy console command. The `/` prefix is optional.

#### `/players` parameters

- `server` *(optional)* — filter to a single server; autocompletes from Velocity's registered servers.

#### `/time` parameters

- `player` — player nickname (required, case-insensitive).
- `period` — `all` *(default)*, `day`, `week` (7 days), `month` (30 days), `year`, `custom`.
- `from`, `to` — range for `period=custom`, format `DD.MM.YYYY` or `DD.MM.YY`.

## Configuration

### `config.yml`

```yaml
config-version: "0.2.0"           # set at build time — do not edit

discord:
  token: ""
  guild-id: ""
  chat-channel-id: ""
  console-channel-id: ""

chat:
  mc-to-discord: true              # forward player chat to Discord
  discord-to-mc: true              # forward Discord messages to MC

events:
  command-log: true                # log player commands to chat channel

console:
  bidirectional: true              # execute messages from console channel as commands
  flush-interval-ms: 1500          # log batching interval (ms)
  exclude-loggers:                 # skip loggers by prefix
    - net.dv8tion
    - okhttp3
    - io.netty

statify:
  enabled: false                   # enable /time slash command
  type: mariadb                    # mysql | mariadb
  host: localhost
  port: 3306
  database: statify
  username: statify
  password: change_me
  use-ssl: false
  servers:                         # server-name values from Statify's config.yml (display order)
    - lobby
    - survival

presence:
  mode: single                     # single | rotate | schedule
  type: PLAYING
  format: "%online%/%max% online"
  rotation-interval-sec: 30
  status:
    mode: auto                     # auto | manual
    manual: ONLINE
    active: ONLINE                 # when players > 0
    empty: IDLE                    # when players == 0
  list:
    - type: PLAYING
      text: "%online%/%max% players"
    - type: LISTENING
      text: "maintenance"
      status: DND
    - at: "20:00"
      type: STREAMING
      text: "Evening stream"
      url: "https://twitch.tv/example"
```

### `messages.yml`

All texts sent by the plugin to Discord and MC. Placeholders: `%player%`, `%displayname%`, `%server%`, `%role%`, `%from%`, `%to%`, `%message%`, `%command%`, `%count%`.

- `%displayname%` — player nick with LuckPerms prefix/suffix (stripped of color codes for Discord), otherwise plain nick.
- `%role%` — highest Discord role, pre-formatted with MiniMessage color tag (e.g. `<color:#FF5555>Admin</color>`).

Missing keys fall back to the bundled default — no message can be lost.

## Config versioning

`config-version` and `messages-version` are injected at build time and must match the running plugin version.

On startup:
1. File missing → created from bundled default.
2. Legacy `config.properties` / `messages.properties` found → renamed to `*.old.legacy.yml`.
3. Version mismatch → old file renamed to `<name>.old.<version>.yml`, fresh default created. Collisions get `.1`, `.2` suffixes.

## Data layout

```
plugins/
├── discord-velocity-0.2.0.jar
└── discord-velocity/
    ├── config.yml
    ├── messages.yml
    ├── config.old.0.1.0.yml
    └── messages.old.0.1.0.yml
```

## Tech stack

- [JDA 5](https://github.com/discord-jda/JDA) — Discord client
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — YAML parser
- [MariaDB Java Client](https://mariadb.com/kb/en/mariadb-connector-j/) — JDBC driver for `/time`
- [LuckPerms API](https://luckperms.net/) *(compileOnly)* — prefix/suffix in MC→Discord chat
- Velocity API + Adventure (MiniMessage for Discord→MC rendering)
- Log4j2 — custom appender for console streaming

JDA, SnakeYAML, and the MariaDB driver are shaded into `ru.dvolk.discordvelocity.shaded.*`.

## Notes

Tested with [Velocity 3.5.0 #605](https://fill-data.papermc.io/v1/objects/0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0/velocity-3.5.0-SNAPSHOT-605.jar), [Paper 1.21.11 #132](https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar), [LuckPerms 5.5.57](https://download.luckperms.net/1645/bukkit/loader/LuckPerms-Bukkit-5.5.57.jar), and [statify-paper 1.0.0](statify-paper-1.0.0.jar).

## License

[MIT](LICENSE)

---

# Discord Velocity

Плагин для [Velocity](https://papermc.io/software/velocity), связывающий прокси с Discord-сервером: трансляция событий и чата, стрим консоли, выполнение команд через Discord и статистика наигранного времени.

## Возможности

- **Чат-канал** (двунаправленный): сообщения игроков уходят в Discord с отображением сервера и prefix/suffix из LuckPerms; Discord-сообщения в MC показывают цветную высшую роль участника.
- **События игроков**: вход / выход, переключение серверов, выполненные команды (опционально).
- **Сообщения о состоянии прокси**: запуск и выключение.
- **Console-канал**: стрим логов Velocity (батчинг ~1.5 с). Сообщения выполняются как команды с реакцией ✅ / ❌.
- **Очистка ANSI escape-кодов** из логов.
- **Presence-бот**: один статус, ротация, расписание. Типы: `PLAYING / LISTENING / WATCHING / STREAMING / COMPETING / CUSTOM`. Плейсхолдеры `%online%` / `%max%`.
- **Онлайн-статус** (`ONLINE / IDLE / DND / INVISIBLE`): авто или вручную.
- **`/commands`** — список команд прокси (console-канал).
- **`/players`** — онлайн-игроки по серверам, автодополнение по серверу.
- **`/time`** — наигранное время игрока по серверам (требует Statify).
- **`/discord-velocity` (`/dv`)** в Velocity: `info` и `reload`.
- **Версионирование конфигов**: при несовпадении версии старый файл бэкапится, создаётся свежий.

## Требования

- Java **21+**
- Gradle **8.10**
- Velocity **3.5.0+**
- Discord-приложение → Bot: включите **MESSAGE CONTENT INTENT** и **SERVER MEMBERS INTENT**
- **Режим разработчика** в Discord (Настройки → Расширенные) для копирования ID
- [LuckPerms](https://luckperms.net/) *(опционально)* — отображение prefix/suffix в чате
- [Statify Paper 1.0.0](statify-paper-1.0.0.jar) + MySQL/MariaDB *(опционально)* — команда `/time`

## Установка

1. Скачайте или соберите `discord-velocity-<version>.jar`.
2. Положите в `plugins/` Velocity.
3. Запустите прокси — создастся `plugins/discord-velocity/` с `config.yml` и `messages.yml`.
4. Заполните `config.yml`:
   - `discord.token` — токен бота
   - `discord.guild-id` — ID сервера (иначе slash-команды появятся до 1 ч)
   - `discord.chat-channel-id`, `discord.console-channel-id` — ID каналов
   - `statify.*` — данные БД и список серверов *(только для `/time`)*
5. Перезапустите прокси.

## Сборка из исходников

```bash
gradle wrapper --gradle-version 8.10
./gradlew clean shadowJar
```

Результат: `build/libs/discord-velocity-<version>.jar`.

## Команды

### Velocity

| Команда                       | Что делает                                             |
| ----------------------------- | ------------------------------------------------------ |
| `/discord-velocity info`      | Версия, статус бота, каналы, текущий presence.         |
| `/discord-velocity reload`    | Перечитать `config.yml` и `messages.yml`.              |
| `/dv ...`                     | Алиас.                                                 |

- Из консоли и console-канала — без permission.
- Из игры — **`discordvelocity.admin`** (LuckPerms: `lp user <name> permission set discordvelocity.admin true`).

### Discord (slash-команды)

| Команда      | Где работает    | Что делает                                                              |
| ------------ | --------------- | ----------------------------------------------------------------------- |
| `/commands`  | console-канал   | Список команд прокси (ephemeral).                                       |
| `/players`   | любой канал     | Онлайн-игроки по серверам; параметр `server` с автодополнением.         |
| `/time`      | любой канал     | Наигранное время игрока. Требует `statify.enabled: true`.               |

Любое сообщение в console-канале выполняется как команда. Префикс `/` опционален.

Параметры `/players`: `server` *(опционально)* — фильтр по серверу с автодополнением из Velocity.

Параметры `/time`: `player` (обязательно), `period` (`all`/`day`/`week`/`month`/`year`/`custom`), `from`/`to` (`DD.MM.YYYY` или `DD.MM.YY` для `custom`).

## Конфигурация

### `config.yml`

```yaml
config-version: "0.2.0"           # подставляется при сборке, не менять

discord:
  token: ""
  guild-id: ""
  chat-channel-id: ""
  console-channel-id: ""

chat:
  mc-to-discord: true              # чат игроков → Discord
  discord-to-mc: true              # Discord → MC

events:
  command-log: true                # логировать команды игроков

console:
  bidirectional: true              # выполнять команды из console-канала
  flush-interval-ms: 1500          # период склейки логов
  exclude-loggers:
    - net.dv8tion
    - okhttp3
    - io.netty

statify:
  enabled: false                   # true — включить /time
  type: mariadb                    # mysql | mariadb
  host: localhost
  port: 3306
  database: statify
  username: statify
  password: change_me
  use-ssl: false
  servers:                         # server-name из config.yml Statify
    - lobby
    - survival

presence:
  mode: single                     # single | rotate | schedule
  type: PLAYING
  format: "%online%/%max% online"
  rotation-interval-sec: 30
  status:
    mode: auto
    manual: ONLINE
    active: ONLINE
    empty: IDLE
  list:
    - type: PLAYING
      text: "%online%/%max% игроков"
    - type: LISTENING
      text: "тех. работы"
      status: DND
    - at: "20:00"
      type: STREAMING
      text: "Вечерний стрим"
      url: "https://twitch.tv/example"
```

### `messages.yml`

Тексты всех сообщений. Плейсхолдеры: `%player%`, `%displayname%`, `%server%`, `%role%`, `%from%`, `%to%`, `%message%`, `%command%`, `%count%`.

- `%displayname%` — ник с prefix/suffix из LuckPerms (без цветовых кодов для Discord), иначе просто ник.
- `%role%` — высшая роль Discord с MiniMessage-цветом (напр. `<color:#FF5555>Admin</color>`).

Если ключ отсутствует в файле — подставляется значение из bundled-дефолта.

## Версионирование конфигов

`config-version` и `messages-version` подставляются при сборке и должны совпадать с версией плагина.

При запуске:
1. Файл отсутствует → создаётся из дефолта.
2. Найден `*.properties` → переименовывается в `*.old.legacy.yml`.
3. Версия не совпадает → файл бэкапится как `<name>.old.<version>.yml`, создаётся свежий.

## Структура данных

```
plugins/
├── discord-velocity-0.2.0.jar
└── discord-velocity/
    ├── config.yml
    ├── messages.yml
    ├── config.old.0.1.0.yml
    └── messages.old.0.1.0.yml
```

## Технологии

- [JDA 5](https://github.com/discord-jda/JDA) — Discord-клиент
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — YAML-парсер
- [MariaDB Java Client](https://mariadb.com/kb/en/mariadb-connector-j/) — JDBC-драйвер для `/time`
- [LuckPerms API](https://luckperms.net/) *(compileOnly)* — prefix/suffix в чате
- Velocity API + Adventure (MiniMessage для рендера Discord → MC)
- Log4j2 — кастомный аппендер стрима консоли

JDA, SnakeYAML и MariaDB-драйвер шейдятся в `ru.dvolk.discordvelocity.shaded.*`.

## Комментарий

Протестировано на [Velocity 3.5.0 #605](https://fill-data.papermc.io/v1/objects/0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0/velocity-3.5.0-SNAPSHOT-605.jar), [Paper 1.21.11 #132](https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar), [LuckPerms 5.5.57](https://download.luckperms.net/1645/bukkit/loader/LuckPerms-Bukkit-5.5.57.jar) и [statify-paper 1.0.0](statify-paper-1.0.0.jar).

## Лицензия

[MIT](LICENSE)
