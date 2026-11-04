# Phase 2 — App foundation

Read `AGENTS.md`, `docs/TDD.md` (§2, §4) and this file. Phase 1 must be complete and
its gate green. Do not build polished UI yet; this phase is the skeleton that makes
the rest testable.

## Goal

A buildable Android TV app that downloads the Phase 1 catalog, installs it atomically,
opens it read-only, exposes typed queries, stores user data in a separate database,
and shows a minimal but real Home/Settings shell. It must run on API 30 with no
background components.

## Gradle setup

Create a single-module project under `app/`:

- Gradle 8.9 wrapper (the local cache is already populated), JDK 17.
- AGP 8.7.2, Kotlin 1.9.24, Compose compiler 1.5.14.
- `compileSdk 34`, `minSdk 26`, `targetSdk 34`, `applicationId app.orbit.tv`,
  `namespace app.orbit.tv`, `versionCode 1`, `versionName "0.1.0"`.
- `buildFeatures { compose = true; buildConfig = true }`.
- `buildConfigField("String", "CATALOG_BASE_URL", ...)` from Gradle property
  `orbitCatalogBaseUrl`, default `https://rocketraccon606.github.io/orbit/`.
  Debug builds may override at runtime with intent extra `catalog_base_url`; release
  builds ignore it.
- `androidResources { noCompress += "db" }` is not needed; do not add native libs.
- `packaging.resources.excludes` for license/notice metadata only.

Dependencies (exact, do not add others):

```
androidx.core:core-ktx:1.13.1
androidx.activity:activity-compose:1.9.0
androidx.lifecycle:lifecycle-runtime-compose:2.8.3
androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3
platform(androidx.compose:compose-bom:2024.06.00)
androidx.compose.ui:ui, ui-graphics, ui-tooling-preview (debug: ui-tooling)
androidx.compose.foundation:foundation
androidx.tv:tv-material:1.0.0
androidx.media3:media3-exoplayer:1.4.1
androidx.media3:media3-exoplayer-hls:1.4.1
androidx.media3:media3-ui:1.4.1
androidx.media3:media3-datasource-okhttp:1.4.1
io.coil-kt:coil-compose:2.6.0
com.squareup.okhttp3:okhttp:4.12.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1
test: junit:4.13.2, kotlinx-coroutines-test:1.8.1
androidTest: androidx.test.ext:junit:1.2.1, androidx.test:runner:1.6.2
```

Media3 is included now because the player phase must not change the build graph.
Coil is configured with memory 20 MB / disk 64 MB, `crossfade(false)`.

## Manifest

- `INTERNET`, `ACCESS_NETWORK_STATE`; no `WAKE_LOCK`, no other permissions.
- `uses-feature android.software.leanback required=false`,
  `android.hardware.touchscreen required=false`.
- `usesCleartextTraffic="true"` (public IPTV streams are often HTTP; no WebView).
- One activity, exported, landscape, `LEANBACK_LAUNCHER` + `LAUNCHER`, banner and icon.
- `allowBackup="false"`, `supportsRtl="true"`.
- **No** service, receiver, provider, WorkManager, AlarmManager or analytics.
- Theme: dark, no action bar, black window background.
- Add a simple vector ring icon and a 320×180 banner drawable (XML acceptable).

## Runtime structure (package `app.orbit.tv`)

Implement the contracts below exactly; later phases depend on them.

### `OrbitApp` / `AppContainer`

- One `OkHttpClient` (connection pool, 10 s connect/read for metadata, 60 s for the
  catalog download), one `CatalogManager`, one `UserDatabase`.
- No player yet; `PlaybackController` arrives in Phase 4.
- Close nothing on `onTerminate` (not called); release network work per screen.

### `catalog/Models.kt`

`ChannelCard(id, name, logoUrl, category, language, country, state, number)`,
`ChannelDetail(card, streams: List<StreamRef>)`,
`StreamRef(id, url, kind, state, userAgent, referrer, position)`,
`CollectionRow(id, title, kind, items: List<ChannelCard>)`,
`CatalogInfo(version, builtAt, counts, schema)`.

### `catalog/CatalogManager.kt`

State: `Missing`, `Downloading(progress)`, `Ready(info)`, `Error(message)`,
`Updating(info)`. Expose `StateFlow<CatalogState>`.

- On start: if `filesDir/catalog.db` opens read-only, emit `Ready` immediately.
- Then `checkForUpdate()` on IO: GET `version.json` with cached ETag/Last-Modified;
  `304` → stop. New `catalog_version` → download `.gz` (progress), verify `size_bytes`
  and SHA-256, gunzip to `cache/catalog-next.db`, open read-only, verify
  `PRAGMA user_version = 1` and `meta.schema = 1`, then close, rename current →
  `catalog.db.bak`, rename next → `catalog.db`, reopen, delete `.bak`. Any failure:
  keep the old catalog, emit a non-fatal message, never crash.
