# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.0.1] - 2026-11-20

First public source release. The version line restarts at `0.0.1` (`versionCode` 1) in `:app`
and `:mobile` after the internal pre-release snapshots recorded below, none of which were
ever published.

### Added
- **MIT license**: the source is now released under the MIT license,
  Copyright (c) 2026 nicolasestrem.
- **Dependabot configuration**: weekly dependency-update pull requests for Gradle dependencies
  and GitHub Actions (`.github/dependabot.yml`).

### Changed
- **Fresh repository history**: the public repository is recreated from one reviewed root
  commit. The previous private history is not published, so old commit SHAs, pull request
  numbers, branches, and CI run URLs are not valid references.
- **Public package identity (DEC-016)**: the `applicationId` is now `com.example.jellywrist`
  in both apps, and the Room database (`jellywrist.db`), encrypted preferences
  (`jellywrist_auth.xml`), DataStores (`jellywrist_settings`, `jellywrist_queue`),
  WorkManager names/tags, and download notification channel use the `jellywrist` identity.
- **Dependency updates**: AGP 9.5.1, KSP 2.3.14, coroutines 1.11.0, Coil 3.6.3,
  slf4j 2.0.20, and current GitHub Actions versions.
- **Documentation refreshed** for the first public release.
- **Install note**: 0.0.1 is the first public identity. Pre-public
  `com.example.jellywatch` builds are a separate package and are not upgraded in place;
  uninstall them before installing this release.

### Fixed
- **Instrumented migration test can find the Room v1 schema again**: the exported v1
  schema now lives under the current `com.example.jellywrist.data.db.JellyDatabase`
  namespace, so `MigrationTest` no longer fails with "Cannot find the schema file in the
  assets folder". The duplicate legacy JellyWatch schema folder was removed; the two v2
  exports were byte-identical.

### Security
- **CI enforces the public-hygiene scan**: every push to `main` and every pull request runs
  `scripts/check-public-hygiene.sh`, which fails when the tree contains credential-shaped
  material, private LAN addresses or developer home paths, unreviewed URL hosts, signing
  material, local configuration, packaged apps, or tracked local assistant artifacts. The scan
  checks the staged index as well as the working tree.

### Privacy
- **Synthetic test identities**: test fixtures use reserved example hosts and visibly fake
  server and account identities instead of data from a real deployment.
- **Assistant run log removed**: the assistant run log and its session artifacts are no longer
  tracked, and the hygiene scan rejects them if they reappear.

Build and test verification for this release is recorded in `specs/05_BUILD_REVIEW.md`; this
changelog deliberately does not restate test counts or lint results.

## Pre-release development history (unpublished)

These entries record internal iterations completed before the first public release and are
retained for traceability only. They describe unpublished pre-release snapshots and are not
part of the published 0.0.1 release.

### Changed — CI artifact storage
- Retain watch and mobile debug/release APK artifacts for seven days and upload
  them only on pushes to `main`; pull requests still run lint, unit tests, and
  debug/release builds without uploading APKs.
- Group CI concurrency by workflow and pull request number or ref. Superseded
  pull request runs are cancelled; running `main` builds are not cancelled.
- Gradle cache settings, pinned actions, and workflow permissions are unchanged.

### Changed — CI Android SDK provisioning
- Cache the `android-37.0` platform, which is not preinstalled on the `ubuntu-latest`
  runner image and was therefore re-downloaded by `sdkmanager` on every run.
  The cache uses a single fixed key, so it is one entry reused by all runs rather
  than a per-run entry, and the `sdkmanager` step is skipped on a cache hit.
- The SDK location is resolved once into `ANDROID_SDK_DIR`, keeping the previous
  `${ANDROID_HOME:-/usr/local/lib/android/sdk}` fallback, so the cache path and the
  provisioning step cannot disagree about where the SDK lives.
- Build, lint, and test steps are unchanged; this only removes repeated downloads.

### Documentation — Wear OS UI direction board (2026-09-04)
- Added an interactive, matched-state comparison under
  `docs/design/watch-ui-directions/` for choosing between Orbit Stack, Cover Stage,
  Signal Ledger, and Edge Orbit before approving DEC-014.
- The board compares Home, Browse, Collection, Now Playing, Sync, and Ambient using
  the same offline-music flows in every direction, and includes an explicit 192dp
  stress probe for each option.
- Documentation only. DEC-014 remains proposed; no production UI, behaviour,
  dependency, or compatibility identifier changed.

### Fixed — download queue thread confinement, and a manual run that never released the queue (2026-09-03)
- **Every `DownloadManager` call now runs on the manager's own looper.** Media3's class
  documentation requires it — "must be accessed only from the thread that created it" — and
  enforces none of it: no public method asserts the thread, `pendingMessages` is incremented
  without synchronisation, and `currentDownloads` returns its live list field. So the previous
  code did not fail when the sync worker and the application scope called in from WorkManager
  and `Dispatchers.Default` threads; it raced, and a read answered with whatever list had last
  been published to the calling thread. That silence is the bug: the foreground recovery
  decided whether to restart the service from a count it had no right to trust. `DownloadQueue`
  is now a `suspend` port whose Media3 implementation hops onto `applicationLooper` first.
- **The pending-work check moved behind that port.** `DownloadResumer` asks
  `DownloadQueue.hasPendingDownloads()` instead of reading `DownloadManager.currentDownloads`
  itself, which also makes the decision testable: `DownloadManager` cannot be built on the JVM,
  so before the split there was nothing to assert against. Three new `DownloadResumerTest`
  cases cover pending work, an empty queue, and a refused service start.
- **A manual sync pass now releases its override — but only if it enqueued nothing.**
  `NetworkAwareness._manualRunActive` was cleared only when the download progress flow *changed*
  to empty. A pass that enqueued nothing — an already-synced library, no pins, a failure before
  the enqueue — produced no such emission, so the flag stayed set and suppressed the REQ-007
  charging and metered-network pauses for every later automatic pass. `ReconWorker` now calls
  `manualRunFinished()` in a `finally`, whatever the outcome. It clears the flag only when the
  pass left nothing to download: `syncPass` returns once tracks are *registered*, so a run that
  enqueued work is still downloading when the worker finishes, and releasing the queue there
  would let the charging observer pause the very download the user authorised off the charger.
  Those runs stay owned until the queue drains. The count comes from Room
  (`TrackDao.outstandingCount()`), which `SyncEngine` writes before handing ids to the
  downloader, so it is accurate the instant the pass returns; asking `DownloadManager` would
  race, since `addDownload` only posts to its internal thread. The rule itself is the pure,
  unit-tested `NetworkPolicy.manualRunEndsWithPass`.
- **Media3 is no longer constructed during activity creation.** `DownloadResumer` takes
  `dagger.Lazy<DownloadQueue>`, so opening the app no longer drags `DownloadManager`,
  `SimpleCache`, its database provider and the TLS-aware upstream onto the main thread on every
  launch, signed-out users included — the cold-start cost the application class already avoids.
  Same pattern as `NetworkAwareness`'s existing `dagger.Lazy<TrackDownloader>`.
- **`SyncTrace` logs a failure's class and nothing else.** Passing the `Throwable` to `Log`
  printed its message and stack trace, and a connection failure's message routinely carries the
  host name, IP address or full URL of the user's server. REQ-005 keeps that out of the log.
- A test fixture used `server.invalid`, which `check-public-hygiene.sh` rejects as an
  unreviewed host; it now uses `server.example`, the convention the other tests follow.

