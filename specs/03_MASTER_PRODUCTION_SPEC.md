# Master Production Specification

- Spec version: 0.2
- Status: APPROVED
- Owner: nicolasestrem
- Last reviewed: 2026-08-07

> **Approval basis:** Human answers Q-001=A and Q-004=A (2026-08-07) confirm the authoritative goal and non-goal list (DEC-008); Q-003=C adds REQ-012 (DEC-009); Q-002=B locks DEC-007. The requirement catalogue mirrors LOCKED decisions confirmed by the project owner.

> **Status note (2026-11-20).** As of this date the product described in this specification is implemented in the repository tree: authentication, library, download/sync, offline playback, the shared `:core` layer and the optional `:mobile` companion. The `[PLANNED]` annotations are historical and describe the plan-time state, not the current tree. No requirement or `[LOCKED]` decision in this document changed. One implementation detail is clarified by DEC-010: the `AuthenticateByName` fallback in REQ-005 was superseded by Quick Connect-only authentication; REQ-005's token-storage clause is unchanged and fully implemented.

> **Bootstrap-era note (historical).** Architecture and data model are the target design from PLAN.md and were **not yet built** when this catalogue was approved — labelled `[PLANNED]` where applicable. Claims about current state reference verified evidence; claims about future behaviour reference PLAN.md sections and decision IDs. See the status note above.

---

## Purpose and implementation boundary

JellyWrist is a standalone Wear OS application (minSdk 34, Wear OS 5+) that synchronises music collections from a Jellyfin server to the watch's local storage and plays them fully offline over Bluetooth headphones. An **optional phone companion app** (`:mobile`) may assist with provisioning and remote management; the watch never *requires* a paired phone [VERIFIED: PLAN.md §1.1, §1.2; DEC-013 supersedes the prior "no phone companion" exclusion].

## Product identity and package

- Public product name: **JellyWrist**
- Store subtitle: **Offline Jellyfin music for Wear OS**
- Visual direction: **Orbit Pulse**, an OLED-black sync loop with a music waveform, magenta-violet energy and a restrained cyan highlight.
- Source namespace: `com.example.jellywrist`
- Published Android application ID: `com.example.jellywrist`

The Android application ID, Room database name, encrypted preferences, DataStores, worker tags and notification channel identifiers use the `jellywrist` identity. 0.0.1 is the first public release; no pre-public installation is preserved or migrated (DEC-016).

This specification defines the v1 production contract. It covers authentication, library browsing, download/sync, offline playback, storage management, the phone companion app (DEC-013), and the corresponding data model, interfaces, and failure modes. It does not cover streaming, transcoding, or multi-server setups — those are explicitly out of scope per PLAN.md §1.3.

## Source-of-truth hierarchy

When sources conflict, use this order and report the conflict:

1. Non-superseded `[LOCKED]` entries in `specs/DECISION_LOG.md`
2. Approved requirements in this document (`specs/03_MASTER_PRODUCTION_SPEC.md`)
3. Approved direction in `specs/02_STRATEGIC_PLAN.md`
4. Verified current-state evidence in `specs/01_PROJECT_CONTEXT.md`
5. Repository implementation, tests, configuration, and observed behaviour
6. Older notes: PLAN.md sections not yet lifted into specs, superseded "Plan A" (`2026-08-05_...`), README, chat history

> **Note:** PLAN.md §0/§1.2 decisions have been lifted into `DECISION_LOG` as DEC-001..DEC-006. PLAN.md remains the detailed design reference until superseded section-by-section by approved specs.

## Requirement catalogue

