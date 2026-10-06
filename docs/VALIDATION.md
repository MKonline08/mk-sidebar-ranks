# Validation

## Release verification — v1.5.0

Verified October 6, 2026 with the exact release jar:

- **64 automated tests passed**, zero failures/errors/skips.
- **67 real Paper scenarios passed**: 44 existing rank/display checks and 23 market checks, using three protocol players and full restarts.
- **64-client load fixture passed**, with wallet/sidebar updates, eight payments and four market opens per second during the active phase, and a placed spawn leaderboard.
- Paper 1.21.11 build 132, Java 21, bundled SQLite 3.53.4.0.
- SHA-256: `b9d5a7ba765347cfc23f3039690915340846cd2ab1427f575d42428ea27ae717`.
- [Rank/display results](validation-report.json), [market results](market-validation-report.json), [performance results](performance-report-v1.5.0.json).

Market checks cover one-time $500 grants, balances, payments and admin permissions, purchase confirmation and cancellation, exact item escrow/delivery, full-inventory rejection, cancelled listing returns, offline seller payouts, history, filters, all coin flip closing/refund paths, repeated acceptance, rotating reminders, spawn-board placement/removal, mailbox/wallet persistence, and reconnect recovery of real serialized inventory snapshots. The recovery fixture injects pending journal records into the **stopped temporary database** for both before/after inventory-removal stages; it does not claim to simulate every filesystem/power-loss failure.

Unit checks also cover concurrent purchases/spending, fee calculations, failed-save rollback, insufficient funds, listing limits, persisted review records, money bounds, and configuration migration. Critical inventory handoffs are queued to at most one per tick and are measured by the market diagnostics. [MARKET.md](MARKET.md) describes the recovery path for inventory changes caused by external plugins or administrators.

Existing rank/name-tag/Tab/promotion functionality passes the complete regression fixture. Historical v1.2.0 and v1.3.0 load data remain associated with their original jars in [PERFORMANCE.md](PERFORMANCE.md); they are not measurements of v1.5.0.

This verifies isolated local fixtures, not the owner's production host or internet latency. Installation is a server restart with the old jar replaced and the MKSidebarRanks folder retained.

## Automated tests

Run `mvn -B clean verify` with Java 21. The test suite covers:

- First-time imports, start-fresh configuration, wall-clock accumulation, reconnects, and username changes.
- Exact promotion boundary, one-time earned markers, and OWNER/custom-rank protection.
- Offline UUID assignment followed by first-join import; unknown and ambiguous names.
- SQLite restart persistence, ordered asynchronous writes, and immutable save snapshots.
- Configuration limits, required ranks, invalid colors, unknown placeholders, and malformed formatting.
- Safe placeholder text, rank styles, ping boundaries, compass directions, and uptime formatting.
- Sidebar objective reuse, unique blank/duplicate entries, restoration, and yielding to competing plugins.
- Command permissions and personal sidebar toggling.
- Config upgrade idempotence, preservation of custom layouts/ranks, footer ownership/restoration, and sound validation.
- Selective placeholder reads, shared server snapshots, unchanged display caching, Tab ping throttling with immediate rank changes, dirty-save acknowledgements, bounded queues, and performance configuration validation.

## Real Paper integration fixture

The fixture boots a separate **local-only** Paper server, connects three protocol clients, and restarts the server to verify persistence. It never connects to your production server. Running it accepts the [Minecraft EULA](https://www.minecraft.net/en-us/eula) for that local test instance.

1. Build the plugin using `mvn -B clean verify`.
2. Download a Paper **1.21.11** server jar from PaperMC.
3. In `scripts/`, run `npm ci` (Node.js 22+).
4. Set `PAPER_JAR` to the absolute path of the server jar, then run `npm test`.

PowerShell example:

```powershell
$env:PAPER_JAR = 'C:\path\to\paper-1.21.11.jar'
npm test
```

Linux example:

```sh
PAPER_JAR=/absolute/path/paper-1.21.11.jar npm test
```

The fixture binds only `127.0.0.1:25586`, uses temporary worlds, and briefly lowers the promotion threshold in its test configuration. The shipped configuration remains 24 hours. Override `MK_SMOKE_PORT` if that port is occupied; optionally set `MK_SMOKE_PARENT` to an existing temporary directory and `MK_SMOKE_REPORT` to the output JSON path. Test worlds remain available for inspection; the printed fixture path identifies them. The server is stopped on success or failure.

Checks include transmitted sidebar and Tab components, red OWNER labels, numeric ping, recorded-playtime imports, broadcast promotions, stable objective updates, custom-rank commands, permissions, sidebar toggles, negative coordinates, invalid reloads, every placeholder, offline assignments, and restart persistence.

Protocol checks verify actual Paper output. The README design preview is an illustration of the intended styling, not a Minecraft client screenshot.

## Market fixture

Use Node.js 24 and run `npm run market` in scripts after `npm ci`, with PAPER_JAR set to Paper 1.21.11. It binds to 127.0.0.1:25588 and uses Node's built-in SQLite reader to inspect only its own temporary fixture. Optional variables: MK_MARKET_PORT, MK_MARKET_REPORT, MK_SMOKE_PARENT.