### Changed — context-file hygiene (2026-09-03)
- Claude Code-specific Wear OS and build guidance now lives in
  `docs/claude-code-reference.md`; `AGENTS.md` is the sole automatically
  discovered repository contract.

### Documentation — watch UI redesign design specification (2026-09-03)
- New `specs/10_WATCH_UI_REDESIGN.md`: a from-scratch design
  specification for the **watch** UI, covering the information architecture (a two-page
  `HorizontalPagerScaffold` home with Library and Now Playing, everything else pushed on
  `SwipeDismissableNavHost`), a route table, a single "Keep on watch" vocabulary backed by a
  sealed `CollectionSyncStatus` and a pure `SyncStatusMapper` with an explicit priority order,
  a sealed `UiError` / `UiAction` pair with a pure `ErrorMapper`, the added/deleted component
  inventory, the storage gauge's single meaning, continuity with DEC-011's design system and
  DEC-012's brand, an explicit out-of-scope list, a verification plan, and five open questions.
- New **DEC-014** in `specs/DECISION_LOG.md`, status **PROPOSED — awaiting owner approval**.
  It supersedes DEC-011's *screen design* only and preserves DEC-012, the Aurora palette and
  typography, the Wear Compose Material3 import convention, and `SwipeDismissableNavHost`.
- `specs/03_MASTER_PRODUCTION_SPEC.md` "User experience requirements" now points at that
  specification and marks it pending approval; the existing bullets stay authoritative until
  DEC-014 is approved.
- Documentation only. No production code, tests, dependencies or build configuration changed.
### Fixed — a destroyed session no longer disappears without trace (2026-09-03)
- `EncryptedTokenStore` deletes the session store **and** its Android Keystore master key
  when the encrypted preferences cannot be read — the recovery added for unreadable keysets.
  It did so silently, and the result is indistinguishable from an ordinary sign-out: both
  leave a preferences file holding nothing but its two Tink keysets. Someone signed in
  yesterday, signed out today, with nothing downloaded, had no way to learn which had
  happened, and neither did anyone reading the bug report. The reset and the sign-out now
  each announce themselves under the `JellyAuth` tag, and `AuthRepository` records which of
  restored / signed-out / signed-out-after-recovery a cold start resolved to. Only exception
  *classes* are logged: a `GeneralSecurityException` message can carry key material, and
  REQ-005 keeps that out of the log along with every session field.
### Fixed — the phone no longer claims a watch is unpaired, or reports stale figures as live (2026-09-03)
- **"No watch is paired" was a claim the Data Layer cannot support.** `NodeClient.connectedNodes`
  lists what is connected *now*; it is not a pairing registry. A paired watch that is off,
  asleep, or out of Bluetooth range landed in the same branch as a phone that never had one, and
  the user was told to go and pair a watch they already own. `NoNodesConnected` becomes
  `NoWatchConnected` and the copy says the watch is not connected, suggesting both causes.
- **A watch that stops answering no longer leaves live-looking numbers on screen.**
  `WatchStatusRepository` keeps the last status across later failures on purpose — the storage
  and sync figures stay worth reading — but the card rendered its diagnosis only while `status`
  was still `null`, so after one successful reply the screen went on presenting stale server,
  storage and sync data as current. A new `statusIsStale` combines status with availability, and
  the card says the values are the last ones received.
- **A setup attempt now republishes the diagnosis its own discovery produced.**
  `watchAvailability` was written only by the status refresh, while `WatchSetupController` ran a
  discovery of its own and discarded it. So a user told "JellyWrist is not installed on your
  watch", who installed it and tapped Set up, kept reading that sentence throughout the new
  exchange — and if the attempt then failed on the link, `SetupButton` suppressed the error as a
  duplicate of the very line that was now wrong. `WatchStatusRepository.discover()` is the single
  writer of the discovery state, the controller goes through it, and `startSetup()` returns its
  `Job` so the ViewModel can await the real outcome rather than read a `StateFlow` still holding
  the previous attempt's terminal value. What each outcome is allowed to conclude now lives in
  two pure functions: a watch that answered is `Reachable` even when setup then failed on the
  server or the account, because an earlier "app missing" is demonstrably wrong once the watch
  has replied; `NO_SESSION` is raised before discovery runs and so changes nothing; and a
  success re-runs the phone's own status refresh instead of asserting reachability, because
  `publish()` ignores whether its follow-up refresh answered and a timed-out one would
  otherwise present cached figures as live.
- GAP-016 records that the first discovery branch is the only one exercised on hardware, and that
  it deliberately asserts nothing about pairing.
### Fixed — companion watch discovery diagnostics & phone edge-to-edge (2026-09-03)
- The phone can now say **why** it found no watch. `WatchLink.discoverWatch()` returns a typed
  `WatchDiscovery` (`Found` / `NoWatchConnected` / `NodesWithoutApp`) built by combining
  `NodeClient.getConnectedNodes()` with the existing `CapabilityClient` query. A capability
  query alone could not tell "no watch is reachable from this phone" from "a watch is connected
  but does not run JellyWrist", so both produced the same dead-end advice.
- `HomeViewModel.refresh()` no longer discards the repository's `Result`: it exposes a
  `WatchAvailability` (unknown / no watch connected / app missing / not answering / discovery
  failed / reachable), and the watch card renders **exactly one** status line from it. The
  screen used to print the same sentence twice, once for `status == null` and once for the
  setup failure; link-level setup errors are now suppressed when the status line already
  states them.
- The advice to "open JellyWrist on the watch" is gone. The capability is declared statically
  in the watch's `wear.xml`, so it is advertised from installation onwards and launching the
  watch app changes nothing.
- `WatchSetupController.startSetup` no longer maps every exception to `watch_unreachable`.
  Only a failure of the discovery step itself does; any other exception (a Jellyfin HTTP
  error, a DataStore read, a serialization fault, a Data Layer send failure) now reports
  `internal` and logs the exception *class* only — never a response message, token, or URL.
- Phone screens draw edge to edge correctly: `enableEdgeToEdge()` plus a Material 3 `Scaffold`
  and `TopAppBar` on Home, Browse, and mobile onboarding, with the scaffold's inset padding
  applied. The "JellyWrist" title used to collide with the status-bar clock (`targetSdk 36`
  forces edge-to-edge on Android 15+, and no screen consumed the insets).
- Release hardening: `app/src/main/res/raw/keep.xml` keeps `@array/android_wear_capabilities`.
  `isShrinkResources` is on for release and the array has no code reference, so the shrinker
  was entitled to strip it — silently disabling companion discovery in release builds only.
- `versionCode` 2 → 3 in both `:app` and `:mobile`; `versionCode = 2` was also the
  pre-companion 1.0.0 release, which made a watch upgrade undetectable.
### Fixed — the watch never downloaded anything (2026-09-03)
- **The download queue is no longer created by starting a service.** Every queue mutation
  travelled as a Media3 `DownloadService` intent, and `sendAddDownload(foreground = true)` is
  an unguarded `startForegroundService()`. Called from `ReconWorker`, Android 12+ answers with
  `ForegroundServiceStartNotAllowedException`; the exception escaped `SyncEngine.syncPass`
  before a single request was registered, the worker returned `retry()`, and WorkManager sat
  on an hour-long exponential backoff with nothing downloaded and nothing said. Media3 itself
  treats this as recoverable — `DownloadManagerHelper.restartService()` catches exactly this
  exception and logs it — because the durable act is adding to the download index and the
  service start is best effort. The code now follows that contract: a new `DownloadQueue` port
  writes through `DownloadManager` (so no process state can refuse it), and the service start
  is attempted separately and reported.
  `MainActivity.onStart` starts the service for any still-pending download, which is the one
  moment the platform reliably allows it.
