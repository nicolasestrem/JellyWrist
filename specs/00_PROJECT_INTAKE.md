# Project Intake

## Metadata
- Record status: historical intake record (2026-08-07) of the pre-release repository; current verified state lives in `specs/01_PROJECT_CONTEXT.md`. The pre-release history and bootstrap documents named below are not part of the published tree.
- Project: JellyWatch — standalone Wear OS app for offline Jellyfin music sync and Bluetooth playback
- Repository: `nicolasestrem/JellyWrist` on GitHub (remote)
- Date: 2026-08-07
- Actor: coding agent (spec-first bootstrap run)
- Selected mode: **EXISTING**
- Mode evidence: Repository contains project-specific Kotlin/Gradle implementation (Hilt DI graph, Wear Compose navigation host, Jellyfin SDK integration merged in the pre-release history), a unified architecture plan (PLAN.md), settled design decisions, and merged milestone changes — not empty, boilerplate, or documentation-only.
- Requested goal: **[CONTRADICTORY]** "Add resumable imports without breaking the current upload and history flows." — see Findings > Contradictory.
- **Goal resolution (2026-08-07):** Resolved by Q-001=A → DEC-008. The PROJECT_GOAL text is a template artifact (the intake workflow's §14 example). Authoritative goal = continue PLAN.md roadmap (next task: Quick Connect auth, task 3).

## Repository state at intake
- Branch: `main`
- Commit: pre-release `main` tip at intake (SHA omitted; that history is not published)
- Working-tree status: Clean except two untracked root-level docs: `SPEC_FIRST_AGENT_WORKFLOW.md`, `SPEC_WORKFLOW_MANUAL.md`
- Behind remote: local main was **1 commit behind** the reviewed remote tip of the pre-release history (the milestone-2 change was already merged; no open pull requests at intake) [VERIFIED: `git log` and the merged pull request at intake].
- Local branches (not touched during bootstrap): several local-only branches existed in the pre-release checkout; their names are omitted and none is part of the published tree [VERIFIED: `git branch` listing at intake].

### Repository state update (2026-08-07, post Q-005=A)
- The approved pull (Q-005=A) was executed during intake; local main then matched the reviewed remote tip, and the historical commits are not part of the published tree [VERIFIED: git log at intake].
- M1 task 2 code present locally: `di/JellyfinModule.kt`, `data/jellyfin/JellyfinBackend.kt` (+Impl), `data/jellyfin/UrlNormalizer.kt`, `data/repo/ServerRepository.kt`; 22 JVM tests green (UrlNormalizerTest 15 + ServerRepositoryTest 7) [VERIFIED: CHANGELOG.md, local source tree].
- Working tree: modified CHANGELOG.md/CLAUDE.md (bootstrap doc edits, stash-preserved and re-applied), untracked AGENTS.md + `specs/` + workflow docs.
- Inspection limitations (updated 2026-08-07): M1 task 2 code is now present locally after the approved pull, so the earlier "local checkout lacks M1 code" limitation no longer applies. Remaining limitations: no physical device or emulator was available for runtime verification, and no Gradle builds were run (this pass is docs-only).

## Available material
- Repository evidence: `PLAN.md` ("unified plan", 2026-08-07 — single product/architecture reference), `CHANGELOG.md` (Keep a Changelog format), `CLAUDE.md` (English, agent-specific guidance with settled decisions), `README.md`, `.github/workflows/android.yml` (CI), `gradle/libs.versions.toml` (version catalog), `app/build.gradle.kts` (module config).
- Product material: `PLAN.md` §1 vision/scope, §3 Jellyfin integration, §4 sync engine, §5 playback, §6 UI flow, §7 Room model, §8 milestones, §9 risks, §10 testing, §12 open questions. The superseded "Plan A" draft is not part of the published tree.
- Architecture/operations material: PLAN.md §2 high-level architecture, §4 sync pipeline design, §7 Room schema. No deployed infrastructure, no Docker/Compose, no cloud services.
- Tests/CI: `.github/workflows/android.yml` runs `./gradlew assembleDebug testDebugUnitTest` on push to main and PRs. Agent workflows (`claude.yml`, `claude-code-review.yml`, `opencode.yml`) existed at intake; they were removed before publication. Merged M1 task 2 includes MockWebServer unit tests (merged in the pre-release history, not in the local checkout at intake).
- Additional supplied material: Human chat statement: "Another change in the plan; the app will be tested in a lite version on a Google Pixel Watch too." (2026-08-07, this run).
- Bootstrap documents (not published): `SPEC_FIRST_AGENT_WORKFLOW.md` and `SPEC_WORKFLOW_MANUAL.md` were placed at the repository root for the intake run, and the agent contract was `CLAUDE.md`. All three are absent from the published tree; the contract is now `AGENTS.md`, with `docs/claude-code-reference.md` preserving tool-specific guidance.

## Auto-extracted signals
- Languages/frameworks: Kotlin 2.4.10, Jetpack Compose for Wear OS (wear-compose-material3/foundation/navigation 1.6.2, compose-bom 2026.06.01) [VERIFIED: `gradle/libs.versions.toml`].
- Package managers/runtime versions: Gradle with AGP 9.3.1, KSP (not kapt), Hilt 2.60.1, JVM target 17 [VERIFIED: `gradle/libs.versions.toml`, `app/build.gradle.kts`].
- Entry points: `JellyWatchApp.kt` (Application, Hilt entry), `ui/MainActivity.kt` (single activity, Wear OS) [VERIFIED: source tree at intake].
- Database/data layer: None yet in the checkout at intake. PLAN.md §7 specifies Room entities (Server, Account, Collection, Track, CollectionTrack, SyncState) — not implemented [VERIFIED: app source tree scan at intake].
- Authentication/authorization: Not implemented yet. PLAN.md §3 + settled decisions specify Quick Connect primary, AuthenticateByName fallback, token in EncryptedSharedPreferences/Keystore (DEC-002).
- Storage/background work: Not implemented yet. PLAN.md §4 specifies WorkManager + foreground `dataSync` service (DEC-005), Media3 DownloadManager, app-private `filesDir` (DEC-006).
- Deployment/infrastructure: No deployment. Single-module `app/` build targeting Wear OS devices. CI via GitHub Actions android.yml [VERIFIED: `.github/workflows/android.yml`].
- Test and validation commands: `.\gradlew.bat assembleDebug`, `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat lint`, `.\gradlew.bat connectedDebugAndroidTest` (needs Wear OS emulator), `.\gradlew.bat installDebug` [VERIFIED: CLAUDE.md "Commands" section, consistent with CI android.yml].

## Findings

### Verified
- Project is a standalone Wear OS app (no phone companion) syncing music from a Jellyfin server for offline Bluetooth playback [VERIFIED: PLAN.md §1.1, README.md, CLAUDE.md].
- M0 scaffold existed in the checkout at intake: Hilt DI graph, empty SwipeDismissable nav host, 4 Kotlin source files [VERIFIED: source tree].
- M1 task 2 (Jellyfin SDK dep, server discovery + `/System/Info/Public` validation) merged in the pre-release history [VERIFIED: git log and the merged pull request at intake].
- Version catalog (`gradle/libs.versions.toml`) is the mandatory version-management mechanism — hardcoding versions is forbidden [VERIFIED: CLAUDE.md "Conventions"].
- Documentation convention: PLAN.md, CHANGELOG.md, KDoc written in English; code identifiers in English [VERIFIED: CLAUDE.md "Conventions"].
- compileSdk 37 requires CI to provision `platforms;android-37.0` via absolute-path sdkmanager — a known Android SDK gotcha [VERIFIED: CLAUDE.md].
- minSdk 34 (Wear OS 5+), targetSdk 36, single module `app/`, namespace `com.example.jellywatch`, versionName 0.1.0 [VERIFIED: `app/build.gradle.kts`].

### Missing
- No Room database, no Media3, no WorkManager, no download or playback code in the checkout at intake [MISSING: source tree scan at intake].
- No production data exists — no Room DB, no user accounts, no synced tracks [MISSING: PLAN.md confirms design-only at M0/M1 stage].
- No `proguard`/R8 rules, no crash reporting, no analytics instrumentation [MISSING: tree scan].

### Ambiguous (both resolved 2026-08-07)
- **[RESOLVED]** Meaning of "lite version on a Google Pixel Watch" — settled by Q-002=B: a separate reduced-feature build flavor (DEC-007 LOCKED). GAP-002 closed; the remaining unknown is the feature set/mechanism, tracked as GAP-007 / Q-006.
- **[RESOLVED]** "Existing production data must remain readable" in KNOWN_CONSTRAINTS — settled by Q-003=C: it means Room-migration discipline (DEC-009 / REQ-012). GAP-003 closed.

### Contradictory (resolved)
- **[RESOLVED 2026-08-07]** PROJECT_GOAL text was verbatim identical to the SPEC_FIRST_AGENT_WORKFLOW.md §14 example; confirmed as template artifact by Q-001=A → DEC-008. Authoritative goal = continue PLAN.md roadmap. Original finding preserved for history: *The repository (JellyWatch) has no import flows, no upload flows, no history flows, no billing, no admin tools, and no queue — it is a Wear OS Jellyfin offline-music-sync app at early milestone.*

### Outdated
- The earlier "Plan A" draft (not part of the published tree) is superseded by PLAN.md ("unified plan", 2026-08-07) [OUTDATED: PLAN.md title/abstract confirms replacement].
- The checkout at intake was one commit behind the reviewed remote tip and therefore did not yet include the milestone-2 change (tracked as GAP-004; closed by the approved pull) [OUTDATED: local checkout state at intake, not outdated content].

### Unverified
- Assumption that a Google Pixel Watch (Wear OS 5+, minSdk 34 satisfied) can run the existing app unmodified without device-specific adaptations [UNVERIFIED: no device available — tracked as ASM-002].
- PLAN.md §12 open product questions (final app name/icon, default sync periodicity, etc.) remain unanswered [UNVERIFIED: acknowledged in PLAN.md itself].

## Human decision queue

| ID | Question | Options | Consequence | Blocks | Answer |
|---|---|---|---|---|---|
| Q-001 | The stated PROJECT_GOAL matches the intake workflow's §14 example verbatim, and the repo has no import/upload/history flows. What is the real goal? | (A) Template artifact — continue the PLAN.md roadmap (next task: Quick Connect auth, task 3); (B) Emphasis on resumable *downloads/sync* — prioritize the sync-engine tasks proving Range resume (tasks per PLAN.md §8); (C) A genuinely new feature — please describe | A/B pick the next phase; C requires new strategy | Strategy + phase approval | **A** (2026-08-07) → DEC-008; goal = continue PLAN.md roadmap |
| Q-002 | What does "lite version on a Google Pixel Watch" mean? | (A) Just a physical test device for the normal debug build (no product change); (B) A separate reduced-feature build flavor ("lite") to be specified; (C) Footprint/performance constraints (storage, battery) to encode as requirements | Only lite-related work | Lite-related work only | **B** (2026-08-07) → DEC-007 LOCKED; lite = separate reduced-feature build flavor; feature set TBD → Q-006 / GAP-007 |
| Q-003 | "Existing production data must remain readable": | (A) Template artifact, ignore; (B) = the client must never mutate/damage Jellyfin server-side data (read-only + optional playback reports only); (C) = future Room schema changes must always ship migrations (no destructive fallback) | B/C become REQs | Nothing now | **C** (2026-08-07) → DEC-009 / REQ-012; Room migration discipline enforced |
| Q-004 | Confirm EXPLICIT_NON_GOALS (billing, admin tools, queue replacement) are generic exclusions and PLAN.md §1.3 is the operative non-goal list | (A) Yes; (B) No — amend | Non-goal scope clarity | Nothing now | **A** (2026-08-07) → DEC-008; generic exclusions confirmed |
| Q-005 | The milestone-2 change (M1 task 2) was merged in the remote history but this checkout was 1 behind. OK to `git pull` main before the first implementation phase? | (A) Yes; (B) No — explain | Implementation start readiness | Implementation start only | **A** (2026-08-07) → executed in the pre-release checkout; the historical commits are not published |
| Q-006 | Which capabilities are excluded from the lite flavor, and how is it delivered? | (A) Same features, reduced footprint (minify/strip, smaller caches); (B) Reduced feature set — e.g. pin+play only, no playback reporting / no artist batching — please confirm the cut list; (C) Other — describe | Lite flavor specification | Lite work only | *Open* |

## Working assumptions

| ID | Assumption | Risk if false | Retirement condition |
|---|---|---|---|
| ASM-001 | ~~PLAN.md (unified plan) is the authoritative roadmap~~ **RETIRED 2026-08-07** — confirmed by Q-001=A → DEC-008 | — | Retired (confirmed) |
| ASM-002 | A Google Pixel Watch (Wear OS 5+, minSdk 34 satisfied) can run the app unmodified; testing on it requires no code change. Now also scoped to the lite flavor (DEC-007) | Device-specific adaptation phase needed | `installDebug` on a physical Pixel Watch |

## Recommended next action

**Approve `specs/04_EXECUTION_PLAN.md`** to start Phase 1 (M1 completion — Quick Connect authentication, PLAN.md task 3). The readiness gate scores 2 on every dimension with verdict `READY FOR HUMAN APPROVAL`; the only remaining precondition is the owner's explicit approval of the plan.

Status of the original batch (all answered 2026-08-07): Q-001=A, Q-002=B, Q-003=C, Q-004=A, Q-005=A (pull executed).

Deferred, not blocking Phase 1:
- **Q-006** — define the lite flavor's cut list and delivery mechanism before any lite-variant work begins (GAP-007).
- **GAP-005** — PLAN.md §12 product questions (app name/icon, default sync periodicity, pin limits).
