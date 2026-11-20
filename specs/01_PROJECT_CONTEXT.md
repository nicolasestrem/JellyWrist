# Project Context

## Summary
JellyWrist is a watch-first Wear OS application that syncs music collections from a Jellyfin media server for fully-offline Bluetooth playback. An optional phone companion (`:mobile`) provisions the watch and manages its library over the Wearable Data Layer; the watch never *requires* a paired phone (DEC-013). The public brand and source namespace changed from JellyWatch to JellyWrist on 2026-08-11 (DEC-012); the Android `applicationId` and every persistence and scheduling identifier now use the `jellywrist` identity (DEC-016), because 0.0.1 is the first public release and no pre-public installation needs preserving.

The project's first public release is `0.0.1` (versionCode 1 in both `:app` and `:mobile`), licensed under MIT (Copyright (c) 2026 nicolasestrem). The public repository starts from a single reviewed root commit; the pre-release private history is not published.

## Users and stakeholders
- **End user**: Wears a Wear OS 5+ smartwatch and wants their Jellyfin music library available offline over Bluetooth headphones. A phone companion is optional and only assists provisioning and remote management [VERIFIED: README.md, REQ-001 as amended by DEC-013].
- **Jellyfin server owner**: Runs a Jellyfin instance (self-hosted or remote). The client must not mutate server-side data beyond optional playback reports; playback reporting itself is still deferred (REQ-010) [VERIFIED: PLAN.md §3, DEC-006, `SyncEngine.kt`].
- **Developer / repo owner**: nicolasestrem. Author of the specs, the repository contract, and the implementation; the attribution is intentional public metadata [VERIFIED: `AGENTS.md`, `specs/DECISION_LOG.md`, `LICENSE`].

## Current or starting state
The public repository starts at the `0.0.1` release state: a single reviewed root commit, versionCode 1, and both application modules on versionName `0.0.1`. No earlier commits, pull requests, branches, or CI runs exist in that repository [VERIFIED: `app/build.gradle.kts`, `mobile/build.gradle.kts`, `specs/09_PUBLIC_RELEASE_AUDIT.md`].

1. **Three Gradle modules** — `:app` (watch, Wear OS), `:core` (shared Jellyfin/auth/TLS layer and wire protocol), `:mobile` (optional phone companion) [VERIFIED: `settings.gradle.kts` and the three module build files].
2. **Application shell and UI** — Hilt DI graph, Wear Compose `SwipeDismissableNavHost`, onboarding, library/collection/downloads/player/settings screens, the Aurora theme, and a "Resume playback" Tile under package `com.example.jellywrist`; the phone app adds Compose Material3 onboarding, browse, and home screens under `com.example.jellywrist.mobile` [VERIFIED: local source tree].
3. **Authentication, Room database, sync, download, and playback are implemented** (detailed below); the earlier "none of these exist" statements in this file are obsolete [VERIFIED: local source tree, `CHANGELOG.md`].

## Verified stack and dependencies
[VERIFIED: `gradle/libs.versions.toml`, `settings.gradle.kts`, `app/build.gradle.kts`, `core/build.gradle.kts`, `mobile/build.gradle.kts`]

| Component | Version / Detail |
|---|---|
| AGP | 9.4.1 |
| Kotlin | 2.4.10 |
| KSP | 2.3.11 (active; kapt not used) |
| Hilt | 2.60.1 (DI) |
| Compose BOM | 2026.06.01 |
| Wear Compose | material3/foundation/navigation 1.6.2 |
| Room | 2.8.4 (schema v2, exported JSON) |
| Media3 | 1.7.1 (pinned to the version Horologist 0.7.15 is compiled against) |
| Horologist | 0.7.15 |
| WorkManager | 2.11.2 |
| DataStore | 1.2.1 |
| Jellyfin SDK | 1.8.12 (OkHttp 5.3.2) |
| Serialization | kotlinx.serialization.json 1.9.0 (wire protocol) |
| Companion transport | Google Play services Wearable 19.0.0 (Data Layer) |
| Coil | 3.5.0 |
| compileSdk | 37 (CI provisions `platforms;android-37.0` via absolute-path sdkmanager) |
| minSdk | 34 for `:app` (Wear OS 5+); 26 for `:core` and `:mobile` |
| targetSdk | 36 |
| JVM target | 17 |
| Modules | `:app`, `:core`, `:mobile` |
| Source namespaces | `com.example.jellywrist`, `com.example.jellywrist.core`, `com.example.jellywrist.mobile` |
| Published application ID | `com.example.jellywrist` in both app modules (DEC-016; the same ID and signing key are required by the Wearable Data Layer) |
| Version | 0.0.1 / versionCode 1 in `:app` and `:mobile` |
| Version catalog | `gradle/libs.versions.toml` — mandatory, never hardcode versions |

