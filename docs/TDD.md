# Orbit — Technical Design Document

Version 0.2 | Companion to `PRD.md` | Audience: builder agents and the project lead

This version turns the v0.1 design into concrete contracts. When this document and a
phase prompt disagree, this document wins; when this document and `AGENTS.md` disagree,
`AGENTS.md` wins.

## 1. Summary

Two parts, one direction of data:

```
public playlists/metadata
        │
        ▼
Curator (GitHub Actions, Python)  ──►  GitHub Pages
  fetch → normalize → group              version.json
  → health → classify → score            catalog.db.gz
  → pre-sorted collections               report.json
  → validate gate                        state/health.json
        │
        ▼
Orbit app (TCL Google TV)
  version check → download → verify → atomic swap
  → read-only SQLite → Compose TV UI → Media3 player
```

The TV never sorts, classifies or health-checks channels. It reads pre-ordered data,
plays streams and stores a few local counters.

## 2. Catalog contract

### 2.1 Files published

| File | Content |
|---|---|
| `version.json` | catalog version, sha256, byte size, schema version, min app version, counts |
| `catalog.db.gz` | gzip of a vacuumed SQLite database |
| `report.json` | human/machine health report for the run (not read by the app) |
| `state/health.json` | per-URL health history carried between runs (not read by the app) |

`version.json`:

```json
{
  "schema": 1,
  "catalog_version": "2026-11-03T12:00:00Z",
  "built_at": "2026-11-03T12:00:00Z",
  "sha256": "<sha256 of catalog.db.gz>",
  "size_bytes": 912345,
  "min_app_version": 1,
  "counts": { "channels": 780, "playable": 640, "collections": 18, "malayalam": 34 }
}
```

The app downloads only when `catalog_version` differs from the local `meta` value.
It sends `If-None-Match` when it has an ETag; `304` means no work.

### 2.2 Database schema (schema version 1)

```sql
PRAGMA user_version = 1;

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- schema, catalog_version, built_at, source, source_license, min_app_version

CREATE TABLE country  (code TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE language (code TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE category (id   TEXT PRIMARY KEY, title TEXT NOT NULL);

CREATE TABLE channel (
  id           TEXT PRIMARY KEY,   -- stable iptv-org id, or sha1(name|country) for personal M3U
  name         TEXT NOT NULL,
  alt_names    TEXT NOT NULL DEFAULT '',
  logo_url     TEXT,
  category_id  TEXT REFERENCES category(id),
  language_id  TEXT REFERENCES language(code),
  country_id   TEXT REFERENCES country(code),
  number       INTEGER,            -- stable 1..N global display number
  state        TEXT NOT NULL,      -- alive | unverified | dead
  best_score   INTEGER NOT NULL,   -- 0..100
  is_curated   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE stream (
  id           TEXT PRIMARY KEY,   -- sha1(url)
  channel_id   TEXT NOT NULL REFERENCES channel(id),
  url          TEXT NOT NULL,
  kind         TEXT NOT NULL,      -- hls | ts | mp4 | other
  state        TEXT NOT NULL,      -- alive | unverified | dead
  latency_ms   INTEGER,
  resolution   TEXT,               -- e.g. "720p" or ""
  score        INTEGER NOT NULL,
  user_agent   TEXT,
  referrer     TEXT,
  position     INTEGER NOT NULL    -- 0 = best; fallback order
);
CREATE INDEX idx_stream_channel ON stream(channel_id, position);

CREATE TABLE collection (
  id        TEXT PRIMARY KEY,
  title     TEXT NOT NULL,
  kind      TEXT NOT NULL,         -- theme | country | curated
  position  INTEGER NOT NULL       -- Home row order
);

CREATE TABLE collection_item (
  collection_id TEXT NOT NULL REFERENCES collection(id),
  channel_id    TEXT NOT NULL REFERENCES channel(id),
  position      INTEGER NOT NULL,  -- pre-sorted; app never re-sorts
  PRIMARY KEY (collection_id, channel_id)
);
CREATE INDEX idx_collection_order ON collection_item(collection_id, position);

CREATE VIRTUAL TABLE channel_fts USING fts5(
  channel_id UNINDEXED,
  name,
  alt_names,
  language,
  category,
  country,
  tokenize = 'unicode61 remove_diacritics 2'
);
```

Rules:

- `catalog.db` is vacuumed and contains **no WAL**. The app opens it `OPEN_READONLY`.
- Only channels with at least one `alive` or `unverified` stream appear in collections.
- `dead` channels and streams stay in the file so search can show them with a warning,
  but the app never attempts playback on a dead stream.
