# Decision Log

> **Record note.** Evidence links to `CLAUDE.md` below are historical: that file was the
> pre-public agent contract and is not part of the published tree. References to the intake
> workflow documents are historical too: those bootstrap files are not published. The current
> contract is `AGENTS.md`, with `docs/claude-code-reference.md` for tool-specific guidance.
> DEC-010 was referenced by the implementation and the specs but its standalone entry was
> missing from this log; it was reconstructed on 2026-11-20 from the implementation and
> cross-references.

## DEC-001 — Official Jellyfin Kotlin SDK
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans (PLAN.md §0). "Plan A" used a hand-rolled HTTP client; "Plan B" specified the official SDK. Verification confirmed the SDK uses OkHttp 5.x (not apache-client) and covers all required Jellyfin API endpoints.
- Decision: Use the official `org.jellyfin.sdk` Kotlin SDK exclusively for all Jellyfin server communication. No hand-rolled HTTP client.
- Rationale: The SDK is maintained by the Jellyfin project, covers authentication and media endpoints, and eliminates duplication of HTTP boilerplate. PLAN.md §3 architecture depends on it.
- Alternatives considered: Hand-rolled Retrofit/OkHttp client (rejected — maintenance burden, no codec-negotiation helpers).
- Consequences/trade-offs: Couples the project to the SDK's release cadence and API surface. SDK version pinned via version catalog.
- Requirements/components affected: REQ-002
- Compatibility or migration impact: None (new project, no existing client code).
- Supersedes: "Plan A" hand-rolled client approach.
- Superseded by: —
- Evidence/links: PLAN.md §0, §1.2, §3; CLAUDE.md "Settled decisions".

## DEC-002 — Quick Connect auth first, AuthenticateByName fallback
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans. Both plans agreed on Quick Connect as primary auth; "Plan B" made the fallback explicit. Verification confirmed the SDK supports both flows.
- Decision: Primary authentication via Jellyfin Quick Connect (PIN-based, no keyboard needed on watch). Fallback: `AuthenticateByName` (username + password). Token stored exclusively in EncryptedSharedPreferences/Keystore.
- Rationale: Quick Connect is the only practical auth flow on a watch (no on-screen keyboard for most users). The fallback covers servers with Quick Connect disabled.
- Alternatives considered: AuthenticateByName only (rejected — poor UX on watch); API key auth (rejected — no per-user scoping).
- Consequences/trade-offs: Two auth flows to implement and test. Token storage complexity for secure enclave.
- Requirements/components affected: REQ-005
- Compatibility or migration impact: None (new project).
- Supersedes: —
- Superseded by: DEC-010 (the `AuthenticateByName` fallback was removed)
- Evidence/links: PLAN.md §0, §3; CLAUDE.md "Settled decisions".

## DEC-003 — Download via GET /Items/{id}/File (Range-resumable)
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans. "Plan A" used `/Audio/{id}/universal` (transcoded, non-resumable); "Plan B" used `/Items/{id}/File`. Verification confirmed `/Items/{id}/File` returns the original file with `Accept-Ranges: bytes` support, while `/Audio/{id}/universal` does not support Range headers. `/Items/{id}/Download` was also considered but rejected (requires extra permission scope).
- Decision: All track downloads use `GET /Items/{id}/File` (original file). Resume after interruption (including process death and FGS 6h cap) via HTTP Range headers.
- Rationale: Range-resumable downloads are essential for a sync engine that must handle FGS timeouts, connectivity drops, and process death on a resource-constrained wearable. `/Items/{id}/File` provides original-quality files without transcoding overhead.
- Alternatives considered: `/Audio/{id}/universal` (rejected — not Range-resumable, adds transcoding); `/Items/{id}/Download` (rejected — requires extra server permission).
- Consequences/trade-offs: Unsupported codecs (ALAC, WMA, etc.) will be downloaded but cannot be played locally — handled by DEC-004.
- Requirements/components affected: REQ-003
- Compatibility or migration impact: None (new project).
- Supersedes: "Plan A" `/Audio/{id}/universal` endpoint choice.
- Superseded by: —
- Evidence/links: PLAN.md §0, §3, §4; CLAUDE.md "Settled decisions".

