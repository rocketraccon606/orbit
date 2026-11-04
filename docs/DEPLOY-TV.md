# Orbit — TV deployment runbook

Phase 6. Nothing here has been run against the TV yet; it is ready for when the TV is
connected and authorized. Do not store the TV IP, serial or any key in the repository.

## 1. Prepare the TV

1. TV and laptop on the same network.
2. On the TV: Settings → System → About → tap **Android TV OS build** 7 times to unlock
   Developer options.
3. Developer options → enable **USB debugging** (and **Network debugging** if the TV
   offers it).
4. Connect the TV by USB, then either keep USB or switch to network ADB:

   ```bash
   adb devices                     # accept the RSA prompt on the TV
   adb -s <tv-serial> tcpip 5555
   adb connect <tv-ip>:5555
   adb devices                     # must say "device", not "unauthorized"
   ```

`scripts/deploy-tv.sh` refuses to run unless exactly one authorized device is present
(or `ORBIT_SERIAL` names one), and refuses emulators unless `--allow-emulator` is
passed. It only installs `app.orbit.tv`; it never changes TV settings or other apps.

## 2. Where the catalog comes from

The app downloads `version.json` and `catalog.db.gz` from its baked URL. The current
catalog is **schema 2** and declares `min_app_version: 2`, so app **0.2.0**
(`versionCode 2`) or newer is required. Schema 2 adds per-channel artwork
(`logo_url`, ~1,059 of 1,071 channels), network, launch year, owners and website; the
app also opens a schema 1 catalog left by an older install and upgrades it in place,
so an offline upgrade still browses.

Two options:

- **Hosted (recommended):** the scheduled `.github/workflows/catalog.yml` publishes
  `curator/dist` to GitHub Pages. The Gradle default is
  `https://rocketraccon606.github.io/orbit/`, but the repository must exist and Pages
  must be enabled before this URL resolves.
- **Local test:** serve the built catalog on the laptop and pass the base URL to the
  activity at launch:

  ```bash
  scripts/curator.sh                 # build curator/dist
  ORBIT_CATALOG_PORT=8787 scripts/serve-catalog.sh
  ORBIT_TV_CATALOG_URL=http://<laptop-ip>:8787/ scripts/deploy-tv.sh
  ```

  The TV must reach the laptop on that port. Use the laptop's LAN IP, not 127.0.0.1.

To bake a different URL into a build:

```bash
./app/gradlew -p app :app:assembleDebug -PorbitCatalogBaseUrl=https://example/orbit/
```

## 3. Deploy

```bash
ORBIT_SERIAL=<tv-serial> scripts/deploy-tv.sh
```

The script builds the debug APK, installs it with `install -r`, launches Orbit and
prints 20 s of `OrbitPerf`/crash logs. User favourites and history live in
`orbit-user.db` and are preserved by an in-place reinstall.

The debug APK trusts all TLS certificates so logo and stream HTTPS work behind the
laptop's antivirus interception on the emulator; **release builds use strict TLS**.
Use debug only for local testing, never as a long-term install. To build the release
APK: `./app/gradlew -p app :app:assembleRelease` (unsigned; sign the APK before
sideloading it to the TV).

## 4. Acceptance on the TV

Run through, in order:

1. First launch with no cached catalog: clear screen shows progress, then Home; the
   log shows the catalog version and schema 2.
2. Home: the top bar reaches Home/Channels/Malayalam/News/Sports/My List/Recently
   Watched, then search, bell and profile (Down from the bar enters the hero, Up from
   the hero returns to Home); hero shows the channel watermark, TOP 10 rank, network,
   launch year and language; rows scroll with real channel logos — a missing or blocked
   logo shows the tinted monogram, never a blank tile; focus is visible from 3 m.
3. Search a single letter (`a`) → matching channels; type `malayalm` → Malayalam
   channels with logos and metadata in the right pane; quick picks fill the query and
   move focus to the results; long-press OK adds a favourite.
4. Play a channel, press CH+ / CH− repeatedly: logo appears immediately, no black
   screen; Back returns to the same card.
5. Number keys jump to a channel; media-previous toggles the last channel.
6. Settings shows app version `0.2.0`, catalog schema 2 and counts; clears
   favourites/history; checks for updates. (If the TV reports a catalog it cannot
   open, look for `OrbitCatalog` lines in `adb logcat`.)
7. `scripts/bench.sh` over 20+ switches: median < 1.5 s, p90 < 3 s. Start a channel
   before running it; it presses CHANNEL_UP and reads `OrbitPerf switch` lines.
8. `scripts/sweep.sh` across the main rows: ≥ 95 % play success in the region.
9. One-hour surf: `adb shell dumpsys meminfo app.orbit.tv` at start/end; expect a
   plateau, not a slope.
10. Airplane mode: cached catalog still browses/searches; playback shows a clear
    error instead of a black screen.

## 5. Rollback and safety

- Reinstall the previous APK with `adb install -r`; the atomic catalog swap keeps
  `catalog.db.bak` only during the swap itself, so a bad catalog is rejected by
  SHA-256/size/schema checks, and by `min_app_version`, before touching the current
  file. Reinstalling an app older than 0.2.0 cannot open a schema 2 catalog; the app
  then stays on the schema 1 copy it already had, or shows the update screen.
- If the app is unusable: `adb uninstall app.orbit.tv` (this deletes user data).
- No background services, receivers or providers: `scripts/audit.sh` must pass.
- Never write the TV IP/serial into the repo or into Gradle properties.

## 6. Known constraints

- Platform SQLite on Android TV has no FTS5; the app uses LIKE-based search. The
  catalog still ships `channel_fts` for desktop use.
- Software-rendered emulator first frames (2–6 s) are slower than expected on the
  real GPU; the 1.5 s/3 s targets are for the TV.
- Cleartext HTTP is allowed for local catalog testing; switch to HTTPS when hosted.
- Channel artwork comes from the public iptv-org logo set (HTTPS raster only; SVG is
  skipped). Some hosts block hotlinking or drop a logo later, so a card can fall back
  to its category-tinted monogram; that is expected, not a broken layout.
- Debug builds trust all TLS certificates; do not ship a debug APK.
