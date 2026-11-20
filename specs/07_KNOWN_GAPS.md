# Known Gaps

> Removing an entry requires evidence or a recorded decision, not optimism.

## Open gaps

### GAP-005 — Collection pin limit and large-library UX remain open
- Type: product uncertainty
- Description: The collection pin limit and the UX for large libraries on a small screen are still
  open. Two items originally listed here are settled: the final app name/icon by DEC-012, and
  default sync periodicity as a user setting (6–168 h, default 24 h).
- Evidence/sources: PLAN.md §12; DEC-012; `SettingsRepository.kt`, `SyncScheduler.kt` [VERIFIED].
- Why it matters: A pin cap affects storage planning and the browse UX; it does not block the
  shipped functionality.
- Work blocked: Later product polish only.
- Resolution needed: Human product decision in a future milestone.
- Owner: nicolasestrem
- Status: OPEN

### GAP-007 — Lite flavor feature set and mechanism undefined
- Type: product ambiguity
- Description: Q-002=B established that a separate reduced-feature "lite" build flavor will exist and be tested on a physical Pixel Watch (DEC-007 LOCKED). The specific capabilities excluded from the lite flavor and the delivery mechanism (productFlavor vs buildType) are undefined.
- Evidence/sources: Q-002=B, 2026-08-07; DEC-007.
- Why it matters: Blocks lite-flavor design and implementation. Does NOT block Phase 1 (Quick Connect auth ships in all flavors).
- Work blocked: Lite-variant design, build configuration, footprint requirements.
- Resolution needed: Human answer to Q-006.
- Owner: nicolasestrem
- Status: OPEN
- Resolution reference: Q-006

### GAP-014 — A background sync pass cannot start the download foreground service
- Type: platform constraint (documented, mitigated)
- Description: Android 12+ forbids a foreground-service start from a process that is not
  visible. `ReconWorker` runs as background work, so the periodic pass can register downloads
  in the Media3 index but frequently cannot start `JellyDownloadService` to move them. Media3
  has the same limit and handles it the same way: `DownloadManagerHelper.restartService()`
  catches the `IllegalStateException` and waits for its `Scheduler`.
- Evidence/sources: `androidx.media3.exoplayer.offline.DownloadService` (1.7.1) sources, the
  `restartService()` catch blocks; the 2026-09-03 sync fix pass.
- Why it matters: DEC-005's hybrid WorkManager + foreground-service model depends on the
  service actually running. Bytes only move while it does.
- Mitigation in place: the enqueue itself is now durable and never fails for this reason; the
  `WorkManagerScheduler` returned by `JellyDownloadService.getScheduler()` restarts the service
  when requirements are met, and `MainActivity.onStart` starts it for any pending download the
  moment the user opens the app.
- Residual risk: on a watch left undocked and unopened, a periodic pass may enqueue without
  downloading until requirements are met or the app is next opened. Needs measurement over a
  real overnight cycle before deciding whether the worker should hold its own `dataSync`
  foreground service.
- Owner: nicolasestrem
- Status: OPEN — mitigated, needs an overnight measurement.

### GAP-015 — The watch's end-to-end download path is unverified on device
- Type: verification gap
- Description: The 2026-09-03 fixes are covered by the JVM unit suite and were smoke-tested on
  the test watch (clean launch, no crash, correct network-policy decisions). The full
  path — pin a collection, watch bytes land in `filesDir/media_downloads`, play offline — could
  not be exercised because the watch is signed out: `shared_prefs/jellywatch_auth.xml` holds
  only its two Tink keysets and no session, and `databases/` contains no `jellywatch.db` at
  all, so the app has never reached its Room layer on this install.
- Evidence/sources: `adb run-as com.example.jellywatch` listings of `shared_prefs`,
  `databases` and `files/media_downloads`, 2026-09-03.
- Work blocked: The on-device acceptance run.
- Resolution needed: the owner signs in on the test watch via Quick Connect, then the
  pin → download → offline-playback sequence is run and its logcat captured.
- Owner: nicolasestrem
- Status: OPEN — needs owner action (sign in on the test watch).

