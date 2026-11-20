# Strategic Plan

- Status: APPROVED
- Owner: nicolasestrem
- Last reviewed: 2026-08-07

> **Approval basis:** Human answers Q-001=A (template artifact — authoritative goal = PLAN.md roadmap) and Q-004=A (PLAN.md §1.3 + billing/admin/queue are operative non-goals), 2026-08-07; see DEC-008 [LOCKED]. Q-002=B settled lite as a separate reduced-feature build flavor (DEC-007 LOCKED).

---

## Objective

Continue the PLAN.md roadmap toward a v1 Wear OS standalone app that syncs music from a Jellyfin server and plays it fully offline over Bluetooth. The next concrete phase is completing M1 (server discovery + Quick Connect auth) and progressing through the download/sync/playback milestones as defined in PLAN.md §8.

The PROJECT_GOAL supplied in the run configuration ("Add resumable imports without breaking the current upload and history flows") was **verbatim identical** to the intake workflow's §14 example invocation and described features that do not exist in this repository. **Resolved 2026-08-07 by Q-001=A:** the text is a template artifact; the authoritative goal is continuing the PLAN.md roadmap (DEC-008 [LOCKED]). Recorded here for traceability.

## Problem and user value

**Problem:** Music playback on Wear OS watches currently requires network connectivity or a paired phone. There is no standalone option to carry an entire music library on the wrist and listen offline during runs, gym sessions, or commutes via Bluetooth headphones.

**User value:** A single-device experience — the user pins playlists, albums, or artists on their watch; the app downloads music over Wi-Fi while charging; playback is entirely offline, with no phone, no network, and no streaming required. Quick Connect authentication avoids typing passwords on a tiny screen.

## Current state versus desired state

| Dimension | Current state | Desired state (v1) |
|---|---|---|
| Milestone | M0 (scaffold) locally; M1 task 2 was merged on the prior internal main before the 0.0.1 release | M1 complete → M6 (tagged release) |
| Server connection | Discovery + URL validation only (task 2) | Full auth flow + library browsing |
| Downloads | None | Range-resumable via Media3 DownloadManager |
| Offline playback | None | Media3 ExoPlayer in MediaSessionService, BT-only output |
| Sync engine | None | WorkManager-driven, charging + Wi-Fi, bidirectional convergence |
| Data layer | No Room DB | Room schema: Server, Account, Collection, Track, CollectionTrack, PlaybackReport [PLANNED per PLAN.md §7] |
| Users | None (no production deployment) | Single-user side-loaded debug builds |

## Scope

### In scope

- PLAN.md M1–M6 direction: Quick Connect auth, library browsing, download/sync engine, offline playback, automatic sync, settings/polish, and release hardening.
- Wear OS 5+ standalone app (minSdk 34, targetSdk 36) — the watch never *requires* a paired phone. An optional `:mobile` companion app assists provisioning and remote management via the Wearable Data Layer (DEC-013).
- Jellyfin server as the sole media source, accessed via the official `org.jellyfin.sdk` Kotlin SDK.
- Download of original audio files via `GET /Items/{id}/File` with HTTP Range resume (DEC-003).
- Offline playback via Media3 ExoPlayer + Horologist backend.
- Storage budget management on 8–32 GB shared watch storage.
- Certificate pinning opt-in for self-hosted servers.
- TDD for JVM-testable surfaces (REQ-011).

### Out of scope

- **PLAN.md §1.3 "Out of scope (v1)":** live streaming, ~~phone companion app~~ (lifted by DEC-013 — optional `:mobile` companion now in scope), server-side transcoding, podcasts/audiobooks/videos, synced lyrics, multi-server/multi-account simultaneous use, playback state reporting (deferred to M5+).
- **EXPLICIT_NON_GOALS** (billing, admin tools, queue replacement): these features never existed here; confirmed 2026-08-07 by Q-004=A as generic exclusions, with PLAN.md §1.3 the operative non-goal list (DEC-008; GAP-006 closed).
- **No visual redesign** — current Compose M3 Wear OS surface preserved.
- **Lite variant** — a separate reduced-feature build flavor is locked (DEC-007, Q-002=B) but its cut list and delivery mechanism are undefined (GAP-007 / Q-006). No lite-variant work begins until Q-006 is answered; it is out of scope for the current phase.

## Decisions

### Locked