- **A collection the server could not answer for no longer aborts the whole pass.** There was
  no per-collection guard around the metadata fetch, so one unreachable or server-deleted
  playlist stopped every *other* pinned collection from being enqueued. A failed collection is
  now reconciled by identity — nothing of it is purged, nothing forgotten — and the pass
  carries on. A pass where some pins failed reports `PARTIAL`; only a pass where they all
  failed reports `FAILED`.
- **The random mix stopped throwing `Invalid UUID string: local-random-songs`.**
  `LibraryRepository.refreshTracks` handed the id of the locally-assembled collection to the
  Jellyfin SDK. The error landed on the collection detail screen and was swallowed silently
  inside `setPinned`. Local-only collections are now recognised and skip the server refresh,
  which they never needed.
- **The charging observer no longer fights the user.** `ACTION_BATTERY_CHANGED` is broadcast
  constantly and each broadcast re-issued a pause, including moments after the sync worker had
  deliberately resumed the queue for a manual pass. The decision moved into
  `NetworkPolicy.chargingAction` (pure, unit-tested), the observer is
  `distinctUntilChanged`, and a manual run the user authorised outranks the automatic pause
  until the queue drains — REQ-007 lifts the charging constraint for manual sync, and now the
  runtime does too.
- **"Sync to watch" starts a sync instead of waiting for a charger.** Pinning called
  `scheduleAutomaticOneShot()`, whose REQ-007 defaults are charger *and* unmetered Wi-Fi, so
  the request waited in WorkManager until the watch was next docked with nothing on screen to
  say so. Pinning now goes through a new `KeepOnWatchCoordinator`, the single consent ladder
  (battery, then metered network) shared with the other manual-sync entry points, and the
  collection screen renders the prompt inline instead of the pin appearing inert.
- **A tap on sync is never dropped, and a failed manual sync says so.** `syncNow` used
  `ExistingWorkPolicy.KEEP`, so any request still `ENQUEUED` — waiting on constraints or
  serving a retry backoff — silently swallowed every later tap and its consent flags;
  it now appends. A manual pass that fails returns `Result.failure()` rather than disappearing
  into an hour-long backoff; automatic passes keep the retry.
- **The pipeline is no longer silent.** A new `SyncTrace` (tag `JellySync`) records one line
  per component boundary — worker gates, per-collection fetches, diff sizes, enqueue results,
  Media3 transitions and every network-policy decision. It carries ids, counts and booleans
  only: never a token and never a server URL (REQ-005).

### Fixed — manual download and companion sync feedback (2026-09-02)
- A phone-triggered sync now observes the same low-battery warning as a watch-initiated
  manual sync. The watch reports a stable blocker and the phone resends only after explicit
  battery consent, so Media3 cannot resume its queue on a low battery without approval.
- Every authorized manual sync resumes the Media3 queue before reconciliation, including
  ordinary Wi-Fi runs while the watch is not charging. Automatic sync retains its
  charging and unmetered-network constraints.
- A manual run that cannot acquire Wi-Fi now stops without a silent retry and reports
  that metered consent is required. The phone asks for confirmation and resends a typed
  sync command only after consent.
- Watch status now carries backward-compatible sync state and blocker fields. The phone
  polls after acknowledgement and shows queued, syncing, waiting, completed, paused, or
  failed state instead of treating the acknowledgement as completion.

### Fixed — resilient authentication startup (2026-09-02)
- Phone and watch now install their Compose roots immediately and render an explicit
  loading state while the encrypted session is read, preventing a blank activity
  during cold-start storage I/O.
- Authentication bootstrap is serialized and returns typed restored, empty, or
  recovered-corruption results. Recognized Keystore, keyset, and decryption failures
  remove only the encrypted authentication preferences and app-owned master key,
  then return to visible onboarding with a reconnect notice.
- The encrypted `jellywatch_auth.xml` session file is excluded from cloud backup and
  device transfer in both applications so an unrestorable keyset is not migrated to
  a device that lacks its Android Keystore key.

### Fixed — sync reliability & silent failures (hardening pass, 2026-09-01)
- **GAP-010 closed**: the periodic sync is re-armed on every provisioning success —
  after a factory reset followed by companion provisioning, and after the watch's own
  re-onboarding, the watch looked configured but silently never synced again. New
  JVM-fakeable `PeriodicSyncArmer` port; armed in `CompanionSetupCoordinator`'s
  post-authentication block and in `OnboardingViewModel`.
- **GAP-011 closed**: both application scopes carry a logging
  `CoroutineExceptionHandler`, and watch command handling moved into a new
  `CompanionCommandHandler` (extracted from `CompanionListenerService`) — PIN /
  SYNC_NOW / STATUS are serialized under one mutex and every unexpected failure
  replies `WireAck(false, internal)` instead of killing the watch process.
- Phone remote commands (sync now, pin, unpin) are no longer fire-and-forget: a sealed
  `OpState` (Idle / InFlight / Success / Error) drives explicit UI feedback, in-flight
  chips are disabled (double taps no longer launch two round trips), and setup errors
  now also render when the watch is already configured.
- Data Layer listener registration on the phone is guaranteed before any send
  (`WatchLink.ensureListening()` + one long-lived buffered listener) — the previous
  per-request `callbackFlow` could lose a fast watch reply to a registration race and
  dropped all unsolicited/late messages.
- `WatchStatus` payloads are capped under the 100 KB `MessageClient` limit
  (`sizeCapped()`, new backward-compatible `collectionsTruncated` field) and send
  failures are logged instead of silently swallowed.
- The watch sync state is reconciled with WorkManager, so a sync surviving a process
  death reports SYNCING instead of resetting to IDLE; the pin TOCTOU on the collection
  row is closed with an atomic insert-ignore.

### Performance — startup, main thread, Compose (hardening pass, 2026-09-01)
- Debug builds install **StrictMode** (thread + VM policies) on both apps; a cold
  launch of the watch and phone apps is now StrictMode-clean on the AVDs.
- The session is memory-cached with an IO warm-up: the first
  EncryptedSharedPreferences/Keystore read used to run on the main thread from six
  call sites (both MainActivities now resolve the initial screen asynchronously).
- OkHttp/Jellyfin SDK and Media3 (`DownloadManager` + `SimpleCache`) are no longer
  constructed during `Application.onCreate` field injection on the main thread
  (StrictMode measured the TLS trust-index build alone at 430–686 ms); they
  materialize lazily on background scopes or first use.
- `DownloadManager.currentDownloads` is read on the manager's own callback thread
  instead of `Dispatchers.IO` (thread-confinement violation); `StatFs` calls are
  suspend + IO; heavy ViewModel combines run on `Dispatchers.Default`.
- Compose: the color scheme is remembered, the aurora gradient/scrim brushes are
  singletons (the seek bar allocated two per frame), the seek-bar gesture detector is
  no longer rebuilt on every 1 Hz position tick, and the player position ticker stops
  when no Player screen is subscribed. `JellyPlaybackService` no longer `runBlocking`s
  a DataStore write in `onDestroy` (eager persistence on pause/transition instead).

### Changed — phone companion UX (hardening pass, 2026-09-01)
- Leftover hardcoded French strings in the phone onboarding are gone: ViewModels emit
  string resources only (English defaults, existing French translations).