| ID | Requirement | Decision status | Verification |
|---|---|---|---|
| REQ-001 | Standalone Wear OS app; never *requires* a paired phone. An optional `:mobile` companion may assist provisioning and remote management (DEC-013). Manifest declares `com.google.android.wearable.standalone=true` and `android.hardware.type.watch` | [LOCKED] | Manifest inspection |
| REQ-002 | Jellyfin access exclusively via official `org.jellyfin.sdk` Kotlin SDK | [LOCKED] | Dependency catalog inspection + code review |
| REQ-003 | Track downloads use `GET /Items/{id}/File` and resume via HTTP Range after interruption (incl. process death / FGS 6h cap) | [LOCKED] | MockWebServer unit tests + instrumented resume test (PLAN.md §10) |
| REQ-004 | No transcoding in v1; unsupported codecs flagged at diff time | [LOCKED] | SyncPlan diff unit tests |
| REQ-005 | Auth: Quick Connect primary, AuthenticateByName fallback; token only in EncryptedSharedPreferences/Keystore, never in Room or logs | [LOCKED] | Code inspection + unit tests |
| REQ-006 | Sync engine converges pinned collections bidirectionally (adds AND removals), refcounted purge via CollectionTrack | [LOCKED] | SyncPlan.diff pure-function unit tests |
| REQ-007 | Default sync constraints: charging + unmetered Wi-Fi; manual sync requires explicit high-bandwidth network acquisition (Horologist) + battery warning | [LOCKED] | WorkManager constraint inspection + instrumented test |
| REQ-008 | Storage budget planner keeps usage within a user-visible budget on 8–32 GB shared storage; capacity check precedes enqueue | [FLEXIBLE] | BudgetPlanner.plan unit tests |
| REQ-009 | Playback fully offline via Media3 ExoPlayer in MediaSessionService (Horologist backend); playback attempt without BT audio output triggers system Output Switcher, never watch speaker by accident | [LOCKED] | Emulator/device verification |
| REQ-010 | (M5+) Offline playback events queued in Room `PlaybackReport`, replayed to server on next sync | [DEFERRED] | Unit tests when built |
| REQ-011 | JVM-testable surfaces (DTOs, repositories, SyncPlan.diff, BudgetPlanner.plan) developed TDD; green `testDebugUnitTest` gates every milestone | [LOCKED] | CI `android.yml` |
| REQ-012 | Every Room schema change ships a versioned `Migration`; instrumented migration tests cover each upgrade path; destructive-migration fallback is prohibited in release builds | [LOCKED] | Code inspection + instrumented Room migration tests |

## Invariants and preserved behaviour

1. **Standalone watch app** — the watch never *requires* a paired phone. An optional `:mobile` companion may assist provisioning and remote management via the Wearable Data Layer (DEC-013). `com.google.android.wearable.standalone=true` is unchanged.
2. **SDK-only Jellyfin access** — all Jellyfin API calls go through `org.jellyfin.sdk`. No raw OkHttp calls to Jellyfin endpoints (REQ-002, DEC-001).
3. **Merged M1 behaviour** — server discovery and URL validation from M1 task 2 is preserved and extended; no rework of existing SDK integration code.
4. **CI green gate** — `testDebugUnitTest` and `assembleDebug` must pass before any milestone is declared complete. CI `android.yml` is the authoritative gate (REQ-011).

## Assumptions and open questions

| ID | Category | Content |
|---|---|---|
| ASM-001 | Retired | PLAN.md is the authoritative roadmap — confirmed 2026-08-07 by Q-001=A (DEC-008) |
| ASM-002 | Assumption | Google Pixel Watch runs the app unmodified; also scoped to the lite flavor (DEC-007). Expiry: `installDebug` on a physical device |
| Q-001 | Answered (A) | Template artifact; authoritative goal = PLAN.md roadmap → DEC-008 |
| Q-002 | Answered (B) | Lite = separate reduced-feature build flavor → DEC-007 LOCKED; feature set → Q-006 / GAP-007 |
| Q-003 | Answered (C) | Room-migration discipline → DEC-009 / REQ-012 |
| Q-004 | Answered (A) | Generic exclusions confirmed → DEC-008 |
| Q-005 | Answered (A) | Pull executed; local main matched the prior internal main |
| Q-006 | Open question | Lite flavor cut list and mechanism — blocks lite work only (GAP-007) |

## System architecture and data flow

[PLANNED — not yet built. Verified as planned per PLAN.md §4.]

**High-level flow:**

```
Jellyfin Server
    ↕ (org.jellyfin.sdk, OkHttp 5)
JellyWrist App (Wear OS)
    ├── Auth (Quick Connect / AuthenticateByName) → EncryptedSharedPreferences
    ├── Library Browse → Room cache (Collection, Track)
    ├── Sync Engine (WorkManager pipeline)
    │   ├── Refresh metadata from server
    │   ├── Diff: server ↔ Room (SyncPlan.diff)
    │   ├── Capacity check (BudgetPlanner.plan)
    │   ├── Enqueue downloads (Media3 DownloadManager)
    │   ├── Verify post-download (size match)
    │   └── Purge orphans (refcounted via CollectionTrack)
    └── Offline Playback (Media3 ExoPlayer → MediaSessionService → BT)
```

**Key architectural decisions:**
- Hilt DI throughout (DEC-006)
- Room as local source of truth for metadata and download state
- Media3 DownloadManager for persistent, resumable downloads
- WorkManager for automatic periodic sync; FGS `dataSync` for manual/active sync
- Horologist for media backend (speaker prevention, audio offload, network awareness)

