# Execution Plan — M1 completion — Quick Connect authentication (PLAN.md task 3)

- Status: ✅ COMPLETE AND VERIFIED
- Phase: M1 completion — Quick Connect authentication (PLAN.md task 3)
- Spec version: 2.0
- Integration: work landed on a short-lived feature branch and was verified locally and on the prior internal CI; the original branch, commits and pull request are not part of the public history
- Created: 2026-08-07
- Last updated: 2026-08-08 (verification pass — all items closed with executed evidence)
- Approved: 2026-08-07 by nicolasestrem ("approve plan")

> **Historical record.** This document records the M1 Quick Connect execution phase as it ran on
> 2026-08-07 → 2026-08-08. That phase is **complete**; this is **not** a current plan and not a
> description of remaining work. References to the prior internal Git history (commit hashes,
> branch names, pull requests, CI run URLs) have been removed; any CI claim here means green on
> the prior internal CI. The document is preserved as engineering history for the 0.0.1 public
> release; for current state see `specs/01_PROJECT_CONTEXT.md`,
> `specs/03_MASTER_PRODUCTION_SPEC.md` and `specs/07_KNOWN_GAPS.md`.

## Objective

Implement the complete Quick Connect authentication flow (initiateQuickConnect → poll getQuickConnectState → authenticateWithQuickConnect), the AuthenticateByName fallback, and secure token storage in EncryptedSharedPreferences/Keystore, following DEC-002 and REQ-005. All JVM-testable surfaces developed TDD with MockWebServer per REQ-011.

## Why this phase exists

Task 2 of M1 (Jellyfin SDK dependency, server discovery, URL normalisation, `/System/Info/Public` validation) was merged on the prior internal main before this phase. Authentication is the prerequisite for every subsequent feature — library browsing, sync engine, playback — none of which can function without an access token. This phase is the smallest dependency-root step that delivers end-to-end onboarding on the watch.

## Requirements covered

| ID | Requirement | Decision status | Verification |
|---|---|---|---|
| REQ-002 | Jellyfin access exclusively via official `org.jellyfin.sdk` Kotlin SDK | [LOCKED] | Dependency catalog inspection + code review |
| REQ-005 | Auth: Quick Connect primary, AuthenticateByName fallback; token only in EncryptedSharedPreferences/Keystore, never in Room/logs | [LOCKED] | Code inspection + unit tests |
| REQ-011 | JVM-testable surfaces developed TDD; green `testDebugUnitTest` gates every milestone | [LOCKED] | CI android.yml |

## Preconditions
- Q-001 ∈ {A, B} — the next phase proceeds along the PLAN.md roadmap (option A) or prioritises sync/download proving Range resume (option B); both include this auth phase. **✓ Satisfied: Q-001 = A (2026-08-07, DEC-008).**
- Q-005 = A — `git pull` main so the checkout contains M1 task 2. **✓ Satisfied: pull executed 2026-08-07; the checkout matched the prior internal main.**
- Human approval of this execution plan. **✓ Satisfied: approved 2026-08-07 by nicolasestrem.**

## Locked constraints

All LOCKED decisions apply [VERIFIED via DECISION_LOG.md, PLAN.md §0/§1.2, AGENTS.md]:

- **DEC-001** — Official Jellyfin Kotlin SDK `org.jellyfin.sdk`; no hand-rolled HTTP client.
- **DEC-002** — Quick Connect primary auth; AuthenticateByName fallback; token in EncryptedSharedPreferences/Keystore.
- **DEC-006** — Hilt DI; certificate pinning opt-in, never global TLS bypass; app-private `filesDir`.
- **REQ-001** — Standalone Wear OS app; never assumes paired phone.
- **REQ-009** — Playback via Media3 ExoPlayer in MediaSessionService (Horologist); BT output guard.
- Version catalog (`gradle/libs.versions.toml`) is mandatory — never hardcode dependency versions.
- Documentation language: code identifiers English; KDoc/CHANGELOG in English; `/specs` files in English.

## Flexible choices

Implementation details left to engineering judgement within the locked boundaries. Document choices when made:

