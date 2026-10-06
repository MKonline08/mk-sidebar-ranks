# Validation

## Release verification — v1.6.0

Verified October 6, 2026 with the exact release jar:

- **74 automated tests passed**, zero failures/errors/skips.
- **76 real Paper scenarios passed**: 44 rank/display regression checks and 32 market/coin flip checks, using three protocol players and full restarts.
- **64-client load fixture passed** across five scenarios, including 4 batches of **16 simultaneous animated coin flips**, active payments/market menus, and a placed leaderboard.
- Paper 1.21.11 build 132, Java 21, bundled SQLite 3.53.4.0.
- SHA-256: `d45e1dbd8d4c45af9077ac86bbadd38409ecdee1a0576bb79eebe687072ce21e`.
- [Rank/display results](validation-report.json), [market results](market-validation-report.json), [performance results](performance-report-v1.6.0.json).

Coin flip checks cover public player heads, confirmation/back, menu item protection, single-match/self-join limits, command/button refunds, held stakes before payout, stale confirmations, red/green frames and final result colors, closing/reopening without rerolling, expiry, waiting disconnects, started-match offline payout, muted/enabled sounds, shutdown during animation, and Paper configuration migration from v1.5 with a backup.

Unit tests cover concurrent joins, insufficient funds, save-failure rollback/retry, one-time settlement, payout-cap protection, v1.5 database migration, and startup recovery from a persisted RUNNING state. The startup test restores that committed state in a stopped database; it does not simulate every filesystem or power-loss failure.

Market regression checks cover $500 grants, transfers/admin permissions, fixed-price purchases, exact item escrow/mail delivery, full inventories, cancellations, offline sellers, receipts/filters, reminders, board placement/removal, and wallet/mail persistence. Inventory recovery injects pending journals into the **stopped temporary database** for before/after inventory-removal stages. Item handoffs remain queued to one per tick; [MARKET.md](MARKET.md) explains ambiguous inventory review.

Ranks, name tags, Tab, sidebar and promotions pass the complete regression fixture. Historical v1.2.0, v1.3.0 and v1.5.0 load reports remain measurements of their original releases in [PERFORMANCE.md](PERFORMANCE.md).

These are isolated local fixtures, not a measurement of the production host or internet latency. Install by replacing the jar during a server restart and retaining the MKSidebarRanks folder.

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
