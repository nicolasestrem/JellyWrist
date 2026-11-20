# JellyWrist brand system

## Product

- Name: **JellyWrist**
- Store subtitle: **Offline Jellyfin music for Wear OS**
- Tagline: **Your Jellyfin music, offline on your wrist.**

JellyWrist is a standalone Wear OS music client. The product promise is explicit: sync music from a Jellyfin server to the watch, then listen over Bluetooth without a phone or a network connection.

## Visual direction: Orbit Pulse

The selected symbol unifies three product cues: the circular transfer loop represents sync, the centre waveform represents music, and the crown-like terminus anchors it to a watch. It is used on an OLED-black field with violet-to-magenta energy and a restrained cyan highlight.

| Asset | Use |
| --- | --- |
| `jellywrist-orbit-pulse-key-visual.png` | 1024 px master artwork and app-icon source |
| `jellywrist-icon-512.png` | Store listing and profile avatar |
| `favicon-32.png` / `favicon-48.png` | Web favicons |
| `jellywrist-social-card.png` | 1200 × 630 social preview |

## Colour and use

- OLED base: `#030208`
- Electric violet: `#8B5CFF`
- Magenta: `#E83CFF`
- Cyan highlight: `#59E7FF`
- White: `#FFFFFF`

Use the generated Orbit Pulse image directly for full-colour marks. The Android launcher also provides a monochrome sync mark for Android themed icons and notification surfaces. Keep the mark clear of text and preserve its black field in square, round, and favicon crops.

## Upgrade compatibility

The public product, source namespace, and Android `applicationId` are JellyWrist (`com.example.jellywrist`). Persistence and scheduling identifiers use the `jellywrist` prefix. 0.0.1 is the first public identity, so there is no pre-public package to preserve (DEC-016).
