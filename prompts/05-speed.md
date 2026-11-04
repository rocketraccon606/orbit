# Phase 5 — Speed, prefetch and tuning

Read `AGENTS.md`, `docs/TDD.md` (§4.5, §4.7) and this file. Phase 4's gate must be
green. Measure before changing anything; keep a baseline table in `artifacts/`.

## Goal

Cut channel-switch time on healthy streams toward the targets (median < 1.5 s,
p90 < 3 s) without adding decoders, memory growth or background work. All tuning must
be backed by numbers from `OrbitPerf` logs on the emulator; the TV re-measurement
happens in Phase 6/7.

## 1. Baseline

1. Install the Phase 4 APK against the local catalog.
2. Run `scripts/bench.sh 30` (or the emulator equivalent) and record median, p90, min,
   max for the first 30 switches.
3. Capture `dumpsys meminfo app.orbit.tv` before, during and after a 10-minute loop.
4. Record which stage dominates: manifest fetch, first segment, decoder init or
   surface. Add temporary debug timing spans (manifest request, first segment bytes,
   first frame) and keep them debug-only.

## 2. LoadControl and start policy

Experiment with at most three configurations, same channels, same network:

| Config | min | max | play | after rebuffer | notes |
|---|---|---|---|---|---|
| A (current) | 1500 | 8000 | 500 | 1000 | baseline |
| B | 1000 | 6000 | 300 | 800 | aggressive |
| C | 2000 | 10000 | 600 | 1200 | stable |

Pick the one with the best median without increasing rebuffers on a 5-channel loop
(observe `onPlaybackStateChanged` and `OrbitPerf rebuffer`). Keep the low-bitrate
start cap (1.2 Mbps / 960×540) and release-after-first-frame behaviour; verify the
release does not cause a visible stall or a second buffering event. If it does,
release after 3 s instead.

## 3. Prefetch (the main win)

Implement manifest + first-segment prefetch without extra players or decoders.

- Trigger: a card holds focus for 300 ms on Home (and 300 ms after the player switches
  to a channel). Cancel immediately on focus change, play, screen change or `onStop`.
- Work: GET the stream's manifest into memory; if it is a master playlist, pick the
  lowest-bandwidth variant, GET it, then GET the first media segment (cap 512 KB).
  Respect the stream's UA/Referer.
- Cache: in-memory LRU, max 2 entries / 6 MB total, 30 s TTL, keyed by exact URL and
  by segment URL. No disk writes, no services, no WorkManager.
- Integration: a thin `PrefetchingDataSource` wrapper around the Media3 HTTP data
  source. On `open()` it serves bytes from the cache when the URL matches and the
  entry is fresh; otherwise it delegates upstream and caches on the fly when small.
  It must implement `read`, `close`, `getUri`, `getResponseHeaders` correctly and be
  unit-tested with a fake upstream.
- Gate: enable by default; disable when `ActivityManager.isLowRamDevice` is true and
  available memory is below 120 MB, or when the active network is metered + slow.
  Expose a debug toggle in Settings; no user-facing P1 toggle yet.
- Never prefetch more than one channel at a time. Never prefetch in the player
  overlay or while the app is not visible.
- Log hit/miss per switch: `OrbitPerf prefetch hit=<0|1> channel=<id>`.

## 4. Network reuse

- One `OkHttpClient` with a 60 s connection pool for both catalog and playback.
- Warm DNS/TCP for the focused channel's best host after 300 ms dwell (a HEAD or a
  tiny GET if the server rejects HEAD), cancelled with the prefetch.
- Verify cross-protocol redirects still work and no request is duplicated in logs.

## 5. Memory and image caps

- Coil: memory 20 MB, disk 64 MB, no crossfade, request size = card size, cancel
  requests for cards that leave composition.
- Player: `targetBufferBytes` 8 MB; confirm `dumpsys meminfo` stays < 200 MB while
  playing and returns to baseline after leaving the player.
- One-hour surf loop (scripted): memory must not grow monotonically. Fix leaks
  (listeners, coroutines, PlayerView references) rather than raising limits.

## 6. Benchmark harness

`scripts/bench.sh`:

- Ensures one emulator/device, launches Orbit, performs N channel switches with
  `adb shell input keyevent` using the channel-up key inside the player, sleeps a
  configurable settle time, then dumps `OrbitPerf switch` lines.
- Parses milliseconds and prints count, median, p90, min, max, and the number of
  switches over 3 s. Exits non-zero if the toggle cannot be exercised.
- Adds `--cold` to measure three cold starts (`OrbitPerf coldstart`).
- Writes `artifacts/bench-<timestamp>.txt` and never modifies the app.

## 7. Profiling checks

- Startup: no main-thread disk reads; catalog opens off the main thread.
- Compose: no recomposition of all rows on every focus change; use derived state and
  stable keys. Check with a temporary recomposition counter in debug only.
- Player screen: no logo loading, no catalog queries, no timers except the 4 s
  overlay and the 250 ms watchdog.
- Confirm no background service appears in `dumpsys activity services app.orbit.tv`.

## Acceptance (paste real output)

```bash
source scripts/env.sh
./app/gradlew -p app :app:testDebugUnitTest --console=plain
scripts/audit.sh
scripts/install-emulator.sh
scripts/bench.sh 30
scripts/bench.sh --cold
adb -s emulator-5554 shell dumpsys meminfo app.orbit.tv | head -30
```

Report a before/after table per configuration, prefetch hit rate, rebuffer count,
cold-start numbers, memory numbers, and which changes were kept or reverted. If the
emulator's software decoder or network makes the target unreachable, say so plainly
and record the TV as the place to lock the target.

## Do not

- Add a second player, a downloader service, a disk video cache or libVLC.
- Prefetch more than one channel or keep work running while not visible.
- Tune by disabling error handling or lowering timeouts below reason.
- Change the catalog contract or re-sort rows at runtime.

## Done

`AGENTS.md` §6 report with the measurement tables and the exact code paths changed.
