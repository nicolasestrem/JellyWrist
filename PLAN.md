# JellyWrist: Unified Design Plan

**Standalone Wear OS application for syncing and offline listening of music from a Jellyfin server.**

> **Product identity (2026-11-20).** The public name is **JellyWrist**. The source namespace and Android `applicationId` are `com.example.jellywrist`; persistence and scheduling identifiers use the `jellywrist` prefix (DEC-016). 0.0.1 is the first public identity, so no pre-public installation is preserved.

> **Single reference document.** Merges and replaces the two previous plans (`2026-08-05_203130-wearos-jellyfin-sync.md` and the old `PLAN.md`) after **fact-checking** the disputed technical claims (3 research sub-agents: Jellyfin SDK, download endpoints, Horologist/Media3 on Wear OS).
>
> No application code exists yet — this plan is the reference for development.

---

## 0. Fact-check summary

The two previous plans rested on opposing technical assumptions. The verification settles them:

| Claim (Plan A) | Verdict | Impact on this plan |
|---|---|---|
| "The official SDK pulls in apache-client / bloats the APK" | **False** — uses OkHttp 5.x; modest dependencies (kotlinx-coroutines, serialization-json, kotlin-logging) | **Use the official `org.jellyfin.sdk` SDK** |
| "Finamp says the generated classes are massive" | **Misleading** — Finamp is a Flutter/Dart app that never used the Kotlin SDK (it uses `chopper`) | Not valid as evidence; ignore |
| `/Audio/{id}/universal` for downloading | Transcoded output sets `Accept-Ranges: none` (no resume) | **Avoid for downloads** |
| `/Items/{id}/File` | Bit-for-bit original file, `Accept-Ranges: bytes`, HTTP 206, no `Download` permission required | **This is the endpoint to use** (Finamp's production choice) |
| 6h FGS limit for `dataSync` (Android 15+) | **Real**, applies to download services | Hybrid WorkManager + FGS mandatory |
| Horologist (MediaDownloadService, offload, speaker-prevention) | Actively maintained (0.7.x stable), features are real | Adopt |
| `wear-compose-material3` 1.5.x | Stable | M3 line, not M2 |

**Conclusion:** Plan B (the old `PLAN.md`) made the **right product/architecture decisions**; Plan A (`2026-08-05_...`) had the **better execution blueprint**. Plan A was also undermined by two central factual errors (SDK weight, endpoint choice). This plan = Plan B's decisions + Plan A's execution + factual corrections.

---

## 1. Vision & scope

### 1.1 Goal

Let users carry their Jellyfin music library on their wrist: the user selects playlists, albums, or artists on their Wear OS watch, the app downloads them from the Jellyfin server over the watch's Wi-Fi, and playback then happens **entirely offline** on Bluetooth headphones or earbuds — no phone, no network. Typical use cases: running, the gym, commuting.

### 1.2 Structural decisions (settled)

| Decision | Choice | Rationale |
|---|---|---|
| Architecture | **Standalone** (100% watch app, direct connection to the server). *Optional* phone companion (DEC-013) | The watch never *requires* a phone; the `:mobile` companion assists provisioning via the Wearable Data Layer |
| Playback mode | **Download only** (no streaming in v1) | Playback always available; reduced surface |
| Jellyfin client | **Official Kotlin SDK `org.jellyfin.sdk`** (Android artifact) | Typed, maintained (~1 release/month), Quick Connect + server discovery included; lightweight dependencies (OkHttp 5, kotlinx-*) |
| Auth | **Quick Connect first**, `AuthenticateByName` as fallback | No password to type on a 4 cm screen |
| Download endpoint | **`GET /Items/{id}/File`** | Bit-for-bit original file, Range resume (HTTP 206), no `Download` permission required |
| Audio quality | **Original files, no transcoding in v1** | Fidelity + resume works. Transcoding deferred to v2 (only if measured storage pressure) |
| Injection | **Hilt** | Cleaner integration of the SDK + WorkManager + Media3 session than a ServiceLocator at this scope |
| Target | **minSdk 34 (Wear OS 5+), targetSdk 36** | OnePlus Watch 4 as reference; modern APIs, reduced compat surface |

### 1.3 Out of scope (v1)

- Live streaming from the server.
- ~~Phone companion app.~~ → **Lifted by DEC-013 (2026-08-11)**: an optional companion app (`:mobile`) assists provisioning and remote management via the Wearable Data Layer. The watch remains standalone — it never *requires* a paired phone.
- Server-side transcoding (v2 option if storage is too constrained).
- Podcasts, audiobooks, videos, synced lyrics.
- Simultaneous multi-server and multi-account support.
- Playback status reporting (kept as a nice-to-have for M5+).

---

## 2. Tech stack

| Domain | Choice | Rationale |
|---|---|---|
| Language | **Kotlin** (+ coroutines/Flow) | Modern Android standard |
| UI | **Jetpack Compose for Wear OS — Material 3** (`androidx.wear.compose:compose-material3` 1.5.x) | Stable M3 line; components suited to round screens; "Material 3 Expressive" for Wear OS 6+ |
| Wear toolkit | **Horologist** 0.7.x (Google) | Proven building blocks: `media3-backend` (audio offload + speaker-prevention), `media-data` (`MediaDownloadService`), `network-awareness`, `media3-outputswitcher`, audio-ui |
| Audio playback | **Media3 / ExoPlayer** + `MediaSessionService` | Native support for FLAC, MP3, AAC, OGG/Opus; system integration (media commands, Output Switcher) |
| Downloads | **Media3 `DownloadManager` / `DownloadService`** | Persistent queue, Range resume, retry/backoff, progress notification; FGS `dataSync` |
| Jellyfin API | **Official Kotlin SDK** (`org.jellyfin.sdk:jellyfin-core`, OkHttp backend) | Typed, maintained, Quick Connect + server discovery included |
| Local database | **Room** | Synced catalog (collections, tracks) + download state |
| Preferences | **DataStore** | Settings (server, quota, sync constraints) |
| Scheduled sync | **WorkManager** | Deferred execution under constraints (charging, Wi-Fi), native retry/backoff |
| Injection | **Hilt** | Standard, well integrated with WorkManager and services |
| Secrets | **Android Keystore / EncryptedSharedPreferences** | Encrypted storage of the Jellyfin access token |

---

## 3. Jellyfin integration

### 3.1 Server connection

1. **Automatic discovery**: `jellyfin.discovery.discoverLocalServers()` (UDP broadcast on 7359, provided by the SDK) to list available servers.
2. **Manual URL entry** as a fallback — painful on a watch, hence: aggressive normalization (scheme/port guessed, `http(s)://`, `:8096`/`:443`), immediate validation via `GET /System/Info/Public`.
3. **HTTPS**: public certificates by default; explicit "trust this certificate" option for self-hosted servers with self-signed certificates (**pinning** of the presented certificate, never a global disabling of TLS verification).

### 3.2 Authentication

- **Quick Connect first**: the watch displays a 6-digit code, and the user approves it from any Jellyfin client (phone, browser). No password to type.
  - SDK flow: `initiateQuickConnect()` → poll `getQuickConnectState(secret)` → `authenticateWithQuickConnect(secret)`.
- **Fallback**: `AuthenticateByName` (username + password) for servers where Quick Connect is disabled.
- The access token is stored encrypted (EncryptedSharedPreferences/Keystore); logging out = local revocation + optional deletion of downloaded files.

### 3.3 Endpoints used

| Usage | Endpoint | Notes |
|---|---|---|
| Server validation | `GET /System/Info/Public` | No auth |
| Auth | `POST /Users/AuthenticateByName`, `QuickConnect` flow (initiate/connect/authorize) | Via SDK |
| Library browsing | `GET /Items` (filters `IncludeItemTypes=Playlist,MusicAlbum,MusicArtist,Audio`, pagination, sorting) | |
| Playlist contents | `GET /Playlists/{id}/Items` | |
| **Original file download** | `GET /Items/{id}/File` | **Range-resumable** (HTTP 206), no `Download` permission required; Finamp's production choice |
| Cover art | `GET /Items/{id}/Images/Primary` (`fillWidth=200`) | Screen ≈ 466 px — no need for 1400 px |

> **Note:** `/Items/{id}/Download` also exists and supports Range, but requires the `Download` permission (which in practice only gates the explicit UI/endpoint — see Jellyfin issue #12264). `/Items/{id}/File` only requires basic auth: it is the canonical endpoint for an offline client like Finamp.

### 3.4 Playback status reporting (nice-to-have, M5+)

Since playback is offline, "track played" events are queued locally (Room `PlaybackReport`) and replayed to `/Sessions/Playing/*` or `/Users/{id}/PlayedItems/{id}` during the next sync session. Keeps server statistics consistent. Purely optional — the app works without it.

---

## 4. Sync engine (core of the application)

### 4.1 Mental model

The user does not "download" files one by one: they **pin collections** (playlist, album, artist). The sync engine converges the local content toward the server content of those collections, in both directions (additions *and* removals).

### 4.2 Pipeline (executed by WorkManager)

1. **Refresh metadata** of pinned collections from the server.
2. **Diff** server ↔ Room database: tracks to download, local tracks that have become orphans (removed from all pinned collections).
3. **Capacity check**: sum of sizes to download vs. quota and actual disk space; if exceeded → partial sync + visible alert (never a silent failure).
4. **Enqueue downloads** in the Media3 `DownloadManager`: resume via `Range` headers, retry with exponential backoff, order = playlists first (most likely usage), then albums.
5. **Post-download verification**: expected size (via `Content-Length`/server metadata); corrupted file → delete + retry.
6. **Purge** orphans and their associated cover art.
7. **Viewable sync log** (last run, number of tracks added/removed, errors).

### 4.3 Triggering and constraints

- **By default: watch charging + Wi-Fi** (`setRequiresCharging(true)` + `NetworkType.UNMETERED`). Nightly scenario: the watch charges on its dock and the music updates itself.
- **Manual sync** from the app: allowed off-charger after a battery warning; **explicit** acquisition of the high-bandwidth network via Horologist `network-awareness` — essential because Wear OS favors the phone's Bluetooth proxy (~125 KB/s) and does not turn on Wi-Fi spontaneously.
- Configurable periodicity (daily by default) + "Sync now" button.
- **6h FGS limit (Android 15+)**: long downloads can be interrupted by the `dataSync` cap. Mitigation: WorkManager re-arms; the `DownloadManager` resumes via Range where it left off; the UI shows "Sync paused — will resume" rather than "Failed".

### 4.4 Storage management (critical with original files)

Watches have 8–32 GB **shared** with the system and other apps; a FLAC album can weigh 400 MB+.

- **Configurable quota** (default ~6 GB on a 32 GB OPW4) with a "used / quota / free disk space" gauge in settings.
- **Estimate before pinning**: show a collection's total size before pinning it (sizes come from the `/Items` metadata), with a warning above a threshold.
- **Artist-level batching**: "Download all" for an artist resolves the album list and enqueues greedily up to the available cap; the rest moves to `PENDING_BUDGET` and the UI shows "6 of 14 albums enqueued — open settings to adjust". `PENDING_BUDGET` albums are released when space frees up (deletions), via the recon worker's re-check.
- **Deletion per collection** (unpinning = delete files not shared with another pinned collection — refcounting via the n-n `CollectionTrack`).
- Files stored in the **app's private internal storage** (`filesDir`): no permission needed, automatic cleanup on uninstall.

---

## 5. Offline playback

- **Media3 ExoPlayer** in a `MediaSessionService` (Horologist backend): background playback, system commands, control from the watch face, **audio offload**.
- **Bluetooth audio output mandatory**: the Horologist media toolkit contains the logic that **prevents accidental playback on the watch speaker** — when "play" is pressed with no headphones connected, show the system prompt to connect an audio device (Output Switcher).
- **Codecs**: FLAC, MP3, AAC, OGG Vorbis, and Opus are decoded natively by ExoPlayer. Consequence of the "original files" choice: exotic formats (ALAC, WMA, DSD…) are **detected at sync diff time** (codec field in Jellyfin metadata) and flagged as unplayable instead of failing at playback. (Possible v2 mitigation: targeted transcoding of only those files.)
- **Watch ergonomics**: rotating crown = volume; shuffle/repeat; queue and position restored after restart; player screen following Wear media patterns (cover art as background, large buttons).

---

## 6. UI — flows and screens

```
Onboarding ──► Library ──► Collection detail ──► Player
                  │             (sync toggle)
                  ├──► Downloads (progress, storage)
                  └──► Settings
```

| Screen | Contents |
|---|---|
| **Onboarding** | Server discovery/entry → Quick Connect code (or login) → suggestion to pin a first playlist |
| **Library** | Playlists / Albums / Artists tabs; "synced" badge on pinned collections |
| **Collection detail** | Track list, total size, "Sync to watch" toggle, playback |
| **Downloads** | Active queue with progress, errors, storage gauge, manual sync button, `PENDING_BUDGET` albums |
| **Player** | Cover art, title/artist, play/pause/skip, volume (crown), shuffle/repeat |
| **Settings** | Account/server, storage quota, sync constraints (charging/Wi-Fi, periodicity), certificate, sync log, logout |

Wear OS extras: **Tile** "Resume playback / last playlist", **Ongoing Activity** (quick return to the player from the watch face), ambient-mode handling during playback.

---

## 7. Data model (Room)

- `Server` — url, name, optionally pinned certificate.
- `Account` — userId, token reference (the token itself lives in EncryptedSharedPreferences/Keystore).
- `Collection` — Jellyfin id, type (playlist/album/artist), name, pinned?, estimated size, last sync date, recon watermark (for auto artists).
- `Track` — Jellyfin id, tags (title, artist, album, duration, codec, size), local path, state (to download / in progress / ready / unsupported / error / `PENDING_BUDGET`).
- `CollectionTrack` — n-n link (a track can belong to several pinned collections → reference counted for purging).
- `PlaybackReport` — queue of playback events to report (nice-to-have §3.4).

---

## 8. Execution plan (TDD where JVM-testable)

Each task is focused work. TDD when a surface is testable on the JVM (DTOs, Repository, diff logic). UI/services verified on emulator/watch.

1. **Scaffold** — Wear OS Compose M3 app, minSdk 34/target 36, Hilt, empty screen that builds and installs on the emulator.
2. **SDK + DI + discovery** — add `jellyfin-core`, Hilt module, `discovery.discoverLocalServers()`; manual URL normalization + `/System/Info/Public` validation. (TDD: MockWebServer on the validation.)
3. **Auth (TDD)** — full Quick Connect flow + `AuthenticateByName` fallback; token in EncryptedSharedPreferences; Onboarding screen.
4. **Library browsing (TDD)** — `getItems` / playlist contents; Room `Collection`/`Track` cache; Library screen (cache→network→cache flow).
5. **Room cache + offline browse** — cache-first repository; browsing works with Wi-Fi off.
6. **Media3 download** — `DownloadManager` (cache root, parallel=1), `MediaDownloadService` (FGS `dataSync`), `DownloadRequest` against `/Items/{id}/File` with `customCacheKey=serverId`; manual check: one track downloads with a notification.
7. **WorkManager + constraints** — `UNMETERED`+charging by default; user-initiated expedited; manual sync override; verify via `dumpsys jobscheduler`.
8. **Storage budget + artist batching (TDD)** — estimate bytes, check `StatFs`, soft/hard cap refusal + partial queue; `ArtistBatch` resolves albums, enqueues up to the cap, remainder in `PENDING_BUDGET`. Pure function `BudgetPlanner.plan(...)` unit-tested.
9. **Playback** — ExoPlayer+MediaSessionService (Horologist), local URI from Room, Player screen, offline playback, speaker-prevention verified.
10. **Cover art + metadata** — Coil against `/Images/Primary?fillWidth=200`, disk cache for offline use.
11. **Reconciliation + orphan purge (TDD)** — `SyncPlan.diff(local, server, budget)` → (enqueue, orphan, pending, watermark): server addition→enqueue, server removal→orphan+file purge, failure→re-enqueue with backoff. Pure diff unit-tested.
12. **Network-awareness** — Horologist observer: Wi-Fi→metered mid-sync = pause+notification; metered→Wi-Fi = resume; manual metered = cap+prompt.
13. **Settings + polish** — server re-login, storage gauge + quota slider, sync constraints, certificate pinning, sync log (rotation + sharing), logout (fresh-install state).
14. **Release hardening** — FGS types `dataSync`/`mediaPlayback`, R8 keep serializers + SDK DTOs, round-screen insets, ambient framerate, icons, lint clean, `POST_NOTIFICATIONS` prompt.

### Milestones (high-level phases)

| Milestone | Contents | Exit criterion |
|---|---|---|
| **M0 — Foundation** | Wear OS Gradle project, CI, Hilt, empty Compose navigation | Green CI on an installable debug APK |
| **M1 — Connection** | Server discovery, Quick Connect + fallback, library browsing (read-only) | Browse your real playlists on the watch |
| **M2 — Download** | Room, collection pinning, Media3 engine, Downloads screen | A pinned playlist arrives complete on the watch |
| **M3 — Playback** | ExoPlayer + MediaSessionService, player screen, BT output, crown=volume | Full offline listening, watch in airplane mode |
| **M4 — Auto sync** | WorkManager (charging+Wi-Fi), bidirectional diff, purge, quota and gauges | Nightly sync reflects server changes without intervention |
| **M5 — Polish** | Tile, Ongoing Activity, played-status reporting, unsupported-codec detection, FR/EN i18n, ambient | App pleasant for daily use |
| **M6 — Quality & release** | Instrumented tests, battery/storage QA, signing, distribution | v1.0 tagged and installable |

---

## 9. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Watch Wi-Fi unstable / slow | Interrupted syncs | Resumable downloads (Range via `/Items/{id}/File`), sync while charging by default, explicit network acquisition via Horologist |
| Large original files (FLAC) | Storage saturated | Quota + estimate before pinning + warnings; artist batching with `PENDING_BUDGET`; transcoding in v2 only if measured pressure |
| **6h FGS cap (Android 15+)** | Long download interrupted | WorkManager re-arms; `DownloadManager` resumes via Range; UI "paused — will resume" |
| Undecodable codecs (ALAC, WMA…) | Silent tracks | Detection at diff time via codec metadata, visible "unsupported" status |
| Battery during sync | Poor experience | WorkManager constraints (charging), warning on manual sync |
| Self-signed certificates | Self-hosters cannot connect | Opt-in certificate pinning, never a global TLS bypass |
| Doze / Wear background limitations | Sync that never starts | WorkManager + sync while charging (restrictions lifted) |

---

## 10. Test / validation strategy

- **Unit (JVM, fast):** serialization, Jellyfin client via MockWebServer, repository cache/network interplay, `SyncPlan`/`BudgetPlanner` diff logic (pure functions). Green before each milestone.
- **Instrumented (one Wear OS emulator):** Room migrations, DataStore persistence, chunked download resume after process death (`DownloadManager` survives the kill).
- **Manual device matrix:** one round Wear OS emulator (API 34/36), one physical watch (OnePlus Watch 4 or Pixel Watch), one BT headset. Scenarios:
  1. Cold start, browse, download an album.
  2. Sync a collection on charging+Wi-Fi overnight.
  3. Cut the network mid-sync, restore, observe auto resume.
  4. Server-side deletion, trigger recon → file removed locally.
  5. Insufficient space → refusal dialog / partial queue.
  6. 30 min of offline playback, no speaker leakage.

---

## 11. Repository layout (single Gradle module, `app/`)

```
app/
  src/main/
    java/com/example/jellywrist/
      JellyWristApp.kt                      # Application, Hilt
      di/                                    # Hilt modules (SDK, DB, repos, media)
      data/
        jellyfin/                            # Wrapper around the official SDK
        db/                                  # Room DB, DAOs, entities
        repo/                                # LibraryRepository, SyncRepository, AuthRepository
      download/
        JellyDownloadService.kt             # extends Horologist MediaDownloadService
        DownloadEnqueuer.kt                  # Media3 DownloadRequest builder (/Items/{id}/File)
        ReconWorker.kt                       # PeriodicWorkRequest — refresh sync sets
      playback/
        JellyPlaybackService.kt             # MediaSession + ExoPlayer (Horologist)
        PlayerController.kt
      ui/
        MainActivity.kt
        nav/JellyNav.kt
        screens/                             # Onboarding, Library, CollectionDetail, Downloads, Player, Settings
    res/
  build.gradle.kts
  src/test/.../                              # JVM unit tests (SDK, Repo, SyncPlan, BudgetPlanner)
  src/androidTest/.../                       # instrumented
```

Root files impacted: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` (version catalog), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` (`<uses-feature android:name="android.hardware.type.watch"/>`, INTERNET, WAKE_LOCK, FOREGROUND_SERVICE + `dataSync`/`mediaPlayback`, POST_NOTIFICATIONS).

---

## 12. Open questions (to settle during development)

- App name and icon: **JellyWrist** with the **Orbit Pulse** visual direction (resolved on 2026-08-11).
- Default sync periodicity: daily or "on every charge"?
- Distribution: sideload/ADB only, or Play Store publication (imposes review + store assets)?
- Auto-download of new albums for followed artists: include in v1 (like Plan A) or defer?

---

## History

- **2026-08-07** — Unified plan. Merges `2026-08-05_203130-wearos-jellyfin-sync.md` (Plan A, detailed execution blueprint) and the old `PLAN.md` (Plan B, product/architecture decisions) after fact-checking the disputed technical assertions. Rules in favor of Plan B's decisions (official SDK, Quick Connect, `/Items/{id}/File`, original files) executed according to Plan A's task-by-task breakdown.
- **2026-08-05** — Plan A (`2026-08-05_203130-wearos-jellyfin-sync.md`): hand-rolled client, `/Audio/{id}/universal` endpoints, transcoding at download time. *Made obsolete by this plan — kept for the record.*
- **2026-08-05** — Plan B (old `PLAN.md`): official SDK, Quick Connect, `/Items/{id}/Download`. *Replaced by this unified plan.*