Every dependency and plugin version is declared in the version catalog; no build script hardcodes a version.

## Current architecture reality
- **Room database exists** — `JellyDatabase` (name `jellywrist.db`, version 2) defines six entities (`ServerEntity`, `AccountEntity`, `CollectionEntity`, `TrackEntity`, `CollectionTrackEntity`, `SyncLogEntity`) and six DAOs (`ServerDao`, `AccountDao`, `CollectionDao`, `TrackDao`, `CollectionTrackDao`, `SyncLogDao`). `exportSchema = true` writes JSON schemas under `app/schemas`, wired as androidTest assets for `MigrationTestHelper`; `MIGRATION_1_2` adds `collections.localOnly` for local collections (REQ-012 / DEC-009) [VERIFIED: `app/src/main/java/com/example/jellywrist/data/db/`, `app/schemas/`, `app/src/androidTest/.../MigrationTest.kt`].
- **Jellyfin network layer exists** — the official Jellyfin SDK behind `JellyfinBackend`; `ServerRepository` (UDP 7359 discovery, `GET /System/Info/Public` validation), `UrlNormalizer`, a TLS layer with opt-in certificate pinning (`CertificateFetcher`, `PinningTrustManager`, `PinRegistry`), and `JellyDataSource` adapting OkHttp 5 to Media3. No global TLS bypass [VERIFIED: `core/src/main/...`, `app/src/main/java/com/example/jellywrist/data/net/`, DEC-006].
- **Authentication exists** — Quick Connect is the implemented sign-in path in both apps: `AuthRepository` initiates a code, polls state (2 s cadence, 5 min budget), exchanges the approved secret for a token, and persists the session only in `EncryptedSharedPreferences` backed by the Android Keystore. The token is never stored in Room or logged. During watch provisioning the phone approves the watch's Quick Connect code over the Data Layer [VERIFIED: `AuthRepository.kt`, `TokenStore.kt`, `MobileOnboardingViewModel.kt`, `WatchSetupController.kt`, `CHANGELOG.md`]. *DEC-002's `AuthenticateByName` password fallback was later removed by DEC-010 (Quick Connect only); no credential UI or implementation remains, and a server without Quick Connect gets an explicit retry state.*
- **Sync, download, and playback exist** — `SyncEngine` runs the diff → budget → enqueue/remove/log pipeline over the pure `SyncPlan`, `BudgetPlanner`, and `SyncStateReconciler`; `ReconWorker` and `SyncScheduler` schedule periodic passes (charging + unmetered constraints, configurable 6–168 h, default 24 h) and manual expedited passes with Horologist Wi-Fi acquisition and consent. `JellyDownloadService` extends Media3 `DownloadService` (`dataSync` foreground service, `WorkManagerScheduler` re-arm, Range-resumable downloads, PAUSED state, DEC-005). `JellyPlaybackService` is a Media3 `MediaSessionService` with a cache-only ExoPlayer (no streaming), audio offload, a Bluetooth speaker guard, and a persisted queue [VERIFIED: `app/src/main/java/com/example/jellywrist/sync/`, `download/`, `playback/`, `network/`].
- **Companion protocol exists** — versioned Wearable Data Layer paths under `/jellywrist/*/v1` (`WirePaths`) carry setup, status, pin/unpin, and sync-now messages; the watch advertises the `jellywrist_watch` capability [VERIFIED: `core/src/main/.../wire/Wire.kt`, `app/.../companion/`, `mobile/.../datalayer/`].
- **Settings and Wear extras exist** — DataStore-backed settings (quota, charging/unmetered constraints, periodicity, pinned certificate), sync log, factory reset, and a "Resume playback" Tile [VERIFIED: `SettingsRepository.kt`, `LocalDataReset.kt`, `JellyTile.kt`].
- **UI status** — the shipped watch UI is the Aurora-era screen set; the DEC-014 full redesign is still PROPOSED and not implemented [VERIFIED: `specs/DECISION_LOG.md`, `app/src/main/java/com/example/jellywrist/ui/`].