- Streams are ordered by `position` (`0` best). This is the fallback chain.
- Collection items are ordered by `position`. The app reads them as-is.
- `channel.id` must be stable across runs so favourites survive catalog swaps.

### 2.3 Collection IDs

Theme collections: `malayalam`, `india-news`, `india-entertainment`, `india-movies`,
`india-music`, `india-kids`, `india-sports`, `india-religious`, `india-other`,
`world-news`, `world-entertainment`, `world-movies`, `world-music`, `world-kids`,
`world-sports`, `world-documentary`, `world-other`.

Country collections: `country-<cc>` for the top countries by playable channel count
(exclude `IN`; cap 10). `kind = 'country'`.

`editor-picks` (`kind = 'curated'`) is always first among remote collections.

### 2.4 Search contract

- FTS5 `MATCH` over name, alt_names, language, category and country.
- Multi-token query → `AND` across tokens; if no result, `OR`; if still none, `LIKE`.
- Typo fallback: for tokens of length ≥ 5, expand one-deletion variants and retry the
  `AND` query. Results are capped at 60 and ordered by `rank` then `best_score`.
- Every result carries `channel.state`; the UI marks `unverified` and blocks `dead`.

## 3. Curator

Package `curator/orbit_curator` (stdlib only at runtime; `pytest` for dev).

```
__main__.py     CLI: build, validate
fetch.py        cached download of iptv-org API files (ETag/hash)
parse.py        tolerant M3U parser for personal sources (P1)
normalize.py    name cleanup, id generation, dedupe
health.py       three-state checks + history merge
classify.py     category/language/country + confidence + Malayalam rules
score.py        stream score, channel score, editorial boost
collections.py  build ordered collections and country rows
build.py        orchestration + SQLite writer + FTS population
validate.py     publish gate
report.py       report.json
config/         editorial lists, keyword rules, source definitions
state/          health history fetched from the published catalog host
```

### 3.1 Pipeline

1. **Fetch** `channels.json`, `streams.json`, `countries.json`, `languages.json`,
   `categories.json` from `https://iptv-org.github.io/api`. Cache for 24 h by hash.
   **Live API note (verified 2026-11-03):** `channels.json` currently exposes no
   `languages` field (0 of 31,725 channels) and usually no `logo`. Tolerate nulls:
   classify language from the curated editorial list, Malayalam-script `alt_names`
   and keyword rules; leave `logo_url` null when absent (the app renders a monogram).
   Never crash or drop a channel because metadata is missing.
2. **Normalize** names (trim, collapse spaces, strip quality/geo tags, fix casing),
   resolve category/language/country codes to titles.
3. **Candidate selection** (keeps runtime and file size bounded):
   - all Malayalam channels;
   - India: editorial picks first, then category rank, cap 520;
   - world: editorial global broadcasters plus up to 3 per country;
   - up to 3 candidate streams per channel, highest declared quality first, deduped.
4. **Health check** every candidate URL (see 3.2).
5. **Classify** using API metadata first, keyword rules second (see 3.3).
6. **Group/score** channels, order streams, compute channel state and score.
7. **Collections** in fixed order, each sorted by editorial boost, score, name.
8. **Write** SQLite (WAL off, `VACUUM`), FTS, `catalog.db.gz`, `version.json`,
   `report.json`, and the new `state/health.json`.
9. **Validate** (3.5). Exit non-zero on failure; CI does not publish.

### 3.2 Health check

- Timeout 8 s total per URL, 24 workers, no retries in the same run beyond one quick
  retry for connection resets, polite `User-Agent: OrbitCurator/1.0`.
- HLS: fetch the manifest; if it is a master playlist, pick the lowest-bandwidth
  variant, fetch it, then fetch the first media segment (≤ 64 KB) and require bytes.
- Direct TS/MP4: require bytes and a plausible signature/content-type.
- HTTP 403/451/407, timeouts, TLS errors → **soft failure** → `unverified`.
- 404/DNS/invalid body twice in a row → **hard failure**; two consecutive hard
  failures across runs → `dead`.
- Non-http(s), YouTube, NSFW and closed channels are excluded before checking.
- History (`state/health.json`) holds `{url: {state, hard_fails, last_ok, latency_ms,
  checked_at, resolution}}` and is merged, not replaced.

### 3.3 Classification

- Category from the API `categories` list, mapped to one of the fixed buckets.
- Language from `languages`; Malayalam if `mal` is present, or the channel is in the
  curated Malayalam ID list, or keywords match. Explicit curated list wins.
