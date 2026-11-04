# Orbit — Product Requirements Document

Version 0.2 | Status: build | Owner: Orbit project lead

## 1. Overview

Orbit is a free, open, Netflix-style live TV app for Android TV / Google TV. It plays
public internet TV channels from IPTV playlists, focused on Indian and Malayalam
channels. It replaces pasting a playlist into VLC with a single app that is fast,
organized and pleasant with a TV remote.

Orbit ships no channels of its own. It reads a curated catalog built from public
playlist sources.

## 2. Problem

The current VLC workflow:

1. Switching is slow. Each channel waits for a timeout, and dead or geo-blocked
   entries waste time.
2. The list is unusable: thousands of flat, unsorted entries with no working search
   or memory.
3. VLC is not a TV-first product: browsing is hostile to a D-pad.

## 3. Vision and principles

Opening Orbit shows something worth watching within two remote clicks.

- **Light first.** Run well on a low-end TV. When in doubt, do less.
- **Work happens elsewhere.** Health checks, sorting and classification run once on a
  free server, never on the TV.
- **Silent when idle.** No background services or jobs. Closed means nothing runs.
- **Remote-first.** Everything works with D-pad, OK, Back and number keys.
- **Free forever.** No payments, accounts, ads or tracking.

## 4. Goals and non-goals

### Goals

- G1: Fast switching on healthy streams (targets in §7).
- G2: Only working channels shown prominently; dead channels never waste time.
- G3: Channels organized automatically by language, category and country.
- G4: Search, favourites, recents and last-channel in a second or two.
- G5: Polished Netflix-like UI that stays responsive on the target TV.
- G6: Zero running cost.

### Non-goals (v1)

Hosting/re-streaming/recording; accounts, sync or social; VOD, downloads or DVR; iOS,
phones, Tizen or webOS; bundling a channel list in the APK.

## 5. User and device

Primary user: a Malayalam/Indian viewer who wants live news, entertainment, movies,
music and kids channels plus some international channels, without a subscription.

Primary device: TCL C06 Smart TV (Google TV 11 / API 30), treated as low-end:
~1 GB RAM, 4× Cortex-A55, 1280×720, limited decoders. All targets are measured there.

## 6. Functional requirements

P0 = v1, P1 = v1.x, P2 = later.

### 6.1 Catalog and sources

- P0: Download a prebuilt catalog from a free static host. Check version/ETag at launch
  and download only when changed.
- P0: Work offline from the last catalog (browsing, favourites, recents). Playback
  needs internet.
- P0: First launch shows a short progress screen, then Home.
- P1: Add a personal M3U URL as a separate, uncurated source.

### 6.2 Home and browsing

- P0: Horizontal rows: Continue Watching, Favourites, Malayalam, Indian News,
  Entertainment, Movies, Music, Kids, Sports, International by country.
- P0: Rows arrive pre-ordered; the app never sorts the catalog at runtime.
- P0: Cards show logo, name and a subtle status marker; focus is unmistakable from
  across the room.
- P0: Hero shows the focused channel's name, language and category.
- P1: "Surprise me" opens a random healthy channel from a category.
- P2: Now/Next EPG on cards.

### 6.3 Search

- P0: One click from Home. Results update as the query changes and tolerate typos.
- P0: Matches name, language, category and country ("malayalam news" works).
- P0: Fully D-pad operable using an on-screen keyboard.
- P1: Voice search via Android TV.

### 6.4 Favourites and recents

- P0: Long-press OK toggles favourite. Favourites appear as a Home row and in the
  player quick panel.
- P0: Recents keep the last 20 channels, newest first.
- P0: "Last channel" toggles between the two most recent channels.
- P1: Custom favourite groups.

### 6.5 Playback

- P0: Full-screen playback with an auto-hiding info bar.
- P0: CH+/CH− (or D-pad Up/Down) changes channel within the current list; number keys
  jump within the current row.
- P0: Try the best stream, then fall back automatically to alternatives, with a small
  non-blocking message.
- P0: Hold the logo/last frame while switching; avoid black screens and spinners.
- P0: Back returns to the same list position.
- P1: Quality and audio-track selection; libVLC fallback for unsupported streams.

#### 6.5.1 Health transparency

- P0: Dead channels are hidden from rows but searchable with a warning.
- P0: Unverified channels (possibly geo-restricted) are shown lower.

### 6.6 Settings

- P0: Clear recents, clear favourites, refresh catalog, about/version, licenses.
- P1: Fast-switch prefetch toggle; language preference for Home.

## 7. Non-functional requirements

Validate on the TCL TV in Phase 5, then lock.

| Requirement | Target |
|---|---|
| Channel switch (key press → first frame) | median < 1.5 s, p90 < 3 s, healthy streams |
| Cold start to Home | < 3 s with cached catalog |
| Memory while playing | < 200 MB, no growth after 1 h surfing |
| Background work | zero services/jobs/wake locks outside active playback |
| Stability | no crash on dead/slow/malformed streams; every failure has a message |
| APK size | < 12 MB |
| Privacy | no analytics/accounts; no personal data leaves the device |
| Cost | free tiers only |

## 8. Success metrics

- Switch benchmark hits targets on the TCL TV.
- ≥ 95 % of channels in main rows play in a test sweep in the target region.
- A favourite Malayalam channel is reachable in ≤ 3 clicks from launch.
- One week of daily use without restarting the app.

## 9. Content, legal and distribution

- Orbit is a player: no channels bundled, no content hosted.
- Curator uses public metadata; record license and credit sources in About.
- No circumvention of geo-blocks, DRM or authentication; such streams are excluded.
- Provide a channel-removal channel for takedown requests.
- v1 distribution: sideload only (ADB, signed APK). Store submission is out of scope.
- Verify the "Orbit" name before any public release.

## 10. Release plan

| Phase | Deliverable | Exit |
|---|---|---|
| 1 Curator | Validated, pre-sorted catalog + health report | Report matches spot checks |
| 2 App v1 | Home, playback, search, favourites, recents, last channel | All P0 works on emulator |
| 3 Speed | Fallback chain, prefetch, tuning, benchmark | Targets met |
| 4 Extras | libVLC, EPG, personal M3U, groups, voice | Later |

## 11. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Sources unreliable | Automated health checks, multi-stream fallback, fast failover |
| Geo-blocked looks dead | Three-state health; `unverified` instead of delete |
| Low-end TV | One player, low memory, no background work, measure from day one |
| Source disappears | Cache last good catalog; tolerant parsers |
| Legal/store pressure | Content-free player, sideload first, takedown channel |
| Scope creep | Hold the P0 list; everything else waits |

## 12. Open questions

- Final package name (proposed `app.orbit.tv`) and signing-key plan.
- Catalog host: **decided — GitHub Pages**.
- First-catalog language/country breadth: decided — all Malayalam, India, top countries.
- India-based health checker later?