| ID | Decision | Status | Rationale |
|---|---|---|---|
| DEC-001 | Official Jellyfin Kotlin SDK `org.jellyfin.sdk` (OkHttp 5 backend); no hand-rolled client | [LOCKED] | Lightweight dependencies, typed, maintained [VERIFIED: PLAN.md §0, §1.2] |
| DEC-002 | Quick Connect auth first; `AuthenticateByName` fallback; token in EncryptedSharedPreferences/Keystore | [LOCKED] | No password entry on watch screen [VERIFIED: PLAN.md §1.2, §3.2] |
| DEC-003 | Download via `GET /Items/{id}/File` (original file, `Accept-Ranges: bytes`, Range-resumable); `/Audio/{id}/universal` and `/Items/{id}/Download` rejected | [LOCKED] | Bit-for-bit original, HTTP 206, no extra permission [VERIFIED: PLAN.md §0, §3.3] |
| DEC-004 | No transcoding in v1; unsupported codecs (ALAC, WMA…) detected at diff time from codec metadata, flagged not failed | [LOCKED] | Fidelity + Range resume; transcoding deferred to v2 if storage pressure measured [VERIFIED: PLAN.md §0, §5] |
| DEC-005 | Hybrid WorkManager + foreground `dataSync` service; FGS 6h cap acknowledged; long syncs re-armed and resumed via Range; UI shows "paused, will resume" | [LOCKED] | Android 15+ cap is real; WorkManager re-arm + Range resume is the mitigation [VERIFIED: PLAN.md §0, §4.3] |
| DEC-006 | Hilt DI; Media3 + Horologist for download/playback; cert pinning opt-in, never global TLS bypass; app-private `filesDir`, no storage permissions | [LOCKED] | Standard stack; secure defaults [VERIFIED: PLAN.md §1.2, §2] |

### Flexible

| ID | Decision | Status | Rationale |
|---|---|---|---|
| REQ-008 | Storage budget planner — keeps usage within user-visible budget on 8–32 GB; capacity check precedes enqueue; implementation detail may vary | [FLEXIBLE] | Critical on shared watch storage; exact threshold/budget defaults are engineering choices [VERIFIED: PLAN.md §4.4] |

### Assumptions

| ID | Assumption | Risk if false | Expiry |
|---|---|---|---|
| ASM-001 | PLAN.md (unified plan) is the authoritative roadmap and the "plan" referenced by the human's "another change in the plan" | Strategy misdirected toward wrong milestones | Q-001 answered |
| ASM-002 | A Google Pixel Watch (Wear OS 5+, minSdk 34 satisfied) can run the existing app unmodified; "testing on Pixel Watch" alone requires no code change | Device-specific compatibility phase needed | `installDebug` on physical device |

### Open questions

| ID | Question | Blocks | Consequence if unanswered |
|---|---|---|---|
| Q-001 | What is the real goal? (A) Continue PLAN.md roadmap; (B) Prioritize resumable sync/downloads; (C) New feature — describe | Strategy + phase approval | Cannot approve strategy or start implementation |
| Q-002 | What does "lite version on Google Pixel Watch" mean? (A) Test device only; (B) Build flavor; (C) Footprint constraints | Lite-variant work only | DEC-007 remains PROPOSED |
| Q-003 | "Existing production data must remain readable": (A) Template artifact; (B) Client must never mutate Jellyfin server data; (C) Room migration discipline | Nothing now | B/C become requirements later |
| Q-004 | Confirm EXPLICIT_NON_GOALS are generic and PLAN.md §1.3 is operative: (A) Yes; (B) No — amend | Nothing now | Non-goal list ambiguity persists |
| Q-005 | OK to `git pull` main before first implementation phase? | Implementation start only | Work must target current local main (M0 only) |

### Deferred

| Item | Reason |
|---|---|
| REQ-010 (PlaybackReport / offline event replay to server) | M5+ nice-to-have; app functions fully without it |
| Server-side transcoding | v2 only, if storage pressure measured |
| Playback state reporting to server | M5+ nice-to-have per PLAN.md §3.4 |
| App name, icon, default sync periodicity, distribution channel | PLAN.md §12 open questions — not blocking |

## Architecture direction and trade-offs

- **Standalone Wear OS app** — no phone dependency. Sync depends on watch Wi-Fi (DEC-001, REQ-001).
- **SDK-first** — all Jellyfin interaction through `org.jellyfin.sdk`. No hand-rolled HTTP for API calls (DEC-001).
- **Download-first, no streaming** — all playback is from local files. Simplifies media pipeline; trades latency for reliability.
- **WorkManager + FGS hybrid** — automatic sync under constraints (charging + Wi-Fi); manual sync via explicit network acquisition (Horologist). Acknowledges the Android 15+ 6h FGS cap and designs for it (DEC-005).
- **Storage-conscious** — quota + budget planner + artist-level batching on 8–32 GB shared storage. Files in app-private `filesDir` (no permissions, auto-cleanup on uninstall).
- **Room as local source of truth** — server is canonical for metadata; Room is canonical for local state and download status.
- **TDD for pure logic** — SyncPlan.diff, BudgetPlanner.plan, DTOs, repositories are JVM-testable and developed TDD (REQ-011).

