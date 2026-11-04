# Phase 4 — Player, fallback chain and fast switching

Read `AGENTS.md`, `docs/TDD.md` (§4.2, §4.5) and this file. Phase 3's gate must be
green. This is the speed-critical phase; keep it small and instrumented.

## Goal

Full-screen live playback with a TV overlay, instant visual switching, automatic
stream fallback, channel navigation, last-channel toggle, a favourites/recents quick
panel, and per-switch timing logs. One ExoPlayer for the process. No background work.

## `player/PlaybackController.kt`

Owns the one `ExoPlayer`, the current queue, and all timing.

```kotlin
data class PlaybackRequest(
    val channelId: String,
    val queueIds: List<String>,   // ordered ids of the originating row
    val queueIndex: Int,
    val rowTitle: String
)

sealed interface PlaybackUi {
    data class Loading(val channel: ChannelCard) : PlaybackUi
    data class Playing(val channel: ChannelCard, val streamIndex: Int, val note: String?) : PlaybackUi
    data class Unavailable(val channel: ChannelCard, val message: String) : PlaybackUi
}
```

Public API: `play(request)`, `next()`, `previous()`, `jumpTo(index)`, `toggleLastChannel()`,
`retry()`, `onAppBackgrounded()`, `onAppForegrounded()`, `ui: StateFlow<PlaybackUi>`,
`player: ExoPlayer` (for `PlayerView` only), `lastSwitchMs` for debug.

### Player construction

- `ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context))`
  with a shared `OkHttpClient` (connection pool from `AppContainer`).
- `DefaultLoadControl`: min 1_500 ms, max 8_000 ms, playback 500 ms, after rebuffer
  1_000 ms, `setPrioritizeTimeOverSizeThresholds(true)`, target buffer 8 MB.
- Build the player lazily on the first `play`, release on `onAppBackgrounded`, recreate
  on demand. Never create more than one instance.

### Per-stream media source

For each attempt build a fresh `MediaSource`:

- `OkHttpDataSource.Factory(okHttpClient)` with the stream's `User-Agent` (default
  `Orbit/0.1`) and `Referer` when present; connect 10 s, read 15 s, cross-protocol
  redirects allowed.
- HLS: `HlsMediaSource.Factory(factory).setAllowChunklessPreparation(true)`.
- Other: `DefaultMediaSourceFactory(context).setDataSourceFactory(factory)`.
- `MediaItem.Builder().setUri(stream.url).setMediaId(stream.id).build()`.

### Switch sequence

1. Record `t0 = elapsedRealtime()`, show `Loading(channel)` immediately (logo overlay,
   not a spinner), keep the previous frame on screen.
2. `player.stop()`, `setMediaSource(source, true)`, `prepare()`, `playWhenReady = true`.
3. Before prepare, cap video at 1.2 Mbps / 960×540. On the first rendered frame,
   restore adaptive defaults (once per switch).
4. On `onRenderedFirstFrame`, log
   `OrbitPerf switch channel=<id> ms=<elapsed> attempt=<n>` (debug only), emit
   `Playing`, touch the recent DB and increment the watch count.
5. On `onPlayerError` or watchdog (not `READY`/rendered within 6 s), advance to the
   next stream in `ChannelDetail.streams` (skip `dead`), show a non-blocking note
   "Trying backup stream", and restart the sequence. After the last stream emit
   `Unavailable` with Retry and Back. Never crash, never loop forever.
6. A 250 ms coroutine ticker drives the watchdog and stops in `onCleared`/release.

### Navigation

- Queue = the originating row's ids in display order (`PlaybackQueue`).
- `next`/`previous` wrap; `jumpTo(n)` clamps. On wrap, keep a subtle note.
- `toggleLastChannel`: remember the previous channel id on every successful play and
  swap between it and the current channel.
- Number keys 1-9 map to `queueIndex = n - 1` (the current row), no wrap.
- Channel change must reuse the player and the same `PlayerView`; do not recreate the
  screen.

