# Orbit — Testing Strategy

Every phase has an automated gate and a manual/scripted gate. A phase is not done
until both pass and the evidence is in the report.

## 1. Automated gates

### Curator

```bash
source scripts/env.sh
$PYTHON -m pytest curator/tests -q
$PYTHON -m orbit_curator build --fast --out curator/dist-test
$PYTHON -m orbit_curator validate --catalog curator/dist-test/catalog.db \
    --report curator/dist-test/report.json
```

Covers: tolerant parsing, name normalization, dedupe, classification (especially
Malayalam), health state transitions (alive/unverified/dead), scoring, collection
ordering, FTS row count, logo selection (https raster, in-use, largest), schema 2
metadata columns, schema integrity, publish gate (drop protection, required
collections), gzip size.

### App

```bash
source scripts/env.sh
./app/gradlew -p app :app:testDebugUnitTest
./app/gradlew -p app :app:assembleDebug
scripts/audit.sh
```

`SearchQueryTest` covers query sanitising (including Malayalam combining marks),
token limits, typo variants (extra-letter deletion and missing-letter stem) and the
number-key mapping. Database, playback and Compose paths are exercised on the
emulator because the platform SQLite and Media3 need a device runtime.

### Emulator

```bash
scripts/emulator.sh
scripts/curator.sh
scripts/serve-catalog.sh
scripts/deploy-tv.sh --allow-emulator
```

The emulator suite is scripted where input is stable (`scripts/smoke.sh`,
`scripts/bench.sh`) and otherwise driven with `adb shell input` while capturing
`artifacts/*.png` and `OrbitPerf` logs. There is no instrumentation APK; the JVM
tests and emulator runs above cover the same ground.

If AVG Web Shield (or another TLS-inspecting antivirus) runs on the laptop, emulator
HTTPS fails with an untrusted AVG root. Debug builds trust all certificates for this
reason (`AppContainer`); release builds remain strict. See `docs/DECISIONS.md`.

## 2. Emulator acceptance (Phase 5)

Run in order, capture screenshots into `artifacts/`:

1. Fresh install, no catalog → boot progress → Home within 3 s after catalog cached.
2. Home: hero updates on focus; rows scroll; reaching the hero buttons or the top bar
   restores the full hero; focus visible at 3 m equivalent.
3. Search: "mal" returns Malayalam channels; typo "malayalm" still returns results.
4. Long-press OK adds a favourite; it appears in the Favourites row and quick panel.
5. Play three channels in a row; each switch shows logo immediately, no black screen;
   measure `OrbitPerf switch` lines.
6. Force one dead stream (fixture catalog) → automatic fallback message.
7. Play → Back → focus returns to the same row and card.
8. CH+/CH− cycles within the row; number key jumps.
9. Last-channel key toggles the previous channel.
10. Settings: clear recents/favourites; refresh catalog; About shows source credit.
11. One-hour surf loop: `dumpsys meminfo app.orbit.tv` at start/end; no upward trend.
12. Airplane mode with cached catalog: browse/search/favourites work; playback shows a
    clear offline message.

### Emulator evidence (2026-11-03, API 30 TV, software GPU)

| Check | Result | Evidence |
|---|---|---|
| Home rows, hero, recents, favourites | PASS | `artifacts/shot-nav.png`, `shot-settings3.png` |
| Long-press OK favourites | PASS | favourite stars shown on Home |
| Search typo `malayalm` | PASS after fix — 41 Malayalam results | `artifacts/shot-search-typo-fixed.png` |
| Search two-pane layout | PASS — keyboard + quick picks left, live results/trending right | `search-v3-final.png`, `search-v3-results-final.png` |
| Search play → Back | PASS — returns to Search with the played result focused | `step-back.png` |
| Back from Search-result playback returns to Search | PASS | `shot-return-search.png` |
| Back from player returns to the played card | PASS | `shot-soak-start.png` |
| CH+/CH− global order, number jump, last channel | PASS | `OrbitPerf` logs + screenshots |
| Catalog update (atomic download/verify/swap) | PASS — `15:02:08Z` → `15:05:31Z` | Settings version, `prefs` |
| APK audit (no components/permissions) | PASS | `scripts/audit.sh` |
| 24-switch soak, same PID | PASS — PSS 72.9 → 92.9 MB, plateau | `dumpsys meminfo` |
| First frame | BBC News 2.4 s, CNN 6.1 s (software GPU) | `OrbitPerf switch` |
| Catalog v2 artwork | PASS — 1,059/1,071 channels with logo, hero + cards + search + quick menu | `final-home-hero.png`, `search-results2.png`, `quick-final.png` |
| Home hero focus | PASS — hero stays fully visible when reaching Play/My channels or Search/Settings | `post-scroll-fix.png`, `post-scroll-fix-top.png` |
| Netflix-style rail focus | PASS — Left from a card/top pill/hero lands on Home; Up from hero reaches the search pill; Down returns to Play Now | `ui-rail-fix-2.png`, `ui-focus-v2-top.png`, `ui-focus-v2-down.png` |
| Rail actions | PASS — Home focuses Play Now; Discover opens Search; My List/Recent scroll to the row and focus its first card; Settings opens | `ui-rail-home.png`, `ui-discover.png`, `ui-my-list.png`, `ui-recent.png`, `ui-settings2.png` |
| Hero → player → Back | PASS — connects with artwork fallback, Back restores the played card | `ui-player-v2.png`, `ui-player-back.png` |
| Channel metadata | PASS — network · launch year · country · CH shown in hero/search/player | screenshots |
| Catalog schema 1 → 2 migration | PASS — old local catalog upgraded in place, then v2 downloaded | `prefs` + `files/catalog.db` |
| Debug HTTPS through AVG | PASS with debug trust; release stays strict | `RealImageLoader successful` logs |
| Release build | PASS — R8, 1.98 MB unsigned (rail/hero redesign) | `assembleRelease` |

Not yet run: one-hour soak and airplane mode. The TV (hardware GPU) benchmark is
pending device access; see `docs/DEPLOY-TV.md`. `scripts/bench.sh` ran on the
emulator but sampled no first frames because most public channels were geo-blocked
from that network; the script and parser are verified.

## 3. Device acceptance (Phase 6)

Only after the user connects and authorizes the TV.

1. `adb devices` shows the TV as `device` (not `unauthorized`).
2. `scripts/deploy-tv.sh` builds and installs only `app.orbit.tv`.
3. Repeat smoke 1–10 on the TV, at 720p, with the real remote.
4. `scripts/bench.sh` over 30 switches: median < 1.5 s, p90 < 3 s.
5. `scripts/sweep.sh` across main rows: ≥ 95 % play success in the target region.
6. One-hour soak: no memory growth, no ANR, no background service in
   `dumpsys activity services app.orbit.tv`.
7. Airplane mode and cold boot: cached catalog still opens in < 3 s.

## 4. Failure-injection fixtures

`curator/tests/fixtures` contains a tiny catalog with:

- a channel whose first stream always fails and second works (fallback);
- a channel whose streams all fail (unavailable state);
- an unverified channel (warning marker, ranked lower);
- a channel with a redirect and a stream requiring a Referer/UA;
- malformed M3U lines and duplicate URLs.

The app must survive every fixture without a crash.

## 5. Evidence format

For each run, record:

```
Command:
Result: PASS/FAIL
Numbers: switch median=... p90=... coldstart=... apk=... mem=...
Screenshots: artifacts/<name>.png
Logs: artifacts/<name>.log
```

Do not claim a UI or playback result that was not observed. If the emulator cannot
render or decode, say so and mark the item blocked.
