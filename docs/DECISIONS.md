# Orbit — Decision Log

Newest first. Every non-obvious choice gets an entry: decision, why, consequence.
Do not rewrite history; supersede entries with a new dated entry.

## 2026-11-03 — Stack and architecture baseline

- **Kotlin 1.9.24 + AGP 8.5.2 + Compose for TV 1.0.0 + Media3 1.4.1.** Compose TV is
  the most direct path to the reference UI and has first-class D-pad focus. Leanback
  remains the documented fallback if the TCL measurements miss budget. No Hilt, no
  Room, no Firebase: fewer dependencies, smaller APK, faster cold start.
- **Read-only SQLite + a tiny hand-written DAO instead of Room.** The catalog is a
  downloaded, immutable file with FTS5; Room adds codegen and schema conflict risk for
  no benefit. User data uses `SQLiteOpenHelper` in a separate file.
- **Catalog compressed with gzip (`catalog.db.gz`).** Android has gzip built in, no
  native dependency, ~3-5x reduction. Zstd would need a native lib.
- **GitHub Pages as the catalog host.** Free CDN with ETag support; Actions publishes
  `version.json`, `catalog.db.gz` and `report.json` to the `gh-pages` branch.
- **Health state is three-valued: alive / unverified / dead.** Unverified covers
  timeouts, 403/451 and geo-restriction so a server outside India does not delete
  channels that work for the viewer. Dead requires two consecutive hard failures and
  is hidden from rows (still searchable with a warning).
- **The app never sorts.** Collections and per-channel stream order are baked into the
  catalog by the curator. Local ordering is limited to recents/favourites.
- **Debug catalog override via intent extra and Gradle property.** The emulator and
  local server can be used without a release. Release builds default to Pages and
  ignore the extra unless `BuildConfig.DEBUG`.
- **No delta catalog updates in v1.** Full download only; the file is small.
- **Single activity, custom screen state, no Navigation-Compose.** Two top-level
  screens and a player; a sealed `Screen` state is lighter and simpler.
- **Cleartext HTTP is allowed for playback only.** A large share of public IPTV URLs
  are plain HTTP. No WebView, no user-supplied HTML, no cleartext for catalog
  downloads (Pages is HTTPS).
- **Package name `app.orbit.tv`** (PRD suggestion). Sideload only; no store listing.
  Name/trademark check remains a release blocker.

## 2026-11-03 — Performance strategy

- **Reuse one ExoPlayer and one surface.** `keepContentOnPlayerReset = true` keeps the
  last frame during a switch; channel logo overlays immediately, so there is never a
  black screen with a spinner.
- **Live-tuned LoadControl** (`minBuffer 1.5 s`, `maxBuffer 8 s`, playback buffers
  0.5 s/1 s) with time-over-size prioritisation. Start capped at a low bitrate/size,
  release the cap after the first frame renders.
- **Fallback chain is data, not logic.** The catalog orders streams per channel; the
  player walks that list on error or a 6 s stall watchdog and shows a non-blocking
  message.
- **Prefetch only manifests and first segments**, for the focused card after a short
  dwell, max two entries, memory-capped, cancelled on navigation. No extra decoders.
  Phase 3+; off when low on memory or on slow networks.
- **Logo loading capped** (Coil memory 10 %, disk 40 MB, 100 ms crossfade) and only
  for visible/look-ahead rows. Artwork also appears in the player quick menu.

## 2026-11-03 — Live source metadata gap

The current iptv-org `channels.json` has **no `languages` array** (0 of 31,725
channels) and frequently no logo. Malayalam detection therefore cannot depend on
metadata: the curator uses the curated editorial ID list, `alt_names` in Malayalam
script, and explicit keyword rules, in that order. Language for other channels is
best-effort from rules, otherwise `und` ("Unknown"); the app must not filter or hide
channels because language is missing. `logo_url` is nullable and the UI falls back to
a category-tinted monogram, so a missing or broken image never leaves a blank tile.

## 2026-11-03 — Curator baseline

- **Chain: fetch → parse → normalize → group → health → classify → score → validate →
  publish.** One Python package, pure functions where possible, fixtures for tests.
- **Stable channel IDs from iptv-org channel IDs.** Favourites must survive rebuilds.
  For user-supplied M3U, derive `sha1(normalized_name|country)`.
- **Health checks fetch manifest + lowest variant + one segment** with an 8 s timeout,
  16 workers, no retry storms, and a per-URL history so one bad run cannot delete a
  channel.
