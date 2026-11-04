# Phase 1 — Curator

Read `AGENTS.md`, `docs/TDD.md` (§2, §3) and this file before writing code.

## Goal

Produce a scheduled pipeline that turns public IPTV metadata into one validated,
health-checked, pre-sorted `catalog.db` plus `catalog.db.gz`, `version.json`,
`report.json` and `state/health.json`. The TV app in later phases consumes this file
and never sorts or checks anything.

Python 3.12, **standard library only** (`urllib`, `sqlite3`, `gzip`, `json`, `hashlib`,
`concurrent.futures`, `argparse`, `ssl`, `datetime`, `re`). `pytest` is the only dev
dependency. No `requests`, no pandas, no ORMs.

## Files to create

```
curator/pyproject.toml
curator/orbit_curator/__init__.py
curator/orbit_curator/__main__.py      CLI
curator/orbit_curator/config.py        paths, constants, source definitions
curator/orbit_curator/fetch.py         cached API/playlist download
curator/orbit_curator/parse.py         tolerant M3U parser (used by P1 and fixtures)
curator/orbit_curator/normalize.py     names, ids, dedupe
curator/orbit_curator/classify.py      category/language/country + confidence
curator/orbit_curator/health.py        three-state checks + history merge
curator/orbit_curator/score.py         stream/channel scores, stream ordering
curator/orbit_curator/collections.py   ordered collections, country rows
curator/orbit_curator/build.py         orchestration + SQLite writer + FTS
curator/orbit_curator/validate.py      publish gate
curator/orbit_curator/report.py        report.json
curator/orbit_curator/config/editorial.json
curator/orbit_curator/config/rules.json
curator/orbit_curator/config/sources.json
curator/tests/...                      pytest suite
curator/tests/fixtures/...             small channels/streams/countries/languages/categories
```

The existing prototype at `../live-tv/build_playlist.py` contains a curated Malayalam
and global channel ID list. Copy those IDs into `config/editorial.json` (with ordering
as the boost order). Do not import or depend on the prototype at runtime.

## CLI

```bash
python -m orbit_curator build \
  --out curator/dist \
  --cache curator/.cache \
  --history curator/.state/health.json \
  --previous-report <optional report.json> \
  [--fast] [--offline] [--max-checks N] [--workers 24] [--timeout 8] \
  [--source-dir <dir with channels.json etc.>]

python -m orbit_curator validate --catalog DIR/catalog.db --report DIR/report.json \
  [--previous-report <path>]
```

- `--fast`: no network health checks; every candidate stream is `unverified` unless
  history says otherwise. Still builds a complete, valid catalog for UI work.
- `--offline`: use cache only; fail clearly if cache is missing.
- `--max-checks N`: cap health checks (used by the phase gate and tests).
- `build` writes artifacts, then runs validation. Exit 1 on gate failure (artifacts
  remain for inspection). Exit 0 only when the publish gate passes.

## Inputs (iptv-org API, CC0-1.0)

`https://iptv-org.github.io/api/{channels,streams,countries,languages,categories}.json`.
Cache for 24 h; never refetch unchanged files (ETag or SHA-256). Send
`User-Agent: OrbitCurator/1.0 (+https://github.com/rocketraccon606/orbit)`.

Channel fields used: `id, name, alt_names, logo, country, categories, languages,
is_nsfw, closed, replaced_by, website`. Stream fields: `channel, url, referrer,
user_agent, quality, feed`.

Exclude: NSFW, closed, non-http(s), YouTube, missing channel, malformed URL.

## Candidate selection (bounded work)

1. All Malayalam-language channels plus those in `editorial.malayalam_ids`.
2. India: editorial first, then category rank, cap 520.
3. World: editorial globals plus up to 3 channels per country from a country pool
   (exclude `IN`).
4. Up to 3 candidate streams per channel: highest declared quality first, dedupe by
   URL, prefer `http(s)`, stop before checking anything for excluded channels.

Targets: ~1,100 candidate channels, ~2,500 checked URLs, catalog gz < 8 MB.

## Health check

- 8 s timeout, 24 workers, one quick retry only for connection reset, otherwise no
  retries. SSL verification stays **on**.
- HLS: GET manifest; if master (`#EXT-X-STREAM-INF`), choose the lowest BANDWIDTH
  variant with `urljoin`; GET that variant; GET the first non-tag URI segment (≤64 KB).
  Success requires bytes. If `#EXT-X-KEY` uses `SAMPLE-AES` or a DRM scheme, do not
  count it as playable (soft failure).
- Direct TS/MP4/other: require bytes; accept TS sync byte `0x47`, MP4 `ftyp`, or a
  video/audio content type.
- Classification of results:
  - success → `alive`, `hard_fails = 0`, record latency and approximate resolution;
  - HTTP 403/451/407, timeout, TLS/DNS-unstable, redirect loop, first hard failure →
    `unverified`;
  - 404 / permanent DNS failure / invalid body → hard failure; **two consecutive hard
    failures across runs** → `dead`.
- Merge history by URL; do not delete channels because of one bad run. `--fast` keeps
  history states and marks unknown URLs `unverified`.
- Record per-stream latency (TTFB) and resolution estimate from manifest (`RESOLUTION`
  or `BANDWIDTH`, or quality tag).

## Classification