- Polling cadence for Quick Connect state (e.g. 2 s interval, max ~5 min timeout).
- UI copy for onboarding screens (Quick Connect code display, fallback login prompt).
- Module-internal structure (e.g. `AuthRepository` interface, `AuthViewModel`, composable screens).
- Hilt module scoping for auth dependencies (singleton vs. activity-scoped).

## Out of scope

- Sync engine (Room schema, SyncPlan, WorkManager pipeline).
- Playback (Media3 ExoPlayer, MediaSessionService).
- Downloads (DownloadManager, Range-resumable HTTP).
- Lite variant (GAP-002).
- Visual redesign or refactors of merged M1 task 2 code.
- Any work on M2, M3, or M4 milestone items.

## Open questions and blocking effect

| Question | Blocks this phase? | Answer / Effect |
|---|---|---|
| Q-001 (PROJECT_GOAL text) | ~~Yes~~ No — resolved 2026-08-07 | **A** — template artifact; authoritative goal = continue PLAN.md roadmap. DEC-008 [LOCKED]. |
| Q-002 (lite version meaning) | No | **B** — separate reduced-feature build flavor, to be specified. DEC-007 → [LOCKED]; feature set unresolved → Q-006 / GAP-007. |
| Q-003 (data-preservation constraint) | No | **C** — Room-migration discipline: every schema change ships a tested migration; no destructive fallback in release builds. DEC-009 [LOCKED], REQ-012 added. |
| Q-004 (EXPLICIT_NON_GOALS confirmation) | No | **A** — generic exclusions confirmed; PLAN.md §1.3 is the operative non-goal list. DEC-008. |
| Q-005 (git pull before implementation) | ~~Yes~~ No — resolved 2026-08-07 | **A** — pull executed; checkout matched the prior internal main. GAP-004 resolved. |
| Q-006 (lite flavor feature set) | No (blocks lite work only) | Open — which capabilities excluded, and how delivered? Not blocking this phase. |

Expected new code locations [VERIFIED via PLAN.md §11]:

```
app/src/main/java/com/example/jellywatch/
  data/jellyfin/        # SDK wrapper / Quick Connect client helpers
  data/repo/            # AuthRepository (new)
  di/                   # Hilt module for auth dependencies (new or extend existing)
  ui/screens/onboarding/ # Onboarding composable screens (new)

app/src/test/.../       # JVM unit tests (MockWebServer for Quick Connect + AuthenticateByName)
app/src/androidTest/... # Instrumented auth flow test (requires Wear emulator)
```

Expected dependency additions (version catalog): none new — `org.jellyfin.sdk:jellyfin-core` already declared in M1 task 2. `androidx.security:security-crypto` for EncryptedSharedPreferences may need adding.

Existing code base (local main in sync with the prior internal main, after M1 task 2):

- `JellyWatchApp.kt` — Hilt `@HiltAndroidApp`.
- `ui/MainActivity.kt` — Wear Compose `SwipeDismissableNavHost` (empty).
- `ui/nav/JellyNav.kt` — Navigation routes (empty).
- `di/BuildInfo.kt` — Hilt module providing build-info.
- `di/JellyfinModule.kt` — Hilt module providing SDK instances (new in M1 task 2).
- `data/jellyfin/JellyfinBackend.kt` (+ `JellyfinBackendImpl`) — SDK wrapper / ApiClient factory (new in M1 task 2).
- `data/jellyfin/UrlNormalizer.kt` — URL validation and normalisation (new in M1 task 2).
- `data/repo/ServerRepository.kt` — Server discovery + `/System/Info/Public` validation (new in M1 task 2).
- Tests: `UrlNormalizerTest.kt` (15 tests) + `ServerRepositoryTest.kt` (7 tests via MockWebServer) = **22 green JVM tests** [VERIFIED per CHANGELOG].

Quick Connect work in this phase builds directly on `JellyfinBackend`/`ApiClient` from M1 task 2.

TDD-first: write failing tests, then implement to pass.