## DEC-004 — No transcoding in v1
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans. "Plan A" included transcode-on-download. "Plan B" deferred transcoding. Verification confirmed transcoding is computationally expensive on a wearable and unnecessary when the Jellyfin library contains widely-supported formats.
- Decision: No transcoding in v1. Unsupported codecs (ALAC, WMA, etc.) are detected at sync-diff time from Jellyfin codec metadata and flagged to the user; they are not failed at playback.
- Rationale: Transcoding on a watch is prohibitively expensive (CPU, battery, heat). Server-side transcoding defeats the purpose of offline originals. Flagging unsupported codecs gives users actionable information without blocking sync.
- Alternatives considered: Server-side transcoding via `/Audio/{id}/universal` (rejected — loses offline original, not resumable); on-device transcoding (rejected — resource constraints).
- Consequences/trade-offs: Users with codec-uncommon libraries will see flagged tracks they cannot play until a future transcoding phase.
- Requirements/components affected: REQ-004
- Compatibility or migration impact: None (new project).
- Supersedes: "Plan A" transcode-on-download approach.
- Superseded by: —
- Evidence/links: PLAN.md §0, §4; CLAUDE.md "Settled decisions".

## DEC-005 — Hybrid WorkManager + foreground dataSync service
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans. Android 15+ enforces a 6-hour limit on foreground services with `dataSync` type. This limit is real and verified. "Plan B" designed the hybrid approach to handle it.
- Decision: Use a hybrid WorkManager + foreground `dataSync` service for sync. Long syncs that exceed the 6h FGS cap are re-armed and resumed via HTTP Range (DEC-003). UI shows "paused, will resume" during re-arm windows.
- Rationale: WorkManager provides constraint-based scheduling (charging + Wi-Fi). The FGS is needed for long-running downloads. The 6h cap makes the re-arm+resume pattern mandatory — not optional.
- Alternatives considered: WorkManager-only with short bursts (rejected — poor UX for large libraries); FGS-only (rejected — killed by 6h cap, no constraint scheduling).
- Consequences/trade-offs: Complex lifecycle management; must handle FGS timeout gracefully; user must understand pause/resume behavior.
- Requirements/components affected: REQ-003, REQ-007
- Compatibility or migration impact: None (new project).
- Supersedes: —
- Superseded by: —
- Evidence/links: PLAN.md §0, §4; CLAUDE.md "Settled decisions"; Android 15 FGS documentation.

## DEC-006 — Hilt DI, Media3 + Horologist, app-private storage
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: Factual verification that unified two conflicting plans. Both plans agreed on Hilt and Media3. "Plan B" added Horologist for Wear OS media session integration. Certificate handling and storage permissions were explicitly settled.
- Decision: Hilt for dependency injection. Media3 ExoPlayer + Horologist for download and playback. Certificate pinning opt-in only, never global TLS bypass. All media stored in app-private `filesDir`; no storage permissions requested.
- Rationale: Hilt is the recommended Jetpack DI solution. Media3 + Horologist is the standard Wear OS media stack. App-private storage avoids permission requests and protects downloaded content.
- Alternatives considered: Koin (rejected — less IDE support, no compile-time guarantees); global TLS bypass (rejected — security anti-pattern); scoped storage (rejected — unnecessary permission surface).
- Consequences/trade-offs: App-private storage means content is lost on uninstall (acceptable for a sync client). Opt-in pinning may require user configuration for self-signed Jellyfin servers.
- Requirements/components affected: REQ-006, REQ-008, REQ-009
- Compatibility or migration impact: None (new project).
- Supersedes: —
- Superseded by: —
- Evidence/links: PLAN.md §0, §4, §5; CLAUDE.md "Settled decisions".

## DEC-007 — Reduced-feature "lite" build flavor tested on physical Google Pixel Watch
- Date: 2026-08-07
- Status: **LOCKED**
- Owner/approver: nicolasestrem
- Context: Human chat statement during this bootstrap run: "Another change in the plan; the app will be tested in a lite version on a Google Pixel Watch too." The meaning of "lite" and the scope of this change were not yet defined at bootstrap time (DEC-007 was PROPOSED). 2026-08-07: PROPOSED → LOCKED via Q-002=B.
- Decision: A separate reduced-feature "lite" build flavor will exist and be tested on a physical Google Pixel Watch. The specific feature set excluded from the lite flavor and the delivery mechanism (productFlavor vs buildType) are unresolved and deferred to Q-006 / GAP-007.
- Rationale: Human confirmed "lite" means a distinct product variant, not just a test device. Feature set to be defined in a future decision round.
- Alternatives considered: (A) bare test device only (rejected — human chose B); (C) footprint constraints as requirements (rejected — human chose B).
- Consequences/trade-offs: Lite flavor design and build configuration are deferred (GAP-007). Phase 1 (Quick Connect auth) ships in all flavors and is not blocked.
- Requirements/components affected: Lite-variant work only (blocked on GAP-007 / Q-006).
- Compatibility or migration impact: None for Phase 1.
- Supersedes: —
- Superseded by: —
- Evidence/links: Human chat statement, 2026-08-07; human answer Q-002=B, 2026-08-07.