- **Classification is metadata first, keyword rules second, confidence third.** Low
  confidence goes to "Other"; no ML in v1.
- **Publish gate:** abort if schema validation fails, a required collection is empty,
  or alive channels drop more than 15 % versus the last good catalog.
- **Sources:** iptv-org API (public metadata) with license/credit recorded. Curator
  records the source license in `meta` and the report.

## 2026-11-03 — On-device search uses LIKE, not FTS5

The emulator (Android TV API 30) proved that the platform SQLite has **no FTS5
module**: every `MATCH` failed with `no such module: fts5` and the app silently fell
back to a name-only `LIKE`, so language/category search and typo tolerance were not
actually working on a TV. Fix:

- The app no longer queries `channel_fts`. Search tokenises the query, expands each
  token into variants (one-letter deletions and a shortened stem for missing-letter
  typos), ANDs the token groups, ORs the variants, and matches `LIKE` substrings
  against name, alt names, language, category and country. The catalog is about 1k
  rows, so this is fast.
- `sanitize` keeps `\p{M}` combining marks. Dropping them split Malayalam words into
  single-letter fragments and lost the language matches.
- `channel_fts` stays in the catalog schema for desktop use and future bundled
  SQLite; the app must not depend on it.
- Verified on emulator: `malayalm` now returns 41 Malayalam results (previously 0).

Other hardening from Phase 5:

- The merged manifest must contain no provider or receiver. The transitive
  `androidx.startup` provider and `androidx.profileinstaller.ProfileInstallReceiver`
  are removed with `tools:node="remove"`; `scripts/audit.sh` then passes.
- Debug error logging no longer prints full TLS stack traces: they filled the log
  ring buffer and hid the `OrbitPerf switch` lines used for benchmarking.
- Catalog update path verified end to end: the app downloaded a different catalog
  over HTTP, verified SHA-256 and size, swapped atomically and kept favourites and
  recents. Settings showed the new version (`2026-11-03T15:05:31Z`, 1071 channels).
- Soak: 24 rapid CH+ switches stayed in one process, PSS went 72.9 MB → 92.9 MB and
  flattened (second round +0.9 MB); first frames 2.4 s (BBC News) and 6.1 s (CNN,
  software-rendered emulator), with fallback chains working on geo-blocked feeds.

## 2026-11-03 — Returning to Home restores D-pad focus

When Search or Settings closes, Home composes with rows already loaded, so the first
row's `FocusRequester` is not registered on the first frame and the screen ended up
with no focused node (the first D-pad press was consumed just to focus the hero).
Home now retries the first-card focus request for up to five frames. Verified with
`scripts/smoke.sh`: Back from Search lands on the first Continue-watching card.

`smoke.sh` and `bench.sh` were made deterministic on that behaviour: Home focuses the
first card, so Up reaches the top bar (Search) and OK starts playback before CH+/CH−
benchmarking. The bench produced no first-frame samples on the emulator because 11 of
12 public channels were geo-blocked from that network; the script and parser are
functional, and the median/p90 targets remain TV checks.

## 2026-11-03 — Channel artwork and metadata enrichment (catalog schema 2)

`channels.json` carries no logos, so the curator now also loads the optional
`logos.json` from the same iptv-org API. For each channel it picks one **https raster**
logo, skipping SVG because the app's Coil build has no SVG decoder, ignoring
`in_use=false`, and preferring the largest image. Result: **1,059 of 1,071** channels
have artwork (up from 0). Real-world coverage also adds `website` (896), `network`
(261) and `launched` (514).

Schema 2 adds `website`, `network`, `launched` and `owners` to `channel`. The app
(`versionCode 2`, `0.2.0`) accepts both schemas: on open it migrates an existing v1
catalog in place by adding the empty columns, so offline browsing survives an app
upgrade. The next catalog update replaces it with v2. `min_app_version` is 2, so an
old app refuses a v2 catalog instead of failing to parse it.

The UI now treats artwork as the product: category-tinted backdrops, 16:9 artwork
cards with a bottom scrim (name, network, CH number), a large featured hero with
network/launch-year/country metadata, artwork in search results and the player quick
menu, and a monogram fallback for failed images. JPEG logos that are full-frame
artwork are cropped; transparent PNG logos are fitted on the dark backdrop.

## 2026-11-03 — Debug-only TLS relaxation for the emulator

The emulator's traffic passes through the host's AVG Web Shield, which re-signs HTTPS
with a root the TV system image does not trust, so every HTTPS logo and stream failed
with `CertPathValidatorException: Trust anchor for certification path not found`. The
TV image is a production build and cannot take a system CA.