## Data model and migrations

[PLANNED — not yet built. Verified as planned per PLAN.md §7.]

Room entities (single-module `app/`, package `com.example.jellywrist`):

| Entity | Key fields | Purpose |
|---|---|---|
| `Server` | url, name, pinned certificate (optional) | Jellyfin server connection config |
| `Account` | userId, token reference (token itself in EncryptedSharedPreferences) | User session; token never stored in Room (REQ-005) |
| `Collection` | Jellyfin id, type (playlist/album/artist), name, pinned flag, estimated size, last sync date, artist refresh watermark | Pinned content container |
| `Track` | Jellyfin id, tags (title, artist, album, duration, codec, size), local path, status (TO_DOWNLOAD / DOWNLOADING / READY / UNSUPPORTED / ERROR / PENDING_BUDGET) | Individual audio file metadata + download state |
| `CollectionTrack` | collectionId, trackId (composite PK) | N-N link enabling refcounted orphan purge (REQ-006) |
| `PlaybackReport` | eventId, trackId, timestamp, position | Queued playback events for server replay (REQ-010, M5+) |

> **Migration note (REQ-012, DEC-009 — LOCKED 2026-08-07 via Q-003=C):** No Room DB exists yet; the first migration is schema creation. From then on, every schema change ships a versioned `Migration` with an instrumented test covering the upgrade path. `fallbackToDestructiveMigration` — or any data-destructive fallback — is prohibited in release builds.

## Authentication and authorization

[VERIFIED as planned per DEC-002, PLAN.md §3.2]

1. **Quick Connect (primary):** App calls `initiateQuickConnect()` → displays 6-digit code → polls `getQuickConnectState(secret)` → on approval, `authenticateWithQuickConnect(secret)`.
2. **AuthenticateByName (fallback):** Username + password for servers where Quick Connect is disabled.
3. **Token storage:** Access token stored exclusively in `EncryptedSharedPreferences` backed by Android Keystore. Token is never written to Room, logs, or any non-encrypted location (REQ-005).
4. **Startup state:** Phone and watch install their Compose roots before session I/O begins, render a loading state while bootstrap runs, then render either the authenticated graph or onboarding. Application warm-up and activity bootstrap share one serialized session read.
5. **Unreadable session recovery:** An empty store is distinct from a recognized Keystore, encrypted-keyset, or decryption failure. For the latter, the app deletes only `jellywrist_auth.xml` and its app-owned master-key alias, preserves Room, settings, downloads, and all unrelated data, and returns to onboarding with a reconnect notice. Exception text and stored values are never logged.
6. **Backup boundary:** Both manifests use shared backup rules that exclude `jellywrist_auth.xml` from cloud backup and device transfer because the Android Keystore key is device-bound.
7. **Logout:** Local token revocation + optional deletion of downloaded files (returns app to fresh-install state).
8. **Server-side permissions:** Read-only access to media metadata and files. No server mutation beyond optional playback report submission (M5+).

## Interfaces and contracts

Jellyfin endpoints consumed by the app [VERIFIED: PLAN.md §3.3]:

| Usage | Endpoint | Auth | Notes |
|---|---|---|---|
| Server validation | `GET /System/Info/Public` | None | Discovery/validation |
| Authentication | `POST /Users/AuthenticateByName` + Quick Connect flow (initiate/connect/authorize) | None → token | Via SDK |
| Library navigation | `GET /Items` (filters: `IncludeItemTypes=Playlist,MusicAlbum,MusicArtist,Audio`, pagination, sort) | Token | Browse collections |
| Playlist contents | `GET /Playlists/{id}/Items` | Token | Track listing per playlist |
| **Track download** | `GET /Items/{id}/File` | Token | Range-resumable (HTTP 206), original file, no `Download` permission required |
| Album art | `GET /Items/{id}/Images/Primary` (`fillWidth=200`) | Token | Scaled for watch display (~466 px) |
| Playback report (M5+) | `POST /Sessions/Playing/*`, `POST /Users/{id}/PlayedItems/{id}` | Token | Optional playback state upload |

## User experience requirements