## DEC-008 — Goal confirmation: template artifact; authoritative goal = PLAN.md roadmap
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: The run-configuration PROJECT_GOAL, KNOWN_CONSTRAINTS, and EXPLICIT_NON_GOALS text were verbatim identical to the SPEC_FIRST_AGENT_WORKFLOW.md §14 example invocation. The repository has no import, upload, or history flows. Q-001 asked the human to confirm the real goal; Q-004 confirmed the non-goal list.
- Decision: The PROJECT_GOAL/KNOWN_CONSTRAINTS/EXPLICIT_NON_GOALS text is a template artifact (workflow §14 example). The authoritative goal is continuing the PLAN.md roadmap (next task: Quick Connect auth, task 3). The operative non-goal list is PLAN.md §1.3 plus the generic exclusions (billing, admin tools, queue replacement).
- Rationale: Human confirmed (Q-001=A) the goal text is boilerplate and the PLAN.md roadmap is the intended direction. Q-004=A confirmed the EXPLICIT_NON_GOALS are generic exclusions.
- Alternatives considered: (B) emphasize resumable downloads/sync (rejected — human chose A); (C) genuinely new feature (rejected — human chose A).
- Consequences/trade-offs: `02_STRATEGIC_PLAN.md` scope is now approvable. Phase 1 = PLAN.md task 3 (Quick Connect auth). No scope pivot required.
- Requirements/components affected: 02_STRATEGIC_PLAN, 04_EXECUTION_PLAN.
- Compatibility or migration impact: None.
- Supersedes: —
- Superseded by: —
- Evidence/links: Human answers Q-001=A and Q-004=A, 2026-08-07.

## DEC-009 — Room migration discipline
- Date: 2026-08-07
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: KNOWN_CONSTRAINTS included "Existing production data must remain readable." No production data exists yet. Q-003 asked what this constraint means; the human chose option C (migration discipline).
- Decision: Every Room schema change must ship a versioned `Migration` with an instrumented migration test. `fallbackToDestructiveMigration` (or any data-destructive fallback) is prohibited in release builds.
- Rationale: The human explicitly chose migration discipline (Q-003=C). This protects user data across app updates and prevents silent data loss.
- Alternatives considered: (A) template artifact, ignore (rejected — human chose C); (B) read-only Jellyfin server data guarantee (rejected — human chose C).
- Consequences/trade-offs: Adds migration development overhead from the first Room schema onward (PLAN.md task ~5+). Destructive migration remains acceptable in debug/test builds for developer convenience.
- Requirements/components affected: REQ-012; all Room schema work from first schema onward.
- Compatibility or migration impact: None yet (no Room DB exists); applies from first schema creation.
- Supersedes: —
- Superseded by: —
- Evidence/links: Human answer Q-003=C, 2026-08-07.

## DEC-010 — Quick Connect is the only authentication path (no password fallback)
- Date: 2026-08-08
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: DEC-002 recorded an `AuthenticateByName` (username + password) fallback for servers where Quick Connect is disabled. That fallback required a password UI on a Wear OS screen that has no practical keyboard and introduced a second credential surface. The owner's direction of 2026-08-08 was Quick Connect only.
- Decision: Quick Connect is the only authentication path in both the watch app and the phone companion. The `AuthenticateByName` / password UI and its implementation are removed. A server without Quick Connect enabled produces an explicit, retryable "enable Quick Connect on the server" state. Tokens remain exclusively in `EncryptedSharedPreferences`/Keystore (REQ-005).
- Rationale: One authentication path means no password entry on a watch and no second credential UI to secure. The phone companion approves the watch's Quick Connect code, so provisioning needs no typed credentials either.
- Alternatives considered: keep the password fallback (rejected — keyboard friction on the watch and a second credential surface); let the phone type credentials for the watch (rejected — still a credential UI, and Quick Connect already covers provisioning).
- Consequences/trade-offs: Servers with Quick Connect disabled must enable it before JellyWrist can sign in. REQ-005's "AuthenticateByName fallback" clause is superseded in practice and retained only as historical requirement text; its token-storage clause is unchanged.
- Requirements/components affected: REQ-005 (authentication path; token storage unchanged); `AuthRepository`, watch onboarding screens/view model, phone onboarding.
- Compatibility or migration impact: Existing sessions are unaffected. A session obtained from an older internal build keeps working; future sign-ins use Quick Connect.
- Supersedes: The `AuthenticateByName` fallback in DEC-002.
- Superseded by: —
- Evidence/links: `core/src/main/java/com/example/jellywrist/data/auth/AuthRepository.kt` ("Quick Connect is **the only authentication path**"); `app/src/main/java/com/example/jellywrist/ui/screens/onboarding/OnboardingViewModel.kt` and `OnboardingScreens.kt` (DEC-010 references, owner decision 2026-08-08); the companion section of `specs/03_MASTER_PRODUCTION_SPEC.md`; DEC-013. **Record reconstructed 2026-11-20:** the original standalone entry was missing from this log while the implementation and specs referenced it.

