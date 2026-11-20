# JellyWrist

Standalone **Wear OS** app that syncs music from a **Jellyfin** server to an Android watch, then plays it **100% offline** over Bluetooth headphones, without a phone.

**JellyWrist: Offline Jellyfin music for Wear OS.**

The Orbit Pulse brand combines a sync loop, an audio waveform, and a watch crown. The Android icons, favicons, and social visuals are collected in [docs/brand](docs/brand/README.md).

The published Android package intentionally remains `com.example.jellywatch` so that the JellyWrist update preserves the data, downloads, and settings of existing installations.

The product and technical details live in the [unified design plan](PLAN.md).

## Status

**`0.0.1`** is the first public source release. The interfaces, local storage,
and companion protocol may still evolve. JellyWrist is an independent project,
not affiliated with or endorsed by the Jellyfin project.

## Modules

- `:app` — standalone Wear OS app;
- `:core` — shared authentication, Jellyfin access, TLS, and protocol;
- `:mobile` — optional Android companion via the Wearable Data Layer.

The watch never requires the phone companion.

## Build and verify

Prerequisites: JDK 17 and an Android SDK with `platforms;android-37.0`. Create a gitignored
local file `local.properties` containing `sdk.dir=<path-to-the-SDK>`, then run:

```bash
./scripts/check-public-hygiene.sh
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Instrumented tests require a watch or a Wear OS emulator. Release APKs are only
signed when a local, never-committed signing configuration is provided.

### Continuous integration

Android CI first runs the public-hygiene scan, then `lintDebug`,
`assembleDebug testDebugUnitTest`, and `assembleRelease` on pull requests and pushes to `main`. Only pushes to `main`
upload the watch and mobile debug/release APKs, retained for seven days. Pull
requests build and verify the same variants without uploading APKs.

Concurrency is grouped by workflow and pull request number or ref. New runs
cancel superseded running pull request runs, but do not cancel running `main`
builds. GitHub may replace an older pending run in the same group. Gradle caching
is unchanged; the APK retention policy applies to new artifacts, not existing ones.

## Security and contributions

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability and
[CONTRIBUTING.md](CONTRIBUTING.md) before contributing. Never publish a real server
address, account identifier, production log, or secret.

## License

JellyWrist is released under the [MIT License](LICENSE).