### GAP-016 — Companion discovery is verified only on its failure paths
- Type: verification gap
- Description: `WearableWatchLink.discoverWatch()` combines `NodeClient.getConnectedNodes()` with the `CapabilityClient` query so the phone can distinguish "no watch connected", "connected without JellyWrist", and "connected but silent". The Data Layer reports connections, not pairings, so the first state deliberately does not claim the phone is unpaired: a paired watch that is off, asleep, or out of range lands there too. Only that first branch has been exercised on hardware: at the time, the test phone had no connected Wear OS device, so `NoWatchConnected` was the only outcome reachable on it. `Found` and `NodesWithoutApp` are covered by JVM tests against `FakeWatchLink` alone, as is the stale-status flag raised when a watch that once answered goes silent.
- Evidence/sources: home screen renders "No watch is connected" on the test phone after `:mobile:installDebug`; the Wear device attached over adb was paired with a different handset at the time [VERIFIED 2026-09-03].
- Why it matters: the `Node.getDisplayName()` values used in the "app missing" message, the nearby-over-cloud node preference, and the release-only resource-shrinker fix (`app/src/main/res/raw/keep.xml`) are all unexercised against a real pairing.
- Work blocked: nothing; end-to-end companion provisioning cannot be verified until a watch is paired with the test phone.
- Resolution needed: pair the watch with the test phone, install both debug APKs, and confirm the card reaches the configured state; then repeat with a release build to confirm the capability array survives resource shrinking.
- Owner: nicolasestrem
- Status: **OPEN**

### GAP-017 — A watch lost its session with no recorded cause
- Type: correctness/observability defect
- Description: The test watch was authenticated (screenshots show a populated
  library) and was later found signed out, with `shared_prefs/jellywatch_auth.xml` holding
  only its two Tink keysets and no `jellywatch.db` in `databases/` at all. Only two code paths
  clear the session — the Settings sign-out button and `LocalDataReset.factoryReset()`, which
  only that button calls — so the likeliest explanation is `EncryptedTokenStore`'s
  unreadable-store recovery firing, which deletes the preferences and the app's Keystore
  master key. Both outcomes leave an identical keysets-only file, and neither logged anything,
  so the two cannot be told apart after the fact.
- Evidence/sources: `adb run-as com.example.jellywatch` listings of `shared_prefs`,
  `databases`, `files/media_downloads`; `TokenStore.load`/`resetUnreadableStore`; call-site
  survey of `clear()`/`factoryReset()`/`signOut()` [VERIFIED 2026-09-03].
- Why it matters: a silently destroyed session presents exactly as the reported bug. Sync and
  downloads both return early without one, so the watch looks configured and simply stops.
- Work blocked: nothing. Diagnosis of any recurrence.
- Resolution needed: the logging added in this pass (tag `JellyAuth`) names the cause the next
  time it happens. Until an occurrence is captured, the trigger — Keystore invalidation after
  a credential change, a system update, or a restore onto a device without the key — is
  unproven, and no fix should be guessed at.
- Owner: nicolasestrem
- Status: OPEN — instrumented, awaiting a captured occurrence.

## Deferred items

### REQ-010 — Playback reporting (M5+)
- Description: Offline playback events queued in Room `PlaybackReport`, replayed to server on next sync. Deferred to M5+ per the requirement catalogue.
- Evidence/sources: REQ-010 in `03_MASTER_PRODUCTION_SPEC.md`; PLAN.md sync engine design.
- Status: DEFERRED

### PLAN.md §12 open product questions
- Description: Final app name/icon, default sync periodicity, and other product-level decisions listed in PLAN.md §12 are intentionally deferred beyond the current bootstrap and M1 phase.
- Evidence/sources: PLAN.md §12 [VERIFIED].
- Status: DEFERRED

## Current assumptions

### ASM-002 — Google Pixel Watch can run existing app unmodified for testing
- Assumption: A Google Pixel Watch running Wear OS 5+ (minSdk 34 satisfied) can run the existing app unmodified; "testing on Pixel Watch" alone requires no code change.
- Reason: The app targets Wear OS 5+ (minSdk 34) and uses standard Wear OS APIs (Horologist, wear-compose-material3). A Pixel Watch (gen 2 or later) runs Wear OS 4+/5+.
- Affected areas: DEC-007 (lite testing proposal), GAP-002 resolution path.
- Risk if false: Device-specific compatibility issues could require a device-specific implementation phase before meaningful testing.
- Confirmation method: `installDebug` on the test watch once available.
- Expiry condition: Successful device install or Q-002 answer ruling out bare-device testing.
- 2026-08-07 update: Now applies to the lite flavor per DEC-007 (LOCKED via Q-002=B). Confirmation method unchanged: `installDebug` on the test watch.