| Step | Status | Description |
|------|--------|-------------|
| 1 | ✅ DONE | **Add security-crypto dependency** — `androidx.security:security-crypto` 1.1.0 to version catalog and `app/build.gradle.kts`. Also added `hilt-navigation-compose` 1.4.0, `lifecycle-viewmodel-compose` 2.11.0, `test-ext-junit` 1.3.0. |
| 2 | ✅ DONE | **TDD: AuthRepository contract + Quick Connect unit tests** — 20 MockWebServer tests covering: init success/null-on-404, state Pending/Approved/Expired, polling Pending→Approved, polling timeout→Expired, authWithQC success+persist, authWithQC expired, loginWithPassword success+persist, loginWithPassword 401, missing token, connection failure, session round-trip, **plus 6 hardening tests added 2026-08-08** (virtual-time budget assertions, poll cancellation, mid-poll expiry, CancellationException propagation, null-User, baseUrl pass-through). |
| 3 | ✅ DONE | **Implement Quick Connect client** — `AuthRepository` (not interface — concrete `@Singleton` class) using `JellyfinBackend` → SDK `QuickConnectApi` + `UserApi`. `initiateQuickConnect`, `quickConnectState`, `pollQuickConnect` (Flow, 2 s interval, 5 min timeout), `authenticateWithQuickConnect`. |
| 4 | ✅ DONE | **TDD: AuthenticateByName fallback tests** — Covered in step 2 (loginWithPassword success, 401→InvalidCredentials, missing token→Failed, connection failure→Failed, CancellationException propagation). |
| 5 | ✅ DONE | **Implement AuthenticateByName** — `loginWithPassword(baseUrl, username, password)` → `AuthResult` via `UserApi.authenticateUserByName`. 401 → `InvalidCredentials`. |
| 6 | ✅ DONE | **TDD: Secure token storage tests** — 5 instrumented tests (`EncryptedTokenStoreTest`, AndroidJUnit4): emptyStoreNull, save→load round-trip, overwrite, null serverId round-trip, clear. |
| 7 | ✅ DONE | **Implement TokenStore** — `interface TokenStore` + `@Singleton EncryptedTokenStore` (MasterKey AES256_GCM + EncryptedSharedPreferences AES256_SIV, file `jellywatch_auth`). Token never logged. `FakeTokenStore` for JVM tests. |
| 8 | ✅ DONE | **Implement Onboarding UI** — `OnboardingViewModel` (@HiltViewModel) with sealed `OnboardingUiState` (5 variants: ServerEntry, Validating, QuickConnect, PasswordLogin, Authenticated). `OnboardingContent` composable with `BasicTextField` (Wear M3 has no TextField). English strings. Private previews. |
| 9 | ✅ DONE | **Integration wiring** — `JellyNav.kt` with `JellyRoutes.ONBOARDING`/`LIBRARY`, switchable start destination via `hasSession`. `MainActivity` injects `authRepository`, reads `currentSession()`, passes to `JellyNav`. `LaunchedEffect` navigates to LIBRARY + pops ONBOARDING on Authenticated state. Hilt `AuthModule` binds `EncryptedTokenStore` → `TokenStore`. |
| 10 | ✅ DONE | **Manual end-to-end verification** — Executed 2026-08-08 on Wear OS emulator `Wear_OS_Large_Round` (API 37, x86_64) against a private test Jellyfin server running Jellyfin 10.11.11 (server identity redacted; see the redaction note under "End-to-end verification evidence"). All scenarios pass — see "End-to-end verification evidence" below. Required two defect fixes first (missing `INTERNET` permission, missing `androidx.test:runner`). |

## Migration and rollout sequence

Not applicable. Reason: no Room database schema exists yet (no prior schema to migrate). This phase introduces no data model changes. EncryptedSharedPreferences is created fresh on first use.