## DEC-011 — Aurora UI redesign (supersedes "No visual redesign")
- Date: 2026-08-10
- Status: **LOCKED**
- Owner/approver: nicolasestrem
- Context: `03_MASTER_PRODUCTION_SPEC.md` §"User experience requirements" stated *"No visual redesign: Current Compose M3 Wear OS patterns preserved."* On 2026-08-10 the owner explicitly requested a modern, bold UI redesign ("create a modern, smart, simple, intuitive and functional UI … be bold and creative") and selected the **Aurora** visual direction (OLED-black base, violet→magenta gradient accent) from the presented options.
- Decision: Supersede the "No visual redesign" constraint. Introduce the **Aurora** design system on top of the existing Wear Compose Material3 architecture: a `ui/theme/` (palette + typography + `JellyWristTheme` + gradient brushes), a `ui/components/` library (`AuroraButton`, `AuroraChip`, `SectionHeader`, `StorageGauge`, `GradientProgressRing`, `AuroraSeekBar`, `TrackStatusIcon`, `GradientText`, `formatBytes`/`formatDuration`), real Material vector icons replacing all unicode glyphs, localized byte units, and a finished player (draggable seek bar + `m:ss` time readout via a 1-line `PlayerViewModel.seekTo` passthrough backed by the existing `PlayerController.seekTo`). Stateless screen contracts, Wear M3 imports, and `SwipeDismissableNavHost` navigation are preserved.
- Rationale: Owner direction. The stock Wear Compose UI carried zero brand identity and the player lacked a seek bar despite the state already exposing `positionMs`/`durationMs`. The redesign adds identity and closes that gap without touching data/sync/playback-engine/DB logic.
- Alternatives considered: keep the stock UI (rejected by owner); the Bioluminescence / Amber Noir / Pure-Mono palettes (rejected — owner chose Aurora).
- Consequences/trade-offs: New dependency `androidx.compose.material:material-icons-{core,extended}` (version-catalog, BOM-versioned; R8 strips unused icons in release). 5 per-state track strings removed (the vector `TrackStatusIcon` system replaces them); 7 player glyph strings + 3 dead onboarding strings removed during consolidation. All screen scaffolds standardized (self-contained `ScreenScaffold` + scroll-state wiring) fixing the prior 2-of-6 inconsistency.
- Requirements/components affected: **REQ-009** (player UI now fully conforms to Wear media patterns — seek + time readout). No other REQ changed.
- Compatibility or migration impact: UI-layer only; no API/data/DB change. Existing 133 JVM tests remain green.
- Supersedes: `03_MASTER_PRODUCTION_SPEC.md` line *"No visual redesign: Current Compose M3 Wear OS patterns preserved."*
- Superseded by: —
- Evidence/links: This session's approved plan; on-device Wear OS emulator screenshot of the Aurora onboarding screen (OLED black + gradient wordmark + gradient "Se connecter" button).

## DEC-012: JellyWrist product identity and upgrade continuity
- Date: 2026-08-11
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: The owner approved the full JellyWrist rebrand after selecting the Orbit Pulse visual direction. The signed JellyWatch 1.0.0 package already has local database, encrypted-token, DataStore, WorkManager, notification and downloaded-media state that must remain reachable after update.
- Decision: Rebrand the public product, source namespace, labels, client identity, launcher assets, notifications, CI artifact names and brand collateral to **JellyWrist**. The Orbit Pulse system is the canonical full-colour mark. Keep the signed Android `applicationId` as `com.example.jellywatch` and preserve persistence and scheduler identifiers containing `jellywatch`.
- Rationale: The selected name and artwork establish a coherent watch-first music-sync identity. Retaining the established package and persistent identifiers ensures that JellyWatch updates in place rather than becoming a separate application or losing local state.
- Consequences/trade-offs: Internal compatibility identifiers intentionally retain the previous name. They must not appear in user-visible copy. Any future application-ID migration requires an explicit signed-data migration plan.
- Requirements/components affected: Product identity, Android launcher and themed icons, notification icon, client identification, build artifacts, documentation and upgrade continuity.
- Compatibility or migration impact: Source namespace moves to `com.example.jellywrist`; `applicationId`, Room database name, encrypted preferences, DataStores, WorkManager names and tags, and notification channel ID remain stable.
- Supersedes: The provisional JellyWatch public identity.
- Superseded by: DEC-016 — the `applicationId` and `jellywatch_*` persistence identifiers moved to the `jellywrist` identity (no public users existed at 0.0.1). The JellyWrist brand decision remains in force.
- Evidence/links: Owner approval "Approve JellyWrist plan" (2026-08-11); `docs/brand/README.md`; Android adaptive icon resources.