## Data ownership and lifecycle
- **Local Room database exists** (schema version 2) in app-private storage. No production data ships with the app; a first install starts empty [VERIFIED: `JellyDatabase.kt`, tree scan].
- **Jellyfin server is the authoritative source** for all library data (collections, tracks, metadata). The client is read-only with respect to server data; optional playback event reporting remains deferred (REQ-010), so the app uploads no playback or status data today [VERIFIED: PLAN.md §3, DEC-006, `SyncEngine.kt`].
- **Local schema lifecycle**: every future schema change ships a versioned `Migration` plus an instrumented migration test; destructive fallback is prohibited in release (DEC-009 / REQ-012). v1→v2 (`collections.localOnly`) is the only migration so far and is covered by `MigrationTest` [VERIFIED: `JellyDatabase.kt`, `MigrationTest.kt`].
- **Client storage**: downloaded audio lives in the app-private `filesDir` through the Media3 `SimpleCache`; tracks are addressed by database id rather than a file path. No storage permissions are requested [VERIFIED: DEC-006, `Entities.kt`, `AndroidManifest.xml`].
- **Credentials**: the access token exists only in `EncryptedSharedPreferences`/Keystore; the `accounts` table holds identity fields but no secrets [VERIFIED: `TokenStore.kt`, `Entities.kt`].

## Delivery and operations reality
- **CI (REQ-011)**: GitHub Actions workflow `.github/workflows/android.yml` runs on pushes to `main` and on pull requests. It runs the public-hygiene scan, sets up Java 17 and Gradle, provisions and caches Android SDK `platforms;android-37.0` through the absolute-path `sdkmanager`, then runs `./gradlew lintDebug`, `./gradlew assembleDebug testDebugUnitTest`, and `./gradlew assembleRelease`. Only pushes to `main` upload the four watch/mobile debug/release APK artifacts, each with `retention-days: 7`; pull requests run all build/test/lint steps without uploading APKs. Permissions are `contents: read`, actions are pinned to immutable SHAs, and concurrency groups cancel superseded PR runs only [VERIFIED: workflow file].
- **Test suites**: JVM unit suites exist in all three modules — `:core` (server discovery/normalization, auth and session cache, certificate pinning, wire protocol and status-size cap), `:app` (sync plan/budget/engine/reconciler, download mapping/queue/resume, network policy, keep-on-watch, library repository, companion handlers, UI/onboarding), and `:mobile` (watch setup controller/listener, status repository, catalog repository, browse/home/onboarding ViewModels). Instrumented suites cover the Room v1→v2 migration and the encrypted token store on the Android Keystore [VERIFIED: `app/src/test`, `core/src/test`, `mobile/src/test`, `app/src/androidTest`].
- **First public release**: version `0.0.1` (versionCode 1), MIT licensed, from the single reviewed root commit. No signed release or store listing has been published [VERIFIED: module build files, `LICENSE`, `specs/09_PUBLIC_RELEASE_AUDIT.md`].
- **No crash reporting, analytics, or monitoring** is instrumented [VERIFIED: source tree scan].
- **Only the Android CI workflow is published**; earlier agent workflows were removed before publication [VERIFIED: `.github/workflows/` listing, `specs/09_PUBLIC_RELEASE_AUDIT.md`].
- **Verified commands** (from `AGENTS.md`, consistent with CI): `.\gradlew.bat assembleDebug`, `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat lint`, `.\gradlew.bat connectedDebugAndroidTest` (requires a Wear OS emulator or watch), `.\gradlew.bat installDebug`.