| Verification | Command / Method | Result |
|---|---|---|
| Unit tests | `.\gradlew.bat testDebugUnitTest` | ✅ **43/43 green** (16 `UrlNormalizerTest` + 20 `AuthRepositoryTest` + 7 `ServerRepositoryTest`), 0 failures / 0 errors |
| Build | `.\gradlew.bat assembleDebug` | ✅ **BUILD SUCCESSFUL**, exit code 0 — debug APK produced |
| Instrumented tests | `.\gradlew.bat connectedDebugAndroidTest` | ✅ **5/5 green** on `Wear_OS_Large_Round` (API 37) — `tests="5" failures="0" errors="0" skipped="0"` |
| CI | `.github/workflows/android.yml` | ✅ **GREEN on the prior internal CI** (Ubuntu + Temurin JDK 17 + android-37.0) |
| Manual auth flow | Wear emulator + private test Jellyfin server (identity redacted) | ✅ **PASSED** — Quick Connect and AuthenticateByName both complete end to end; see evidence table below |

## End-to-end verification evidence

Executed 2026-08-08. Emulator `Wear_OS_Large_Round` (Wear OS, API 37, x86_64, 454×454 round). Server: identity redacted — Jellyfin **10.11.11**, `StartupWizardCompleted: true`, `QuickConnect/Enabled: true`.

> **Redaction note.** This run used a private test Jellyfin server and account.
> The hostname, server name, and user name are not published: wherever this repository shows
> `jellyfin.example.com`, `example-server`, or `sample-user`, those are placeholders standing
> in for redacted values, not the inputs that were actually used. Every other value recorded
> here (versions, flags, codes, observed behaviour) is reported as observed.

| # | Scenario | Observed result |
|---|---|---|
| 1 | Fresh install → onboarding shown | Server-entry screen with labels "Jellyfin server address" / "Connect" |
| 2 | Enter the server URL (redacted) → submit | Server validated via `/System/Info/Public`; app advanced to Quick Connect |
| 3 | Quick Connect code displayed | 6-digit code rendered (`636198`) with "Approve this code in Jellyfin.", polling spinner, and "Password" fallback button |
| 4 | Approve code server-side | `POST /QuickConnect/Authorize` → `true` (HTTP 200) |
| 5 | App detects approval | Poll picked up `Authenticated`, exchanged secret for token, navigated to LIBRARY — onboarding popped from back stack |
| 6 | Restart app (force-stop → relaunch) | Went **straight to LIBRARY**, no onboarding — session restored from `EncryptedSharedPreferences` |
| 7 | Token encrypted at rest | `shared_prefs/jellywatch_auth.xml` contains only Tink-encrypted keys/values + `AesSivKey` keyset. Plaintext scan for the real server name / user name (both redacted) / password / `https` → **0 matches** |
| 8 | Clear app data | Returned to onboarding; new Quick Connect code issued (`949364`) |
| 9 | Password fallback — invalid credentials | Password masked (`PasswordVisualTransformation` confirmed); HTTP 401 surfaced as error **"Incorrect credentials."** |
| 10 | Password fallback — valid credentials | Authenticated and navigated to LIBRARY; session persisted across restart; plaintext scan again **0 matches** |

> The LIBRARY destination is currently a placeholder rendering "JellyWatch" — library browsing is M1 task 4 and out of scope for this phase. Reaching it is the correct post-authentication outcome.

## Acceptance matrix

| REQ ID | Status | Evidence | Method |
|---|---|---|---|
| REQ-002 | ✅ **SATISFIED** | All Jellyfin API calls route through `org.jellyfin.sdk` via `JellyfinBackend` → `QuickConnectApi`/`UserApi`. Grep of `app/src/main` for `OkHttpClient`, `HttpURLConnection`, `URL(`, `Retrofit`, raw sockets → no matches. | Catalog inspection (`jellyfin-core` 1.8.12) + full-tree grep + live E2E against Jellyfin 10.11.11 |
| REQ-005 | ✅ **SATISFIED** | Quick Connect primary + AuthenticateByName fallback, both exercised against a live server. Token persisted only in `EncryptedSharedPreferences` (MasterKey AES256_GCM, keys AES256_SIV, values AES256_GCM). Zero `Log`/`println` of secrets in `app/src/main`. **Plaintext scan of the prefs file in the emulator's app-private storage returned 0 matches.** | Code inspection + 20 JVM tests + 5 instrumented tests + `adb run-as` inspection of `shared_prefs` on the emulator |
| REQ-011 | ✅ **SATISFIED** | 43/43 `testDebugUnitTest` green + 5/5 instrumented green + CI green. Budget assertions are **bite-verified**: perturbing `POLL_TIMEOUT` 5→4 min fails `AuthRepositoryTest`, proving the test defends the contract rather than passing vacuously. | Gradle exit code 0 + JUnit XML + deliberate-perturbation check |

