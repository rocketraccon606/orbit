# Orbit

A free, open, TV-first live TV app for Android TV / Google TV. Orbit replaces the
"paste a public M3U URL into VLC" workflow with a fast, organized, Netflix-style
browser that only shows channels that actually work.

Orbit ships no channels and hosts no content. It is a player. A scheduled curator
turns public IPTV metadata into one pre-sorted, health-checked catalog file; the TV
app downloads that file and plays source URLs directly.

## Repository

| Path | Purpose |
|---|---|
| `curator/` | Python pipeline: fetch, normalize, dedupe, health-check, classify, score, publish |
| `app/` | Android TV app (Kotlin, Compose for TV, Media3) |
| `docs/` | PRD, technical design, decisions, testing strategy |
| `prompts/` | Phase-by-phase builder-agent briefs (the build is driven by these) |
| `scripts/` | Local toolchain, catalog serving, emulator, deploy and benchmark helpers |
| `.github/workflows/` | Scheduled curator run and GitHub Pages publish |

## Live catalog

The scheduled `catalog` workflow publishes the current schema-2 catalog to GitHub
Pages:

- Catalog base: `https://rocketraccon606.github.io/orbit/`
- Version check: `https://rocketraccon606.github.io/orbit/version.json`
- Report and gate: `https://rocketraccon606.github.io/orbit/report.json`

Release builds use that URL by default. Debug builds point at the local catalog
(`http://10.0.2.2:8787/`) unless built with `ORBIT_CATALOG_URL` set to Pages.

## Start here

- Builder agents: read [`AGENTS.md`](AGENTS.md) first, then the current phase brief in `prompts/`.
- Humans: read [`docs/PROMPTS.md`](docs/PROMPTS.md) for how to run the build.
- TV sideload: [`docs/DEPLOY-TV.md`](docs/DEPLOY-TV.md) (ADB setup, catalog hosting, rollback).
- Design: [`docs/PRD.md`](docs/PRD.md) and [`docs/TDD.md`](docs/TDD.md).

## The interface

- **Top navigation:** fixed ORBIT bar with Home, Channels, Malayalam, News, Sports,
  My List and Recently Watched, plus search, notifications and profile shortcuts.
  It is transparent over the hero and turns solid once the page scrolls.
- **Home:** a 96vh-style cinematic hero with a large channel watermark, LIVE and
  TOP 10 badges, descriptive metadata and Play / More Info, followed by Continue
  Watching, Top 10 portrait cards and curated 16:9 artwork rows.
- **Browse:** a focus-first adaptive grid with per-channel logos, live state, channel
  number and a pop-out action panel on focus.
- **Channel detail:** full-bleed header, Play / My List, real language, category,
  country, network, launch year, owners, sources and website, then similar channels.
- **Search:** D-pad keyboard and quick picks on the left, live two-column results or
  a trending grid on the right. Single-letter prefix searches and small typos work.
- **Player:** one reused ExoPlayer, a 9-second start watchdog, stream fallback and a
  clear unavailable state instead of a black screen; channel, LIVE and stream status
  overlays appear on OK.
- **Remote-first:** Left/Right/Up/Down/OK/Back reach every action; hold OK adds a
  channel to My List; Back restores the previous list position.


## Ground rules

- The TV does no heavy work: no sorting, no health checks, no background services.
- The catalog is pre-sorted and health-checked on a free CI runner.
- Channel artwork, network, launch year and website are baked into the catalog from
  public metadata; 1,059 of 1,071 channels ship with a raster logo.
- User data (favourites, recents) lives in a separate database and survives catalog swaps.
- Sideload first; no store release in v1.
