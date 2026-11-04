# Orbit — Agent Operating Contract

This file binds every agent that works in this repository. Read it fully before
touching code. If a task conflicts with this contract, stop and report the conflict.

## 0. Mission

Orbit is a TV-first IPTV player for one target device: a TCL C06 Smart TV
(Android TV 11 / API 30, ~1 GB RAM, 1280x720, `armeabi-v7a`, `low_ram=true`).
It must feel fast, show healthy channels first, and do nothing when closed.

Success is measured on the device: channel switch median < 1.5 s and p90 < 3 s on
healthy streams, cold start < 3 s with a cached catalog, < 200 MB RAM while playing,
zero background components.

## 1. Hard invariants

1. **No background work.** No `Service`, `WorkManager`, `AlarmManager`, boot
   receivers, push, scheduled jobs, analytics, crash SDKs or wake locks outside the
   active player. Debug builds ship a manifest audit that fails on violations.
2. **One player.** Exactly one ExoPlayer instance exists app-wide and is reused for
   every channel. Never construct a player per screen or per switch.
3. **The catalog is pre-sorted.** The app never sorts channels at runtime. It only
   orders local favourites/recents and joins pre-ordered catalog collections.
4. **User data is separate.** Favourites, recents and watch counts live in their own
   SQLite database. A catalog download/swap must never touch it.
5. **No bundled channel list.** The app ships no channels. It downloads the catalog.
   Debug builds may import a local catalog file for testing.
6. **No re-streaming.** Orbit never proxies, transcodes, records or re-hosts content.
   It plays the source URL directly.
7. **No circumvention.** Never bypass geo-blocks, DRM, authentication or paywalls.
   Streams requiring them stay out of the catalog.
8. **No secrets.** No signing keys, tokens, TV IP addresses or serial numbers in the
   repo, logs, docs or commits.
9. **Remote-first.** Every user action must work with D-pad, OK, Back and number
   keys. Typing is a last resort.
10. **Light by default.** A new dependency requires a line in
    `docs/DECISIONS.md` stating its size and why it is worth it.

## 2. Target-device budget

| Budget | Limit |
|---|---|
| APK size | < 12 MB (no native libs unless libVLC is explicitly enabled) |
| Cold start to Home | < 3 s with cached catalog |
| RAM while playing | < 200 MB |
| Switch time | median < 1.5 s, p90 < 3 s on healthy streams |
| Idle work | zero; app process does nothing when not visible |

Measure, don't guess. Report numbers with the command that produced them.

## 3. Repository map

```
orbit/
├─ AGENTS.md                 this contract
├─ docs/                     PRD, TDD, DECISIONS, PROMPTS, TESTING
├─ prompts/                  phase briefs (source of truth for build order)
├─ curator/                  Python package + tests + fixtures
│  └─ orbit_curator/
├─ app/                      Gradle project, module `app`
│  └─ app/src/main/java/app/orbit/tv/
├─ scripts/                  env, serve, build, deploy, bench, audit
└─ .github/workflows/        scheduled curator + Pages publish
```

App package: `app.orbit.tv`. Single activity. Modules inside the app source tree are
packages, not Gradle modules: `catalog`, `userdata`, `player`, `ui`, `settings`,
`debug`. Do not add Gradle modules without a decision entry.

## 4. Toolchain

All commands run from the repo root. `scripts/env.sh` exports the local JDK 17,
Android SDK and Python; never install global tools and never assume Android Studio.

```bash
source scripts/env.sh                 # JAVA_HOME, ANDROID_HOME, PATH, PYTHON
./app/gradlew -p app :app:assembleDebug
$PYTHON -m pytest curator/tests -q
```

Gradle downloads its distribution and Maven dependencies on first run. Do not commit
`~/.gradle`, SDK files, APKs, catalogs or build outputs.

## 5. Coding rules

- Kotlin, no DI framework, no reflection. Wire dependencies in `OrbitApp`.
- No IO on the main thread. Catalog download and DB reads run on `Dispatchers.IO`.
- No runtime work proportional to the whole catalog. Page from SQLite; keep lists small.
- Compose for TV: `LazyRow`/`LazyColumn` with stable keys, D-pad focus handling,
  no blur, no infinite animation, no gradient recomposition loops. Focus must be
  obvious from 3 m away.
- Prefer small, plain Kotlin types over abstractions. Three similar lines beat a
  premature helper. No comments that restate the code.
- Every failure ends in a clear message plus a next action — never a dead end.
- Debug-only code lives behind `BuildConfig.DEBUG` or the `debug` source set.
- Never log stream URLs in release builds.

## 6. Definition of done (every task)

1. `scripts/check.sh` passes (format/compile/unit tests/manifest audit).
2. The phase acceptance commands pass and their output is pasted in the report.
3. `docs/DECISIONS.md` updated for any non-obvious choice.
4. No unrelated refactors in the diff.
5. Report in this exact shape:

```
## What changed
## Evidence (commands + output)
## Known gaps / risks
## Next phase readiness
```

## 7. Phase discipline

Work on exactly one phase brief at a time, in order. Do not start the next phase,
do not "while I'm here" refactor, and do not expand P0 scope. If a requirement is
ambiguous, choose the lightest option consistent with this contract, record it in
`docs/DECISIONS.md`, and flag it in the report. Stop and ask only when a choice is
irreversible or affects the target device.

## 8. Device guardrails (ADB)

- Operate only on package `app.orbit.tv` and its data.
- Never uninstall, disable or clear other apps; never factory reset; never change
  system settings; never push files outside the app's directories.
- Always pass `-s <serial>` when more than one device is attached.
- If ADB reports `unauthorized` or `offline`, stop and ask the human.
- Do not store the TV address or serial anywhere in the repo.

## 9. Content and legal guardrails

- The curator only consumes public metadata (default: iptv-org). Record the source
  license and credit it in the catalog `meta` table and the app's About screen.
- Never scrape at app runtime. The app only checks `version.json`, downloads the
  catalog, loads logos and plays streams.
- Do not add channels that require authentication or DRM.