## Stop conditions

The spec-first workflow requires stopping affected work and reporting when:

- A locked requirement (DEC-001..009, REQ-001..012) conflicts with verified repository reality.
- Two authoritative sources contradict each other.
- Q-001 and Q-005 resolved (2026-08-07); no remaining product decisions block this phase. Q-006 (lite flavor) blocks lite work only.
- Required validation (`.\gradlew.bat testDebugUnitTest`, `assembleDebug`) cannot be run or meaningfully substituted.
- The phase requires scope expansion (e.g., discovering auth needs Room schema — defer, do not expand).

Do not stop independent, harmless work merely because an unrelated question remains open.

## Rollback or recovery

Revert this phase's changes. No data is at risk: no Room schema exists, EncryptedSharedPreferences is app-private and can be cleared by uninstalling the debug build, and no production deployment exists. The merged M1 task 2 code is untouched by this phase.

## Readiness gate (post-verification)

| Dimension | Score (1–3) | Rationale |
|---|---|---|
| Current reality | 3 | 9 new files, 5 modified. 43 JVM + 5 instrumented tests green. Build and CI both pass. |
| Goal and user value | 3 | Proven on a Wear OS **emulator** (`Wear_OS_Large_Round`, API 37) against a live server: Quick Connect code → approval → token persisted → LIBRARY. AuthenticateByName fallback proven, including the 401 error path. Not yet exercised on physical watch hardware. |
| Scope | 3 | All 10 steps complete, including manual E2E. Two defects found during verification were fixed inside the phase's own scope (network permission, test runner). No scope creep. |
| Decisions | 2 | DEC-001..005 and DEC-007..009 honored. **DEC-006 caveat:** `network_security_config.xml` uses `<base-config cleartextTrafficPermitted="true">`, which permits cleartext to **all** hosts, not a scoped allow-list. TLS verification itself is untouched (system trust anchors, no bypass), so this is not a TLS bypass — but it is broader than ideal and should be narrowed before release. Tracked as GAP-008. |
| Contracts | 3 | AuthRepository, TokenStore, OnboardingViewModel contracts implemented and exercised end to end on the emulator. Hilt graph resolves at runtime. |
| Verification | 3 | Build ✅, 43 unit ✅, 5 instrumented ✅, CI ✅, manual E2E ✅, encryption-at-rest inspected on the emulator's app-private storage ✅, timeout assertion bite-verified ✅. |
| Delivery | 3 | Branch pushed to the prior internal repository; green on the prior internal CI. |
| Traceability | 3 | Every REQ has executed-test evidence. Plan claims reconciled against repository reality; all inaccuracies corrected. |

**Verdict: ✅ COMPLETE AND VERIFIED** — every item in this plan is closed with direct evidence. Two shipping-blocking defects were caught by verification and fixed; the phase is genuinely done rather than merely written.

## Progress log