## `ui/PlayerScreen.kt`

- `AndroidView { PlayerView }` using `SurfaceView`, `useController = false`,
  `setShowBuffering(SHOW_BUFFERING_NEVER)`, `setKeepContentOnPlayerReset(true)`.
- Keep screen on while this screen is composed (`LocalView.keepScreenOn`); no wake lock
  permission.
- Overlay (Compose):
  - top-left: channel number, name, `language · category`, stream quality/backup note;
  - top-right: favourite state, clock;
  - bottom: progress-free live badge (`LIVE`), hint line
    `OK: panel   CH+/−: channel   Back: list`;
  - auto-hide after 4 s; any D-pad key shows it again.
- Keys (activity `onPreviewKeyEvent` / screen handler):
  - `DPAD_UP`, `CHANNEL_UP`, `MEDIA_NEXT` → previous? Use UP = next, DOWN = previous
    (channel numbers increase downward in the row) — document the mapping in-code;
  - `CHANNEL_DOWN`/`DPAD_DOWN` → previous;
  - `KEYCODE_0..9` → jump within the current row;
  - `KEYCODE_LAST_CHANNEL` → toggle last channel;
  - `DPAD_CENTER`: hidden → show overlay; visible → open quick panel; panel open →
    select focused item;
  - `BACK`: panel open → close; overlay visible → hide; otherwise return Home.
- Switching must never show a black screen or a spinner. Logo overlay + last frame only.
- `Unavailable`: full overlay with channel logo, message, `Retry` and `Back`, D-pad
  focus on Retry.

### Quick panel

Bottom sheet-style static panel (no animation): two rows, `Favourites` and `Recent`,
from `UserDao` joined through `CatalogDao.channelsInOrder`, max 12 each, 96 dp wide
cards. Focus enters on the first item; Left/Right moves, Up/Down changes row, OK
plays and closes, Back closes. Empty state: "No favourites yet — long-press OK on a
card to add one."

## Recents / last channel

- On successful first frame: `touchRecent(channelId)`, `incrementWatchCount`, and set
  `lastChannelId` to the channel that was playing before this one (in memory only,
  reset on app restart).
- Home's Continue Watching must reflect the new order on return.

## Timing and debug

- `SwitchTiming`: ring buffer of the last 50 switch durations, exposed in the debug
  Settings section. Log only in debug builds.
- `settings` debug entry: "Play test stream" using
  `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` (HLS, public test asset).
- Cold-start timing: log `OrbitPerf coldstart ms=<n>` from `MainActivity.onCreate` to
  the first Home composition (debug only).

## Tests

- JVM: `PlaybackQueue` next/prev/wrap/jump/number mapping; last-channel toggle;
  fallback decision (advance, skip dead, exhaust); timing parser used by
  `scripts/bench.sh`.
- Instrumentation (if emulator decodes): play the test stream, assert no crash and
  `Playing` within 20 s; force a bad first stream in a fixture and assert fallback.
  If software decoding cannot render video, record the blocked result honestly.

## Acceptance (paste real output)

```bash
source scripts/env.sh
./app/gradlew -p app :app:testDebugUnitTest --console=plain
./app/gradlew -p app :app:assembleDebug --console=plain
scripts/audit.sh
scripts/install-emulator.sh
scripts/smoke.sh --player
adb -s emulator-5554 logcat -d -s OrbitPerf | tail -30
```

Report: switch durations observed, fallback behaviour, Back focus restoration, and
whether video actually rendered in the emulator. Do not claim playback if only audio
or a black surface was observed.

## Do not

- Create a second player, preload with extra decoders, or add a background service.
- Show a blocking spinner or black screen between channels.
- Retry a dead URL forever; cap attempts at the channel's stream list.
- Add prefetch yet (Phase 5) or libVLC.
- Change the catalog schema.

## Done

`AGENTS.md` §6 report with logs, test results, screenshots of overlay and quick panel,
and gaps.
