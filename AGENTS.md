# Repository Agent Contract

## Purpose

This repository uses a spec-driven workflow. Durable project intent and implementation requirements live in `/specs`; chat history is not a source of truth.

## Mandatory read order

Before changing code, read:

1. `/specs/01_PROJECT_CONTEXT.md`
2. `/specs/02_STRATEGIC_PLAN.md`
3. `/specs/03_MASTER_PRODUCTION_SPEC.md`
4. `/specs/DECISION_LOG.md`
5. `/specs/07_KNOWN_GAPS.md`
6. `/specs/04_EXECUTION_PLAN.md`

Read `/specs/00_PROJECT_INTAKE.md` when investigating provenance or discovery material.

## Source-of-truth hierarchy

1. non-superseded locked decisions in `specs/DECISION_LOG.md`;
2. approved production requirements in `specs/03_MASTER_PRODUCTION_SPEC.md`;
3. approved strategic direction in `specs/02_STRATEGIC_PLAN.md`;
4. verified current-state context in `specs/01_PROJECT_CONTEXT.md`;
5. repository implementation, tests, configuration, and observed behaviour;
6. older notes (`PLAN.md` sections not yet lifted into specs, superseded Plan A, README, chat history).

Note: `PLAN.md` remains the detailed design reference. Its §0/§1.2 decisions have been lifted into `DECISION_LOG` as DEC-001..DEC-006; until a section is superseded by a `/specs` document, `PLAN.md` is authoritative for that section's detail.

Report conflicts instead of hiding them.

## Decision semantics

- Follow `[LOCKED]` decisions exactly.
- Treat `[FLEXIBLE]` items as bounded engineering choices and document the chosen approach.
- Treat `[ASSUMPTION]` items as provisional and reversible.
- Treat `[OPEN QUESTION]` items as unresolved; do not silently decide them.
- Do not implement `[DEFERRED]` or `[DO NOT BUILD]` items.
- Report `[CONTRADICTORY]` sources explicitly.

## Working rules

- Do not invent missing product behaviour.
- Do not expand scope silently.
- Work on the approved phase only.
- Preserve existing behaviour unless an approved requirement changes it.
- Prefer existing project conventions and dependencies unless the spec approves a change.
- Keep changes small and reviewable.
- Before coding, summarize the phase, locked decisions, assumptions, open questions, risks, and implementation order.
- Stop only the affected work when a documented blocking condition is reached.

## Public repository hygiene (mandatory for humans and all LLMs)

- Assume every tracked file, Git ref, commit message, branch name, patch, PR, issue, review,
  screenshot, build log, and generated artifact can become public.
- Never copy real server addresses, account names, email addresses, device identifiers,
  credentials, tokens, certificate/private-key material, production responses, or private
  infrastructure details into the repository. Use RFC-reserved domains (`example.com`,
  `example.org`, `example.net`), documentation IP ranges, and visibly synthetic identities.
- Never track local assistant/session artifacts (`.logs/`, `.zcode/`, `.claude/`, `.cursor/`,
  session transcripts, scratch plans), environment files, signing stores, device dumps, APKs,
  or AABs.
- Treat data supplied in chat or discovered locally as private unless the owner explicitly
  approves that exact value for publication. Product names and maintainer attribution already
  present in approved public-facing documents are the only default exceptions.
- Run `./scripts/check-public-hygiene.sh` before every commit. Inspect both `git diff` and
  `git diff --cached`; automated scanning supplements human review and never replaces it.
- If sensitive data enters Git, stop. Do not claim that a deletion commit fixes exposure.
  Rotate affected secrets and either sanitize every reachable ref or publish a new repository
  from a reviewed clean snapshot, as directed by `specs/09_PUBLIC_RELEASE_AUDIT.md`.
- Do not add issue/comment-triggered bots with write permissions or secrets. Any privileged
  automation requires explicit owner approval, trusted-actor gating, least privilege,
  immutable action SHAs, fork-safe secret handling, and a documented threat review.
- Do not claim that the project is open source until the owner has selected and committed a
  license. Public visibility alone grants no reuse rights.

### Repo-specific rules