| Date | Actor | Entry |
|---|---|---|
| 2026-08-07 | coding agent (spec-first bootstrap) | Plan created as PROPOSED draft 0.1. No implementation code written. Readiness gate: NEEDS TARGETED DECISIONS. |
| 2026-08-07 | coding agent (spec-first second pass) | Q-001..Q-005 answered (A/B/C/A/A); main fast-forwarded to the prior internal main tip; readiness re-scored all dimensions 2; verdict READY FOR HUMAN APPROVAL; awaiting explicit plan approval. |
| 2026-08-07 | coding agent (implementation) | Short-lived feature branch. Implemented auth layer (TokenStore/EncryptedTokenStore, AuthModels, AuthRepository), Hilt `AuthModule`, ViewModel + Wear Compose onboarding screens, nav routing. 13 new JVM tests (AuthRepositoryTest) + 5 instrumented tests (EncryptedTokenStoreTest). Total: 37 unit + 5 instrumented — all green. Build `assembleDebug` passes. Subagents died on rate limit; implementation done directly. Changes recorded in the prior internal history. |
| 2026-08-07 | coding agent (post-implementation) | Committed the spec-first documentation set (AGENTS.md, `specs/00-08` and `DECISION_LOG`). Branch pushed to the prior internal origin. Execution plan updated: status → IMPLEMENTED, task completion tracked, acceptance matrix satisfied, readiness gate re-scored. Remaining: manual E2E on Wear emulator, instrumented test run, CI on the prior internal pipeline. |
| 2026-08-08 | coding agent (verification pass) | Full verification with 7 parallel subagents. Build + 43 JVM tests green; 5 instrumented tests green on `Wear_OS_Large_Round`; green on the prior internal CI; manual E2E passed against the live server (identity redacted). **Found and fixed two shipping-blocking defects** (missing `INTERNET` permission; missing `androidx.test:runner`). Hardened `AuthRepositoryTest` 14→20 with bite-verified budget assertions. Corrected several inaccurate counts in this document. Verdict: COMPLETE AND VERIFIED. |

## Surprises and discoveries

1. **`UserDto` requires `Has*Password` fields**: the SDK's generated `UserDto` has `hasPassword: Boolean`, `hasConfiguredPassword: Boolean`, and `hasConfiguredEasyPassword: Boolean` without defaults — every test fixture returning a `User` object must include all three. Omitting them yields `InvalidContentException` / `MissingFieldException` at the serialization layer, not a friendly error.
2. **Subagent rate limits**: all three parallel agents died on provider quota exhaustion before producing any output. The lead must be prepared to carry implementation alone when delegation stalls this way.
3. **🔴 Missing `INTERNET` permission (shipping-blocker, fixed 2026-08-08)**: `AndroidManifest.xml` declared no permissions at all. Every Jellyfin call — M1 task 2 server validation *and* all of task 3's auth — would fail on a real device with `SecurityException`. JVM MockWebServer tests cannot catch this because they never cross the Android permission boundary, and the code compiles and unit-tests perfectly. PLAN.md §11 had specified `INTERNET` from the start; it simply was never added. Fixed by adding `INTERNET` + `ACCESS_NETWORK_STATE`.
4. **🔴 Instrumented tests were never runnable (fixed 2026-08-08)**: `testInstrumentationRunner` pointed at `androidx.test.runner.AndroidJUnitRunner`, but only `androidx.test.ext:junit` was declared — which does **not** pull in `androidx.test:runner` transitively. The test APK therefore shipped without the runner class and `connectedDebugAndroidTest` died with `ClassNotFoundException` before discovering a single test. The 5 `EncryptedTokenStoreTest` tests had been written but had never once executed. Fixed by adding `androidx.test:runner` 1.7.0.
5. **Cleartext HTTP must be allowed for LAN servers**: Android 9+ blocks cleartext, so `http://…:8096` self-hosted servers (explicitly supported by `UrlNormalizer` per PLAN.md §3.1) are unreachable without a network security config. Added `network_security_config.xml`. See GAP-008 — the current allowance is global rather than host-scoped.
6. **`security-crypto` 1.1.0 deprecates its own API**: `MasterKey` and `EncryptedSharedPreferences` are both marked `@Deprecated` (9 build warnings). REQ-005/DEC-002 mandate exactly this API, so it stays for now, but a successor must be chosen before release. Tracked as GAP-009.
7. **`hiltViewModel` moved packages**: now `androidx.hilt.lifecycle.viewmodel.compose`; `JellyNav.kt:52` uses the deprecated location. Cosmetic, compiles fine.
8. **Wear emulator IME does not commit into `BasicTextField`**: typing through the Wear full-screen keyboard and confirming leaves the field empty. Injecting keys directly (IME dismissed) works perfectly, which proves the composable's `value`/`onValueChange` wiring is correct — this is an emulator input-stack quirk, not an app defect. Worth re-checking on physical hardware, since real-watch text entry would be affected if it reproduces.

