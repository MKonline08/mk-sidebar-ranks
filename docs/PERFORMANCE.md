# Performance checks

The local fixture connects 64 Minecraft protocol clients to Paper 1.21.11, waits for warm-up, and samples three 30-second scenarios: displays disabled, displays enabled with stationary players, and displays enabled with eight moving players. Every client must remain connected, the update backlog must stay bounded, and unchanged Tab footers and overhead team data must not be resent. Scheduled plugin work must average below 2 ms/tick in every recorded sample for the fixture to pass.

Metrics from `/mksb performance` cover the scheduled update/rank loop, excluding initial joins, configuration reloads, database worker work, and the diagnostics command. The server's overall average tick time is a separate metric. Each plugin sample covers the most recent 200 ticks; six samples per scenario overlap. The disabled scenario still runs playtime tracking and the queue, so it is a display comparison rather than a server without the plugin installed.

This is a flat spectator world using loopback clients on the same Windows PC. It does not establish production capacity, internet latency, behavior on slower hardware, or performance of unusually large custom templates. A one-minute TPS average can include earlier warm-up/connection work. No zero-lag or zero-ping-impact guarantee is implied.

## Reproduce

Build with Java 21 and `mvn -B clean verify`. In `scripts/`, run `npm ci`, then set `PAPER_JAR` to a Paper 1.21.11 jar and run `npm run load`. This accepts the Minecraft EULA for a temporary local test server. It binds only to `127.0.0.1:25587`.

Optional environment variables: `MK_LOAD_PLAYERS` (1–200, default 64), `MK_LOAD_PORT`, `MK_SMOKE_PARENT` (existing scratch directory), `MK_LOAD_REPORT` (JSON output file), and `MK_LOAD_CACHE` (a previously tested Paper 1.21.11 fixture directory whose libraries/versions/cache can be reused). Fixtures remain available for inspection and the server is stopped after the test.

Run the ordinary three-player `npm test` fixture as well; it verifies display output, every placeholder, promotion sound delivery, offline assignments, and restart persistence.

## Recorded v1.2.0 results

Verified October 5, 2026: **64 clients**, Paper **1.21.11 build 132**, Java 21, 2 GiB server heap, Windows, AMD Ryzen 5 9600X 6-Core Processor. Every client remained connected.

| Scenario | Reported average plugin ms/tick range | Largest observed plugin sample ms | One-minute TPS range |
| --- | ---: | ---: | ---: |
| displays-disabled | 0.007–0.008 | 0.128 | 19.89–19.93 |
| enabled-static | 0.114–0.208 | 1.206 | 20.00–20.00 |
| enabled-moving | 0.124–0.171 | 3.626 | 20.00–20.00 |

The largest observed scheduled-loop sample was **3.626 ms**, above the approximate 1 ms processing target because one operation or scheduling pause can overrun it. All enabled samples showed **20.00 TPS**; this is specific to the fixture. Unchanged Tab footer traffic was zero in each scenario. Raw v1.2.0 measurements: [performance-report-v1.2.0.json](performance-report-v1.2.0.json).

## Recorded v1.3.0 results

Verified October 5, 2026: **64 clients**, Paper **1.21.11 build 132**, Java 21, 2 GiB heap, Windows, AMD Ryzen 5 9600X 6-Core Processor. The enabled scenarios include the new overhead rank badges and Hours row. Every client remained connected.

| Scenario | Reported average plugin ms/tick range | Largest observed plugin sample ms | One-minute TPS range |
| --- | ---: | ---: | ---: |
| displays-disabled | 0.014–0.017 | 0.247 | 19.94–19.97 |
| enabled-static | 0.126–0.184 | 4.622 | 20.00–20.00 |
| enabled-moving | 0.125–0.200 | 3.955 | 20.00–20.00 |

Unchanged overhead team and Tab footer traffic was zero in every recorded scenario. The same fixture limits above apply; these are local measurements rather than production-host or internet-latency guarantees. Raw measurements: [performance-report.json](performance-report.json).
