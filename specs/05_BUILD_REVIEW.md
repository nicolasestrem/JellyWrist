# Build Review — JellyWrist 0.0.1

- Status: PASS — local build, lint, JVM, and instrumented gate
- Date: 2026-11-20
- Scope: the `0.0.1` release snapshot (versionCode 1 in `:app` and `:mobile`), built after the
  merged Dependabot updates and the Room v1 schema fix
- Environment: Windows 11, JDK 21.0.13 (Amazon Corretto), Gradle 9.6.0, AGP 9.5.1,
  Android SDK platform `android-37.0`. CI uses Temurin JDK 17; the JVM target is 17.

## Verification commands

```powershell
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest
bash ./scripts/check-public-hygiene.sh
```

The full build completed with **BUILD SUCCESSFUL** (330 tasks; 4m 47s). The instrumented
suite completed with **BUILD SUCCESSFUL** on a Wear OS API 37 emulator. The hygiene scan
reports `PUBLIC-HYGIENE: index and working tree passed`.

## Evidence

| Gate | Result | Details |
|---|---|---|
| `lintDebug` | PASS | `:app` 0 errors / 0 warnings / 2 hints; `:core` 0 / 0 / 3 hints; `:mobile` 0 / 0 / 1 hint. The hints are the documented, informational exceptions for per-server TLS pinning (DEC-006), LAN cleartext (GAP-008), the exported Media3 session service (REQ-009), and version checks disabled in the build scripts. |
| `testDebugUnitTest` | PASS | **273 tests, 0 failures, 0 errors, 0 skipped** — `:app` 155, `:core` 71, `:mobile` 47. |
| `:app:connectedDebugAndroidTest` | PASS | **7/7** on a Wear OS Large Round API 37 emulator: 6 `EncryptedTokenStoreTest` (real Android Keystore encryption) and 1 `MigrationTest` (populated Room v1 → v2 migration). |
| Watch smoke launch | PASS | `app-debug.apk` installed and cold-launched on the 454×454 Wear emulator; the onboarding screen renders (wordmark, server field, Connect) and `AndroidRuntime` reports no fatal error. |
| Phone smoke launch | PASS | `mobile-debug.apk` installed and cold-launched on a Pixel 6 API 35 emulator; no fatal error. Pairing/Data Layer discovery was not exercised. |
| `assembleDebug` | PASS | Watch and phone debug APKs produced. |
| `assembleRelease` | PASS | Watch and phone release APKs produced with R8 and resource shrinking, **signed with the local release keystore** (not committed). |
| Version metadata | PASS | All four APKs report `versionName` `0.0.1`, `versionCode` 1, `applicationId` `com.example.jellywatch` in both modules. |
| Public hygiene | PASS | Credential, private-infrastructure, unreviewed-host, signing-material, local-configuration, packaged-app, and assistant-artifact scans pass on the staged index and the working tree. |
| Signing material | PASS | `keystore.properties` is gitignored and no keystore, key, or credential is tracked. |

## Signed release artifacts

Both release APKs are signed with the same certificate (required for Wearable Data Layer
delivery between the watch and phone apps):

```
Signer #1 certificate DN: CN=JellyWrist Release, OU=Android, O=JellyWrist, L=Paris, ST=Ile-de-France, C=FR
Signer #1 certificate SHA-256: 8e112eaf2b999f41a165b4d3f7b4ac0695475116edb3da1486c95508786288e4
```

| Artifact | Description |
|---|---|
| `app/build/outputs/apk/release/app-release.apk` | Signed watch release APK |
| `mobile/build/outputs/apk/release/mobile-release.apk` | Signed phone release APK |

Exact SHA-256 checksums are published with the release assets
(`SHA256SUMS-0.0.1.txt`), because AGP embeds the tagged commit in
`META-INF/version-control-info.textproto`: the APK bytes change when the commit changes even
though the application code does not. The versioned release assets are
`jellywrist-0.0.1-watch.apk` and `jellywrist-0.0.1-phone.apk`.

## Not verified in this pass

- **Full on-device product path**: Quick Connect sign-in against a populated Jellyfin server,
  a real download, and offline Bluetooth playback were not exercised. No test Jellyfin server
  was available; these paths remain tracked as open gaps in `specs/07_KNOWN_GAPS.md`
  (GAP-014, GAP-015, GAP-016, GAP-017).
- **Paired companion transport**: both apps cold-launch on emulators, but the phone↔watch
  Wearable Data Layer exchange (discovery, provisioning, status, pin, sync) was not exercised
  on a paired emulator pair.
- **Kotlin compiler warnings** remain for deprecated AndroidX APIs (EncryptedSharedPreferences
  / MasterKey, `hiltViewModel`, a few auto-mirrored icons, `TabRow`) and for coroutine
  experimental opt-ins in tests. They are compiler warnings, not lint findings.
- **CI on the final release commit** is the post-publication gate; the release page and the
  repository checks record its result.

## Verdict

The `0.0.1` snapshot passes the local build, lint, JVM, instrumented-test, and public-hygiene
gates, and both applications cold-launch on their respective emulators. Release verification
of the full Jellyfin download/playback path on hardware remains outstanding and is tracked as
open gaps rather than claimed here.
