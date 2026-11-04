# Phase 7 — TV deployment, CI publishing and hardening

Read `AGENTS.md`, `docs/TDD.md` (§5), `docs/TESTING.md` (§3) and this file. Phase 6
must be green. No new product features in this phase.

## Goal

Make the app publishable and deployable: guarded ADB scripts, a scheduled curator
that publishes to GitHub Pages with a safety gate, manifest/privacy hardening, a TV
runbook, and a signed (debug) APK ready to sideload. **Do not touch the TV until the
human connects and authorizes it.**

## 1. Scripts (from repo root, all source `scripts/env.sh`)

### `scripts/build-app.sh`
- Cleans nothing by default; runs `:app:assembleDebug`.
- Prints APK path, bytes, sha256 and the 12 MB budget check.
- Supports `--release` later; debug signing is acceptable for v1.

### `scripts/deploy-tv.sh`
Ordered, abort-on-failure:
1. Resolve the target: `ORBIT_SERIAL` if set, else require exactly one device in
   `adb devices` with state `device`. Any `unauthorized`/`offline`/multiple devices →
   exit 2 with a clear human instruction.
2. Refuse to run if the serial contains "emulator" unless `--allow-emulator`.
3. Build the debug APK.
4. `adb -s <serial> install -r <apk>` — never uninstall, never clear data, never touch
   any other package.
5. Launch `app.orbit.tv/.MainActivity` with an optional
   `--es catalog_base_url "$ORBIT_CATALOG_URL"` when `ORBIT_CATALOG_URL` is a LAN URL.
6. Stream `logcat -s OrbitPerf AndroidRuntime` for 20 s or until `--no-logs`.
7. Print the package version and the installed APK path.

### `scripts/import-catalog.sh`
- For debug builds: `adb shell run-as app.orbit.tv` copy `curator/dist/catalog.db` into
  `files/catalog.db`, then force-stop and relaunch. This is the offline path when
  Pages is not yet configured. Never pushes outside the app's directory.

### `scripts/bench.sh N`
- Works on emulator or TV via the same device resolution as deploy.
- Drives channel-up N times with a settle delay, dumps `OrbitPerf switch` lines and
  prints count/median/p90/min/max plus the over-3 s count. `--cold` measures cold
  starts. Exits non-zero if no samples.

### `scripts/sweep.sh`
- Debug-only app mode: launch with `--ez sweep true` (guard with `BuildConfig.DEBUG`).
  The app cycles the visible row's channels with an 8 s dwell, logs
  `OrbitPerf sweep channel=<id> ok=<0|1> ms=<n>` and stops after N channels.
- The script parses the log and writes `artifacts/sweep.csv` + a success percentage.
- If implementing sweep mode would delay the phase, provide a manual script that
  navigates with `adb input` and parses `OrbitPerf`, and say so.

### `scripts/perf.sh`
- Samples `dumpsys meminfo app.orbit.tv` and `dumpsys gfxinfo` every 10 s during a
  scripted surf, writes a CSV, and prints start/end memory.

### `scripts/audit.sh`
- Parses the merged manifest (or `apkanalyzer manifest print`) and fails on
  `service`, `receiver`, `provider`, `WorkManager`, `AlarmManager`, `firebase`,
  `analytics`, `WAKE_LOCK` or any permission beyond INTERNET/ACCESS_NETWORK_STATE.
- Fails if the APK exceeds 12 MB.
- Prints a short summary.

## 2. CI publishing

Create `.github/workflows/catalog.yml`:

- Triggers: `schedule` every 6 hours, `workflow_dispatch`, and push to `main` only
  for `curator/**`. `concurrency` group `catalog-publish`, cancel in progress false.
- Job: checkout, Python 3.12, `pip install pytest`, run `pytest`, fetch the current
  published `report.json` and `state/health.json` from Pages (curl, tolerate 404 on
  first run), run a full build with `--previous-report`, publish with
  `actions/configure-pages`, `actions/upload-pages-artifact`, `actions/deploy-pages`
  (Pages source must be "GitHub Actions").
- Cache the API cache directory keyed by date; do not cache `catalog.db`.
- Upload `catalog.db.gz`, `version.json`, `report.json`, `state/health.json`.
- On gate failure: upload the report as a workflow artifact, do not deploy.
- Permissions: `contents: read`, `pages: write`, `id-token: write`.
- One-time human setup: create `github.com/rocketraccon606/orbit` (private or public),
  enable Pages → GitHub Actions, push `main`. Do not push without human approval and
  do not add secrets.

## 3. Hardening

- Remove all debug-only paths from release: no intent extras, no test stream, no
  timing ring buffer, no verbose URLs. Verify with a release build.
- Confirm cleartext is allowed only for playback; catalog/metadata calls stay HTTPS.
- Confirm no analytics, ads, crash SDKs, WebView or accounts.
- About screen: app version, catalog version and counts, source (iptv-org), license
  (CC0-1.0), "Orbit hosts no content and is a player only", and a takedown/removal
  contact placeholder the human must fill (do not invent an email).
- Add `docs/RUNBOOK.md`: install, first launch, catalog offline import, update,
  rollback to the previous APK, how to read `OrbitPerf`, and what to do when the
  catalog gate fails.
- Add `docs/PRIVACY.md`: no data collected, network endpoints used, local storage
  contents, no accounts/analytics.
- Add `.gitignore`: Gradle, build outputs, SDK, local properties, keystores, catalogs,
  artifacts, `*.db`, `.cache`, `.state`, OS junk. Never commit a keystore or TV IP.

## 4. TV setup (human steps, document; do not perform)

1. Settings → About → tap build number to enable Developer options.
2. Enable USB/network ADB debugging.
3. Find the TV IP; from the laptop run `adb connect <ip>:5555`.
4. Accept the RSA fingerprint on the TV ("always allow").
5. `adb devices` must show `device`. If `unauthorized`/`offline`, stop and ask the
   human; never retry blindly.
6. Deploy with `ORBIT_SERIAL=<serial> scripts/deploy-tv.sh`.
7. Run `scripts/bench.sh 30` and `scripts/sweep.sh`; record results.

## 5. Acceptance (paste real output)

```bash
source scripts/env.sh
scripts/check.sh
scripts/build-app.sh
scripts/audit.sh
ORBIT_SERIAL=not-a-device scripts/deploy-tv.sh   # must fail safely with exit 2
python - <<'PY'
import pathlib
try:
    import yaml
    yaml.safe_load(pathlib.Path(".github/workflows/catalog.yml").read_text())
    print("workflow yaml ok")
except ImportError:
    print("pyyaml not installed; skipped")
PY
```

CI cannot be fully validated without the human's GitHub repo; state that clearly.
Do not claim a successful Pages deploy.

## Do not

- Push to GitHub, create repositories, add secrets or change Pages settings.
- Install anything on the TV or change TV settings without the human.
- Uninstall/disable/clear other apps or push files outside `app.orbit.tv` data.
- Host, proxy or record streams; add analytics or crash reporting.
- Raise the APK budget or add dependencies to pass your own checks.

## Done

`AGENTS.md` §6 report plus: APK path/sha256/size, script dry-run proof, audit output,
the exact remaining human steps, and a list of what is still BLOCKED (TV connection,
Pages repo, signing key).