## DEC-013 — Optional phone companion app (supersedes the "no phone companion" non-goal)
- Date: 2026-08-11
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: The owner requested a phone companion app with full remote management. The watch app was previously locked standalone (REQ-001, DEC-012-era scope) with no phone companion as a v1 non-goal. The owner approved lifting that exclusion.
- Decision: Add a `:mobile` phone companion module and a `:core` shared library. The phone app (`applicationId = "com.example.jellywatch"` — required by the Wearable Data Layer same-package/same-signature rule, consistent with DEC-012) provides: (1) zero-typing watch provisioning via the Wearable Data Layer (phone sends the server URL, watch runs Quick Connect, phone auto-approves the code); (2) watch status (server, user, storage, pinned collections, last sync); (3) library browsing with remote pin/unpin and "sync now". The watch remains standalone — `com.google.android.wearable.standalone=true` unchanged; the watch never *requires* a paired phone. Phone auth is Quick Connect only (extends DEC-010). The wire protocol uses versioned message paths under `/jellywrist/*/v1`.
- Rationale: Typing server URLs and credentials on a 4 cm watch screen is the primary onboarding friction. A phone companion eliminates it while keeping the watch fully autonomous. Remote library management makes pinning collections practical from a larger screen.
- Consequences/trade-offs: New Gradle modules (`:core`, `:mobile`); shared JellyfinBackend/AuthRepository/TokenStore/TLS layer extracted to `:core` (split-package cutover, no package renames). The phone app is signed with the same release keystore as the watch. CI builds both APKs.
- Requirements/components affected: REQ-001 reworded from "never assumes a paired phone" to "never *requires* a paired phone; an optional companion may assist provisioning and remote management". New companion section in 03_MASTER_PRODUCTION_SPEC.
- Compatibility or migration impact: Watch `applicationId`, Room database, encrypted preferences, DataStores, WorkManager names unchanged. Phone uses the same `applicationId` and signing key (Data Layer constraint). No data migration.
- Supersedes: PLAN.md §1.3 exclusion "Phone companion app"; 03_MASTER_PRODUCTION_SPEC "No phone companion is required at any point"; 02_STRATEGIC_PLAN non-goal "Phone companion app".
- Superseded by: none.
- Note: the shared `applicationId` is now `com.example.jellywrist` per DEC-016; the same-package/same-signature requirement is unchanged.
- Evidence/links: Owner approval "Build the companion app on the phone to go with the Wear OS app" (2026-08-11); `mobile/` module; `core/` module; `com.example.jellywrist.wire.Wire` protocol.