## Decisions made during execution

- AuthRepository uses `response.content` (typed deserialization) rather than inspecting raw response bodies. This is deliberate: it aligns with how `ServerRepository` already uses `SystemApi(...).getPublicSystemInfo().content`, and the SDK's `InvalidStatusException.status` field gives reliable access to HTTP codes without string-matching on bodies.
- The onboarding ViewModel uses `StateFlow` + sealed `OnboardingUiState`, not a mutable `var state: OnboardingUiState`. The sealed approach makes illegal transitions (e.g. displaying a Quick Connect code while showing a password error) unrepresentable — worth the extra `when` branches.
- `SurfaceContainer` and `PasswordVisualTransformation` compiled without complaints — they're available in the Wear Compose Material3 1.6.2 + foundation surface despite not being prominently documented for Wear.

## End-of-pass report

- **Build**: `assembleDebug` BUILD SUCCESSFUL, exit code 0 ✅
- **Unit tests**: **43/43 green** — 16 `UrlNormalizerTest` + 20 `AuthRepositoryTest` + 7 `ServerRepositoryTest`, 0 failures / 0 errors ✅
- **Instrumented tests**: **5/5 green** on `Wear_OS_Large_Round` (API 37) ✅
- **CI**: green on the prior internal CI ✅
- **Manual E2E**: Quick Connect + AuthenticateByName + persistence + clear-data, all passed against the live server (identity redacted, Jellyfin 10.11.11) ✅
- **Files**: 9 new, 5 modified — *not* the "14 new / 3 modified" and "10 new / 3 modified" previously stated here, both of which were wrong.
- **Defects found by verification**: 2 shipping-blockers (missing `INTERNET` permission; missing `androidx.test:runner`), both fixed. Neither was detectable from JVM tests alone.
- **Contract**: All REQs satisfied with *executed* evidence. Hilt graph resolves at runtime on device.
- **Carried forward**: GAP-008 (global cleartext allowance to narrow before release), GAP-009 (`security-crypto` API deprecated upstream).

## Phase completion status

### ✅ DONE — implemented and verified

| Artifact | Details |
|---|---|
| `gradle/libs.versions.toml` | `securityCrypto` 1.1.0, `hiltNavigationCompose` 1.4.0, `androidxTestJunit` 1.3.0, `lifecycle` 2.11.0, **`androidxTestRunner` 1.7.0 (added 2026-08-08)** |
| `app/build.gradle.kts` | 5 test/impl dependency entries, all via `libs.*` accessors — zero hardcoded versions |
| `AndroidManifest.xml` | **`INTERNET` + `ACCESS_NETWORK_STATE` permissions and `networkSecurityConfig` (added 2026-08-08)** |
| `res/xml/network_security_config.xml` | **New 2026-08-08** — permits cleartext for LAN Jellyfin servers; TLS verification untouched. See GAP-008. |
| `data/auth/AuthModels.kt` | `AuthSession`, `QuickConnectHandle`, `QuickConnectState` (Pending/Approved/Expired), `AuthResult` (Success/InvalidCredentials/QuickConnectExpired/Failed) |
| `data/auth/TokenStore.kt` | `interface TokenStore` + `@Singleton EncryptedTokenStore` (MasterKey AES256_GCM, keys AES256_SIV, values AES256_GCM, file `jellywatch_auth`). Encryption at rest confirmed on the emulator. |
| `data/repo/AuthRepository.kt` | `@Singleton AuthRepository`: initiateQuickConnect, quickConnectState, pollQuickConnect (Flow, 2 s / 5 min — both now assertion-pinned), authenticateWithQuickConnect, loginWithPassword, currentSession, signOut |
| `di/AuthModule.kt` | `@Binds` `EncryptedTokenStore` → `TokenStore` in `SingletonComponent` |
| `ui/screens/onboarding/OnboardingViewModel.kt` | `@HiltViewModel`, sealed `OnboardingUiState` (5 variants), `StateFlow`, cancels `quickConnectJob` on path switch |
| `ui/screens/onboarding/OnboardingScreens.kt` | Stateless `OnboardingContent`, `BasicTextField`, English strings — rendering and error states confirmed on the emulator |
| `ui/nav/JellyNav.kt` | ONBOARDING/LIBRARY routes, `hasSession` start switch, `LaunchedEffect` nav — back-stack pop confirmed in E2E |
| `ui/MainActivity.kt` | Injects `authRepository`, reads `currentSession()`, passes `hasSession` |
| `AuthRepositoryTest.kt` | **20 JVM tests** (14 original + 6 hardening). Budget assertions bite-verified. |
| `EncryptedTokenStoreTest.kt` | 5 instrumented tests — **now actually executing** and green |
| `CHANGELOG.md` | M1 task 3 entries |

