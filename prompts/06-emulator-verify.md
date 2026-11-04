# Phase 6 — Emulator verification and iteration

Read `AGENTS.md`, `docs/TESTING.md` and this file. Phases 1–5 must be green. This
phase is a test-and-fix phase: no new features, no restyling, no dependency changes.

## Goal

Prove the golden paths and the failure paths on an Android TV API 30 emulator, capture
evidence, and fix only defects found. Everything here is a rehearsal for the TCL TV in
Phase 7.

## 1. Emulator setup

```bash
source scripts/env.sh
sdkmanager --list_installed
sdkmanager "emulator" "system-images;android-30;android-tv;x86_64"
avdmanager create avd -n "$ORBIT_EMULATOR" -k "system-images;android-30;android-tv;x86_64" -d tv_720p
emulator -avd "$ORBIT_EMULATOR" -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
```

- If the `android-tv` image is unavailable, use the closest API 30 TV image and record
  the substitution. Do not silently use a phone image; the UI and input model differ.
- Set ~1 GB RAM in the AVD config to approximate the TV. 1280×720, 213 dpi.
- Wait for `sys.boot_completed=1` before installing.
- If hardware acceleration is unavailable, run with software GPU and note it; video
  decode may be software-only, which affects switch timings. Say so.

## 2. Install and serve the catalog

```bash
scripts/curator.sh build --fast --out curator/dist      # or use the real catalog
scripts/serve-catalog.sh                                # separate terminal
scripts/install-emulator.sh                             # install + intent extra URL
```

Verify the app reaches Home without a network catalog after the first install
(`--es catalog_base_url http://10.0.2.2:8787/`). Then test the update path by bumping
`version.json` and confirming a silent swap while the old catalog stays usable.

## 3. Checklist (record PASS/FAIL/BLOCKED per item)

1. Fresh install, no catalog → boot progress → Home; catalog download works; second
   launch is offline-capable.
2. Cold start with cached catalog < 3 s (three runs, `OrbitPerf coldstart`).
3. Hero updates on focus; focus ring visible; rows scroll; top bar reachable.
4. Search "mal" returns Malayalam channels; "malayalm" still returns them; "dead"
   result shows Unavailable and cannot play.
5. Long-press OK favourites a card; it appears in the Favourites row and player panel;
   unfavourite removes it.
6. Play three channels; logo appears immediately; no black screen; no spinner.
7. Fallback: install the fixture catalog where the first stream 404s; expect a
   "Trying backup stream" note and playback on attempt 2.
8. All-dead channel shows the Unavailable overlay with Retry/Back; app stays alive.
9. Back from player restores the exact row and card; CH+/− cycles; number key jumps;
   last-channel toggles the previous channel.
10. Settings actions: clear recents, clear favourites, refresh catalog (with network
    off shows a clear error and keeps the old catalog), About shows CC0-1.0 credit.
11. Airplane mode: browse/search/favourites work; playback shows a clear offline
    message; no crash.
12. One-hour loop: memory return to baseline; `dumpsys activity services app.orbit.tv`
    shows no services.
13. `adb shell am force-stop app.orbit.tv` at any point; relaunch recovers.
14. Rotate/resize? TV is fixed landscape; confirm no crash on config change.

## 4. Evidence

For each item, capture a screenshot (`adb exec-out screencap -p > artifacts/...`) or
log snippet. Save the catalog version, emulator image, APK sha256 and the benchmark
table. Update `artifacts/phase6-report.md` with a PASS/FAIL/BLOCKED table.

## 5. Fix policy

- Fix defects in place, add a unit/instrumentation test when the defect is logic.
- Do not add features to make a test pass; adjust the test only if it contradicts
  `AGENTS.md` or the TDD, and record why.
- Re-run the full checklist after any fix that touches the player or catalog swap.
- If a failure is environment-only (software decoder, emulator network), mark it
  BLOCKED with evidence and leave a note for the TV phase.

## Acceptance

All checklist items PASS or explicitly BLOCKED with reason; `scripts/check.sh` green;
`artifacts/phase6-report.md` complete.

## Done

`AGENTS.md` §6 report plus the checklist table and evidence paths.
