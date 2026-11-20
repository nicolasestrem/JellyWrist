# Build Review — JellyWrist 0.0.1

- Status: PASS — local build, lint, and JVM unit-test gate
- Date: 2026-11-20
- Scope: the `0.0.1` release snapshot (versionCode 1 in `:app` and `:mobile`)
- Environment: Windows 11, JDK 21.0.13 (Amazon Corretto), Gradle 9.6.0, AGP 9.4.1,
  Android SDK platform `android-37.0`. CI uses Temurin JDK 17; the JVM target is 17.

## Verification commands

```powershell
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug assembleRelease
```

Result: **BUILD SUCCESSFUL**, exit code 0, 325 actionable tasks
(150 executed, 160 from cache, 15 up-to-date). The final clean run completed in 1m 25s;
an immediately preceding identical run executed the same tasks and completed in 6m 05s.
Gradle satisfied the final run's unit-test tasks from its build cache because the task
inputs were identical to the executed run; the recorded XML results below are the same
tests that ran against this tree.

```bash
bash ./scripts/check-public-hygiene.sh
```

Result: `PUBLIC-HYGIENE: index and working tree passed`.

## Evidence

| Gate | Result | Details |
|---|---|---|
| `lintDebug` | PASS | `:app` 0 errors / 0 warnings / 2 hints; `:core` 0 / 0 / 3 hints; `:mobile` 0 / 0 / 1 hint. The hints are the documented, informational exceptions for per-server TLS pinning (DEC-006), LAN cleartext (GAP-008), the exported Media3 session service (REQ-009), and version checks disabled in the build scripts. |
| `testDebugUnitTest` | PASS | **273 tests, 0 failures, 0 errors, 0 skipped** — `:app` 155 tests across 20 suites, `:core` 71 across 7, `:mobile` 47 across 9. |
| `assembleDebug` | PASS | Watch and phone debug APKs produced. |
| `assembleRelease` | PASS | Watch and phone release APKs produced with R8 shrinking/resource shrinking. Both are **unsigned**: no `keystore.properties` exists on this machine and no key is committed, so the build intentionally falls back to an unsigned release APK. |
| Version metadata | PASS | `output-metadata.json` for all four APKs reports `versionName` `0.0.1`, `versionCode` 1, `applicationId` `com.example.jellywatch` in both modules. |
| Public hygiene | PASS | Credential, private-infrastructure, unreviewed-host, signing-material, local-configuration, packaged-app, and assistant-artifact scans pass on the staged index and the working tree. |
| Signing material | PASS | No `keystore.properties`, `*.jks`, `*.keystore`, `.env`, or private-key file is tracked or present in the snapshot. |

## Artifacts

| Artifact | Size (bytes) | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 100,220,788 | `23E9E530A24D34F945C93602BBDEDAF66CA677FDC0DE7EB4D614952C04A5AD1E` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 9,493,154 | `13CA800C5C0898B10993286FDC25EFE6EFDC7B815EC7F8307E5F10C99FA4181E` |
| `mobile/build/outputs/apk/debug/mobile-debug.apk` | 17,957,279 | `6D9ADAFF809039942E504796DA7DE20C0927BBC3B20A1B5A6A68392160866BE0` |
| `mobile/build/outputs/apk/release/mobile-release-unsigned.apk` | 3,145,412 | `2C3DE1F5B56125380B0492649DA820118101327B55632B8FB169C5381E0578FD` |

The debug APKs embed full debug symbols, which is why the watch debug APK is much larger
than its release counterpart.

## Not verified in this pass

- **Instrumented and on-device tests were not run** (`connectedDebugAndroidTest`); no watch,
  phone, or emulator was attached. The on-device gaps remain open in `specs/07_KNOWN_GAPS.md`
  (GAP-014, GAP-015, GAP-016, GAP-017).
- **CI has not yet run on the recreated public repository.** The first `Android CI` run after
  the initial push is the post-publication gate; its result is reported separately.
- **No signed release APK** exists: signing requires a local, never-committed keystore.
- **Kotlin compiler warnings** remain for deprecated AndroidX APIs (EncryptedSharedPreferences /
  MasterKey, `hiltViewModel`, a few auto-mirrored icons, `TabRow`) and for coroutine
  experimental opt-ins in tests. They are compiler warnings, not lint findings, and none is
  new to this pass.

## Verdict

The `0.0.1` snapshot passes the local build, lint, and JVM unit-test gate, and the
public-hygiene scan. Release verification on hardware remains outstanding and is tracked as
open gaps rather than claimed here.