> **Pending redesign (DEC-014, 2026-09-03) — not yet approved.** The owner has asked for the
> **watch** UI to be redesigned from scratch. The design is specified in
> `specs/10_WATCH_UI_REDESIGN.md` and recorded as **DEC-014** in
> `specs/DECISION_LOG.md`, both with status **PROPOSED — awaiting owner approval**.
>
> Until DEC-014 is approved and implemented, the bullets below remain the approved requirements
> and describe the shipped watch UI. Once it is approved, DEC-014 supersedes the Library screen,
> Collection detail, Downloads screen and Player screen bullets, and the screen-design half of the
> Visual design bullet; the design system, the BT output guard and the Settings bullet are carried
> over unchanged. The `:mobile` phone companion (see "Phone companion app (DEC-013)") is
> explicitly **out of scope** for that redesign.

- **Onboarding flow:** Server discovery/manual URL → Quick Connect code (or login) → prompt to pin a first playlist [PLANNED per PLAN.md §6]. *(The "prompt to pin a first playlist" step is still missing in the shipped app; DEC-014 §5.2 specifies it as `onboarding/first-pick`.)*
- **Library screen:** Tabs for Playlists / Albums / Artists; "synced" badge on pinned collections.
- **Collection detail:** Track list, total size, sync toggle, play action.
- **Downloads screen:** Active queue with progress, errors, storage gauge, manual sync button, `PENDING_BUDGET` items.
- **Player screen:** Album art, title/artist, play/pause/skip, volume (rotary crown), shuffle/repeat. Conforms to Wear media patterns.
- **Settings:** Account/server, storage quota slider, sync constraints, certificate pinning, sync log, logout.
- **BT output guard:** Play attempt without connected Bluetooth audio → trigger system Output Switcher; never play through watch speaker (DEC-006, REQ-009, PLAN.md §5).
- **Visual design (DEC-011, 2026-08-10):** The "No visual redesign" constraint formerly stated here is **superseded** by owner direction. The UI now ships the **Aurora** design system — OLED-black base, violet→magenta gradient accent, real Material vector icons, a unified `ui/components/` library, localized byte units, and a finished player (seek bar + time readout). Stateless screen contracts, Wear Compose **Material3** imports, and `SwipeDismissableNavHost` navigation are preserved. *(DEC-014, PROPOSED, supersedes the screen design only: the palette, typography, gradient, vector icons, byte units, finished player, Wear Material3 imports and `SwipeDismissableNavHost` navigation are all carried over. The `AuroraChip`-centred component library and the screen inventory are not.)*

## Background and asynchronous work

[PLANNED per DEC-005, REQ-007]

- **Automatic sync:** `PeriodicWorkRequest` via WorkManager with constraints: `setRequiresCharging(true)` + `NetworkType.UNMETERED`. Configurable periodicity (default daily). Fires during overnight charging — Doze restrictions lifted while charging.
- **Manual sync:** Explicit user action from Downloads screen or companion. Low battery requires an explicit per-request confirmation before the queue can resume. The companion observes `LOW_BATTERY_CONFIRMATION_REQUIRED` and resends `SyncCommand(allowLowBattery = true)` only after consent. An authorized pass acquires high-bandwidth network via Horologist `network-awareness` and resumes the Media3 queue, even when Wi-Fi is already active and the watch is not charging. If Wi-Fi cannot be acquired on a metered connection, the pass ends successfully with `METERED_CONFIRMATION_REQUIRED`; the phone resends `SyncCommand(allowMetered = true)` only after explicit consent. Automatic sync keeps its charging and unmetered-network constraints.
- **FGS `dataSync`:** Foreground service with `dataSync` type for active download operations. Subject to Android 15+ 6-hour cap.
- **FGS cap mitigation:** When cap expires, WorkManager re-arms the sync cycle. Media3 `DownloadManager` resumes partial downloads via HTTP Range from the byte offset where interruption occurred. UI displays "Sync paused — will resume" rather than failure (DEC-005).
- **Network changes during sync:** Metered network detected during active sync → pause + notification. Return to unmetered → auto-resume. Manual sync on metered → cap + prompt (Horologist network-awareness, PLAN.md task 12).

## Storage and retention

[PLANNED per REQ-008, PLAN.md §4.4]

- **Location:** App-private internal storage (`filesDir`). No `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` permissions. Files cleaned up automatically on app uninstall.
- **Budget:** User-configurable quota (default ~6 GB on a 32 GB OnePlus Watch 4). Gauge shows used / quota / free disk.
- **Capacity check:** `BudgetPlanner.plan()` runs before enqueue. If download batch exceeds budget: partial queue + visible alert. Never silent failure.
- **Artist batching:** "Download all" for an artist resolves albums greedily up to budget cap. Remaining albums get status `PENDING_BUDGET` and auto-release when space frees (PLAN.md §4.4).
- **Orphan purge:** Track removed from all pinned collections → file deleted (refcounted via `CollectionTrack` n-n table, REQ-006).
- **Storage full:** Sync stops, user sees warning, must free space or adjust quota before sync resumes.

