# Orbit — Build Playbook (how to command the builder agent)

This is the human-facing control manual for the agent-driven build. The technical
contract is `AGENTS.md` + `docs/TDD.md`; the executable instructions are the numbered
files in `prompts/`.

## 1. One-time setup

1. Open this folder (`orbit/`) as the workspace root in the builder tool (Qoder,
   Cursor, Claude Code, etc.).
2. Confirm the builder can see `AGENTS.md` and `docs/`.
3. Make sure the local toolchain is present at `../toolchain` (JDK 17, Android SDK,
   Python) or set `ORBIT_TOOLCHAIN` to its path.
4. The repo is local-only until Phase 6. Do not push anywhere before the human
   approves and creates `github.com/rocketraccon606/orbit` (or the chosen repo name).

## 2. Kickoff message (paste with Phase 1)

```text
You are the builder agent for Orbit. Work in this repository only.

Before doing anything:
1. Read AGENTS.md in full.
2. Read docs/TDD.md and docs/PRD.md.
3. Read prompts/01-curator.md.

Then implement exactly that phase. Do not start later phases. Do not add
dependencies or scope without a decision-log entry. Run every acceptance command
in the prompt and paste the real output.

If something is ambiguous, choose the lightest option consistent with AGENTS.md,
write it in docs/DECISIONS.md, and flag it in your report. Stop and ask only if a
choice is irreversible or device-specific.

Finish with the report format from AGENTS.md section 6.
```

For each later phase, replace the prompt filename and add:

```text
Phase <n-1> is complete and its tests pass. Do not redo or restyle it. Stay inside
the current phase's files and acceptance criteria.
```

## 3. Phase order and gates

| Order | Prompt | Depends on | Gate |
|---|---|---|---|
| 1 | `01-curator.md` | — | `pytest` green; real `catalog.db.gz` built; report + validate pass |
| 2 | `02-app-foundation.md` | 1 | APK assembles; boot → Home with local catalog; DAO/instrumentation tests pass |
| 3 | `03-browse.md` | 2 | Home rows, hero, focus, search, favourites and recents verified on emulator |
| 4 | `04-player.md` | 3 | Playback, fallback, keys, Back restore, timing logs verified |
| 5 | `05-speed.md` | 4 | Prefetch + LoadControl tuned; benchmark numbers recorded |
| 6 | `06-emulator-verify.md` | 5 | Full TESTING.md §2 checklist passes; screenshots in `artifacts/` |
| 7 | `07-tv-deploy.md` | 6 | Guarded deploy scripts, CI publish, manifest audit, TV runbook ready |

Never run two phases in one agent session. A phase that fails its gate is fixed in
place, not papered over by starting the next one.

## 4. Architect review checklist (after every phase)

Reject the phase if any answer is "no":

- [ ] Did the agent paste real command output, not a claim?
- [ ] Does `scripts/check.sh` pass from a clean shell?
- [ ] Any new dependency, service, receiver, permission or Gradle module? Justified?
- [ ] Is runtime work proportional to the catalog? (It must not be.)
- [ ] Does the diff stay inside the phase?
- [ ] Is user data still in a separate DB from the catalog?
- [ ] Is the UI still fully D-pad operable, with an obvious focus state?
- [ ] Were logs free of stream URLs in release paths and free of secrets?
- [ ] Is every new failure path visible to the user with an action?
- [ ] `docs/DECISIONS.md` updated for every judgment call?

## 5. Commands the reviewer runs

```bash
source scripts/env.sh
scripts/check.sh                     # curator tests + app build/tests + audit
scripts/curator.sh --fast            # cheap catalog for UI work
scripts/serve-catalog.sh             # :8787 for emulator/debug builds
scripts/emulator.sh                  # create/start Android TV API 30 AVD
scripts/build-app.sh                 # debug APK + size report
scripts/install-emulator.sh          # install + launch with local catalog
scripts/smoke.sh                     # scripted smoke + screenshots
scripts/bench.sh artifacts/*.log     # switch median/p90
```

## 6. Retry protocol

When a phase fails, send one precise repair instruction, not a re-prompt:

```text
Gate failed: <command> → <first error line>.
Fix only <file/behavior>. Constraints: <AGENTS.md rule that was violated>.
Re-run <acceptance command> and paste the output. Do not change unrelated files.
```

If the same gate fails twice, stop and switch from "implement" to "diagnose":
require the agent to explain root cause and propose two options with cost/risk, then
the human chooses.

## 7. Human approval gates

The agent must stop and request approval before:

1. Creating or pushing a GitHub repository, or adding CI secrets.
2. Installing anything on the TV, or changing TV settings (never change settings).
3. Adding any dependency not listed in `docs/TDD.md`.
4. Changing the catalog schema or `version.json` contract.
5. Anything that re-hosts, proxies or records content.
6. Deleting user data (clear favourites/recents) outside the in-app action.

## 8. Evidence and handoff

Keep per phase in `artifacts/`:

```
artifacts/phase2-build.log
artifacts/phase3-home.png
artifacts/phase4-switch.log
artifacts/phase5-bench.txt
artifacts/phase6-sweep.csv
```

The final handoff must state: what works, what is blocked, measured numbers, the
exact APK path and hash, the catalog URL/version, and the TV setup steps that still
need a human (enable debugging, accept RSA prompt).
