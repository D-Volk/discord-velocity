# Discord-Velocity

A plugin for [Velocity](https://papermc.io/software/velocity) that bridges the proxy with a Discord server: relay of player events and chat, console log streaming, and command execution from Discord.

## Features

- **Chat channel** (bidirectional): player chat is forwarded to Discord, and messages posted in Discord reach all players on the proxy.
- **Player events**: join / leave, switching between backend servers, executed commands (optional).
- **Proxy state messages**: startup and shutdown.
- **Console channel**: streams all Velocity logs (batched every ~1.5 s to avoid Discord rate limits). Messages sent to this channel are executed as console commands with a ✅ / ❌ reaction.
- **ANSI escape stripping** from logs before sending.
- **Presence bot**: a single status, list rotation with an interval, or schedule-based switching (`HH:mm`). Activity types: `PLAYING / LISTENING / WATCHING / STREAMING / COMPETING / CUSTOM`. Supports `%online%` / `%max%` in the template.
- **Online status** (`ONLINE / IDLE / DND / INVISIBLE`): automatic based on player count or manual; each rotation entry may define its own status.
- **Slash command `/commands`** in the console channel — lists all proxy commands.
- **`/discord-velocity` (`/dv`)** in Velocity: `info` and `reload` without restarting the proxy.
- **Versioned YAML configs**: when the file version does not match the plugin version, the old file is renamed to `*.old.<version>.yml` and a fresh one is created next to it.

## Requirements

- Java **21+**.
- Gradle **8.10**.
- Velocity **3.5.0+**.
- A Discord application → Bot. Enable in Bot settings:
  - **MESSAGE CONTENT INTENT**
  - **SERVER MEMBERS INTENT**
- Enable **Developer Mode** in Discord (Settings → Advanced) so you can copy channel and guild IDs.

## Installation

1. Download or build `discord-velocity-<version>.jar`.
2. Put it into the Velocity `plugins/` directory.
3. Start the proxy — a `plugins/discord-velocity/` directory with `config.yml` and `messages.yml` will be created.
4. Open `config.yml` and fill in:
   - `discord.token` — bot token;
   - `discord.guild-id` — guild ID (recommended; otherwise slash commands are registered globally and can take up to 1 h to appear);
   - `discord.chat-channel-id`, `discord.console-channel-id` — channel IDs;
5. Restart the proxy.

## Build from source

```bash
gradle wrapper --gradle-version 8.10
./gradlew clean shadowJar
```

Result: `build/libs/discord-velocity-<version>.jar` (fat-jar with JDA, SnakeYAML, MariaDB JDBC and others shaded).

## Commands

### Velocity

| Command                       | What it does                                                  |
| ----------------------------- | ------------------------------------------------------------- |
| `/discord-velocity info`      | Plugin version, bot status, channels, current presence.       |
| `/discord-velocity reload`    | Reload `config.yml` and `messages.yml`.                       |
| `/dv ...`                     | Alias.                                                        |

- From the proxy console and the Discord console channel — no permission required.
- From the game (backend server) — permission **`discordvelocity.admin`** (via LuckPerms: `lp user <name> permission set discordvelocity.admin true`).
- `reload` applies new values from `config.yml` (except `discord.*` and `console.flush-interval-ms`, which emit a `[warn]`) and `messages.yml`.

### Discord (slash commands)

| Command      | Where it works    | What it does                                                               |
| ------------ | ----------------- | -------------------------------------------------------------------------- |
| `/commands`  | console channel   | Lists all proxy commands (ephemeral reply).                                |

In the console channel, any plain message is executed as a proxy console command. The `/` prefix is optional.

## Configuration

### `config.yml` (main sections)

```yaml
config-version: "0.1.0"            # substituted at build time, do not edit

discord:
  token: ""
  guild-id: ""
  chat-channel-id: ""
  console-channel-id: ""

chat:
  mc-to-discord: true              # player chat → Discord
  discord-to-mc: true              # Discord → MC

events:
  command-log: true                # log player commands

console:
  bidirectional: true              # execute messages from the console channel
  flush-interval-ms: 1500          # log-batching interval
  exclude-loggers:                 # skip these loggers (by prefix)
    - net.dv8tion
    - okhttp3
    - io.netty

presence:
  mode: single                     # single | rotate | schedule
  type: PLAYING                    # PLAYING | LISTENING | WATCHING | STREAMING | COMPETING | CUSTOM
  format: "%online%/%max% online"
  rotation-interval-sec: 30

  status:
    mode: auto                     # auto | manual
    manual: ONLINE                 # ONLINE | IDLE | DND | INVISIBLE
    active: ONLINE                 # auto mode: when players > 0
    empty: IDLE                    # auto mode: when players == 0

  list:                            # for rotate / schedule
    - type: PLAYING
      text: "%online%/%max% players"
    - type: LISTENING
      text: "maintenance"
      status: DND                  # overrides presence.status for this entry
    - at: "20:00"                  # for schedule
      type: STREAMING
      text: "Evening stream"
      url: "https://twitch.tv/example"
```

### `messages.yml`

Text of every message the plugin sends to Discord and MC. Placeholders: `%player%`, `%server%`, `%from%`, `%to%`, `%message%`, `%command%`, `%count%`.

If a key is missing from the user file, the plugin falls back to the bundled default — no message can be lost.

## Config versioning

`config-version` and `messages-version` are substituted at build time (`project.version`) and must match the running plugin version.

On startup:
1. If the file is missing — it is created from the default.
2. If an old `config.properties` or `messages.properties` is found — it is renamed to `*.old.legacy.yml` (the initial concept used `.properties`, later replaced by `.yml`).
3. If the file version does not match the plugin version — the file is renamed to `<name>.old.<old-version>.yml` and a fresh one is created from defaults. Name collisions are resolved by appending `.1`, `.2`…

## Data layout

```
plugins/
├──discord-velocity-0.1.0.jar
├──discord-velocity/
├──── config.yml
├──── messages.yml
├──── config.old.0.0.7.yml         # backup of the previous version
├──── messages.old.0.0.7.yml       # backup of the previous version
└──── config.old.legacy.yml        # backup of legacy .properties (no longer used)
```

## Tech

- [JDA 5](https://github.com/discord-jda/JDA) — Discord client.
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — YAML parser.
- [MariaDB Java Client](https://mariadb.com/kb/en/mariadb-connector-j/) — JDBC driver for `/time`.
- Velocity API + Adventure (MiniMessage for Discord → MC rendering).
- Log4j2 — custom appender for console streaming.

JDA, SnakeYAML and the MariaDB driver are shaded into `ru.dvolk.discordvelocity.shaded.*` to avoid clashes with other plugins.

## Notes

All settings and code have been tested against [Velocity 3.5.0 #605](https://fill-data.papermc.io/v1/objects/0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0/velocity-3.5.0-SNAPSHOT-605.jar), [Paper 1.21.11 #132](https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar) and [LuckPerms v. 5.5.57](https://download.luckperms.net/1645/bukkit/loader/LuckPerms-Bukkit-5.5.57.jar).

## License

Released under the [MIT](LICENSE) license.

---

Плагин для [Velocity](https://papermc.io/software/velocity), связывающий прокси с Discord-сервером: трансляция событий и чата, стрим консоли, выполнение команд через Discord.

## Возможности

- **Чат-канал** (двунаправленный): сообщения чата игроков уходят в Discord, сообщения из Discord приходят всем игрокам прокси.
- **События игроков**: вход / выход, переключение между серверами, выполненные команды (опционально).
- **Сообщения о состоянии прокси**: запуск и выключение.
- **Console-канал**: стрим всех логов Velocity (батчинг ~1.5 с, чтобы не упереться в rate-limit Discord). Сообщения в этом канале выполняются как команды от консоли прокси с реакцией ✅ / ❌.
- **Очистка ANSI escape-кодов** из логов перед отправкой.
- **Presence-бот**: один статус, ротация по списку с интервалом, либо переключение по расписанию (`HH:mm`). Типы активности: `PLAYING / LISTENING / WATCHING / STREAMING / COMPETING / CUSTOM`. Поддержка `%online%` / `%max%` в шаблоне.
- **Онлайн-статус** (`ONLINE / IDLE / DND / INVISIBLE`): автоматически в зависимости от числа игроков либо вручную; каждый элемент ротации может задать собственный статус.
- **Slash-команда `/commands`** в console-канале — список всех команд прокси.
- **Команда `/discord-velocity` (`/dv`)** в Velocity: `info` и `reload` без рестарта прокси.
- **YAML-конфиги с версионированием**: при несовпадении версии файла с версией плагина старый файл автоматически переименовывается в `*.old.<version>.yml`, рядом создаётся свежий.

## Требования

- Java **21+**.
- Gradle **8.10**.
- Velocity **3.5.0+**.
- Discord-приложение → Bot. Включите в Bot settings:
  - **MESSAGE CONTENT INTENT**
  - **SERVER MEMBERS INTENT**
- В Discord включите **Режим разработчика** (Настройки → Расширенные), чтобы копировать ID каналов и сервера.

## Установка

1. Скачайте или соберите `discord-velocity-<version>.jar`.
2. Положите в каталог `plugins/` Velocity.
3. Запустите прокси — будет создан `plugins/discord-velocity/` с `config.yml` и `messages.yml`.
4. Откройте `config.yml`, заполните:
   - `discord.token` — токен бота;
   - `discord.guild-id` — ID сервера (рекомендуется, иначе slash-команды регистрируются глобально и появляются до 1 ч);
   - `discord.chat-channel-id`, `discord.console-channel-id` — ID каналов.
5. Перезапустите прокси.

## Сборка из исходников

```bash
gradle wrapper --gradle-version 8.10
./gradlew clean shadowJar
```

Результат: `build/libs/discord-velocity-<version>.jar` (fat-jar с шейдингом JDA, SnakeYAML и др.).

## Команды

### Velocity

| Команда                       | Что делает                                              |
| ----------------------------- | ------------------------------------------------------- |
| `/discord-velocity info`      | Версия плагина, статус бота, каналы, текущий presence.  |
| `/discord-velocity reload`    | Перечитать `config.yml` и `messages.yml`.               |
| `/dv ...`                     | Алиас.                                                  |

- Из консоли прокси и из console-канала Discord — без permission.
- Из игры (сервера) — permission **`discordvelocity.admin`** (через LuckPerms: `lp user <name> permission set discordvelocity.admin true`).

- Команда `reload` применит новые параметры `config.yml` (кроме `discord.*` и `console.flush-interval-ms`, выйдет предупреждение `[warn]`) и `messages.yml`:

### Discord (slash-commands)

| Команда      | Где работает      | Что делает                                       |
| ------------ | ----------------- | ------------------------------------------------ |
| `/commands`  | console-канал     | Список всех команд прокси (ephemeral-ответ).     |

В console-канале любое обычное сообщение выполняется как команда от консоли прокси. Префикс `/` опционален.

## Конфигурация

### `config.yml` (основные секции)

```yaml
config-version: "0.0.7"            # подставляется при сборке, не менять

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
  exclude-loggers:                 # не пересылать (по префиксу)
    - net.dv8tion
    - okhttp3
    - io.netty

presence:
  mode: single                     # single | rotate | schedule
  type: PLAYING                    # PLAYING | LISTENING | WATCHING | STREAMING | COMPETING | CUSTOM
  format: "%online%/%max% online"
  rotation-interval-sec: 30

  status:
    mode: auto                     # auto | manual
    manual: ONLINE                 # ONLINE | IDLE | DND | INVISIBLE
    active: ONLINE                 # для auto: если игроков > 0
    empty: IDLE                    # для auto: если игроков 0

  list:                            # для rotate / schedule
    - type: PLAYING
      text: "%online%/%max% игроков"
    - type: LISTENING
      text: "тех. работы"
      status: DND                  # перекрывает presence.status для этого item
    - at: "20:00"                  # для schedule
      type: STREAMING
      text: "Вечерний стрим"
      url: "https://twitch.tv/example"
```

### `messages.yml`

Тексты всех сообщений, которые плагин шлёт в Discord и MC. Плейсхолдеры: `%player%`, `%server%`, `%from%`, `%to%`, `%message%`, `%command%`, `%count%`.

```yaml
messages-version: "0.1.0"

proxy:
  start: "🟢 **Прокси запущен**"
  stop: "🔴 **Прокси выключается**"

player:
  join: "🟢 **%player%** зашёл на сервер `%server%`"
  leave: "🔴 **%player%** вышел"
  switch: "↔️ **%player%**: `%from%` → `%to%`"
  chat: "**%player%**: %message%"
  command: "📝 `%player%` выполнил /%command%"

discord:
  chat:
    mc: "[Discord] %player%: %message%"     # MiniMessage-формат

command:
  list:
    header: "**Доступно команд прокси: %count%**"
    empty: "Команд не зарегистрировано"
    wrong-channel: "Эта команда доступна только в console-канале"
```

Если в пользовательском файле какой-то ключ отсутствует, плагин подставит значение из bundled-дефолта — потерять сообщение нельзя.

## Версионирование конфигов

Поля `config-version` и `messages-version` подставляются при сборке (`project.version`) и должны совпадать с версией работающего плагина.

При запуске:
1. Если файл отсутствует — создаётся из дефолта.
2. Если найден старый `config.properties` или `messages.properties` — переименовывается в `*.old.legacy.yml`. (ранее концепт был сделан на `*.properties`, позже сделан на `*.yml`)
3. Если версия в файле ≠ версии плагина — файл переименовывается в `<имя>.old.<старая-версия>.yml`, рядом создаётся свежий из дефолтов. Существующие коллизии разрешаются добавлением `.1`, `.2`…

## Структура данных

```
plugins/
├──discord-velocity-0.1.0.jar
├──discord-velocity/
├──── config.yml
├──── messages.yml
├──── config.old.0.0.7.yml         # бэкап со старой версии
├──── messages.old.0.0.7.yml       # бэкап со старой версии
└──── config.old.legacy.yml        # бэкап legacy .properties (не используется больше)
```

## Технологии

- [JDA 5](https://github.com/discord-jda/JDA) — Discord-клиент.
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) — YAML-парсер.
- Velocity API + Adventure (MiniMessage для рендера Discord → MC).
- Log4j2 — кастомный аппендер для стрима консоли.

JDA и SnakeYAML шейдятся в `ru.dvolk.discordvelocity.shaded.*`, чтобы не конфликтовать с другими плагинами.

## Комментарий

Все настройки и в целом код проверен на [Velocity 3.5.0 #605](https://fill-data.papermc.io/v1/objects/0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0/velocity-3.5.0-SNAPSHOT-605.jar), [Paper 1.21.11 #132](https://fill-data.papermc.io/v1/objects/5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba/paper-1.21.11-132.jar) и [LuckPerms v. 5.5.57](https://download.luckperms.net/1645/bukkit/loader/LuckPerms-Bukkit-5.5.57.jar)

## Лицензия

Проект распространяется под лицензией [MIT](LICENSE).