- Localized byte sizes (`Formatter.formatShortFileSize`) and live relative times
  (`DateUtils` with a one-minute tick) replace the hand-rolled mixed-language
  formatters; the storage gauge shows a "no quota" line instead of a misleading 100 %
  bar when the quota is 0 and surfaces truncated collection lists.
- Browse tabs distinguish "never loaded" from "loading" and keep a 5-minute in-memory
  catalog cache — a tab switch no longer re-paginates the entire album/artist list.

### Security — public-repository preparation
- Removed real infrastructure addresses and account identifiers from the
  documentation and tests, replaced with reserved example values.
- Removed the AI workflows triggerable by comments/issues, one of which had
  write permissions and a privileged token, as well as logs, session plans, and
  old internal working documents.
- Hardened the remaining Android CI: GitHub permissions explicitly read-only and
  third-party actions pinned to immutable SHAs with their human-readable version in a comment.
- Added an automated check `scripts/check-public-hygiene.sh`, mandatory rules
  for all agents, a security policy, a contribution guide, and a publication
  audit. The old Git history is explicitly barred from publication: the
  future `0.0.1-beta` repository must start from a sanitized snapshot and a fresh root commit.
- Review fixes on `scripts/check-public-hygiene.sh`: scanning the index (`git grep
  --cached`) in addition to the working tree, so that a secret staged in the index but
  redacted only in the working tree still fails; detection of suffixed environment files
  (`.env.local`, `config/.env.production`) that the old expression did not recognize;
  reporting only the offending paths, to avoid copying a secret into the log.
  Script verified with ShellCheck 0.11.0 (`--severity=style`, zero warnings) and with
  a matrix of ten reproduction cases (index, working tree, suffixed `.env`,
  `.pem`, `.apk`, and non-offending cases).
- The placeholder values (`jellyfin.example.com`, `example-server`, `sample-user`) are
  now announced as redacted in the verification evidence, instead of being
  presented as the inputs actually used.
- Explicit documentation of the license blocker: no reuse permission is
  asserted until the owner has chosen a license.

### Added — Phone companion app (DEC-013)
- **`:mobile` module**: companion phone app (applicationId `com.example.jellywatch`, same keystore as the watch — required by the Wearable Data Layer). Provisioning without typing on the watch, remote status, remote library with pin/unpin and immediate sync.
- **`:core` module**: shared library (JellyfinBackend, AuthRepository, ServerRepository, TokenStore, TLS layer, Hilt modules) extracted from `:app` — the watch and the phone share Quick Connect authentication, URL validation, and certificate pinning.
- **Wire protocol** (`com.example.jellywrist.wire`): versioned message paths `/jellywrist/*/v1` and `@Serializable` DTOs for phone↔watch (setup, status, pin, sync).
- **Watch companion receiver** (`companion/`): `CompanionSetupCoordinator` + `CompanionListenerService` (WearableListenerService) on the watch — provisioning via the Data Layer, status, remote pin/unpin, immediate sync.

### Added — JellyWrist identity
- **JellyWrist identity**: product brand, Kotlin source `com.example.jellywrist`, visible name, Jellyfin client headers, and CI artifacts harmonized.
- **Orbit Pulse**: new OLED-black adaptive icon, round variant, monochrome mark for Android themed icons and notifications, plus the visual pack in `docs/brand/` (1024 px master, 512 px icon, PNG favicons, and 1200 × 630 social card).