Metadata first, rules second. `config/rules.json` holds keyword rules and explicit
Malayalam rules. Category buckets: News, Entertainment, Movies, Music, Sports, Kids,
Documentary, Religious, Other. Language/country from the API with name resolution.
Malayalam = language `mal`, editorial list, or explicit keyword rule; the editorial
list always wins. Missing country → `ZZ` / "International". Low-confidence Indian
channels go to `india-other`. No ML.

## Score and ordering

- Stream score: alive base 70; resolution bonus 15/12/8/5/2 for 1080p+/720p+/576p+/
  480p+/other; latency bonus 15/12/8/4/1 for ≤0.5/1/2/4 s/longer; subtract
  `min(10 * hard_fails, 30)`. Unverified base 30 with half bonuses. Dead = 0.
- Order streams per channel: alive → unverified → dead, then score desc, resolution
  desc, latency asc, URL. `position` starts at 0.
- Channel state: alive if any alive stream, else unverified if any unverified, else
  dead. Channel score = max stream score.
- Collections are pre-sorted: editorial boost (list order) desc, channel score desc,
  name asc. Only playable channels (alive or unverified) enter collections.
- `channel.number` = 1..N in final collection-first-order.

## Collections

Order and titles exactly:

1. `editor-picks` "Editor's Picks" (curated, max 30)
2. `malayalam` "Malayalam"
3. `india-news` "Indian News"
4. `india-entertainment` "Indian Entertainment"
5. `india-movies` "Indian Movies"
6. `india-music` "Indian Music"
7. `india-kids` "Indian Kids"
8. `india-sports` "Indian Sports"
9. `india-religious` "Indian Devotional"
10. `india-other` "Indian Channels"
11. `world-news` "World News"
12. `world-entertainment` "World Entertainment"
13. `world-movies` "World Movies"
14. `world-music` "World Music"
15. `world-kids` "World Kids"
16. `world-sports` "World Sports"
17. `world-documentary` "World Documentary"
18. `world-other` "World Channels"
19. `country-<cc>` for the top 10 countries by playable count (>= 3), excluding IN;
    `kind = 'country'`, title = country name.

Empty collections are omitted (except `malayalam` may never be empty — validation
fails instead).

## Outputs

- `catalog.db`: schema version 1 exactly as `docs/TDD.md` §2.2. Read-only, no WAL,
  vacuumed. Populate FTS with name, alt_names, language, category, country.
- `catalog.db.gz`: deterministic gzip (`mtime=0`, level 9) of the vacuumed db.
- `version.json`: schema, catalog_version (= built_at ISO-8601 UTC), built_at, sha256
  of the `.gz`, size_bytes, min_app_version 1, counts.
- `report.json`: version, source, source_license "CC0-1.0", durations, candidates,
  counts (channels, playable, alive, unverified, dead, malayalam, live_share),
  collections with counts, gzip size, gate result and reasons, top 20 dead examples.
- `state/health.json`: merged history, safely written (temp + rename).

## Publish gate

Fail (exit 1) if: schema/table/FTS integrity fails; FTS rows ≠ channel rows;
`malayalam` < 10 playable; total playable < 250; gz > 8 MB; playable drops > 15 %
versus `--previous-report`; a required collection that previously had items is now
empty. The report must list every reason.

## Tests (`pytest`, no network)

Fixtures must cover: messy M3U, names with `[Geo-blocked]`/`HD`, duplicate URLs,
missing logos, Malayalam by language and by keyword, a channel with one failing and
one working stream, three-state transitions across two runs, master HLS manifest
parsing, scoring order, collection ordering, drop-protection failure, FTS row count,
and an end-to-end `build --fast --source-dir fixtures/api` that asserts the schema,
collection positions and `version.json` hash.

## Acceptance (paste real output)

```bash
source scripts/env.sh
$PYTHON -m pytest curator/tests -q
scripts/curator.sh build --fast --out curator/dist
scripts/curator.sh validate --catalog curator/dist/catalog.db --report curator/dist/report.json
$PYTHON - <<'PY'
import sqlite3, json
db = sqlite3.connect("curator/dist/catalog.db")
for q in ("select count(*) from channel", "select count(*) from stream",
          "select count(*) from channel_fts", "select count(*) from collection",
          "select count(*) from collection_item"):
    print(q, db.execute(q).fetchone()[0])
print(db.execute("select id,title,kind,position from collection order by position limit 8").fetchall())
print(db.execute("select c.name from collection_item i join channel c on c.id=i.channel_id where i.collection_id='malayalam' order by i.position limit 8").fetchall())
PY
scripts/curator.sh build --fast --max-checks 40 --out curator/dist-health-sample
```

Then run one real health sample (`--max-checks 150`, no `--fast`) and report the
alive/unverified/dead counts and elapsed time. Do not run the full 2,500-URL sweep in
this phase unless it finishes under 15 minutes.

## Do not

- Add runtime dependencies.
- Sort in the app contract or emit unsorted collections.
- Disable TLS verification or hammer sources (respect concurrency/timeouts).
- Publish on a failed gate.
- Write secrets, source credentials or personal data.

## Done

`AGENTS.md` §6 report, with the acceptance output, catalog counts, gz size, sample
health results and known gaps. Update `docs/DECISIONS.md` for any judgment call.