- `refreshNow()` for Settings.
- Debug: `importLocal(File)` performs the same validation and swap; `baseUrl` respects
  the intent extra `catalog_base_url` (debug only).
- Never download catalog on a metered connection? No: allow, it is small. Log bytes.

### `catalog/CatalogDao.kt`

Owns the read-only `SQLiteDatabase`. All calls off the main thread.

```kotlin
fun collections(limit: Int = 20): List<CollectionSummary>   // id,title,kind,position
fun collectionItems(collectionId: String, limit: Int = 40, offset: Int = 0): List<ChannelCard>
fun channel(id: String): ChannelDetail?
fun search(rawQuery: String, limit: Int = 60): List<ChannelCard>
fun channelCount(): Int
fun meta(): CatalogInfo
fun close()
```

SQL requirements:

- Collections: `ORDER BY position LIMIT ?`; count items, skip empty.
- Items: join `channel`, `ORDER BY i.position LIMIT ? OFFSET ?`.
- Search: FTS5 `MATCH`; tokens joined with `AND`, fallback `OR`, fallback `LIKE`;
  one-deletion typo expansion for tokens length ≥ 5; order by `rank`, `best_score`;
  cap 60; never throw on bad FTS syntax (sanitize input).
- Channel detail: streams `ORDER BY position`; the app plays them in that order.

### `userdata/UserDatabase.kt` + `UserDao.kt`

Separate `orbit-user.db`, schema from TDD §4.4. Functions:
`toggleFavorite(id): Boolean`, `isFavorite(id): Boolean`, `favorites(limit): List<String>`,
`touchRecent(id)`, `recents(limit): List<String>`, `incrementWatchCount(id)`,
`clearFavorites()`, `clearRecents()`. Recents trim to 20 inside the same transaction.
Never open the catalog here.

### UI shell (minimal, not the Phase 3 design)

- `MainActivity`: edge-to-edge, keeps the screen on only while playing (later),
  dispatches Back through Compose.
- `ui/OrbitRoot.kt`: sealed `Screen` (`Home`, `Search`, `Settings`, `Player`), holds
  `MainViewModel` with catalog/settings state.
- `BootScreen`: progress, error message + Retry, debug URL hint.
- `HomeScreen` placeholder: list collection titles and the first 5 channel names per
  row, D-pad navigable; enough to prove data and focus work.
- `SettingsScreen`: Refresh catalog, Clear recents, Clear favourites, About (version,
  catalog version, counts, source license), Debug section (base URL, catalog path,
  schema) visible only in debug.
- Use the Phase 3 theme file stub now; Phase 3 fills it in.
- All text resources or plain strings; no analytics.

## Tests

JVM unit tests (no Android framework): version comparison, FTS query builder (AND/OR/
typo/LIKE fallback), input sanitization, number-key mapping stub, recents trim query.

Instrumentation tests (`app/src/androidTest`): create a tiny catalog DB on device with
the real schema + FTS, open it read-only through `CatalogDao`, assert collection order,
search and channel-detail fallback order; create `UserDatabase`, insert 25 recents and
assert 20 remain, toggle favourites. Run when an emulator is available.

## Acceptance (paste real output)

```bash
source scripts/env.sh
./app/gradlew -p app :app:testDebugUnitTest --console=plain
./app/gradlew -p app :app:assembleDebug --console=plain
scripts/audit.sh
ls -l app/app/build/outputs/apk/debug/app-debug.apk
```

`scripts/audit.sh` will be provided by the architect; if it is missing, write it:
it must fail if the merged manifest contains `service`, `receiver`, `provider`,
`WorkManager`, `AlarmManager`, `firebase`, `analytics` or permissions beyond INTERNET
and ACCESS_NETWORK_STATE, and report APK bytes against the 12 MB budget.

If an emulator is available: `scripts/install-emulator.sh` then confirm the app opens,
shows a boot/error state without a catalog, and opens Home after the local catalog is
served. Otherwise say the device check is blocked and do not claim it.

## Do not

- Build polished rows, hero or player (Phase 3/4).
- Add Room, Hilt, Navigation-Compose, Firebase, WebView or any dependency above.
- Add background components or periodic refresh.
- Store stream URLs or catalog data outside the app's files.
- Rehost/proxy streams.

## Done

`AGENTS.md` §6 report with build output, APK size, test results, the exact
`CatalogState` transition observed, and known gaps. Update `docs/DECISIONS.md` if you
made a judgment call.
