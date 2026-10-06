# Validation

## Release verification — v1.4.0

Verified on October 6, 2026:

- **47 automated tests passed**, with no failures, errors, or skipped tests.
- **44 real Paper integration scenarios passed** on Paper **1.21.11 build 132**, including three protocol clients and a full server restart.
- Windows Java **21.0.12** runtime; SQLite **3.53.4.0** bundled with Windows and Linux native libraries.
- Captured display components and scenario results: [validation-report.json](validation-report.json).
- Release jar SHA-256: `642c52aa78a9d5125bfde92cb173711a7c80466ebe25cd19d77b0bd0a6822497`.
- Verified compact inline label/value rows, one XYZ line, exact MK/108e footer text, one celebration sound to every online player per promotion or effective manual rank change, no replay after restart, and automatic v1.3 configuration upgrade with a backup. Unit tests also cover direct upgrades from v1.0.0 and preservation of custom layouts/settings.
- Manual sound checks cover OWNER/custom assignments, same-rank silence, resets, offline assignments, mute settings, and avoiding a duplicate sound when reset earns OG. No sounds replay after restart.
- Overhead team packets verify colored labels, automatic/manual/custom changes, sidebar-hidden observers, quit cleanup, feature switches, and restart restoration. The Hours row includes imported playtime. Unit tests also cover shared boards, cache reuse, external team conflicts, and config migration. The fixture verifies transmitted labels rather than a rendered game screenshot.
- The real Paper fixture inserts a historical duplicate UUID for an online username, verifies that name commands select the connected UUID, and confirms the old saved record remains available by UUID.
- The **v1.3.0 64-client load fixture** remains recorded in [PERFORMANCE.md](PERFORMANCE.md). Those measurements used the previous v1.3.0 jar; v1.4.0 was verified with the automated and real Paper functional checks above.

New command/event checks cover colored manual announcements, same-rank silence, offline changes, reset earning OG without duplication, independent announcement/sound switches, ordinary-player progress, earned/manual rank status after restart, and sound testing to all three clients with configured volume/pitch. Unit tests also verify that notifications wait for a successful save and main-thread delivery and that failed saves suppress notifications.

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
