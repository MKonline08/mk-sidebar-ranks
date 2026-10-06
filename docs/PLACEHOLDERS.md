# Placeholder reference

Use these inside sidebar title/lines, Tab format/footer, and promotion or manual rank-change announcements. Values describe the listed player in Tab and the viewing player in the sidebar. Player-supplied text is inserted as a component and cannot inject formatting tags.

| Placeholder | Value |
| --- | --- |
| `%player_name%` | Minecraft username |
| `%player_displayname%` | Display-name component, preserving its formatting |
| `%player_health%` | Current health points, one decimal, e.g. `18.0` |
| `%player_max_health%` | Maximum health points, e.g. `20.0` |
| `%player_food%` | Food level, normally 0–20 |
| `%player_level%` | XP level |
| `%player_exp%` | XP progress toward next level, rounded percentage 0–100; add `%` yourself if desired |
| `%player_ping%` | Estimated connection ping in milliseconds, without `ms` |
| `%player_ping_color%` | Ping number colored green <100, yellow 100–199, red ≥200; without `ms` |
| `%player_world%` | Current world name |
| `%player_gamemode%` | Game mode, e.g. `SURVIVAL` |
| `%player_x%` | Block X coordinate, rounded down including negatives |
| `%player_y%` | Block Y coordinate |
| `%player_z%` | Block Z coordinate |
| `%player_deaths%` | Recorded deaths |
| `%player_kills%` | Recorded player kills |
| `%player_blocks_walked%` | Vanilla walking distance in meters, one decimal; excludes separately tracked sprinting, swimming, and flying |
| `%player_playtime_hours%` | Imported playtime plus tracked connected time, two decimals |
| `%player_armor%` | Current armor attribute points, normally 0–20, one decimal |
| `%player_direction%` | Eight-point compass direction: N, NE, E, SE, S, SW, W, NW |
| `%player_item_in_hand%` | Main-hand material name, e.g. `DIAMOND_SWORD` or `AIR` |
| `%player_biome%` | Biome key, e.g. `plains` |
| `%player_rank%` | Colored rank label, e.g. `OWNER` |
| `%player_rank_badge%` | Colored rank with brackets, e.g. `[OWNER]` |
| `%server_name%` | Configured server name |
| `%server_online%` | Current online player count |
| `%server_max_players%` | Configured maximum player count |
| `%server_tps%` | Paper one-minute TPS average, one decimal, clamped to 0–20 |
| `%server_uptime%` | Server Java-process uptime, e.g. `2h 35m 12s`; does not reset on plugin reload |

These are internal placeholders, not an external PlaceholderAPI expansion. All are available during online promotion announcements too. If an administrator resets an eligible offline player, offline announcements have the saved name, rank, server name, and playtime; unavailable live values show `—`.