## DEC-014 — Watch UI redesigned from scratch (supersedes DEC-011's screen design)
- Date: 2026-09-03
- Status: **PROPOSED — awaiting owner approval**
- Owner/approver: nicolasestrem
- Context: The owner's verdict on 2026-09-03 is that the watch UI is confusing and should be redone from scratch, and that the `:mobile` companion is not part of this round. A read of `app/src/main/java/com/example/jellywrist/ui/` turns that verdict into locatable faults: `LibraryScreen` puts a brand header, a four-tab strip, a bulk-download tool, the whole content list *and* the app's only navigation menu into one `ScalingLazyColumn`, so Settings sits behind every song; one act carries three verbs ("Sync to watch" as a pin toggle, "Sync now" as a real imperative, and 50/100/200/500 pills that pin and sync in a single tap); six `TrackState` values render as unexplained tinted glyphs in three contexts and `PENDING_BUDGET` shows as a bare "Budget" badge; `collection_detail_refresh_error` prints `Throwable.message` on a 1.9-inch screen; consent prompts *replace* the control that raised them and are styled two different ways on two screens; `JellyNav` is a one-way tree in which `DOWNLOADS`, `SETTINGS` and `PLAYER` are leaves with no outbound edge and `JellyPlaybackService` sets no `sessionActivity`, so nothing leads back to what is playing; `StorageGauge` renders used/disk on one screen and used/quota on another with identical visuals; every row, button, toggle, navigation item and call to action is the same 52 dp `AuroraChip`, with the brand gradient applied to six competing roles; onboarding ends on an empty Playlists tab instead of `PLAN.md` §6's "suggestion to pin a first playlist"; and Songs rows, the `SectionHeader` chevron and the rotary crown are all inert. Two design inputs are already settled by the owner: pinning starts an immediate manual sync with consent prompts (`KeepOnWatchCoordinator`, `ConsentPrompt`, `SyncPreconditions`, shipped on a pre-merge development branch), and "random songs" becomes a **Random mix** collection whose size picker lives on its own detail screen. Dependency verification against the published POMs and the 1.6.2 AAR established that `horologist-compose-layout` and `horologist-media-ui` 0.7.15 both pull `androidx.wear.compose:compose-material` 1.5.0-rc01 and a second theme system that cannot follow `JellyWristTheme`, while Wear Compose Material3 1.6.2 and Foundation 1.6.2 — already in the version catalog — supply every primitive this design needs, including `TransformingLazyColumn`'s built-in rotary scrolling.
- Decision: Redesign the `:app` watch UI from scratch to the specification in `specs/10_WATCH_UI_REDESIGN.md`. In outline: a single `HOME` destination hosting a two-page `HorizontalPagerScaffold` (page 0 Library, page 1 Now Playing), with `browse`, `browse/{type}`, `collection/{collectionId}`, `sync`, `settings`, `settings/quota` and `settings/log` pushed on `SwipeDismissableNavHost` and dismissed back to `HOME`; a new `onboarding/first-pick` step shown once when nothing is pinned; deep links into Now Playing (a `sessionActivity` on the playback session) and into `sync` (the download notification), which makes `MainActivity` `singleTask`; a single vocabulary whose verb is **"Keep on watch"**, expressed by a sealed `CollectionSyncStatus` of eight cases (Not on watch / Waiting for Wi-Fi / Waiting for charger / Waiting for consent / Downloading n of m / Waiting for space / On watch / Needs attention) produced by a pure, JVM-testable `SyncStatusMapper` with an explicit priority order; a sealed `UiError` with at most one `UiAction` each and a pure `ErrorMapper` classifying the Jellyfin SDK's `InvalidStatusException` by status and the `IOException` family against known network state, with raw technical text confined to the sync log; a component set replacing `AuroraChip`-for-everything, in which the brand gradient fills exactly one *control container* per screen — that screen's primary action — and otherwise only paints four surfaces that are not control containers (wordmark, Quick Connect code, quota arc, seek-bar elapsed magnitude); and one storage gauge with one meaning, used of quota, everywhere. Three small additive, read-only data inputs are required for the status vocabulary: a `GROUP BY collectionId, state` count query on `CollectionTrackDao`, `SyncScheduler.observeSyncBlocker()`, and a public `NetworkAwareness.isCharging`. No other data source is introduced — no new DataStore key, no new table, no new column. Zero new runtime dependencies: neither `horologist-compose-layout` nor `horologist-media-ui` is added; `horologist-audio` and `horologist-media3-outputswitcher` are retained.
- Rationale: Owner direction, plus a code audit that traces the confusion to structural causes rather than styling. A screen that answers two questions cannot be made clear by restyling it, and one 52 dp control used for eight roles cannot be given visual rank. The redesign fixes the causes: one screen answers one question, one act has one verb, every status is a sentence rather than a glyph, every failure is a classified cause rather than a `Throwable`, one primary action per screen wears the gradient, and one gauge has one denominator. Making Now Playing a page of the start destination — rather than a leaf route — is what guarantees the music is at most one dismiss plus one swipe away from anywhere, without a persistent navigation bar a round 1.9-inch screen cannot afford. `TransformingLazyColumn` is what makes the crown scroll, and it is already on the classpath.
- Alternatives considered: restyle the existing screens without changing the information architecture (rejected — the Library-screen and navigation faults are structural, not visual); adopt `horologist-compose-layout` / `horologist-media-ui` for their Wear scaffolds and media UI (rejected — both pull Wear Material 2.5 and a second theme system that cannot follow `JellyWristTheme`); keep the four category tabs on Home and skip the `browse` chooser (deferred to OQ-1 in the specification — saves a tap at the cost of Home answering two questions); strip the gradient from every surface including the wordmark and the Quick Connect code (deferred to OQ-5 — rejected as written, because none of those four surfaces is a control container and they are where DEC-011 and DEC-012 put the identity).
- Consequences/trade-offs: `LibraryScreen`, `LibraryViewModel`, `LibraryUiState`, `LibraryTab`, `RandomSyncState`, `DownloadsScreen`, `AuroraChip`, `AuroraChipVariant`, `AuroraButton`, `SectionHeader`, `GradientProgressRing`, `StorageGauge` and `SyncLogEntry.outcomeLabel` are deleted; `AuroraSeekBar`, `GradientText`, `JellyImage`, `formatBytes` / `formatDuration`, `TrackState.statusDescriptionRes()` and the whole `ui/theme/` package are kept, with their existing JVM tests. Two behaviour changes need owner sign-off and are recorded as open questions in the specification: the `sync` screen no longer lists every downloaded track and no longer offers a per-track remove button (OQ-2 — removal is expressed by removing a collection from the watch, which is the only operation a refcounted model supports), and `UNSUPPORTED` tracks never raise "Needs attention" (OQ-3 — under DEC-004 that warning could never be cleared). `MainActivity` becomes `launchMode="singleTask"` and gains `onNewIntent`; that is the only manifest change. `:app` gains an `androidTest` source set for the one new Room query. Every string added or removed must ship in both `values/` and `values-fr/`, which are currently at parity. Three ViewModels are added (`HomeViewModel`, `BrowseViewModel`, `FirstPickViewModel`) and one is renamed (`DownloadsViewModel` → `SyncViewModel`); no ViewModel observes anything new beyond the three additive inputs.
- Requirements/components affected: REQ-006 (the refcount model is why per-track removal goes), REQ-007 (consent surfaces move to a single overlay dialog; the ladder itself is unchanged), REQ-008 (`WaitingForSpace` and the quota screen replace the bare "Budget" badge), REQ-009 (Now Playing is unchanged in content and gains a working `sessionActivity`). No requirement's *behaviour* changes. Components: `ui/nav`, `ui/screens/**`, `ui/components/**`, plus additive read-only members on `CollectionTrackDao`, `SyncScheduler` and `NetworkAwareness`.
- Compatibility or migration impact: UI layer only. No Room schema change — the new count query is an additional `@Query` over existing tables, so DEC-009's migration discipline is not engaged. No change to `applicationId`, the Room database name, encrypted preferences, DataStores, WorkManager names or tags, or the notification channel id, so DEC-012's compatibility clause is preserved in full. No change to the `:core` wire protocol or to anything the companion observes in a `STATUS_REPLY`, so a phone running the current `:mobile` build keeps working against a redesigned watch. No new runtime dependency, so no version-catalog change. The specification is written against the tree as it will exist once the pre-merge development work is merged; implementing before that merge would mean re-deriving the consent ladder that branch already implements (OQ-4).
- Supersedes: the **screen design** of DEC-011 — its screen inventory, the `AuroraChip`-centred component library, the tab-strip Library, the Downloads screen, and the `PLAYER` route. DEC-011's design system is **not** superseded: the Aurora palette, `JellyWristTypography`, `auroraGradient()` and the scrim brushes, the real Material vector icons, the localized byte units and the finished player all survive unchanged. DEC-012 is **not** superseded in any respect. The Wear Compose Material3 import convention and `SwipeDismissableNavHost` navigation are preserved as repository conventions.
- Superseded by: —
- Evidence/links: `specs/10_WATCH_UI_REDESIGN.md`; the audited sources `ui/nav/JellyNav.kt`, `ui/screens/library/LibraryScreen.kt`, `ui/screens/downloads/DownloadsScreen.kt`, `ui/screens/settings/SettingsScreen.kt`, `ui/components/AuroraChip.kt`, `ui/components/StorageGauge.kt`, `ui/components/SectionHeader.kt`; `playback/JellyPlaybackService.kt` (verified: no `sessionActivity`); `sync/KeepOnWatchCoordinator.kt` and `network/NetworkAwareness.kt` on a pre-merge development branch; `gradle/libs.versions.toml` (`wearCompose = "1.6.2"`, `horologist = "0.7.15"`); `PLAN.md` §6; DEC-011; DEC-012.