## Milestones

Milestones follow PLAN.md §8 "Milestones (high-level phases)". Each is an independently reviewable outcome:

| Milestone | Content | Exit criterion |
|---|---|---|
| **M0 — Scaffold** | Gradle Wear OS project, CI green, Hilt, empty Compose navigation | CI green, debug APK installable on emulator |
| **M1 — Connection** | Server discovery, Quick Connect + fallback auth, library browse (read-only) | Browse real playlists on the watch |
| **M2 — Download** | Room schema, collection pinning, Media3 download engine, Downloads screen | A pinned playlist fully downloads to the watch |
| **M3 — Playback** | ExoPlayer + MediaSessionService (Horologist), player screen, BT-only output | Complete offline listening, watch in airplane mode equivalent |
| **M4 — Auto-sync** | WorkManager (charge + Wi-Fi), bidirectional diff, orphan purge, quota/gauges | Nightly sync reflects server changes without user intervention |
| **M5 — Polish** | Tile, Ongoing Activity, playback state reporting, unsupported-codec detection, i18n FR/EN, ambient mode | Daily-driver quality |
| **M6 — Quality & release** | Instrumented tests, battery/storage QA, signing, distribution | v1.0 tagged, installable |

## Compatibility, migration, or rollout direction

- **Current target:** single-user side-loaded debug builds via `.\gradlew.bat installDebug`. No production users, no Play Store listing, no signed release builds yet.
- **No migration burden** — no Room DB exists, no production data exists, no previous releases to migrate from.
- **Device target:** Wear OS 5+ (minSdk 34). Emulator (round, API 34/36) for development; physical device for validation — including a Google Pixel Watch running the lite flavor (DEC-007), pending the Q-006 cut list.
- **Future rollout** (deferred): sideload vs. Play Store distribution is an open question (PLAN.md §12).

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Watch Wi-Fi instability / slowness | Interrupted syncs | Range-resumable downloads (DEC-003), default charging+Wi-Fi constraint, explicit network acquisition via Horologist |
| Large original files (FLAC) | Storage saturation | Quota + pre-pin size estimate + warnings; artist batching with `PENDING_BUDGET`; transcoding deferred to v2 |
| **FGS 6h cap (Android 15+)** | Long download interrupted | WorkManager re-arm; DownloadManager resumes via Range; UI shows "paused, will resume" (DEC-005) |
| Undecodable codecs (ALAC, WMA…) | Silent tracks | Detection at diff time via codec metadata, flagged as unsupported not failed (DEC-004) |
| Battery drain during sync | Poor experience | WorkManager charging constraint; battery warning on manual sync |
| Self-signed certificates | Connection failure for self-hosters | Opt-in certificate pinning, never global TLS bypass (DEC-006) |
| Doze / background restrictions on Wear | Sync never fires | WorkManager + charging (restrictions lifted during charge) |

[VERIFIED: PLAN.md §9]

## Success criteria

Success is measured by observable outcomes, not effort:

1. **Pinned collection fully synced:** A playlist or album pinned on the watch downloads all tracks completely (verified by file count and total size matching server metadata).
2. **Offline playback in airplane-mode-equivalent:** After sync, enabling airplane mode (or equivalent network isolation) and playing the synced collection produces continuous audio over Bluetooth headphones with no gaps or errors.
3. **Resume after kill:** A download interrupted mid-track (process death or FGS cap) resumes from the correct byte offset on next sync cycle, completing without corruption.
4. **CI green gate:** `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat assembleDebug` pass on every commit; CI `android.yml` is green.
5. **Bidirectional convergence:** Adding a track server-side to a pinned collection triggers download; removing a track server-side triggers local file purge after next sync.
6. **No silent failures:** Storage full → visible warning + partial queue; unsupported codec → flagged track (not hidden error); network loss → sync pauses visibly and resumes automatically.

## Approval checklist

- [x] Q-001 resolved — A: template artifact; authoritative goal = PLAN.md roadmap (DEC-008), 2026-08-07
- [x] Q-002 resolved — B: lite = separate reduced-feature build flavor (DEC-007 LOCKED); cut list → Q-006 / GAP-007
- [x] Q-003 resolved — C: Room-migration discipline (DEC-009 / REQ-012)
- [x] Q-004 resolved — A: generic exclusions confirmed; PLAN.md §1.3 operative (DEC-008)
- [x] Q-005 resolved — A: pull executed; local main matched the prior internal main
- [x] Scope reviewed against PLAN.md §1.3 non-goals
- [x] Milestones reviewed and prioritized
- [x] Risks reviewed and mitigations accepted
- [x] Success criteria agreed as measurable
- [x] Owner signs off on DRAFT → APPROVED (2026-08-07, via Q-001=A / Q-004=A)
