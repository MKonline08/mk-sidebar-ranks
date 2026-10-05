# Validation

## Release verification — v1.2.0

Verified on October 5, 2026:

- **33 automated tests passed**, with no failures, errors, or skipped tests.
- **16 real Paper integration scenarios passed** on Paper **1.21.11 build 132**, including three protocol clients and a full server restart.
- Windows Java **21.0.12** runtime; SQLite **3.53.4.0** bundled with Windows and Linux native libraries.
- Captured display components and scenario results: [validation-report.json](validation-report.json).
- Release jar SHA-256: `91ee27266369bf624cd40149038a4ade47290234bb9d9011effb17ef67af6139`.
- Verified compact inline label/value rows, one XYZ line, exact MK/108e footer text, one celebration sound to every online player per promotion, no replay after restart, and automatic v1.1.0 configuration upgrade with a backup. Unit tests also cover direct upgrades from v1.0.0 and preservation of custom layouts/settings.
- **64-client load fixture passed** with displays disabled, enabled/static, and enabled/moving. See [PERFORMANCE.md](PERFORMANCE.md) for measurements and limits. The same packaged jar was used for both fixtures.

This verifies the local test server. Installation on the owner's production server is a separate step.

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
