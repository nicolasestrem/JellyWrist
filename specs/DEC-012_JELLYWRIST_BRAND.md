# DEC-012: JellyWrist product identity and upgrade continuity

- Date: 2026-08-11
- Status: LOCKED
- Owner/approver: nicolasestrem

## Decision

Rebrand the product from JellyWatch to **JellyWrist**. Use the selected **Orbit Pulse** visual system across the launcher icon, round icon, Android themed-icon monochrome mark, notification mark, favicon set, social card, documentation, client identity and CI artifacts.

## Compatibility decision

The source namespace moves to `com.example.jellywrist`, while the published Android `applicationId` stays `com.example.jellywatch`. Keep established storage and scheduling identifiers containing `jellywatch` unchanged, including the Room database, encrypted token preferences, DataStores, WorkManager names and tags, and notification channel ID.

## Rationale

Keeping the signed package and persistent identifiers lets a JellyWatch installation upgrade in place. Changing them would either install a second application or discard locally persisted session, library, download and scheduling state.

## Requirements and verification

- Product name, app label, client identity and CI artifact names identify JellyWrist.
- Adaptive launcher, round launcher, monochrome themed icon and notification icon use the Orbit Pulse system.
- Brand assets are stored under `docs/brand/`.
- Build verification must inspect the merged manifest and release APK, in addition to unit tests, lint and debug/release builds.

> **Superseded in part (2026-11-20).** DEC-016 moves the `applicationId` and all `jellywatch`
> persistence identifiers to the `jellywrist` identity. 0.0.1 was the first public release and
> no users existed, so the upgrade-continuity rationale recorded above no longer applies. The
> brand decision itself (name, Orbit Pulse artwork, launcher icons) remains in force.