## DEC-015 — Public 0.0.1 release reset
- Date: 2026-11-20
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: First public release. The prior private history contains private LAN addresses, agent session artifacts and internal iteration, and is not approved for publication. The project is being prepared for public version 0.0.1.
- Decision: Reset the version line to 0.0.1 (versionCode 1) in `:app` and `:mobile`; adopt the MIT License in `LICENSE` with `Copyright (c) 2026 nicolasestrem`; keep `applicationId` `com.example.jellywatch` and all legacy persistence identifiers per DEC-012; publish from a single reviewed root commit in a recreated public GitHub repository; and delete the old repository history without publishing it.
- Rationale: The prior history cannot be made safe for publication by a later cleanup commit, because the old blobs remain reachable, and the owner has not approved that history for publication. Publishing from one reviewed root commit gives the public repository a clean, auditable starting point. Resetting the version line marks the first public release, while retaining the established `applicationId` and persistence identifiers preserves DEC-012's upgrade path. The MIT License gives the public release an explicit grant of reuse rights.
- Alternatives considered: Keep the prior history and add a cleanup commit (rejected — old blobs remain reachable); keep the repository private (rejected by the owner).
- Consequences/trade-offs: No in-place upgrade from prior internal versionCode-3 builds without uninstall; the 27 prior pull requests are discarded; DEC-012 upgrade continuity for installed apps is retained.
- Requirements/components affected: Release engineering, docs, repository governance; no REQ behavior change.
- Compatibility or migration impact: The version code restarts at 1, so prior internal versionCode-3 builds cannot upgrade in place and must be uninstalled first. The `applicationId` and all legacy persistence identifiers remain unchanged per DEC-012, retaining DEC-012's upgrade continuity for installed apps.
- Supersedes: none.
- Superseded by: DEC-016 — only the `applicationId`/legacy-identifier retention clause; the version reset, MIT license and single-root-commit publication remain in force.
- Evidence/links: This 0.0.1 release operation; `specs/09_PUBLIC_RELEASE_AUDIT.md`; `specs/05_BUILD_REVIEW.md`.