## Recently resolved

### GAP-001 — PROJECT_GOAL text is workflow-example boilerplate
- Type: scope ambiguity
- Description: The stated PROJECT_GOAL ("Add resumable imports without breaking the current upload and history flows") matches verbatim the intake workflow's §14 example invocation (lines ~1220-1229). The repository has no import, upload, or history flows — it is a Wear OS Jellyfin offline-music-sync app at early milestone M0/M1. A charitable mapping exists ("resumable imports" ≈ Range-resumable track downloads from Jellyfin, settled as DEC-003), but choosing it is a consequential decision.
- Evidence/sources: The intake workflow's §14 example text; repository inventory (no import/upload/history code exists).
- Why it matters: Blocks approval of `02_STRATEGIC_PLAN.md` scope and `04_EXECUTION_PLAN.md` phase selection. The entire strategic direction depends on whether we continue the PLAN.md roadmap or pivot.
- Work blocked: Strategy approval, execution plan approval, any phase start.
- Resolution needed: Human answer to Q-001. A charitable mapping (continue PLAN.md roadmap) is proposed but not assumed.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution reference: Q-001=A
- Resolution: Q-001=A — template artifact; authoritative goal = continue PLAN.md roadmap. See **DEC-008**.
- Date resolved: 2026-08-07

### GAP-002 — "Lite version on Google Pixel Watch" undefined
- Type: product ambiguity
- Description: Human statement during this bootstrap run: "Another change in the plan; the app will be tested in a lite version on a Google Pixel Watch too." The meaning of "lite" is unresolved — it could mean a physical test device for the normal debug build, a separate reduced-feature build flavor, or footprint/performance constraints to encode as requirements.
- Evidence/sources: Human chat statement, 2026-08-07.
- Why it matters: Blocks any lite-variant work; may affect requirements, build configuration, and testing strategy if it means more than just a test device.
- Work blocked: Lite-variant design, build flavors, footprint requirements.
- Resolution needed: Human answer to Q-002.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution reference: Q-002=B
- Resolution: Q-002=B — a separate reduced-feature build flavor; the meaning of "lite" is now settled. The remaining unknown is the feature set/mechanism, tracked as **GAP-007** (non-blocking, resolution via Q-006). See **DEC-007**.
- Date resolved: 2026-08-07

### GAP-003 — KNOWN_CONSTRAINTS "existing production data must remain readable" has no referent
- Type: constraint ambiguity
- Description: The KNOWN_CONSTRAINTS text includes "Existing production data must remain readable," but no production data exists — there is no Room database, no deployed app, no user data. This may be a template artifact, or it may mean the client must never mutate Jellyfin server-side data (read-only client), or that future Room schema changes must always ship migrations.
- Evidence/sources: Repository inventory — no Room DB, no production deployment, no user data [VERIFIED].
- Why it matters: Could become a requirement (server-side data integrity guarantee or migration discipline) once clarified.
- Work blocked: Nothing now; B/C options in Q-003 would become REQs.
- Resolution needed: Human answer to Q-003.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution reference: Q-003=C
- Resolution: Q-003=C — every Room schema change ships a versioned Migration with instrumented test; destructive fallback prohibited in release builds. See **DEC-009** / **REQ-012**.
- Date resolved: 2026-08-07

### GAP-004 — Local main was 1 commit behind origin/main
- Type: repository state
- Description: The local checkout was one commit behind `origin/main`; the merged M1 task 2 work (Jellyfin SDK dependency, `UrlNormalizer`, `ServerRepository` with discovery and validation, MockWebServer tests) was absent from the local checkout.
- Evidence/sources: `git log` [VERIFIED]; upstream merge of that work.
- Why it matters: Specs are written against the local checkout state. Any implementation phase must start from a checkout that includes that work.
- Work blocked: Implementation start only.
- Resolution needed: `git pull` main when the human permits (bootstrap must not mutate git state).
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution reference: Q-005=A
- Resolution: `git pull` executed; local main fast-forwarded to the `origin/main` tip. No spec divergence.
- Date resolved: 2026-08-07