`AppContainer` therefore installs a trust-all `X509TrustManager` **only when
`BuildConfig.DEBUG`**; release builds keep platform TLS validation. Coil uses the same
OkHttp client, so artwork and catalog/stream requests behave consistently in debug.
This is a development-network workaround, not a release behaviour.

## 2026-11-03 — Two-pane TV search; Home keeps the hero in view

- **Search** uses the landscape: a 358 dp column holds the D-pad keyboard and six
  quick picks, while results or a trending grid fill the right pane. The earlier
  stacked layout showed only two result rows below the keyboard and left half the
  screen empty.
- A quick pick sets the query and moves focus to the first result; typing keeps focus
  on the keyboard so results update live. Back from a search result returns to Search
  with that result focused, matching Home's return-to-card behaviour.
- **Home** scrolls the hero into view when focus reaches the hero buttons or the top
  bar; otherwise the list stayed at the rows' offset and showed a clipped hero.

## 2026-11-04 — Netflix-style rail and full-bleed hero

The Home screen was rebuilt around a 156 dp left rail, a full-bleed cinematic hero and
wide 16:9 channel cards, matching the reference the user supplied:

- The rail holds Home, Discover (search), My List and Recent above a bottom Settings
  entry. My List and Recent scroll to their row and focus its first card; when the row
  is absent they show a one-line explanation instead of doing nothing.
- The hero uses the channel logo as a dimmed backdrop over a category palette, with
  live/featured/language/category chips, network · year · country · CH metadata, a red
  Play Now pill and favourite/info circles.
- A coral-red accent on a deep purple-black background replaces the amber theme;
  artwork cards keep a bottom scrim so names stay readable on any logo.
- **Focus model.** Compose's geometric focus search put Left-from-content on whichever
  rail item happened to be vertically nearest, which was usually Settings, and Up-from-
  hero skipped the top bar. Edge keys are now explicit: Left from the top search pill,
  hero buttons, top-bar left edge and the first card of every row focuses Home in the
  rail; Up from the hero focuses the top search pill; Down from the top bar focuses
  Play Now. Right and vertical travel inside the rail stay on normal geometric search,
  so Right returns to the content beside the focused rail item.

Verified on the emulator: Left from a card → Home rail, Home → Play Now, rail actions
(Discover/My List/Recent/Settings), top bar round trip, hero play → player → Back to
the same card, `scripts/smoke.sh` and `scripts/audit.sh` (11.72 MB debug, 1.98 MB
release, no components/extra permissions). Evidence: `ui-rail-fix-*.png`,
`ui-my-list.png`, `ui-recent.png`, `ui-settings2.png`, `ui-focus-v2-top.png`,
`ui-player-back.png`.

## 2026-11-04 — Top-nav reference layout, real channel identity

The supplied `orbit.html` was implemented as the final visual target rather than the
earlier rail design:

- **Top navigation replaces the sidebar.** ORBIT wordmark, Home, Channels, Malayalam,
  News, Sports, My List, Recently Watched, search, bell and a square blue profile tile.
  The bar is transparent over the hero and switches to solid #141414 after 24 dp of
  scroll, as in the reference.
- **Home hero matches the reference hierarchy:** LIVE CHANNEL line, oversized title,
  TOP 10 red badge with rank, description, Play and More Info, plus a large channel
  logo on the right over the category radial glow.
- **Rows:** Continue Watching with progress, Top 10 portrait cards with 104 sp outlined
  numerals, then curated 16:9 rows. Focus expands a card and reveals Play, My List and
  More Info circles plus real `language · category · CH n` metadata. Match percentages,
  age ratings and fake EPG were deliberately not invented.
- **Channel detail** uses real catalog data only: language, category, country, network,
  launched year, owners, source count, website and status, with a three-column
  "More like this" grid. This replaces the reference's placeholder modal content.
- **Search** now keeps one-letter queries: single letters are prefix searches across
  name/alt name/language/category/country, so `a` surfaces Asianet and Al Arabiya
  instead of returning an empty pane. Longer queries keep deletion and stem variants
  for typo tolerance.
- **Catalogue assets:** every card uses the channel's actual logo URL from schema 2,
  with a tinted monogram fallback when a logo is missing or fails to load.

Verified: curator 9 tests, app unit tests, debug build (11.79 MB) and R8 release build
(2.02 MB), `scripts/audit.sh`, and emulator checks of Home, Browse, Detail, Search
results and playback.