- **Version catalog only.** All dependency/plugin versions live in `gradle/libs.versions.toml`; never hardcode a version in a `build.gradle.kts`.
- **Wear Compose Material3 imports.** Import from `androidx.wear.compose.material3`, not `androidx.compose.material3` — the Wear line has its own `MaterialTheme`, `Text`, `AppScaffold`, `ScreenScaffold`, `TimeText`.
- **Navigation.** Use `SwipeDismissableNavHost` / `rememberSwipeDismissableNavController` from `androidx.wear.compose.navigation`; routes are constants on `JellyRoutes` in `ui/nav/JellyNav.kt`.
- **Hilt via KSP.** Hilt annotation processing goes through KSP, not kapt. Keep `kotlin-gradle-plugin` on the buildscript classpath in sync with the `kotlin` version in the catalog.
- **English docs, English identifiers, English specs.** `PLAN.md`, `CHANGELOG.md`, and KDoc are written in English. Code identifiers are English. All `/specs` documents are written in English.
- **Update CHANGELOG.md** (Keep a Changelog format, `## [Unreleased]`) with every substantive change.
- **compileSdk 37 CI provisioning.** `compileSdk = 37` requires `platforms;android-37.0`, which is not preinstalled on GitHub runners. CI provisions it via `sdkmanager` by absolute path. Bumping `compileSdk` means updating that CI step.
- **PLAN.md is the single design reference**. Superseded draft plans are intentionally absent from the public tree.
- **Optional phone companion (DEC-013).** The watch never *requires* a paired phone — manifest `com.google.android.wearable.standalone = true` is unchanged. An optional `:mobile` companion app communicates via the Wearable Data Layer (same `applicationId` + signing key). Modules: `:app` (watch), `:core` (shared Jellyfin/auth/TLS layer + wire protocol), `:mobile` (phone app).
- **App-private storage.** Files are stored in app-private `filesDir`; no storage permissions.

## Verified project commands

```bash
# setup (once, gitignored):
# local.properties must contain sdk.dir=<path-to-Android-SDK>

# unit tests (JVM, no emulator needed):
.\gradlew.bat testDebugUnitTest          # Windows (PowerShell)
./gradlew testDebugUnitTest              # Bash / CI

# single test class or method:
.\gradlew.bat testDebugUnitTest --tests "com.example.jellywrist.sync.SyncPlanTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.jellywrist.sync.SyncPlanTest.track removed server-side with no other reference is purged"

# lint:
.\gradlew.bat lint                       # Windows (PowerShell)
./gradlew lint                           # Bash / CI

# build:
.\gradlew.bat assembleDebug              # Windows (PowerShell) -> app/build/outputs/apk/debug/
./gradlew assembleDebug                  # Bash / CI

# instrumented tests (requires Wear OS emulator or physical watch):
.\gradlew.bat connectedDebugAndroidTest   # Windows (PowerShell)
./gradlew connectedDebugAndroidTest       # Bash / CI

# install onto attached watch/emulator:
.\gradlew.bat installDebug               # Windows (PowerShell)
./gradlew installDebug                   # Bash / CI

# CI (.github/workflows/android.yml) runs on push to main and PRs:
# public-hygiene scan, ./gradlew lintDebug, ./gradlew assembleDebug testDebugUnitTest,
# and ./gradlew assembleRelease
```

## Tool-specific reference

[Claude Code Reference](docs/claude-code-reference.md) preserves detailed Claude
Code, Wear OS, and build guidance. It is intentionally not an automatically
discovered context contract; read it when the tool or task needs it and keep it
aligned with this file.

## Verification

- Run the checks listed in `/specs/04_EXECUTION_PLAN.md`.
- Add or update tests for changed behaviour.
- Show commands, results, and requirement IDs covered.
- Do not claim completion when required checks were not run.

## End-of-pass report

Provide:

- files created or changed;
- requirement IDs implemented;
- work not implemented;
- tests and checks run with results;
- deviations from the approved plan;
- assumptions used;
- new gaps or contradictions;
- unresolved risks;
- recommended next action.

Update the relevant spec documents when implementation changes verified project reality.