### GAP-006 — EXPLICIT_NON_GOALS name features that never existed here
- Type: constraint ambiguity
- Description: The EXPLICIT_NON_GOALS ("Billing, admin tools, queue replacement") are generic exclusions naming features that have never existed in this repository. They appear to be template artifacts. The operative non-goal list is PLAN.md §1.3 ("out of scope v1").
- Evidence/sources: Repository inventory — no billing, admin, or queue code exists [VERIFIED]; PLAN.md §1.3 [VERIFIED].
- Why it matters: Could confuse agents into treating these as project-specific exclusions rather than template noise.
- Work blocked: Nothing now.
- Resolution needed: Human answer to Q-004 to confirm these are generic exclusions.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution reference: Q-004=A
- Resolution: Q-004=A — billing/admin tools/queue replacement are generic exclusions; PLAN.md §1.3 is the operative non-goal list. See **DEC-008**.
- Date resolved: 2026-08-07

### GAP-008 — Launch crash: background `startService` blocked (NetworkAwareness → DownloadService)
- Status: **RESOLVED**
- Resolution: `Media3TrackDownloader.pauseAll()`/`resumeAll()` now wrap the Media3 `sendPauseDownloads`/`sendResumeDownloads` intents in `runCatching`, so a background service-start refusal (Android 12+ `BackgroundServiceStartNotAllowedException`) during `NetworkAwareness`'s first cold-launch emission no longer crashes the process before the first frame. State is reconciled by the WorkManager scheduler once the app is foreground.
- Date resolved: 2026-08-11

### GAP-009 — `./gradlew lint` is red on pre-existing issues (never run in CI)
- Status: **RESOLVED**
- Resolution: Lint hygiene pass: real fixes (`Uri.parse`→`toUri()`, track-count strings→`<plurals>` FR/EN, `taskAffinity` for WearRecents), site-local suppression of the intentional synchronous token write (`ApplySharedPref`/`UseKtx` on `EncryptedTokenStore`), and `app/lint.xml` downgrading the deliberate-architecture findings (`CustomX509TrustManager`, `TrustAllX509TrustManager`, `InsecureBaseConfiguration`, `ExportedService`) to informational with documented rationale. Advisory version-checks disabled in `app/build.gradle.kts`. `lintDebug` now passes with 0 errors/0 warnings and runs in CI.
- Date resolved: 2026-08-11

### GAP-010 — Periodic sync stays off after a factory reset followed by companion provisioning
- Type: correctness defect (silent)
- Description: `LocalDataReset.factoryReset()` calls `SyncScheduler.cancelAll()` and deliberately never re-arms — a signed-out watch must have nothing scheduled. A `SETUP` arriving afterwards then runs with `currentSession() == null`, so `reconfiguring` is false and `ServerDataReset.clearServerData()` is skipped — and that is the only call on this path that reaches `schedulePeriodic()`. The watch ends up fully provisioned (server, user, storage all shown) and silently never syncs on its own until the next process start or settings change. The mirror case on the watch's own re-onboarding after sign-out (`OnboardingViewModel.applyResult`) re-arms nothing either.
- Evidence/sources: `LocalDataReset.kt` (`factoryReset`, `clearServerData`, `purge`); `CompanionSetupCoordinator.runSetup` (`reconfiguring` branch); `schedulePeriodic()` call sites — `LocalDataReset.clearServerData`, `JellyWristApp`, `SettingsViewModel` (settings changes) and nowhere else. Raised during an earlier review pass on the companion setup path; pre-existing, but the setup lock added in that pass makes the queued-`SETUP` path take it deterministically rather than occasionally.
- Why it matters: Same silent symptom as the re-arming bug fixed in an earlier review pass, on the two remaining paths. The user sees a healthy, configured watch that quietly stops syncing.
- Work blocked: Nothing — the watch syncs correctly on every other path, and a manual sync or any settings change restores it.
- Resolution needed: A scheduling port injectable into `CompanionSetupCoordinator` (`SyncScheduler` needs WorkManager and an Android `Context`, so it cannot be constructed in the JVM tests — this needs the same `fun interface` treatment as `ServerDataReset`), plus a re-arm on the watch's own onboarding success. Both paths in one dedicated pass.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution: 2026-09-01 hardening pass. Both paths fixed exactly as described: a new `PeriodicSyncArmer` `fun interface` port (bound to `SyncScheduler::schedulePeriodic` in `ResetModule`) is armed unconditionally in `CompanionSetupCoordinator`'s post-authentication `NonCancellable` block, and `OnboardingViewModel.applyResult` arms it on the watch's own authentication success. JVM-covered by `CompanionSetupCoordinatorTest` (fresh-setup arm, failed-setup no-arm) and `OnboardingViewModelTest` (watch-local re-arm).
- Date resolved: 2026-09-01