### Fixed — companion review, 4th pass (PR #10)
- **Sign-out cancelled but did not lock**: `CompanionSetupCoordinator.reset()` released the provisioning lock before `LocalDataReset.factoryReset()` began. A `SETUP` request arriving during the purge — which takes time: WorkManager jobs, Media3 bytes, Room tables — immediately grabbed the free lock and started a full Quick Connect flow. That flow could authenticate **after** the token was erased (erased last, `factoryReset` purging first) and persist a new session: the watch stayed signed in to the account the user had just removed, and a late `setPinnedCertificate` could likewise survive `settingsRepository.clear()`. `reset` now accepts a block executed **without releasing the lock** (`reset { localDataReset.factoryReset() }`): since `handleSetup` must take the same mutex, a concurrent request waits for the factory reset to finish, then provisions a genuinely blank watch — the intended flow, not the race. The cost is bounded: `handleSetup` releases the lock before joining its work, so a five-minute Quick Connect poll never holds it; only the purge does. This was the known limitation left open by the 3rd pass.
- **The Quick Connect poll could kill the process**: `AuthRepository.quickConnectState` rethrows any non-4xx `InvalidStatusException` and catches no `IOException`. A network drop or a 5xx occurring **after** the code was emitted travelled up the cold `pollQuickConnect` flow and escaped its collectors' `launch`. On the watch, that runs in `appScope` — a `SupervisorJob` with no `CoroutineExceptionHandler`: the process died instead of replying, and the phone only got a timeout after 90 s, never learning the cause. On the phone as on the watch's onboarding screen, the same collector left the screen frozen on the code, with no retry. The poll now treats a transport failure as a **state, not an exception**: it is absorbed during `FAILURE_GRACE` (20 s, i.e. ten attempts — a two-second Wi-Fi handover must not doom a wait that can last five minutes), then reported as `QuickConnectState.Unavailable`, a new terminal state distinct from `Expired` (the request may still be valid server-side; it is the path that vanished, so we offer a retry, not a new code). The grace stays well under the phone's 90 s `RESULT_TIMEOUT`, so the phone shows "server unreachable" rather than a silent timeout. Adding a variant to a sealed interface makes each of the **three** collectors' `when` non-exhaustive: the compiler therefore forced fixing all three, including the watch onboarding screen the review had not mentioned.
- **Last line of defense**: `CompanionSetupCoordinator.runSetup` now catches whatever remains (the `reply` send goes through Play Services and can itself throw) and answers `SETUP_RESULT` with the new `WireErrors.INTERNAL` code — both peers already have an `else` branch, so no interface string or binary compatibility is at stake. Both `startQuickConnect` calls (watch and phone) get the same guard, publishing a retryable state. Cancellation is rethrown before any other catch: the reset waits for it. The verdict of this catch-all depends on what has already happened: **past authentication, the session is persisted and the watch is configured** — it is the announcement that failed, not the provisioning. Publishing `Failed` would then send the home screen to an error while the watch is connected; the coordinator therefore publishes `Done` and retries the send once.
- **Staged pin surviving a failed write**: corollary of the catch-all above. `configured` — the flag that keeps the `try/finally` from undoing the pin — was set **before** `setPinnedCertificate` and `clearServerData`, so it covered them preemptively. As long as those writes killed the process, the in-memory registry died with it; now that they are caught, they left `staged` in place. `PinRegistry` gives the staged pin priority over the accepted one and `update()` cannot erase it (by design, so the settings collector does not overwrite an in-flight validation): the watch would therefore have trusted, for the lifetime of the process, a certificate that no setting records — a crash traded for a silent TLS trust anomaly. `configured` is now set **last**, after both writes; `configuredUserName`, which attests only to the persisted token, stays set first.
- **Token erased last during factory reset**: `LocalDataReset.factoryReset()` purged WorkManager, Media3, five Room tables, and the playback queue **before** `authRepository.signOut()`. Yet `SettingsViewModel.signOut` announces "signed out" from its `finally` and navigates regardless: a failed purge left the user convinced they were signed out while their credentials still slept in the Keystore. The token now goes first — nothing in the purge needs the session. Same symptom as the race fixed above, via another trigger.
- **Cancellation swallowed by `runCatching`**: `CompanionListenerService.send` wrapped the Play Services send in a `runCatching`, which catches `Throwable` and thus also `CancellationException`. `reply(...)` no longer threw anything after a cancellation: provisioning carried on — `Done` published, `SETUP_RESULT ok=true` emitted — for a configuration the sign-out had just cancelled. The final state stayed correct only because `reset()` joins before writing `Idle`; relying on that ordering was fragile. Cancellation is rethrown. (Same family as the 1st pass's "Cancellation swallowed", in another place.)
- **Tests**: 183 cases (up from 175). `AuthRepositoryTest` gains `pollQuickConnect reports Unavailable instead of throwing when the server dies` (giving up exactly at `FAILURE_GRACE`) and `pollQuickConnect survives a transient failure and still completes`; its two existing virtual-clock assertions (exactly 2 s, exactly 5 min) pass **unmodified** — they are the guardrail of the loop rework. `CompanionSetupCoordinatorTest` gains `a server that dies during the poll yields a result instead of an exception`, `a transport failure after authentication still reports the watch as configured`, `a write failure after authentication still restores the previous certificate pin`, and `a SETUP arriving during the factory reset waits for it to finish` (in real time: the targeted window is a suspended purge, out of reach of the virtual clock; a MockWebServer dispatcher records whether the purge had finished at the provisioning's very first network call). New `MobileOnboardingViewModelTest` — the `:mobile` module had no ViewModel test. Each fix was falsified: reverted to the old behaviour, it fails exactly the test that covers it.
- **Known limitations (observed in this pass, not fixed)**:
  - *Periodic sync stays off after a factory reset followed by phone provisioning* (GAP-010). `factoryReset()` calls `cancelAll()` without re-arming, by design. The `SETUP` request that the lock now makes wait then runs without a session, so `reconfiguring` is false and `clearServerData()` — the only point on this path that calls `schedulePeriodic()` — is skipped. The watch ends up fully provisioned and no longer syncs on its own until the next process start or settings change. It is the exact counterpart of the limitation already noted in the 3rd pass for the local path; fixing it requires injecting a scheduler into the coordinator (new port, `SyncScheduler` depending on WorkManager and a `Context`), hence a dedicated pass covering both paths.
  - *`appScope` still has no `CoroutineExceptionHandler`* (GAP-011). `CompanionListenerService` launches four handlers on it; only `handleSetup` is protected. A network failure in `handlePin` (`libraryRepository.setPinned`) or WorkManager in `handleSyncNow` still kills the process through the very mechanism this pass describes. A `CoroutineExceptionHandler` in both `provideAppScope` functions (watch and phone) would cover everything; it is a global behaviour change, to be decided separately.
  - *`handlePin` and `handleSyncNow` do not go through the provisioning lock*: they write to the database and schedule jobs during the purge (their `currentSession()` check now passes for a shorter window, the token going first, but the window remains).
  - *`FAILURE_GRACE` is calibrated for the companion.* The 20 s are bounded by the phone's 90 s timeout, but `pollQuickConnect` also serves the watch's standalone onboarding, where the user has five minutes: a prolonged Wi-Fi outage there forces a new code. Making it a flow parameter would separate the two uses.

### Fixed — companion review, 3rd pass (PR #10)
- **Cancellation limited to the poll**: `CompanionSetupCoordinator.quickConnectJob` was only assigned **after** `validateServer` and `initiateQuickConnect`. A reset (sign-out) or a new `SETUP` request arriving during those two steps therefore found nothing to cancel: the provisioning coroutine kept running and could persist a session **after** the factory reset, resurrecting the account that had just been erased. It is now the entire operation (`setupJob`, launched in `appScope` by `handleSetup`) that is tracked and cancelled, with replacement serialized by a `Mutex`; `reset()` becomes `suspend` and does `cancelAndJoin`. `SettingsViewModel.signOut` calls it **before** `LocalDataReset.factoryReset()` — the sign-out knows the provisioning is dead before erasing the token. Corollary: since cancellation now reaches every suspension point, the **local writes** that follow authentication (persisting the pin, purging the catalog) are wrapped in `withContext(NonCancellable)` — the token being already persisted at that stage, a cancellation slipped between authentication and the purge would otherwise leave the new session sitting on the old server's catalog, precisely the inconsistency reconfiguration must eliminate. The region stops there: `reply(SETUP_RESULT)` goes through `Wearable.getMessageClient(...).sendMessage(...)` (Play Services, with no timeout of its own) and including it would let a sign-out wait on a degraded Bluetooth round-trip with `setupMutex` held; the send and the `Done` publication remain cancellable, a companion that receives nothing falling back on its own timeout. `SettingsViewModel.signOut` likewise wraps its body (`reset()` then `factoryReset()`): since `reset()` is now suspending, a ViewModel destroyed during the wait would have run its `finally` — thus announced "signed out" on screen — while the token would have survived.
- **TLS pin persisted before validation**: `request.pinnedCertSha256` was written to `SettingsRepository` and `PinRegistry` as soon as the request was received. A reconfiguration that subsequently failed kept the previous session while the trust manager only accepted the new certificate: the server that worked became unreachable. The pin is now only **staged** in memory (`PinRegistry`, required for the TLS handshake that follows) and is only persisted after successful authentication; a `try/finally` returns the watch to the previous certificate on any unsuccessful outcome, cancellation included. The staged pin is a **distinct state** from the accepted fingerprint (`stage`/`clearStaged` versus `update`, staged taking priority): `JellyWristApp` collects `settingsRepository.settings` for the whole process lifetime and calls `update(...)` on every emission, including for a setting unrelated to TLS. Without that distinction, a periodicity, quota, or "Wi-Fi only" change during the Quick Connect window (up to 5 minutes) overwrote the staged certificate with the old one and made the next handshake fail. The coordinator stages, promotes on success, and `clearStaged()` suffices for rollback.
- **Periodic sync not re-armed after reconfiguration**: `LocalDataReset.clearServerData()` calls `SyncScheduler.cancelAll()`, which also cancels the unique periodic job, and nothing recreated it on the successful reconfiguration path — `schedulePeriodic()` is only called at app startup or after a settings change. The reconfigured watch therefore silently stopped syncing on its own. `LocalDataReset` now separates `purge()` (private) from `clearServerData()` (purge + `schedulePeriodic()`) and `factoryReset()` (purge + settings + token, without re-arming — a signed-out watch must have nothing scheduled).
- **Tests**: `CompanionSetupCoordinatorTest` goes from 7 to 10 cases — `a failed reconfiguration restores the previous certificate pin`, `a successful setup persists the requested certificate pin`, and `reset cancels a setup still validating and no session is persisted` (the latter runs in real time on real dispatchers: the targeted window is network I/O, out of reach of `runTest`'s virtual clock). On the `:core` side, `CertificatePinningTest` goes from 6 to 7 cases with `un pin a l'essai survit aux reemissions des reglages`.
- **Not covered by JVM tests**: the periodic-job re-arming is not verified by any test — `SyncScheduler` depends on WorkManager and an Android `Context`, hence unverifiable without Robolectric or an instrumented test. To be checked during the first E2E on paired emulators, alongside process survival during the Quick Connect poll.
- **Known limitations (not fixed in this pass)**:
  - *Sign-out cancels, it does not lock.* — **fixed in the 4th pass**, see above.
  - *The periodic-sync re-arming only covers the companion path.* Signing out then back in **on the watch** leaves periodic sync off until the next process start or settings change: `OnboardingViewModel` never calls `schedulePeriodic()` (only `JellyWristApp`, `SettingsViewModel`, and `LocalDataReset.clearServerData()` do). Same silent symptom as the fixed bug, on the local path — to be handled in a dedicated pass.

### Fixed — companion review, 2nd pass (PR #10)
- **"Reconfigure" with no effect**: the watch acknowledged "already configured" as soon as a session existed, without comparing the requested server or account. The phone's button could therefore never move the watch to the current server, and collections pinned afterwards targeted identifiers from a foreign server. `SetupRequest` now carries `serverId` and `userId` (nullable — an older peer falls back to URL comparison); on a mismatch, the watch replays the full Quick Connect flow then purges the previous server's catalog via `ServerDataReset`. The purge follows authentication and never precedes it: an expired code or an unreachable server leaves the watch intact.
- **Remote provisioning state lingering**: `CompanionSetupCoordinator.state` being a process-scoped singleton, a `Done` survived the sign-out — the home screen recreated by navigation believed itself authenticated and headed back to the library without a token. Sign-out now calls `reset()`, and the home screen only honours a `Done` if a session actually exists.

### Fixed — companion review (PR #10)
- **Provisioning result lost during approval**: `WatchSetupController` now maintains a **single subscription** (`SetupReplyListener`) from the `SETUP` send to the outcome. The old code closed the subscription after `SETUP_QC` then re-subscribed after `authorizeQuickConnect`: the watch, which polls the approval continuously, could emit `SETUP_RESULT` in that gap — since `incoming()` replays nothing, the phone showed a timeout despite a successful provisioning.
- **Status frozen after first setup**: a favourable `SETUP_RESULT` refreshes `WatchStatusRepository`. The home screen otherwise stayed on the "not configured" layout ("browse" and "sync" buttons hidden) until a manual pull-to-refresh.
- **Phone artwork outside the TLS policy**: new `MobileCoilModule` installed via `SingletonImageLoader.Factory` on `MobileApp` — browse thumbnails go through the shared `OkHttpClient` (opt-in pinning, DEC-006) and carry the Jellyfin header. The default Coil loader failed TLS against a self-signed yet approved server. The same wiring was missing on the watch: `di/CoilModule` was provided by Hilt without ever being installed (`JellyWristApp` now implements the factory).
- **Companion replies after service destruction**: `CompanionListenerService.send` uses the application context rather than the service's, which can be destroyed during the Quick Connect poll.
- **Cancellation swallowed**: the Quick Connect authorization call no longer converts a coroutine cancellation (second setup request) into a displayed authentication failure.
- **Possible crash on a disconnected watch**: `startSetup` catches transport errors (`getCapability`/`sendMessage` throw an `ApiException` if the watch disappears mid-exchange). Since `appScope` has no exception handler, they killed the process instead of showing "watch not found".

### Added — companion tests
- **`WatchLink` becomes a port** (`WearableWatchLink` in production, `WatchMessage` decoupled from `MessageEvent`) and **`MobilePrefs` receives its `DataStore`** by injection: the phone's subscription races are now covered on the JVM (storage path unchanged, no migration). 11 tests on the phone side, and 6 more tests on the watch side for the 2nd review pass:
  - `WatchSetupControllerTest` — **a single end-to-end subscription** (the fake transport's subscription log must equal `[+, -]`; one subscription per phase fails the test), status refresh after success, unreachable watch reported without crashing.
  - `SetupReplyListenerTest` — result received during authorization, immediate terminal result, filtering by node, unreadable packet, transport failure.
  - `WatchStatusRepositoryTest` — status pushed right after the pin ACK, refused ACK, unreachable watch.
  - `CompanionSetupCoordinatorTest` (watch) — reconfiguration to another server then purge, reconfiguration on account change, reconfiguration failure without data loss, offline acknowledgement of a phone without `serverId`, terminal-state reset.
  - `OnboardingViewModelTest` (watch) — a lingering outcome does not authenticate a screen created after sign-out, and does authenticate one when the session is alive.

### Changed
- **Local wipe factored out**: `LocalDataReset` (port `ServerDataReset`) carries the catalog purge and the factory reset, previously buried in `SettingsViewModel.signOut` — the phone-driven reconfiguration reuses exactly the same wipe.
- **Companion strings**: the code-approval label goes through a localized resource (FR/EN) instead of a literal, and the `timeout` error code becomes `WireErrors.TIMEOUT`.
- **Phone artwork URL** centralized in `JellyfinUrls.primaryImage` (server-side resizing at 200 px); `JellyfinUrls.authorizationHeader` accepts a `device` to distinguish watch and phone in Jellyfin sessions.
- **Upgrade compatibility**: the historical `applicationId`, Room database, encrypted preferences, DataStores, WorkManager identifiers, and notification channel stay on `jellywatch` to preserve existing installations.

### Internal 1.0.0 snapshot - 2026-08-11
### Added
- **CI quality gate**: `lintDebug` (0 errors / 0 warnings) and `assembleRelease` (R8 + shrinking) added to the `android.yml` workflow; release APK artifact uploaded.
- **Release signing**: `signingConfigs.release` reading a local `keystore.properties` (gitignored, never committed); `assembleRelease` produces a signed APK (v2) when the keystore is present, unsigned otherwise (CI has no key and builds without failing).

### Changed
- **Version bump**: `versionCode` 1→2, `versionName` `0.1.0`→`1.0.0`.

### Fixed
- **Cold-start crash (GAP-008)**: `Media3TrackDownloader.pauseAll`/`resumeAll` now catch `BackgroundServiceStartNotAllowedException` — the first network emission from `NetworkAwareness` no longer kills the process before the first frame is rendered.
- **Lint hygiene (GAP-009)**: `Uri.parse`→`toUri()`, count strings converted to `<plurals>` (FR/EN), `taskAffinity` for Wear recents, and `app/lint.xml` documenting the deliberate exceptions (TLS pinning DEC-006, exported media service REQ-009, LAN cleartext).

### Note
- This 1.0.0 release includes all previously merged work — Quick Connect authentication (M1), library/downloads/sync/playback (M1t4→M6, PR #7), and the Aurora redesign (DEC-011, PR #8).

### Internal 1.0.0 snapshot - 2026-08-10
### Added — Aurora UI redesign (2026-08-10, DEC-011)
- **"Aurora" theme** (`ui/theme/`): OLED-black base, signature violet → magenta accent, tightened typography. `JellyWristTheme` replaces the bare `MaterialTheme {}` at the root of `JellyNav`. `auroraGradient()` / `auroraScrimBottom()` / `auroraScrimCenter()` brushes. Supersedes the "No visual redesign" constraint (`03_MASTER_PRODUCTION_SPEC.md`).
- **Component system** (`ui/components/`): `AuroraButton` (round gradient), `AuroraChip` (list row, 3 variants — replaces the `Button`s repurposed as chips), `SectionHeader`, `StorageGauge` (gradient ring), `GradientProgressRing`, `AuroraSeekBar` (drag/tap progress bar), `TrackStatusIcon`, `GradientText`, `formatBytes`/`formatDuration`. Centralizes and removes the private duplicates (`SectionHeader`, `StorageGauge`, `formatBytes`, `formatCollectionBytes` ×3).
- **Material vector icons** (`material-icons-core`/`-extended` via the BOM): replace all unicode glyphs (`▶ ⏸ ⏭ 🔀 🔁` player; `✓ ◐ ⏸ ✗` log; `●`/`✓` badges).
- **Full player (REQ-009)**: full-bleed artwork + scrim, **draggable** progress bar + `m:ss` times, icon transports. New `PlayerViewModel.seekTo` (1 line) backed by the existing `PlayerController.seekTo`.
- **Consistency**: all screens standardized (self-sufficient scaffold + scroll state wired into the `ScreenScaffold`, fixing the 2/6 inconsistency). Onboarding restyled (the word "JellyWrist" in gradient, rounded field, "Connect" button as a gradient pill).
- **i18n**: localized byte units (`unit_bytes_*` FR/EN) — fixes the "Go/Mo" leak in the EN build. Removal of 7 player glyph strings, 3 dead onboarding strings (DEC-010), 5 track-state strings (replaced by icons), and 2 glyph badges.
- **Verified**: `assembleDebug` green, **133 JVM tests green** (0 failures), theme rendered on a Wear OS emulator (onboarding screenshot). Lint has ~25 **pre-existing** errors (never run in CI — DEC-006 TLS pinning, REQ-009 exported media service, version info); 0 errors introduced by this redesign.
### Fixed — Aurora review (PR #8)
- Self-signed-certificate prompt made scrollable: the "Trust" and "Reject" actions stay reachable on small Wear screens.
- `AuroraSeekBar` touch target raised to 48 dp, without changing the track (6 dp) or the thumb (12 dp).
- Track-state icons described for TalkBack, with FR/EN localized resources.
- The player's icon action buttons and the settings steppers now named for TalkBack (FR/EN).
- Removal of accidentally tracked session exports and targeted exclusion rules to prevent their reintroduction.
- Server-address entry made scrollable: the "Connect" action stays reachable even when a validation error lengthens the content.
- Player content made scrollable: title, transports, and shuffle/repeat stay reachable on small round screens.
- `AuroraSeekBar` exposed as an adjustable control (progress + set action) for TalkBack and switch access.
- The "Sync to watch" toggle announced again as a switch with its checked state.

### Added — M1 t4 → M6: full application (2026-08-08)
- **Library (t4-5, cache-first)**: `LibraryRepository` (paginated SDK, preservation of `pinned` and download states on refresh), Library screens (Playlists/Albums/Artists/**Tracks** tabs) and Collection detail (real size, "Sync to watch" toggle), authenticated Coil 3 artwork with disk cache (`di/CoilModule`, `JellyImage`).
- **Tracks tab + "Sync 500 random tracks"** (product request 2026-08-08): server-side draw (`sortBy=RANDOM`), **local** pinned collection (`localOnly`, Room schema v2 + `MIGRATION_1_2` tested instrumented, REQ-012) reconciled by identity by the sync engine; re-draw = replacement, the old draw's bytes are freed by the SQL orphan sweep.
- **Downloads (t6)**: `JellyDownloadService` (FGS `dataSync`, `WorkManagerScheduler`, "paused — will resume" notification, DEC-005), idempotent `Media3TrackDownloader` (shared cache, key = trackId), Downloads screen (gauge, queue, errors, metered-network consent).
- **Sync (t7-8, 11)**: `SyncPlan.diff` / `BudgetPlanner.plan` / `artistBatch` pure and tested (REQ-006/008), `SyncEngine` (§4.2 pipeline, log, PAUSED never presented as failure), `ReconWorker` + `SyncScheduler` (periodic on charge+Wi-Fi; manual expedited with Horologist Wi-Fi acquisition and metered consent, REQ-007).
- **Playback (t9)**: `JellyPlaybackService` (MediaSessionService + ExoPlayer, **cache-only** source — no streaming, audio offload, speaker guard via Horologist `SystemAudioRepository`/`OutputSwitcher`, REQ-009), persisted queue (`QueueStore`), Player screen (crown = volume, shuffle/repeat).
- **Network (t12)**: pure tested `NetworkPolicy` + `NetworkAwareness` (Horologist network-awareness: observation, pause/resume, high-bandwidth lease).
- **Settings & polish (t13-14)**: quota, constraints, periodicity (re-scheduling), sync log, "factory reset" sign-out, end-to-end opt-in TLS pinning (`PinningTrustManager` + `PinRegistry` + `CertificateFetcher`, shared OkHttp client including the SDK), "Resume playback" Tile, FR/EN i18n, contextual `POST_NOTIFICATIONS` prompt, R8 enabled in release with justified rules.
### Changed — **Quick Connect only** auth (owner decision 2026-08-08)
- Removal of the `AuthenticateByName` fallback (UI, `AuthRepository.loginWithPassword`, states, tests): no password ever transits through the watch. Server without Quick Connect → explicit state with retry.
- **Scheme auto-detection**: input without a scheme probes `https://` then `https://:8096`, `http://:8096`, `http://` — a TLS **trust** failure stops the probe (pinning offered, never a downgrade to cleartext); address field with URI keyboard and no autocorrection (the Wear IME was altering the input).
- Orphan purge: the **bytes** are freed, the Room catalog survives (offline browsing of unpinned collections).
### Tests
- **131 JVM tests green** (SyncPlan 14, BudgetPlanner 10, ArtistBatch 5, SyncEngine 10 including identity recon and re-draw sweep, NetworkPolicy 6, MediaItems 4, CertificatePinning 6, LibraryRepository 8, + reworked auth/server/normalizer); **6 instrumented** green on an emulator (5 TokenStore + Room v1→v2 migration). Verified on a Wear OS emulator against a private test Jellyfin server (identity redacted, Jellyfin 10.11.11): Quick Connect, real library, persisted session.

### Added — M6 (download), plan task 6 (download engine and Downloads screen)
- Media3 download engine: `DownloadEnqueuer` (`DownloadRequest` construction to `/Items/{id}/File`, DEC-003, customCacheKey = trackId), `DownloadStateMapper` (`Download.STATE_*` → `TrackState` translation), `CodecSupport` (decodable/non-decodable codec classification, DEC-003/DEC-004), `Media3TrackDownloader` (`TrackDownloader` implementation, single-thread executor, ~1 s progress snapshot).
- Download service: `JellyDownloadService` (extends Media3 `DownloadService`, `@AndroidEntryPoint`, FGS type `dataSync`, subject to the Android 15+ 6 h cap, DEC-005).
- Hilt module `DownloadModule`: `TrackDownloader` → `Media3TrackDownloader` binding (singleton).
- Wear Compose Downloads screen: `DownloadsScreen` (sections by state, storage gauge, sync/pause button, progress rows with percentage or bytes, unplayable-codec badge).
- `DownloadsViewModel`: aggregates 8 Room flows + progress + refresh signal into a `StateFlow<DownloadsUiState>` (WhileSubscribed 5 s).
- Navigation: `JellyRoutes.DOWNLOADS` composable in `SwipeDismissableNavHost`.
- **TDD**: 30 pure JVM tests (`DownloadStateMapperTest` 12 cases, `CodecSupportTest` 18 cases). `DownloadEnqueuer` requires an instrumented test (`androidTest`) because of `android.net.Uri`.
- Strings: 12 `downloads_*` resources in `strings.xml`.
### Added
- `/specs` specification system (spec-first workflow): intake, context, strategic plan, production specification, execution plan (phase M1 — Quick Connect authentication, status PROPOSED), decision log (DEC-001…007), known gaps, and the `AGENTS.md` contract. No application code changed.
  - Human answers Q-001…Q-005 integrated: DEC-007 (*lite* variant for Pixel Watch) locked, DEC-008 (goal = `PLAN.md` roadmap) and DEC-009 (Room migration discipline, REQ-012) added; "M1 — Quick Connect" execution plan ready for approval (`READY FOR HUMAN APPROVAL`).

### Added — M1 (server connection), plan task 3 (Quick Connect)
- Quick Connect authentication (primary) and `AuthenticateByName` fallback: `data/auth/AuthModels.kt` (`AuthSession`, `QuickConnectHandle`, `QuickConnectState`, `AuthResult`), `data/auth/TokenStore.kt` (interface + `EncryptedTokenStore` backed by the Android Keystore, REQ-005), `data/repo/AuthRepository.kt` (3-step Quick Connect + Flow polling with a 5 min budget / 2 s cadence + credentials fallback). The token is never logged or stored in cleartext.
- Hilt module `di/AuthModule.kt` (`TokenStore` → `EncryptedTokenStore` binding).
- Wear Compose onboarding screens: `ui/screens/onboarding/OnboardingViewModel.kt` (sealed 5-state machine, Quick Connect poll cancellation on lane change) + `OnboardingScreens.kt` (server entry → code display → credentials fallback).
- Conditional navigation (`ui/nav/JellyNav.kt`, `MainActivity.kt`): onboarding screen if no session, library otherwise.
- **TDD**: 20 JVM AuthRepository tests (MockWebServer, PascalCase bodies for the SDK's `@SerialName`) + 5 instrumented EncryptedTokenStore tests (Android Keystore). Post-task-3 total: **43 unit tests + 5 instrumented**, all green.
- Dependencies: `androidx.security:security-crypto` 1.1.0, `androidx.hilt:hilt-navigation-compose` 1.4.0, `androidx.lifecycle:lifecycle-viewmodel-compose` 2.11.0, `androidx.test.ext:junit` 1.3.0, and `androidx.test:runner` 1.7.0 (androidTest).

### Fixed — M1 task 3, verification pass (2026-08-08)
- **Missing `INTERNET` permission** (blocking): `AndroidManifest.xml` declared no permission. Every call to the Jellyfin server — task 2 validation as well as task 3 authentication — failed on a real device. Invisible to JVM tests (MockWebServer does not cross the Android permission barrier). Added `INTERNET` and `ACCESS_NETWORK_STATE`.
- **Instrumented tests unrunnable** (blocking): `testInstrumentationRunner` pointed to `androidx.test.runner.AndroidJUnitRunner` while only `androidx.test.ext:junit` was declared — which does not provide the runner transitively. `connectedDebugAndroidTest` failed with `ClassNotFoundException` before even discovering a test: the 5 `EncryptedTokenStore` tests had therefore never run. Added `androidx.test:runner`.
- **Cleartext traffic to LAN servers**: added `res/xml/network_security_config.xml`, required since Android 9 to reach a self-hosted Jellyfin server at `http://…:8096` (a scheme explicitly supported by `UrlNormalizer`). TLS verification remains intact (DEC-006); the current scope is global and should be tightened before release (GAP-008).
- **Test hardening**: 6 tests added to `AuthRepositoryTest` (the 2 s / 5 min budget now verified in virtual time, poll cancellation, server-side expiry mid-poll, `CancellationException` propagation, null `User`, verbatim `baseUrl` pass-through). The budget assertion is *bite-verified*: lowering `POLL_TIMEOUT` to 4 min fails the test.
- End-to-end verification succeeded on a Wear OS emulator against a private test Jellyfin 10.11.11 server: Quick Connect, credentials fallback, session persistence, data wipe, and token encryption at rest (no cleartext data in `shared_prefs`).

### Added — M1 (server connection), plan task 2
- **Official Jellyfin SDK** (`org.jellyfin.sdk:jellyfin-core` 1.8.12, OkHttp backend) integrated into the Hilt graph via `di/JellyfinModule` (`createJellyfin { context ; clientInfo }`, singleton instance).
- `data/jellyfin/JellyfinBackend`: SDK access port (UDP 7359 discovery + `ApiClient` creation), implemented by `JellyfinBackendImpl`. Abstracted so the repository can be tested without a `Context`.
- `data/jellyfin/UrlNormalizer`: "aggressive" normalization of a typed URL (`http://` scheme guessed, default port `8096` for http, implicit `443` for https, trailing slash removed, non-http/https schemes rejected). Pure, with no SDK dependency.
- `data/repo/ServerRepository`: `discoverServers()` (UDP 7359 Flow → `DiscoveredServer`) and `validateServer(rawUrl)` (normalizes then validates via `GET /System/Info/Public`, unauthenticated) returning a `ServerValidation` (`Valid` / `InvalidUrl` / `Unreachable`).
- **TDD**: 22 JVM tests green (15 pure `UrlNormalizer` + 7 `ServerRepository` via MockWebServer, JSON bodies in PascalCase to match the SDK's generated `@SerialName` values).

### Changed
- `app/build.gradle.kts`: `buildFeatures.buildConfig = true` (for `BuildConfig.VERSION_NAME`, identifying the client to the Jellyfin server).
- `gradle/libs.versions.toml`: adds `jellyfinSdk = 1.8.12`, `coroutines = 1.10.2`, `okhttp = 5.3.2`, `slf4j = 2.0.18`, `junit = 4.13.2`.
- **Unified plan** (`PLAN.md`): merges the two previous design plans after factual verification (3 research sub-agents) of the disputed technical claims.
  - Keeps "Plan B"'s product/architecture decisions: official Kotlin SDK `org.jellyfin.sdk`, Quick Connect auth, `/Items/{id}/File` endpoint, original files without transcoding in v1, Hilt.
  - Executes according to "Plan A"'s task-by-task blueprint (14 tasks) and engineering rigor (TDD, storage budget, artist batching, reconciliation).
  - Factual corrections: the official SDK does not use apache-client (OkHttp 5.x); `/Items/{id}/File` is Range-resumable unlike `/Audio/{id}/universal`; the 6 h `dataSync` FGS cap (Android 15+) is real and makes the hybrid WorkManager+FGS mandatory; `wear-compose-material3` 1.5.x is the stable line to use.

### Technical notes
- `org.slf4j:slf4j-nop` as `implementation`: the SDK logs via `kotlin-logging` → slf4j, whose API is not transitively `runtime` (left to the app). Without a binding, loading the SDK's classes throws `NoClassDefFoundError: org/slf4j/LoggerFactory` — in production as in tests. NOP kept for now (an slf4j→logcat bridge remains an option during polish).
- AGP resolves the SDK's KMP **Android variant** *even* for JVM unit tests: `createJellyfin{}` then requires a `Context`. Hence the `JellyfinBackend` port — tests build the `ApiClient` via `OkHttpFactory` directly, without a `Context` or Robolectric (which would be risky on AGP 9 / compileSdk 37).

### Deprecated
- `2026-08-05_203130-wearos-jellyfin-sync.md` ("Plan A"): marked obsolete, kept for history with a pointer to the unified plan. Its central technical choices (hand-rolled client, `/Audio/{id}/universal` endpoint, transcoding at download time) rested on two factually incorrect claims.

### Internal 0.0.1 snapshot - 2026-08-05

### Added
- Initial repository: README, `PLAN.md` (design plan, "Plan B"), and `2026-08-05_203130-wearos-jellyfin-sync.md` (implementation plan, "Plan A").
