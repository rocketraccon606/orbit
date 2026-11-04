# Phase 3 — Netflix-style browsing, search, favourites, recents

Read `AGENTS.md`, `docs/TDD.md` (§2.4, §4.4, §4.6) and this file. Phase 2's gate must
be green. Keep the Phase 2 data layer; extend it, do not rewrite it.

## Goal

Make Home feel like the reference: a cinematic hero, large focused cards and fast
horizontal rows, all driven by pre-sorted catalog collections. Add a fully D-pad
search screen, long-press favourites, Continue Watching, and exact focus restoration.
No player work in this phase.

## Visual system (`ui/theme`)

Implement a single dark theme. Exact values:

| Token | Value |
|---|---|
| background | `#07080B` |
| surface | `#14161C` |
| surfaceHigh | `#1D2029` |
| text | `#F5F6F8` |
| textDim | `#9AA0AC` |
| accent | `#F2B36B` |
| accentDim | `#7A5A33` |
| focusRing | `#FFFFFF` |
| danger | `#E06C6C` |

- Type: system sans. Hero title 40 sp semibold, row title 20 sp, card label 14 sp,
  meta 12 sp, minimum 12 sp. Letter spacing 0.
- Spacing: screen padding 48 dp horizontal, rows 24 dp apart, cards 208×117 dp,
  12 dp gaps, 16 dp radius.
- Focus: 1.06 scale, 2 dp white ring + 8 dp accent glow drawn as a second border
  (no blur), 150 ms `animateFloatAsState`. Nothing else animates.
- Hero: 42 % height, channel logo centered-right or as a large contained image,
  vertical gradient into background, title + `language · category · country`,
  `▶ Watch` pill. Update on focus after a 120 ms debounce. If no logo, use a
  deterministic gradient from the channel id and a monogram.
- Top bar: Orbit ring mark + wordmark left; `Search` pill and `Settings` action right.
  Translucent solid `#0B0C10` (no blur). Focusable in the same vertical order.
- No gradients on every card, no shadow stacks, no infinite animation, no blur.

## Home composition

Row order, built in the UI from two sources:

1. **Continue Watching** — `recents(20)` ids, joined through the catalog in recent
   order, max 20. Hidden when empty.
2. **Favourites** — `favorites(20)` ids, added-at descending. Hidden when empty.
3. **Catalog collections** — `CatalogDao.collections()` in `position` order, max 16
   rows total including the two local rows. Fetch 40 items per row; do not fetch the
   whole catalog.

- Add `CatalogDao.channelsInOrder(ids: List<String>): List<ChannelCard>` that preserves
  the input order and silently drops unknown ids.
- A missing collection is skipped. No runtime sorting of catalog rows, ever.
- Empty state: if the catalog has zero collections, show a clear message with
  "Refresh catalog".

## Cards and rows

- `ChannelRow`: title, optional "N channels" meta, `LazyRow` with `contentPadding`
  matching screen padding, `rememberLazyListState` retained per row id.
- `ChannelCard`: logo (Coil, sized to the card, no crossfade, memory/disk caps),
  name, subtle state dot; `unverified` gets a dim dot with a tooltip/label,
  `dead` is never in a row.
- Focus: `Modifier.focusable()` + `onFocusChanged`; persistence key = collection id +
  channel id. Long-press OK (`combinedClickable(onLongClick = ...)`) toggles favourite
  and shows a short snackbar "Added to favourites" / "Removed". A small heart marker
  appears on favourited cards.
- OK plays (wire a callback to `onPlay(channel, row)`; Phase 4 supplies the receiver).
- D-pad: Left/Right within a row, Up/Down between rows, and moving up from the first
  row reaches the top bar. Focus must always be visible; scroll the focused card into
  view with 2-card look-ahead.

## Focus restoration

- `MainViewModel` holds `HomeFocus(rowId, channelId)` updated on every focus change.
- Opening a channel stores the focus and the row's scroll index.
- Returning from Search/Settings/Player restores the same row and card; request focus
  after the composition settles. If the card disappeared (recents changed), fall back
  to the first item of the same row, then the first row.
- Back from Home exits the app (default behaviour); Back elsewhere returns Home.

## Search

- One click from the top bar (or `KEYCODE_SEARCH` / `KEYCODE_ASSIST`) opens Search.
- Use a **D-pad on-screen keyboard**, not the system IME: QWERTY rows
  (`q w e r t y u i o p`, `a s d f g h j k l`, `z x c v b n m`), plus Space, Delete,
  Clear and `123`/symbols if cheap. Focusable keys, 48 dp minimum, visible focus.
  The query line shows the text plus a caret. No text field, no IME popup.
- Query updates results after 120 ms debounce, minimum 2 characters.
- Results: vertical list of wide rows (logo 64×36, name, meta, state marker), capped
  60; empty state suggests a category. `dead` results are shown dimmed with
  "Unavailable" and cannot be played. `unverified` shows "May be region-locked".
- OK plays; long-press OK favourites; Back returns to Home with restored focus.
- `SearchQuery.build(raw)` stays a pure function with unit tests (Phase 2), extended
  here if the UI needs token display.

## Settings (finish it)

Add real actions with confirmation dialogs: Clear Continue Watching, Clear Favourites
(both operate only on the user DB), Refresh catalog (calls `CatalogManager.refreshNow`,
shows progress and result), About (app version, catalog version, counts, source and
CC0-1.0 credit, "Orbit is a player and hosts no content"). Debug section: base URL,
catalog path, schema, number of channels, last switch timing placeholder. Keep D-pad
only; no switches that need touch.

## Errors and states

- Boot: downloading progress with bytes, retry on error, "Using saved catalog" notice
  when the update fails but a catalog exists.
- Home: skeleton only for the first frame (a simple row of dim placeholders, static).
- Snackbars auto-dismiss in 2 s and never steal focus.

## Tests

- JVM: home row assembly (local rows first, cap 16, empty skipped), focus-restore
  fallback rules, search result mapping, long-press toggle state.
- Instrumentation (if emulator available): DAO `channelsInOrder` preserves order and
  drops unknown ids; favourites/recents reflected in the row builder; search returns
  Malayalam results for "mal".
- Screenshots: Home with rows, focused card + hero, Search with results, favourite
  marker, Continue Watching after playing one channel (player can be a stub in this
  phase).

## Acceptance (paste real output)

```bash
source scripts/env.sh
./app/gradlew -p app :app:testDebugUnitTest --console=plain
./app/gradlew -p app :app:assembleDebug --console=plain
scripts/audit.sh
scripts/install-emulator.sh          # if the emulator exists
scripts/smoke.sh --home-only
```

Capture `artifacts/phase3-home.png`, `phase3-search.png`, `phase3-focus.png` via
`adb exec-out screencap -p`. Report the row count, first/last row titles and search
latency for "mal". Do not claim visuals you did not screenshot.

## Do not

- Implement video playback or the player overlay (Phase 4).
- Add web images, fonts, icon packs or animation libraries.
- Sort catalog rows at runtime or load the full catalog into memory.
- Add voice search, EPG, personal M3U or custom groups (later phases).

## Done

`AGENTS.md` §6 report with screenshots, test output, row/search evidence and gaps.