### GAP-011 — `appScope` has no `CoroutineExceptionHandler`; three companion handlers are unguarded
- Type: robustness defect (process kill)
- Description: `provideAppScope` supplies a bare `CoroutineScope(SupervisorJob() + Dispatchers.Default)` on both the watch and the phone. `CompanionListenerService.onMessageReceived` launches four handlers on it; an earlier review pass hardened only `handleSetup`. `handlePin` (network `setPinned`, WorkManager `scheduleAutomaticOneShot`), `handleSyncNow` (`syncNow`) and `sendStatus` (Room, DataStore, `usedBytes`) remain bare, so a failure in any of them kills the watch process by exactly the mechanism that pass documented for the Quick Connect poll. `viewModelScope` has no handler either.
- Evidence/sources: `di/NetworkAwarenessModule.kt` (`provideAppScope`) and the phone's `MobileModule` equivalent; `CompanionListenerService.onMessageReceived` and its four handlers. `WatchSetupController` already documents this hazard verbatim for the phone side. Raised during a later review pass on the companion command path.
- Why it matters: A phone command that fails — an unreachable server during a pin, a WorkManager error during a sync request — takes down the watch app instead of reporting.
- Work blocked: Nothing.
- Resolution needed: A logging `CoroutineExceptionHandler` in both `provideAppScope` factories, keeping the local catches where a user-visible state must still be produced. This changes global crash behaviour, so it wants its own review rather than riding along with a fix pass.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution: 2026-09-01 hardening pass. Both `provideAppScope` factories now carry a logging `CoroutineExceptionHandler` (last line of defense), and the three bare handlers moved into `CompanionCommandHandler` — extracted from `CompanionListenerService`, serialized under one mutex, each command wrapped so an unexpected failure replies `WireAck(false, INTERNAL)` instead of escaping. JVM-covered by `CompanionCommandHandlerTest`.
- Date resolved: 2026-09-01

### GAP-012 — Public release license is not selected
- Type: publication/legal blocker
- Description: The repository has no license. Public visibility alone does not grant rights
  to copy, modify, or redistribute the source.
- Work blocked: Describing the project as open source and accepting reusable third-party
  contributions under known terms.
- Resolution needed: The owner selects a license after considering application, dependency,
  and brand-asset goals; add the license and contribution terms before the beta is advertised
  as open source.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution: MIT LICENSE added for 0.0.1, Copyright (c) 2026 nicolasestrem. See **DEC-015**.
- Date resolved: 2026-11-20

### GAP-013 — Existing Git history is not approved for publication
- Type: privacy/release blocker
- Description: Historical commits contain private operational identifiers even though the
  current tree has been sanitized. Deleting text in a later commit does not remove old blobs.
- Work blocked: Pushing, mirroring, importing, or forking this repository into a public host.
- Resolution needed: Initialize the public repository from the reviewed sanitized tree as a
  new root commit, then run current-tree and full-history scanning on that new repository.
- Owner: nicolasestrem
- Status: **RESOLVED**
- Resolution: The 0.0.1 public repository is created from one reviewed root commit and the old
  history is deleted and not published. See **DEC-015**.
- Date resolved: 2026-11-20

### ASM-001 — PLAN.md authoritative roadmap (retired assumption)
- Status: **RESOLVED**
- Resolution: Q-001=A confirmed PLAN.md is the authoritative roadmap. Assumption retired — promoted to verified fact. See **DEC-008**.
- Date resolved: 2026-08-07