## DEC-016 — JellyWrist package identity cutover (supersedes DEC-012's applicationId retention)
- Date: 2026-11-20
- Status: LOCKED
- Owner/approver: nicolasestrem
- Context: The 0.0.1 release was prepared with the legacy `com.example.jellywatch` applicationId and `jellywatch_*` persistence identifiers to preserve upgrades from pre-public JellyWatch builds. The owner confirmed that the project has no users, so that upgrade-continuity rationale no longer applies, and the legacy identifiers would ship in the first public release as unnecessary pre-public artifacts.
- Decision: Set `applicationId = "com.example.jellywrist"` in both `:app` and `:mobile` (identical, as required by the Wearable Data Layer same-package/same-signature rule). Rename the app-owned persistence and scheduling identifiers to the `jellywrist` identity: Room database `jellywrist.db`; encrypted preferences `jellywrist_auth.xml`; DataStores `jellywrist_settings` and `jellywrist_queue`; WorkManager tag/names `jellywrist_sync`, `jellywrist_auto_sync`, `jellywrist_download_restart`; download notification channel `jellywrist_downloads`. Current product documentation and release notes use the new identity.
- Rationale: With no existing users, 0.0.1 can be the first public identity rather than an upgrade target for unpublished builds. Matching the applicationId to the source namespace removes a permanent legacy identifier and the associated compatibility language from a public release that never had users to preserve.
- Alternatives considered: keep `com.example.jellywatch` (rejected — no users to preserve, and it would ship a legacy identifier in the first public release); rename the source namespace too (rejected — it is already `com.example.jellywrist`; only the applicationId and persistence names needed to change).
- Consequences/trade-offs: 0.0.1 is a fresh application identity. Pre-public `com.example.jellywatch` builds are not upgraded in place and must be uninstalled; no public user data exists to migrate. The watch and phone remain interoperable because they share the new applicationId and signing certificate. The release tag and release are re-cut at the new identity.
- Requirements/components affected: application packaging, Room database name, encrypted preferences, DataStores, WorkManager identifiers, notification channel, backup rules, tests, release assets and documentation. No product requirement changes.
- Compatibility or migration impact: No migration from pre-public builds (none exist). The `com.example.jellywrist` package and `jellywrist_*` identifiers become the stable identities from 0.0.1 onward.
- Supersedes: DEC-012's applicationId retention and the legacy-identifier clause of DEC-015. The rest of DEC-012 (brand) and DEC-015 (version reset, history replacement, MIT license) remain in force.
- Superseded by: —
- Evidence/links: Owner statement "there are no users" (2026-11-20); `app/build.gradle.kts`, `mobile/build.gradle.kts`, `JellyDatabase.kt`, `TokenStore.kt`, `SettingsRepository.kt`, `QueueStore.kt`, `SyncScheduler.kt`, `JellyDownloadService.kt`, `auth_backup_rules.xml`; this 0.0.1 re-cut.