### ✅ RESOLVED — every former re-check item is closed

| Item | Outcome |
|---|---|
| **Manual E2E (step 10)** | ✅ **PASSED** — 10 scenarios against the live server (identity redacted); see "End-to-end verification evidence". Required the `INTERNET` permission fix first. |
| **Instrumented tests** | ✅ **5/5 green** — but only after fixing the missing `androidx.test:runner`; they had never executed before. |
| **CI green** | ✅ Green on the prior internal CI. |
| **`OnboardingScreens.kt` on CI** | ✅ Compiled clean locally **and** on CI (Ubuntu/JDK 17). `surfaceContainer` and `PasswordVisualTransformation` are fine; no fix needed. |
| **Plan factual accuracy** | ✅ Corrected: test counts were 15/13 (actually 16/14, now 16/20), the "2 scaffold tests" never existed, and file counts were wrong in two places (true: 9 new / 5 modified). |

### 📋 REMAINING — future phases (out of this phase scope)

| Milestone | Tasks | PLAN.md ref |
|---|---|---|
| **M1 task 4** | Jellyfin browsing — GET `/Items`, pagination, library views (Playlists/Albums/Artists) | PLAN.md §8 step 4 |
| **M2** | Room schema (Server, Account, Collection, Track, CollectionTrack), sync engine, Media3 DownloadManager, pinning collections | PLAN.md §7 + §8 steps 5–8 |
| **M3** | ExoPlayer + MediaSessionService, player screen, BT output, crown=volume | PLAN.md §8 step 9 |
| **M4** | WorkManager (charge+Wi-Fi), bidirectional diff, purge, quota/gauges | PLAN.md §8 steps 10–11 |
| **M5** | Tile, Ongoing Activity, played-status, codec detection, i18n FR/EN, ambient | PLAN.md §8 steps 12–13 |
| **M6** | Instrumented tests, battery/storage QA, signing, distribution | PLAN.md §8 step 14 |

### 🔓 OPEN — product questions (non-blocking for this phase)

| ID | Question | Status |
|---|---|---|
| Q-006 / GAP-007 | Lite flavor: which capabilities excluded, and how delivered? | Open — blocks lite work only |
| GAP-005 / PLAN.md §12 | Final app name/icon, default sync periodicity, pin limits, distribution (sideload vs Play Store), auto-download new albums for followed artists? | Open — owner decisions pending |
| **GAP-008** (new) | `network_security_config.xml` permits cleartext to **all** hosts via `base-config`, not a scoped allow-list. Narrow before release (runtime `domain-config`, or debug-variant-only). TLS verification is unaffected, so DEC-006 is not violated. | Open — pre-release hardening |
| **GAP-009** (new) | `androidx.security:security-crypto` 1.1.0 deprecates `MasterKey` and `EncryptedSharedPreferences` (9 build warnings), yet REQ-005/DEC-002 mandate exactly that API. Choose a successor before release. | Open — needs decision |
| **GAP-010** (new) | Wear emulator's full-screen IME does not commit text into `BasicTextField`. Proven to be an emulator quirk (direct key injection works), but must be re-checked on physical watch hardware — if it reproduces, onboarding text entry is unusable for real users. | Open — verify on device |
