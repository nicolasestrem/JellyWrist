# Claude Code Reference

This non-auto-discovered reference preserves Claude Code-specific guidance for
this repository.

**Read [AGENTS.md](../AGENTS.md) first** as the repository-wide agent contract
and follow its required `/specs` read order. Keep this reference aligned with
that contract.

## Project

JellyWrist is a **standalone Wear OS** app (no paired phone *required*) that syncs music from a **Jellyfin** server to the watch and plays it **fully offline** over Bluetooth headphones. An **optional phone companion** (`:mobile`) assists provisioning and remote management via the Wearable Data Layer (DEC-013).

Three Gradle modules: `:app` (watch, Kotlin + Compose for Wear OS), `:core` (shared JellyfinBackend/AuthRepository/TokenStore/TLS layer + wire protocol), `:mobile` (phone companion, Compose Material3). The watch app remains standalone — the companion is optional.

## Commands

Windows (PowerShell) uses `.\gradlew.bat`; the Bash tool / CI use `./gradlew`.

```powershell
.\gradlew.bat assembleDebug                 # build debug APK -> app/build/outputs/apk/debug/
.\gradlew.bat testDebugUnitTest             # JVM unit tests (app, core, and mobile modules)
.\gradlew.bat lint                          # Android Lint
.\gradlew.bat connectedDebugAndroidTest     # instrumented tests (needs a Wear emulator/device)
.\gradlew.bat installDebug                  # install onto attached watch/emulator

# single test class / method
.\gradlew.bat testDebugUnitTest --tests "com.example.jellywrist.sync.SyncPlanTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.jellywrist.sync.SyncPlanTest.track removed server-side with no other reference is purged"
```

CI (`.github/workflows/android.yml`) runs the public-hygiene scan, `./gradlew lintDebug`,
`assembleDebug testDebugUnitTest`, and `assembleRelease` on every push to `main` and every PR.

## Build-configuration gotchas

- **`compileSdk = 37`** requires the `platforms;android-37.0` SDK package. It is *not* preinstalled on GitHub runners, so CI provisions it via `sdkmanager` (which is not on `PATH` — the workflow calls it by absolute path under `$ANDROID_HOME`). Bumping `compileSdk` means updating that CI step.
- **AGP 9 ships built-in Kotlin with an older KGP runtime.** The root `build.gradle.kts` pins `kotlin-gradle-plugin` on the buildscript classpath to match the `kotlin` version in the catalog. Keep those two in sync when upgrading Kotlin.
- All dependency/plugin versions live in `gradle/libs.versions.toml` (version catalog). Never hardcode a version in a `build.gradle.kts`.
- Hilt annotation processing goes through **KSP**, not kapt.
- `local.properties` (gitignored) must contain `sdk.dir`.
- **Jellyfin SDK** (`org.jellyfin.sdk:jellyfin-core`): a logging backend is required — the SDK logs via `kotlin-logging`→slf4j, whose API isn't transitively on the runtime classpath, so without `slf4j-nop` (current choice) the SDK throws `NoClassDefFoundError: org/slf4j/LoggerFactory` on class load, in production *and* unit tests.
- **Testing the Jellyfin layer on JVM:** AGP resolves the **Android** variant of the KMP SDK even for `testDebugUnitTest`, so `createJellyfin{}` requires a `Context`. Repository code depends on the `JellyfinBackend` *port* (not the concrete `Jellyfin`); tests build the `ApiClient` via `OkHttpFactory` directly (see `ServerRepositoryTest`), avoiding Robolectric. MockWebServer JSON bodies must be **PascalCase** to match the SDK's generated `@SerialName` values (e.g. `ServerName`, `Version`, `Id`).

## Wear OS specifics

- Import Compose from **`androidx.wear.compose.material3`**, not `androidx.compose.material3` — the Wear line has its own `MaterialTheme`, `Text`, `AppScaffold`, `ScreenScaffold`, `TimeText`.
- Navigation uses `SwipeDismissableNavHost` / `rememberSwipeDismissableNavController` from `androidx.wear.compose.navigation`; routes are constants on `JellyRoutes` in `ui/nav/JellyNav.kt`.
- `minSdk 34` (Wear OS 5+), `targetSdk 36`. Manifest declares `android.hardware.type.watch` and `com.google.android.wearable.standalone = true` — the app must never assume a paired phone.

## Architecture (target)

`PLAN.md` is the **single reference document** — read it before designing anything. It defines the target package layout (§11), the data model (§7), the 14-task execution plan (§8), and milestones M0–M6. Superseded draft plans are intentionally absent from the public tree.

Planned shape under `com.example.jellywrist/`: `di/` (Hilt modules) · `data/{jellyfin,db,repo}/` · `download/` (Media3 `DownloadService` + `ReconWorker`) · `playback/` (`MediaSessionService` + ExoPlayer) · `ui/{nav,screens}/`.

The core of the app is the **sync engine**, not a file downloader: the user *pins collections* (playlist/album/artist) and a WorkManager pipeline converges local state toward the server in both directions — refresh metadata → diff against Room → capacity check → enqueue in Media3 `DownloadManager` → verify → purge orphans (refcounted through the `CollectionTrack` n-n table).

## Settled decisions — do not re-litigate

These were resolved by factual verification (see `PLAN.md` §0) after two earlier plans disagreed:

- **Official Kotlin SDK `org.jellyfin.sdk`** (OkHttp 5 backend), not a hand-rolled client.
- **Download via `GET /Items/{id}/File`** — original file, `Accept-Ranges: bytes`, resumable. `/Audio/{id}/universal` is *not* resumable (transcoded output sets `Accept-Ranges: none`); `/Items/{id}/Download` needs an extra permission.
- **No transcoding in v1** — original files; unsupported codecs (ALAC, WMA…) are detected at diff time from Jellyfin codec metadata and flagged, not failed at playback.
- **Quick Connect first**, `AuthenticateByName` as fallback; token in EncryptedSharedPreferences/Keystore.
- **Hybrid WorkManager + foreground service is mandatory** — the Android 15+ 6h `dataSync` FGS cap is real; long syncs get re-armed and resumed by Range, and the UI says "paused, will resume" rather than "failed".
- Hilt for DI; Media3 + Horologist for download and playback; certificate pinning opt-in, never a global TLS bypass.

## Conventions

- Docs (`PLAN.md`, `CHANGELOG.md`, KDoc in existing sources) are written in **English**; code identifiers in English.
- Update `CHANGELOG.md` (Keep a Changelog format, `## [Unreleased]`) with every substantive change.
- Files are stored in app-private `filesDir` — no storage permissions.
- TDD where the surface is JVM-testable (DTOs, repositories, and the pure functions `SyncPlan.diff` / `BudgetPlanner.plan`); UI and services are verified on a Wear emulator or watch.
