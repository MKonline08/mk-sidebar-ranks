# MK Sidebar & Ranks

**Your server. Your ranks. A sidebar worth showing off.**

A standalone plugin for **Paper 1.21.11 · Java 21**, created by **MK**. Gold and aqua sidebar styling, colored rank badges in Tab, numeric ping, and automatic playtime promotions. No companion plugins or client mods required.

![MK Sidebar & Ranks design preview](docs/preview.svg)

[Download the plugin](https://github.com/MKonline08/mk-sidebar-ranks/releases/latest) · [Placeholders](docs/PLACEHOLDERS.md) · [Configuration](src/main/resources/config.yml) · [Validation](docs/VALIDATION.md)

## Install

1. Download **`mk-sidebar-ranks-1.0.0.jar`** from the release page.
2. Stop your Paper 1.21.11 server and copy the jar into its **`plugins/`** folder.
3. Start the server. Settings appear in **`plugins/MKSidebarRanks/config.yml`**.
4. Run **`/mkrank set YourMinecraftName owner`** after you have joined. Your OWNER label will be bold red in your sidebar and Tab.

The label does not make someone an operator or grant permissions. Keep using your existing server permissions for administrative access.

## What players see

- A right-side sidebar showing **server name, player name, rank, health, coordinates, online count, TPS, and uptime**.
- Tab entries such as **`[OWNER] MK • 42ms`**, with rank colors and green/yellow/red numeric ping.
- **New Player** on first join, then **OG Player** after **24 total connected hours**, including time spent AFK.
- A server-wide chat announcement when someone earns OG. Existing recorded Minecraft playtime counts once on first join after installation.

Both displays update once per second; rank assignments update immediately. Manually assigned OWNER and custom ranks stay protected from automatic promotion. Names, playtime, earned ranks, manual ranks, and sidebar preferences survive restarts.

## Commands

| Command | Purpose |
| --- | --- |
| `/mksb toggle` | Hide or restore your own sidebar; Tab stays active |
| `/mksb reload` | Validate and apply configuration changes |
| `/mkrank list` | List rank IDs and colored labels |
| `/mkrank create <id> <color> <display name...>` | Create a custom rank |
| `/mkrank color <id> <color>` | Change a rank’s color |
| `/mkrank set <player\|UUID> <rank>` | Assign a protected manual rank |
| `/mkrank reset <player\|UUID>` | Restore the automatic rank, retaining playtime |
| `/mkrank info <player\|UUID>` | View rank, identity, and accumulated playtime |

Examples:

```text
/mkrank set YourMinecraftName owner
/mkrank create builder #55ffff Master Builder
/mkrank set Alex builder
/mkrank color builder light_purple
/mkrank reset Alex
```

Rank IDs use lowercase letters, numbers, and underscores and start with a letter. Colors accept Minecraft color names or six-digit hex values. Rank labels can contain spaces. Use a UUID to assign a player who has never joined; their recorded playtime will still import on first join. Offline names must already be known to this plugin.

**Permissions:** `mksidebar.admin` controls rank commands and reloads (operators by default); `mksidebar.toggle` allows personal sidebar toggling (everyone by default). Console supports all administrative commands.

## Customize the look

Edit `config.yml`, then run `/mksb reload`. Use [MiniMessage](https://docs.papermc.io/adventure/minimessage/format/) colors and formatting around `%placeholders%`:

```yaml
server-name: 'My Server'
sidebar:
  enabled: true
  title: '<gold><bold>%server_name%</bold></gold>'
  lines:
    - '<aqua>Rank</aqua> <dark_gray>»</dark_gray> %player_rank%'
    - '<aqua>XYZ</aqua> <white>%player_x%, %player_y%, %player_z%</white>'
    - '<aqua>Biome</aqua> <white>%player_biome%</white>'
tab:
  enabled: true
  format: '%player_rank_badge% <white>%player_name%</white> <dark_gray>•</dark_gray> %player_ping_color%<gray>ms</gray>'
```

This is a configuration excerpt; retain the other settings in the generated file. Close formatting tags. Unknown placeholders, malformed YAML, missing required ranks, invalid colors, and more than 15 sidebar lines reject the reload without replacing the working settings. Blank and duplicate lines work.

`promotion.hours` defaults to `24`. Already-earned OG ranks remain earned if you raise the threshold. `promotion.import-existing-playtime` controls first-time imports only; changing it does not reset saved players. Set rank `bold: true` for bold labels. Required rank IDs are `new_player`, `og_player`, and `owner`. Custom ranks are manual labels; this version has one automatic promotion step.

The placeholders are built into this plugin’s templates; they are not registered as a PlaceholderAPI expansion. Chat-name prefixes and overhead labels are outside this version.

## Storage and other plugins

Player records live in `plugins/MKSidebarRanks/players.db`. SQLite is bundled. Saves run on a separate worker every minute, on disconnect, during rank changes, and at shutdown. Back up the whole plugin folder while the server is stopped. A hard crash can lose connected time since the last completed save, normally up to one minute. Promotion markers are saved before announcements.

This plugin needs control of the sidebar and Tab display names. Disable the corresponding feature in another scoreboard/Tab plugin, or turn off `sidebar.enabled` / `tab.enabled` here. If another plugin replaces a display, MK logs a warning and yields rather than repeatedly overwriting it. Reconnect to resume after resolving the conflict; toggling the sidebar also retries sidebar ownership. Releasing displays restores prior values only while MK still owns them.

## Build and test

With JDK 21 and Maven 3.9+:

```text
mvn -B clean verify
```

Installable output: `target/mk-sidebar-ranks-1.0.0.jar`. Do not install the `original-` jar. GitHub Actions builds and tests the project and provides the packaged jar as an artifact.

For the local Paper integration fixture, see [validation instructions](docs/VALIDATION.md).

**Created by MK**