## Constraints
- **Watch standalone, optional companion** — the watch must never *require* a paired phone (`com.google.android.wearable.standalone=true`, `android.hardware.type.watch`); the `:mobile` companion is optional (DEC-013). REQ-001 [LOCKED] [VERIFIED: `AndroidManifest.xml`, DEC-013].
- **English documentation convention** — specs, PLAN.md, CHANGELOG.md, and KDoc are written in English; code identifiers are English. `AGENTS.md` [VERIFIED: `AGENTS.md`].
- **Version catalog rule** — all dependency/plugin versions live in `gradle/libs.versions.toml`; hardcoding versions in build scripts is forbidden. `AGENTS.md` [VERIFIED: `gradle/libs.versions.toml` usage].
- **compileSdk 37 CI gotcha** — CI must provision `platforms;android-37.0` via absolute-path sdkmanager because that SDK package is not preinstalled on GitHub runners. `AGENTS.md` [VERIFIED: workflow file].
- **Wear OS 5+ watch** (minSdk 34); the shared library and phone companion support minSdk 26 [VERIFIED: module build files].
- **Hilt DI** — mandatory; no manual service locators or other DI frameworks. DEC-006 [LOCKED].
- **No transcoding in v1** — unsupported codecs detected and flagged, not transcoded. DEC-004 [LOCKED].
- **Room migration discipline** — versioned migrations with instrumented tests; no destructive fallback in release. DEC-009 / REQ-012 [LOCKED].
- **Public repository hygiene** — tracked content can become public: reserved example hosts only, no secrets, no local agent artifacts, and `scripts/check-public-hygiene.sh` runs in CI. `AGENTS.md` [VERIFIED: workflow file].

## Behaviour or assets that must not be damaged
- **Room schema and migrations** — the exported schemas, `JellyDatabase` version 2, and `MIGRATION_1_2` must not be lost or regressed; migration tests read the exported JSON [VERIFIED: `JellyDatabase.kt`, `app/schemas/`, REQ-012].
- **Stable identifiers** — the `applicationId` `com.example.jellywrist`, database name, encrypted preferences file, DataStore names, and WorkManager tags are compatibility identifiers for this release line; changing them loses local state or breaks phone↔watch delivery (DEC-016, DEC-013) [VERIFIED: module build files, DECISION_LOG.md].
- **Wire protocol compatibility** — DTOs and versioned `/jellywrist/*/v1` paths must stay compatible; an incompatible payload change requires a new path version, because both apps ship independently (DEC-013) [VERIFIED: `Wire.kt`].
- **Encrypted token handling** — no secret may reach Room, plain preferences, or logs (REQ-005) [VERIFIED: `TokenStore.kt`].
- **Sync refcount and purge semantics** — a track may only be deleted when it belongs to no pinned collection, and local-only collections reconcile by identity (REQ-006) [VERIFIED: `CollectionTrackDao.orphanTrackIds`, `SyncEngine.kt`].
- **PLAN.md** — the unified architecture plan remains the detailed design reference; decisions lifted from it into `DECISION_LOG.md` are preserved there [VERIFIED: `AGENTS.md`].
- **Repository contract** — `AGENTS.md` is the sole auto-discovered agent contract; `docs/claude-code-reference.md` preserves tool-specific build and Wear OS guidance [VERIFIED: tree listing].
- **CI workflow** — must remain functional and green; any phase plan must keep CI green (REQ-011) [VERIFIED: workflow file].

## Non-goals
From PLAN.md §1.3 ("out of scope v1"):
- Live streaming (no server-side transcoding pipeline) [VERIFIED: PLAN.md §1.3, DEC-004].
- A *required* phone companion: the phone app is optional and the watch remains fully standalone (DEC-013 supersedes the earlier "no phone companion" exclusion) [VERIFIED: DEC-013].
- Multi-user / multi-server profiles in v1 (single server/account) [VERIFIED: PLAN.md §1.3].
- Video playback on the watch [VERIFIED: PLAN.md §1.3].

From EXPLICIT_NON_GOALS — confirmed 2026-08-07 by Q-004=A as generic exclusions (DEC-008); PLAN.md §1.3 remains the operative non-goal list:
- Billing [VERIFIED: run configuration — though no billing ever existed in this repo].
- Admin tools [VERIFIED: run configuration].
- Queue replacement [VERIFIED: run configuration].

## Existing risks
From PLAN.md §9 and repo evidence:

| Risk | Source | Impact |
|---|---|---|
| **Foreground Service (FGS) 6-hour cap** (Android 15+) | PLAN.md §9, DEC-005 | Implemented: the download service re-arms via `WorkManagerScheduler`, a truncated pass ends `PAUSED`, and downloads resume by HTTP Range. Physical-device verification of the cap itself remains outstanding. |
| **Storage pressure on 8–32 GB shared storage** | PLAN.md §9, REQ-008 | `BudgetPlanner` + `DeviceStorage` + the user quota cap usage before enqueue; constrained-device behaviour is unit-tested, not device-verified. |
| **Wi-Fi acquisition on Wear OS** | PLAN.md §9, REQ-007 | Implemented via Horologist network-awareness plus consent prompts; availability still varies by watch model. |
| **Room migration discipline** | DEC-009, REQ-012 | Schema v2 and the v1→v2 migration exist and are tested; every future schema change must ship a versioned `Migration` with an instrumented test. |
| **Lite flavor scope undefined** | GAP-007 | A reduced-feature "lite" build flavor is locked (DEC-007) but its cut list and mechanism (Q-006) are unspecified; blocks lite work only. |

## Open questions
See the Human decision queue in [`00_PROJECT_INTAKE.md`](00_PROJECT_INTAKE.md) for full question text and options.

Answered 2026-08-07: **Q-001**=A (template artifact; goal = PLAN.md roadmap → DEC-008) · **Q-002**=B (lite = separate reduced-feature build flavor → DEC-007 LOCKED) · **Q-003**=C (Room-migration discipline → DEC-009 / REQ-012) · **Q-004**=A (generic exclusions confirmed → DEC-008) · **Q-005**=A (the approved pull was executed in the pre-release checkout; the public repository is created from one reviewed root commit, so that history is not published).

Still open:
- **Q-006**: Which capabilities are excluded from the lite flavor, and how is it delivered (footprint-only, reduced feature set, or other)? Blocks lite-flavor work only (GAP-007).

Additional open items from PLAN.md §12 (product questions not yet asked in the batch):
- Final app name and icon — settled by DEC-012 (JellyWrist; Orbit Pulse mark) [VERIFIED: `specs/DECISION_LOG.md`, `docs/brand/`].
- Default sync periodicity — implemented as a user setting (6–168 h, default 24 h); the product default has not been separately approved [VERIFIED: `SettingsRepository.kt`, `SyncScheduler.kt`].
- Collection pin limit / UX for large libraries on small screens — still open; no pin cap is implemented, and a full watch-UI redesign is proposed but not implemented (DEC-014) [VERIFIED: source tree scan, `specs/DECISION_LOG.md`].

## Sources used
| Source | Sections consulted | Role |
|---|---|---|
| PLAN.md ("unified plan") | §1 (vision/scope), §3 (Jellyfin integration), §4 (sync engine), §5 (playback), §7 (Room model), §8 (milestones), §9 (risks), §12 (open questions) | Primary architecture and roadmap reference |
| `AGENTS.md` | Full file | Repository contract, conventions, commands, hygiene rules |
| `docs/claude-code-reference.md` | Full file | Tool-specific build and Wear OS guidance |
| `specs/DECISION_LOG.md` | Full file | Settled decisions and DEC-014 status |
| `specs/07_KNOWN_GAPS.md` | GAP-005, GAP-007, ASM-002 entries | Open gaps and assumptions |
| `specs/09_PUBLIC_RELEASE_AUDIT.md` | Full file | Publication model and release state |
| `gradle/libs.versions.toml` | Full file | Stack version evidence |
| `settings.gradle.kts`, `app/build.gradle.kts`, `core/build.gradle.kts`, `mobile/build.gradle.kts` | Full files | Modules, SDK targets, application IDs, version |
| `app/src/main/java/`, `core/src/main/java/`, `mobile/src/main/java/` | Full scan | Current implementation inventory |
| `app/src/test/`, `core/src/test/`, `mobile/src/test/`, `app/src/androidTest/` | Full scan | Test-suite inventory |
| `app/schemas/` | Exported schema v2 | Room schema evidence |
| `.github/workflows/android.yml` | Full file | CI pipeline evidence |
| `LICENSE` | Full file | MIT license and copyright |
| `README.md` | Status and module sections | Product status and module summary |