- Country from `country`; missing → `ZZ` ("International").
- Confidence: metadata = high, keyword = medium, default = low. Low-confidence Indian
  channels go to `india-other`, not a main theme row.
- No ML. Rules live in `curator/orbit_curator/config/`.

### 3.4 Scoring

- Stream: `alive` base 70 + resolution (up to 15) + latency (up to 15) − failures;
  `unverified` base 30, never above an alive stream.
- Channel: max stream score. Editorial picks get a large, explicit boost for ordering
  only (never shown to the user).
- Channel state: `alive` if any alive stream, else `unverified` if any unverified,
  else `dead`.
- Global `channel.number` is assigned after final ordering and is stable only as long
  as the ordering is stable. Number keys use the current row, not this value.

### 3.5 Publish gate

Abort (exit 1, keep the old catalog) when any of these fail:

- schema/table/FTS integrity, or FTS row count ≠ channel count;
- `malayalam` missing or < 10 playable channels;
- total playable channels < 250;
- gzip size > 8 MB;
- playable count drops > 15 % versus the previous `report.json` fetched from Pages;
- any required theme collection is empty after a previous run had ≥ 1 item.

## 4. App

### 4.1 Stack

Kotlin 1.9.24, AGP 8.7.2, Gradle 8.9, JDK 17, Compose for TV 1.0.0 (Compose BOM
2024.06.00), Media3 1.4.1 (`exoplayer`, `exoplayer-hls`, `ui`, `datasource-okhttp`),
Coil 2.6.0, OkHttp 4.12.0, coroutines 1.8.1. `compileSdk 34`, `minSdk 26`,
`targetSdk 34`, `applicationId app.orbit.tv`. No Hilt, Room, Firebase, analytics.

### 4.2 Runtime structure

```
OrbitApp (Application)
└─ AppContainer: OkHttpClient, CatalogManager, UserDatabase, PlaybackController
MainActivity (single, landscape, exported)
└─ OrbitRoot (Compose)
   ├─ BootScreen          first launch / catalog missing / fatal error
   ├─ HomeScreen          hero + rows + top bar
   ├─ SearchScreen        D-pad keyboard + results
   ├─ SettingsScreen      list
   └─ PlayerScreen        AndroidView(PlayerView) + overlay + quick panel
```

- Catalog and user databases are opened on IO and exposed through small DAOs.
- `CatalogManager` exposes `StateFlow<CatalogState>`: `Missing`, `Ready(info)`,
  `Downloading(progress)`, `Error(message)`.
- The UI never queries more than ~40 rows at a time; rows paginate in place.
- Screen state is a sealed class held in a `MainViewModel`; Back restores Home row and
  item focus exactly.

### 4.3 Catalog install flow

1. On start, if `filesDir/catalog.db` exists and opens, go straight to Home.
2. In parallel, fetch `version.json` (10 s timeout, ETag cached in prefs).
3. If newer: download `.gz` to `cache/catalog-download.gz`, verify size and sha256,
   gunzip to `cache/catalog-next.db`, open read-only and check `meta.schema` and
   `user_version`, then close, `rename` old → `.bak`, `rename` next → `catalog.db`,
   reopen, delete `.bak`. Any failure keeps the old catalog and surfaces a message.
4. Release builds default to `https://rocketraccon606.github.io/orbit/`.
   Debug builds accept `--es catalog_base_url <url>` (emulator: `http://10.0.2.2:8787/`).
5. A debug-only local import (`scripts/import-catalog.sh`) copies a catalog into the
   app's `filesDir` for airplane-mode tests.

### 4.4 User data

Separate DB `orbit-user.db` (`SQLiteOpenHelper`), never touched by catalog swaps:

```sql
favorite(channel_id TEXT PRIMARY KEY, added_at INTEGER NOT NULL)
recent(channel_id TEXT PRIMARY KEY, last_watched_at INTEGER NOT NULL)
watch_count(channel_id TEXT PRIMARY KEY, count INTEGER NOT NULL, last_watched_at INTEGER)
```

`recent` is trimmed to 20 rows on every write. Reads return IDs; the UI joins them
against the catalog and silently drops IDs missing from the new catalog (they are
kept in the DB so a channel returning in a later catalog reappears).

### 4.5 Player

State machine: `Idle → Preparing(streamIndex) → Playing → Switching → Failed`.

- One `ExoPlayer` for the process lifetime, created lazily on the first play, released
  when the app goes to background (`onStop`) and recreated on return.