## Configuration and secrets

| Item | Location | Notes |
|---|---|---|
| Jellyfin access token | `EncryptedSharedPreferences` (Android Keystore) | Never in Room, logs, or plaintext (REQ-005) |
| SDK directory | `local.properties` `sdk.dir` | Standard Android SDK path; not committed |
| Sync constraints | DataStore | Charge + Wi-Fi defaults; configurable in Settings |
| Storage quota | DataStore | Configurable slider in Settings |
| Server URL / cert | Room `Server` entity | URL is not a secret; certificate pin is opt-in |

> **No secret values are recorded in this specification or any `/specs` file.**

## Observability and operations

**Current state: none beyond logs** `[MISSING]`.

- No crash reporting, analytics, or tracing SDK is integrated.
- Android Logcat output is the only current observability surface.
- Sync log (consultable in-app, last sync pass summary) is planned per PLAN.md §4.2 step 7 but not yet implemented.
- No remote telemetry is planned for v1.
- CI `android.yml` provides build/test signal; no performance or battery monitoring infrastructure exists.

> This is a gap — no observability tooling exists. Not blocking for bootstrap; should be addressed before release (M6).

## Security and privacy

- **TLS:** Public certificates validated by default. Self-signed certificates handled via opt-in certificate pinning (per-server, explicit user action). Global TLS bypass is never used (DEC-006).
- **Token handling:** Access token stored in `EncryptedSharedPreferences` backed by hardware-backed Keystore. Token never persisted in Room, shared preferences (non-encrypted), logs, or files.
- **Storage:** All downloaded files in app-private `filesDir` — inaccessible to other apps without root.
- **Network:** All Jellyfin communication over HTTPS where available; HTTP allowed only for local network discovery (UDP 7359 + manual URL entry for LAN servers).
- **Permissions:** No storage permissions requested. Only `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (types `dataSync` + `mediaPlayback`), `POST_NOTIFICATIONS`.
- **No analytics/tracking:** No third-party analytics, no telemetry SDKs, no advertising identifiers.


## Phone companion app (DEC-013)

[VERIFIED per DEC-013, 2026-08-11]

The `:mobile` module is an optional phone companion app that communicates with the watch over the Wearable Data Layer. Both apps share `applicationId = "com.example.jellywrist"` and the same signing certificate — required by the Wearable Data Layer same-package rule.

**Shared layer (`:core`):** JellyfinBackend, AuthRepository, ServerRepository, TokenStore, TLS certificate pinning (PinRegistry/PinningTrustManager/CertificateFetcher), and the wire protocol (`com.example.jellywrist.wire`). The phone signs into Jellyfin via Quick Connect (extends DEC-010 — no password UI on any form factor).

**Wire protocol:** Versioned message paths under `/jellywrist/*/v1`:
- `SETUP` (phone→watch): sends the validated server URL, the phone's `serverId` and `userId`, and an optional pinned cert SHA-256; the watch compares that identity against its own session (see **Reconfiguration** below), then validates, initiates Quick Connect, and replies with the code. `serverId`/`userId` are nullable — a peer that predates them falls back to URL comparison.
- `SETUP_QC` (watch→phone): the Quick Connect code; phone auto-approves it via `QuickConnectApi.authorizeQuickConnect`.
- `SETUP_RESULT` (watch→phone): success + user name, or error (invalid URL, unreachable, QC unavailable, expired, auth failed).
- `STATUS_REQUEST` / `STATUS_REPLY` (phone→watch→phone): watch status (server, user, storage, pinned collections, last sync).
- `PIN` / `PIN_REPLY` (phone→watch→phone): pin/unpin a collection remotely; watch upserts the collection and triggers sync.
- `SYNC_NOW` / `SYNC_REPLY` (phone→watch→phone): `SyncCommand(allowMetered = false, allowLowBattery = false)` triggers an immediate sync. Both consent fields are per-request and default to `false`; an empty legacy payload remains equivalent to no consent.

**Watch receiver:** `CompanionListenerService` (WearableListenerService) handles all paths; `CompanionSetupCoordinator` orchestrates the provisioning flow on an app-scoped coroutine. The watch declares the `jellywrist_watch` capability for phone discovery.

**Phone UI:** Onboarding (server entry + Quick Connect) → Home (watch status card, storage gauge, sync now, pinned collections with unpin) → Browse (playlists/albums/artists with pin toggle). Material3 Compose with the Aurora palette.

**Transport invariant — subscribe before you send.** `WatchLink.incoming()` is a hot, non-replaying callback flow: any message emitted while no collector is attached is lost forever. Every phone-side exchange therefore opens its listener *before* sending the request, and provisioning keeps **one** listener (`SetupReplyListener`) alive across both phases — the watch polls Quick Connect continuously and can emit `SETUP_RESULT` while the phone's `authorizeQuickConnect` call is still in flight. The listener records the terminal result whether or not anyone is awaiting it yet. `WatchLink` is an interface (`WearableWatchLink` in production) so these races are covered by JVM tests with a fake transport.

**Reconfiguration — an existing session is not automatically the right one.** The watch acknowledges `SETUP` as `alreadyConfigured` **only** when its session matches the requested server *and* account; `serverId` decides when both peers know it (two canonical URLs can name the same server, and comparing URLs alone would trigger a destructive reconfiguration by mistake), otherwise the normalised base URL decides. On a mismatch the watch replays the full Quick Connect flow against the requested server, and purges the previous server's catalogue — downloads, Room rows, sync log, playback queue — through `ServerDataReset` **after** the new session is authenticated, never before: an expired code or an unreachable server must leave the watch exactly as it was. Without this, the phone's "Reconfigure" button was inert and subsequently pinned collections carried IDs belonging to a foreign server.

**Terminal remote-setup state is process-scoped.** `CompanionSetupCoordinator.state` is a singleton `StateFlow`; `Done`/`Failed` persist until the next provisioning or `reset()`. Sign-out calls `reset()` and runs `LocalDataReset.factoryReset` inside it, under the setup lock (see **Cancellation** and **Sign-out holds the setup lock** below), and the onboarding screen honours a `Done` only while a session actually exists — otherwise a completed setup would authenticate an onboarding instance created *after* sign-out and navigate to a library with no token.

**Cancellation covers the whole provisioning operation.** What is tracked and cancelled is the entire `SETUP` run — server validation and Quick Connect initiation included, not only the poll that follows them. Tracking the poll alone left the earlier steps uncancellable, so a coroutine still validating could persist a session *after* a factory reset and resurrect the account the user had just deleted. `reset()` therefore suspends and `cancelAndJoin`s, sign-out calls it **before** `LocalDataReset.factoryReset()` — the token is erased only once provisioning is known dead — and a mutex serialises replacement so a newer `SETUP` request also waits for the previous run to die. Because cancellation now reaches every suspension point, the **local writes** that follow authentication are non-cancellable: once the token is persisted, committing the pin and purging the previous catalogue complete as one indivisible step — a cancellation landing between authentication and the purge would otherwise leave the new session sitting on the previous server's catalogue, the exact inconsistency reconfiguration exists to remove. The region stops there. `SETUP_RESULT` is sent through Play Services with no timeout of its own, so including it would let a sign-out wait on a degraded Bluetooth round trip while holding the setup mutex; the reply and the `Done` state stay cancellable, and a companion that receives nothing falls back on its own timeout. Sign-out's own body is likewise non-cancellable — `reset()` suspends, and a ViewModel destroyed mid-wait would run its `finally` and report "signed out" on screen while the token survived.

**Sign-out holds the setup lock for the whole factory reset.** Cancellation alone was not mutual exclusion: `reset()` released the mutex before the purge began, and a `SETUP` arriving during it — WorkManager items, Media3 bytes, Room tables, none of it instant — took the free lock and started a full Quick Connect run that could authenticate *after* the token was erased (erased last) and leave the watch signed in to the account the user had just deleted. `reset` therefore takes a block run **while still holding the lock** (`reset { localDataReset.factoryReset() }`); since `handleSetup` must take the same mutex, a concurrent request waits for the wipe to finish and then provisions a genuinely blank watch — the intended flow rather than the race. The cost is bounded: `handleSetup` releases the lock before joining its job, so a five-minute Quick Connect poll never holds it; only the purge does. `SETUP` arrives over `MessageClient`, which never replays, so nothing stale is redelivered afterwards.

**The Quick Connect poll is a state machine, not an exception channel.** `pollQuickConnect` must never throw (cancellation aside), and that is a contract rather than a convenience: all three collectors run inside a `launch` with no exception handler, and on the watch that scope is a bare `SupervisorJob` — anything escaping it kills the process instead of answering `SETUP_RESULT`, leaving the phone with a blind 90 s timeout. A transport failure is therefore absorbed for `FAILURE_GRACE` (20 s — a two-second Wi-Fi handover must not condemn a wait that can last five minutes) and then reported as `QuickConnectState.Unavailable`, a terminal state distinct from `Expired`: the request may well still be valid server-side, it is the path that vanished, so the UI offers a retry rather than a new code. The grace stays well under the phone's 90 s `RESULT_TIMEOUT` so a genuinely dead server is *named* while the companion is still listening. Behind that, `runSetup` carries a last-resort catch answering `WireErrors.INTERNAL` (both peers already have an `else` branch, so no new strings and no wire break) — `reply` itself goes through Play Services and can throw. That catch's verdict depends on how far the run got: once authentication has persisted a session the watch *is* configured, so a failure past that point is a lost announcement rather than a failed provisioning, and it publishes `Done` and retries the send once — publishing `Failed` would drive the onboarding screen to an error while the watch was signed in. Cancellation is always rethrown first: `reset()`'s `cancelAndJoin` depends on it.

**Factory reset clears the token first.** `LocalDataReset.factoryReset()` erases the session before purging WorkManager, Media3, Room and the playback queue. The order is a security guarantee, not housekeeping: sign-out announces "signed out" from a `finally` and navigates regardless, so a token surviving a purge that threw would leave the user believing they had signed out while their credentials still sat in the Keystore. Nothing in the purge needs the session.

**A certificate pin proposed by the phone is staged, not committed.** The SHA-256 carried by `SETUP` is applied to the in-memory `PinRegistry` only — enough for the TLS handshake that follows — and written to `SettingsRepository` only once authentication succeeds. Any other outcome, cancellation included, restores the previous pin. Persisting on arrival meant a failed reconfiguration kept the previous session while the trust manager had already stopped accepting that server's certificate, making a working server unreachable. The commit flag that stops the rollback is therefore set **last**, after both writes: setting it up front covered them by anticipation, and once those writes were caught rather than fatal, a failed `setPinnedCertificate` left the staged pin in the registry — where it outranks the accepted fingerprint and `update()` deliberately cannot clear it — so the watch would trust a certificate no setting records for the rest of the process's life. A staged pin is a **state of its own** in the registry, distinct from the accepted fingerprint and taking precedence over it: the settings collector re-applies the accepted pin on every emission, including settings unrelated to TLS, so without that separation any setting changed during the Quick Connect window (up to five minutes) would revert the certificate under validation and fail the handshake in flight.

**Clearing a server's data re-arms periodic sync; factory reset does not.** The purge cancels every work item carrying the sync tag, the unique periodic request included, and nothing else recreates it before the next process start or settings change. `ServerDataReset.clearServerData()` therefore re-schedules it, leaving a reconfigured watch able to sync on its own; `factoryReset()` deliberately does not — a signed-out watch must have nothing scheduled. This re-arming has no JVM coverage (`SyncScheduler` needs WorkManager and an Android `Context`); it is a check for the first paired-emulator E2E run. It also covers the companion path only: signing out and back in *on the watch* leaves periodic sync off until the next process start or settings change, because onboarding never schedules it — a known gap with the same silent symptom on the local path.

**Status coherence.** A successful `SETUP_RESULT` refreshes `WatchStatusRepository` from `WatchSetupController`, so the Home screen leaves the `configured = false` layout without a manual pull-to-refresh.

**Manual sync feedback.** `WatchStatus.syncState` and `WatchStatus.syncBlocker` are nullable and defaulted so v1 peers remain compatible. After `SYNC_REPLY`, the phone polls status for a bounded period and renders queued, syncing, waiting for consent, completed, paused, or failed. `METERED_CONFIRMATION_REQUIRED` opens a confirmation dialog; only confirmation sends a second command with metered use enabled.

**Cover art.** The phone declares its own Coil `ImageLoader` (`MobileCoilModule`, installed through `SingletonImageLoader.Factory` on `MobileApp`) built on the shared `OkHttpClient`. Covers therefore honour the opt-in certificate pinning (DEC-006) and carry the Jellyfin `Authorization` header; the default Coil loader would fail TLS on an approved self-signed server and send no credentials.

## Testing and verification

[VERIFIED per REQ-011, PLAN.md §10]

| Test tier | Scope | Method |
|---|---|---|
| **JVM unit** (fast) | DTOs, serialization, Jellyfin client via MockWebServer, repository cache/network interplay, `SyncPlan.diff`, `BudgetPlanner.plan` | TDD; green before each milestone |
| **Instrumented** (emulator) | Room migrations, DataStore persistence, download resume after process kill (`DownloadManager` survives kill) | Wear OS round emulator (API 34/36) |
| **Manual device matrix** | End-to-end scenarios | 1× emulator (round), 1× physical watch, 1× BT headphones |

**Manual scenarios (PLAN.md §10):**
1. Cold start → browse → download an album
2. Sync a collection on charge+Wi-Fi overnight
3. Cut network mid-sync → restore → observe auto-resume
4. Server-side deletion → trigger recon → local file removed
5. Insufficient space → refusal dialog / partial queue
6. 30 min offline playback → no speaker leak

**CI gate:** `.\gradlew.bat assembleDebug testDebugUnitTest` on every push to `main` and on PRs (`.github/workflows/android.yml`) [VERIFIED].

## Delivery, rollout, and rollback

- **Build:** `.\gradlew.bat assembleDebug` produces installable APK.
- **Install:** `.\gradlew.bat installDebug` (ADB/Wi-Fi deploy to connected device or emulator).
- **CI:** `.github/workflows/android.yml` runs `assembleDebug` + `testDebugUnitTest` on push/PR [VERIFIED].
- **Distribution:** Side-load only. No Play Store, no signed release builds at this stage. Distribution channel is an open question (PLAN.md §12).
- **Rollback:** No production users exist. Rollback = discard local build. No migration, no data loss risk.
- **No OTA updates:** App updates via fresh `installDebug` during development.

## Failure modes

| Failure | Detection | Response |
|---|---|---|
| **FGS 6h cap** | System kills `dataSync` foreground service | WorkManager re-arms sync cycle; DownloadManager resumes via Range from last byte offset; UI shows "paused, will resume" (DEC-005) |
| **Storage full** | `BudgetPlanner.plan()` or `StatFs` reports insufficient space | Sync stops; visible warning in Downloads screen; user must free space or adjust quota. No silent failure (REQ-008) |
| **Offline server** | Network unreachable or DNS failure during sync | Sync pauses; retries on next WorkManager cycle or manual trigger; already-downloaded content remains playable |
| **Manual Wi-Fi acquisition denied** | High-bandwidth lease denied, active network metered, and no explicit consent | Finish the request without retry; expose `METERED_CONFIRMATION_REQUIRED`; phone asks before resending with metered use enabled |
| **Process death during download** | DownloadManager persists state across process death | Resume from byte offset on next sync/enqueue (Media3 feature) |
| **Unsupported codec** | Codec field in Jellyfin metadata checked at diff time | Track flagged as `UNSUPPORTED` in Room; visible in UI; not enqueued for download (DEC-004) |
| **Corrupted download** | Post-download size verification vs. `Content-Length` / server metadata | File deleted; re-enqueued with backoff retry |
| **Self-signed certificate** | TLS handshake failure | Prompt for opt-in certificate pinning; never bypass TLS globally (DEC-006) |
| **Auth token expiry** | 401 response from Jellyfin | Re-authentication flow (Quick Connect or login) |
| **Unreadable encrypted session** | Recognized Keystore, keyset, or decryption failure while loading `jellywrist_auth.xml` | Remove only the encrypted session preferences and app-owned key alias; preserve all other local data; show onboarding with a reconnect notice |

## Definition of done for v1

A v1 release is done when all of the following are true:

1. All REQ-001 through REQ-009 requirements have passing verification evidence (unit tests, instrumented tests, or device checks as specified in the requirement catalogue).
2. CI `android.yml` is green on the release candidate commit.
3. The app installs and runs on a physical Wear OS 5+ device (emulator acceptable for bootstrap, physical required for M6).
4. **Core scenario:** A user can pin a playlist → the playlist fully downloads → they can play the entire playlist offline with Bluetooth headphones, with no phone connected and no network available.
5. **Resume scenario:** A download interrupted (process kill or FGS cap) resumes correctly from the interruption point on the next sync cycle.
6. **Convergence scenario:** Server-side changes (track added to / removed from a pinned collection) are reflected locally after the next automatic sync cycle.
7. **No silent failures:** Every error condition listed in Failure modes produces a visible user-facing signal.
8. No `[MISSING]` or `[UNVERIFIED]` items remain in the sections governing delivered behaviour.
9. `specs/05_BUILD_REVIEW.md` contains a PASS verdict from an independent review.