- `PlayerView` uses `SurfaceView`, `keepContentOnPlayerReset = true`, art-mode off,
  controller disabled (custom overlay).
- Per-stream HTTP: `DefaultHttpDataSource.Factory` with the stream's UA/Referer, using
  the shared OkHttp client (connection reuse). One `MediaSource` per attempt.
- `LoadControl`: min 1.5 s, max 8 s, playback 0.5 s, after rebuffer 1 s,
  `prioritizeTimeOverSizeThresholds = true`, target buffer 8 MB.
- Fast start: initially cap video at 900 kbps / 854×480; on first rendered frame,
  restore adaptive defaults (debounced, once per switch).
- Switching: `stop()` → `setMediaSource(next)` → `prepare()` → `play()`. The logo
  overlay appears immediately; the old frame stays until the new one renders.
- Fallback: `onPlayerError` → next stream; watchdog if not `READY`/rendering within
  6 s → next stream; after the last stream, show "Channel unavailable" with Retry and
  Back. A non-blocking toast names the fallback.
- Keys (player): `CH+/CH-` and `DPAD_UP/DOWN` change channel in the originating row;
  number keys 1-9 jump within the current row; `DPAD_CENTER` toggles overlay/quick
  panel; `KEYCODE_LAST_CHANNEL` toggles the previous channel; Back closes the panel or
  returns to the list at the saved position.
- Timing: debug builds log `OrbitPerf switch channel=<id> ms=<n> attempt=<k>` from
  the key event to `onRenderedFirstFrame`, and `OrbitPerf coldstart ms=<n>` from
  `onCreate` to first Home frame.

### 4.6 UI system

- Deep charcoal `#07080B` background, `#14161C` surfaces, warm accent `#F2B36B`,
  focus ring `#FFFFFF` + accent glow, 16 dp card radius, 24 dp row gaps.
- Hero: 42 % screen height, logo as the image, vertical gradient to background, title,
  language · category · country, and a `▶ Watch` hint. Updates on focus after 120 ms.
- Card: 208×117 dp logo panel with gradient fallback and monogram, name below, 1-line
  ellipsis, subtle dot for `unverified`, 1.06× scale + ring on focus.
- No blur, no infinite animation, one 150 ms focus transition. Text is at least 14 sp
  (labels) / 18 sp (titles); focus must read from 3 m.
- Screen transitions are instant cuts (no cross-fades) to protect low-end performance.

### 4.7 Settings / About

Clear recents, clear favourites, refresh catalog now, catalog version and counts,
source license credit (iptv-org), app version. Debug-only: base URL, catalog path,
last switch timing, schema version.

## 5. Local development

| Command | Effect |
|---|---|
| `scripts/curator.sh --fast` | build a catalog without health checks (UI dev) |
| `scripts/curator.sh` | full build with health checks |
| `scripts/serve-catalog.sh` | serve `curator/dist` on `:8787` for the emulator |
| `scripts/emulator.sh` | create/start an Android TV API 30 AVD |
| `scripts/build-app.sh` | assemble the debug APK |
| `scripts/install-emulator.sh` | install and launch on the emulator with the local catalog URL |
| `scripts/smoke.sh` | scripted emulator smoke test + screenshots |
| `scripts/deploy-tv.sh` | guarded ADB install/launch on the TCL TV |
| `scripts/bench.sh` | parse `OrbitPerf` logs into median/p90 |
| `scripts/audit.sh` | manifest background-component + APK size audit |

## 6. Testing

- Curator: fixture-driven unit tests (parser, normalization, health classification,
  scoring, query, validation) and an integration test that builds a small catalog and
  asserts schema, FTS and collection order.
- App JVM tests: search query builder, typo expansion, recents trimming, number-key
  mapping, version comparison.
- Emulator instrumentation: catalog open/read, FTS search, user DB trim, install
  atomic swap (no network), player smoke (one known-good HLS stream).
- Emulator manual/scripted: cold start, rows, search, favourite, recent, playback,
  fallback message, channel change, Back position, memory screenshot.
- TV: same smoke plus benchmark and one-hour soak. No test may use the TV unless the
  device phase is active.

## 7. Risks specific to this design

- Compose on 1 GB: mitigated by no blur/animations, small lists, one activity; Leanback
  fallback is pre-approved if measurements fail.
- HLS startup dominated by network: mitigated by prefetch (Phase 3), low-bitrate start,
  connection reuse and instant logo overlay.
- Public sources vanish: health gate, 15 % drop protection, last good catalog retained.
- Geo-restriction outside India: three-state health keeps channels visible but lower.
