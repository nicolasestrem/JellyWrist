# Watch UI redesign — design specification

- Date: 2026-09-03
- Status: **PROPOSED — awaiting owner approval**
- Scope: the `:app` (Wear OS) UI layer only
- Decision record: **DEC-014** in `specs/DECISION_LOG.md` (also PROPOSED)
- Supersedes: the *screen design* of DEC-011 (Aurora). It preserves DEC-012 (brand),
  DEC-011's palette and typography, Wear Compose Material3, and `SwipeDismissableNavHost`.

> This document is a design specification, not an implementation plan. It is precise enough to
> implement from: every screen names its route, its single primary action (or states explicitly
> that it has none, and why), its empty, loading and error states, and the ViewModel it binds.
> It contains no production code and mandates no code changes on its own —
> implementation starts only after the owner approves DEC-014.

An interactive, matched-state direction comparison is available at
[`docs/design/watch-ui-directions/index.html`](../docs/design/watch-ui-directions/index.html).
It is a selection aid, not approval of this specification.

---

## 1. Why this redesign exists

The owner's verdict is that the watch UI is confusing. A read of the current sources
(`app/src/main/java/com/example/jellywrist/ui/`) turns that verdict into specific, locatable
faults. Each is listed with the evidence, because each one drives a decision below.

### 1.1 The Library screen is five products in one list

`ui/screens/library/LibraryScreen.kt` puts all of the following into a single
`ScalingLazyColumn`: a brand header, a four-item horizontally scrolling tab strip, a bulk
download tool (the 50/100/200/500 pills), the content list for the selected tab — up to the
entire song library — and, at the very bottom, the app's **only** navigation menu (Downloads and
Settings as two `AuroraChip` rows). Reaching Settings on a 500-song library means scrolling past
500 rows. There is no other route to it.

### 1.2 Three different verbs for one act

- `collection_detail_pin_toggle` = **"Sync to watch"** — a pin *toggle* dressed as a gradient
  call to action (`CollectionDetailScreen.kt`), so it reads as an imperative but behaves as a
  switch.
- `downloads_sync_button` = **"Sync now"** — a genuine imperative, on a different screen.
- The size pills on the Songs tab — which pin *and* start a sync in one tap
  (`LibraryViewModel.syncRandomSongs`), with no visible relationship to either of the above.

Three surfaces, three words, one underlying act: *decide what lives on the watch*.

### 1.3 Six track states rendered as unexplained glyphs

`ui/components/StatusIcon.kt` maps all six `TrackState` values to tinted vector icons. Those
icons appear in three different contexts (Songs tab, collection detail, Downloads) with no
legend anywhere. `PENDING_BUDGET` additionally renders as a bare `"Budget"` badge
(`downloads_budget_label`) in the Downloads screen, which names an internal concept rather than
the user's situation ("waiting for space").

### 1.4 Raw exception strings on screen

`collection_detail_refresh_error` is `"Refresh failed: %1$s"`, filled from
`e.message ?: e.javaClass.simpleName` in `CollectionDetailViewModel.refresh()`. The same pattern
appears in `RandomSyncState.Failed(e.message ?: e.javaClass.simpleName)`. A self-hoster gets
`UnknownHostException` on a 1.9-inch screen; everyone else gets nothing actionable.

### 1.5 Consent prompts replace the control that raised them

On both Library and Downloads, a metered or low-battery prompt is rendered *in place of* the
button the user just tapped (`RandomSyncSection`'s `when (state)`, `DownloadsScreen`'s
`when { batteryWarning -> … meteredPrompt -> … else -> SyncCta(…) }`). The control vanishes and
different-looking prompt UI appears where it was. The two screens style the same two prompts
differently: Library uses `AuroraChip` pairs inside a `PromptCard`, Downloads uses raw Wear M3
`Button`s.

### 1.6 Navigation is a one-way tree with no way back to the music

`ui/nav/JellyNav.kt` declares five destinations plus `collection/{collectionId}`. `LIBRARY` is
the only node with outgoing edges. `DOWNLOADS`, `SETTINGS` and `PLAYER` are leaves: once the user
is on one of them, the only exit is the system dismiss gesture, and there is no affordance saying
so. Nothing anywhere navigates *to* `PLAYER` except a track tap in collection detail, so a user
who leaves the player cannot get back to it without replaying a track. `JellyPlaybackService`
sets no `sessionActivity` on its `MediaSession` (verified: `MediaSession.Builder(this,
guarded).build()`), so the system media notification opens nothing useful either.

### 1.7 One gauge, two meanings

`StorageGauge` is used with `totalBytes = state.totalDiskBytes` on the Downloads screen and with
`totalBytes = state.settings.quotaBytes` in Settings. Same component, same visual, two different
denominators, no label distinguishing them.

### 1.8 Everything is the same control

`AuroraChip` — a fixed 52 dp row — is the list row, the navigation item, the toggle, the consent
button, the account display and the primary call to action. Variants differ only by fill. The
brand gradient (`AuroraChipVariant.Filled`) is applied to the pin toggle, the sync CTA, the
onboarding submit, the certificate "Trust" button, the consent "Continue" button and the selected
tab. When the gradient marks six different roles, it marks none.

### 1.9 Onboarding stops one step short

`PLAN.md` §6 says onboarding ends with a "suggestion to pin a first playlist". It does not:
`OnboardingUiState.Authenticated` navigates straight to `LIBRARY`, whose default tab is Playlists,
which on a fresh install is empty. The first thing a new user sees is "Nothing here".

### 1.10 Dead affordances

- Songs-tab rows are `onClick = {}` (`SongChip`) — they look tappable and do nothing.
- `SectionHeader(showChevron = true)` draws a chevron that is not a target.
- Downloads' `ActiveTrackChip` / `ReadyTrackChip` / `ErrorTrackChip` all pass `onClick = {}` with
  `enabled = false`.
- No list in the app wires rotary input, so the crown scrolls nothing. `ScalingLazyColumn` does
  not provide rotary scrolling on its own.

### 1.11 What is *not* wrong, and must survive

The palette, the typography, the gradient itself, the launcher and notification marks, the
Quick Connect code treatment, the seek bar, offline-first behaviour, and the whole non-UI stack.
This redesign changes the *shape* of the UI, not the identity and not one line of sync, download,
playback, auth or persistence logic.

---

## 2. Constraints and settled inputs

These are decided. They are recorded here so the design is readable without the surrounding
conversation, not to be re-argued.

### 2.1 Decisions made by the owner

1. The **watch** UI is redesigned from scratch this round. The `:mobile` companion is not touched.
2. Pinning ("keep on watch") starts an **immediate manual sync** with consent prompts. This
   already shipped on a pre-merge development branch:
   `app/src/main/java/com/example/jellywrist/sync/KeepOnWatchCoordinator.kt`, with its
   `ConsentPrompt` enum and the `SyncPreconditions` port. The redesign **reuses it as-is** and
   changes none of its semantics.
3. "Random songs" becomes a **Random mix** collection listed like any other. The
   50/100/200/500 picker moves to that collection's detail screen and leaves the Songs list.

### 2.2 Dependency findings (verified; treat as settled)

- `horologist-compose-layout` and `horologist-media-ui` **0.7.15** both depend on
  `androidx.wear.compose:compose-material` **1.5.0-rc01** (Wear Material 2.5) and bring a second
  theme system that cannot follow `JellyWristTheme`. **Neither is added. Zero new runtime
  dependencies.**
- Wear Compose **Material3 1.6.2** — already in `gradle/libs.versions.toml` as
  `wearCompose = "1.6.2"` — ships everything this design needs: `AppScaffold`, `ScreenScaffold`,
  `TimeText`, `ScrollIndicator`, `HorizontalPagerScaffold` + `HorizontalPageIndicator`,
  `EdgeButton`, `ListHeader`, `ListSubHeader`, `AlertDialog`, `ConfirmationDialog`, `Card`,
  `TitleCard`, `SwitchButton`, `RadioButton`, the `Button` family with a `containerPainter`
  parameter (so a gradient container is first-party), `Stepper`, `LevelIndicator`,
  `CircularProgressIndicator(startAngle/endAngle)`, `LinearProgressIndicator`, `Placeholder` +
  `placeholderShimmer`, `FadingExpandingLabel`, and `SurfaceTransformation` +
  `rememberTransformationSpec`.
- Wear Compose **Foundation 1.6.2** provides `TransformingLazyColumn`, which has **built-in
  rotary scrolling**. This is the fix for §1.10's dead crown.
- `horologist-audio` (`VolumeRepository`, `OutputSwitcher`) and `horologist-media3-outputswitcher`
  stay. So do `horologist-network-awareness` and `horologist-media3-backend`.

### 2.3 Baseline

This specification is written against the tree **as it will exist once
the pre-merge development work is merged**. `KeepOnWatchCoordinator`, `ConsentPrompt`,
`SyncPreconditions`, `DownloadQueueController` and `DownloadResumer` do not exist on `main`.
Sequencing is Open Question **OQ-4**.

---

## 3. Design principles

Five rules. Every decision below is traceable to one of them.

1. **One screen answers one question.** If a screen answers two, it becomes two screens. This is
   the direct answer to §1.1.
2. **One act, one verb.** The act of deciding what lives on the watch is called **"Keep on
   watch"**, everywhere, in every language. See §6.
3. **State is a sentence, not a glyph.** Every status is a short phrase the user can act on. A
   glyph may accompany the phrase; it never replaces it in the primary position. See §6.2.
4. **One primary action per screen, and it is the only thing wearing the gradient.** Wear M3's
   `EdgeButton` gives it a fixed position — the bottom edge — on every screen that has one.
   See §9.
5. **Never show a `Throwable`.** Every failure reaching a screen is a classified `UiError` with a
   named cause and, where one exists, one action. Technical text lives in the sync log, where a
   self-hoster can find it. See §7.

---

## 4. Information architecture

### 4.1 The shape

One `HOME` destination hosting a **two-page horizontal pager**, and a push stack for everything
else:

```
                    ┌──────────── HOME (start destination) ────────────┐
                    │                                                  │
                    │   page 0: Library          page 1: Now Playing   │
                    │   (what is on the watch)   (what is playing)     │
                    │                                                  │
                    └──────────────────────────────────────────────────┘
                             │  push                       ▲
                             ▼                             │ swipe-dismiss
              ┌──────────────────────────────────────────────────────┐
              │  browse            sync            settings          │
              │  browse/{type}                     settings/quota    │
              │  collection/{id}                   settings/log      │
              └──────────────────────────────────────────────────────┘

  onboarding ──► onboarding/first-pick ──► HOME     (start destination when no session)
```

**Why a pager.** Now Playing must be reachable from anywhere in at most *one dismiss plus one
swipe*. Making it page 1 of the start destination guarantees that without adding a persistent
navigation bar (which a round 1.9-inch screen cannot afford) and without a back stack that can
grow unboundedly.

**Why the rest is a push stack.** `SwipeDismissableNavHost` gives every pushed screen a free,
learned "go back" gesture. Every sub-screen therefore has exactly one exit, and it is the system
gesture the user already knows. There is no in-app back button anywhere.

**Gesture arbitration.** `SwipeDismissableNavHost` only enables its dismiss gesture when there is
something to dismiss *to*. `HOME` is the start destination, so while the user is on `HOME` the
gesture is inert and the pager owns horizontal swipes in both directions. Sub-screens are never
pagers, so their horizontal swipes are always dismiss. This is a hard invariant of the design and
an on-device verification item (§13.3, check 2): **no pushed screen may contain a horizontal
pager.**

`HorizontalPagerScaffold` supplies the `HorizontalPageIndicator` itself; no page indicator is
hand-drawn.

### 4.2 Route table

Routes are constants on `JellyRoutes` in `ui/nav/JellyNav.kt`, as today.

| Route | Constant | Args | Purpose (the one question it answers) | Primary action | ViewModel |
|---|---|---|---|---|---|
| `onboarding` | `ONBOARDING` | — | "How do I connect this watch to my server?" | Connect / Retry | `OnboardingViewModel` |
| `onboarding/first-pick` | `FIRST_PICK` | — | "What should I put on the watch first?" | Keep on watch | `FirstPickViewModel` (new) |
| `home` | `HOME` | — | page 0: "What is on my watch, and how is it doing?" · page 1: "What is playing?" | page 0: Add music · page 1: play/pause | `HomeViewModel` (new) + `PlayerViewModel` |
| `browse` | `BROWSE` | — | "What kind of thing am I looking for?" | none (a chooser) | none (static) |
| `browse/{type}` | `BROWSE_TYPE` | `type` ∈ `playlists`,`albums`,`artists`,`songs` | "Which one?" | none (a list) | `BrowseViewModel` (new) |
| `collection/{collectionId}` | `COLLECTION_DETAIL` | `collectionId` | "What is in this, and is it on my watch?" | Keep on watch / Remove from watch | `CollectionDetailViewModel` |
| `collection/__random__` | — | (same route, reserved id) | "How many random songs do I want?" | Keep on watch / Reshuffle | `CollectionDetailViewModel` (random variant) |
| `sync` | `SYNC` | — | "Why isn't my music here yet?" | Sync now | `SyncViewModel` (renamed from `DownloadsViewModel`) |
| `settings` | `SETTINGS` | — | "Change how this app behaves." | none (peers) | `SettingsViewModel` |
| `settings/quota` | `SETTINGS_QUOTA` | — | "How much space may it use?" | none (applies as you change it) | `SettingsViewModel` (shared) |
| `settings/log` | `SETTINGS_LOG` | — | "What actually happened during sync?" | none (a log) | `SettingsViewModel` (shared) |

Deleted routes: `LIBRARY` (becomes `HOME` page 0), `DOWNLOADS` (becomes `sync`), `PLAYER`
(becomes `HOME` page 1).

`collection/__random__` is not a separate route: `RANDOM_COLLECTION_ID` is already a real
`CollectionEntity` id in `LibraryRepository`, and `CollectionDetailViewModel` selects the random
variant when `collection.localOnly == true`. One route, one ViewModel, one variant flag.

### 4.3 Entry points and deep links

| Entry point | Lands on | Mechanism |
|---|---|---|
| Launcher icon | `HOME` page 0, or `onboarding` when no session | existing `MainActivity` bootstrap |
| System media notification / media controls | `HOME` page 1 | **new**: `MediaSession.Builder(...).setSessionActivity(pendingIntent)` in `JellyPlaybackService`, carrying `EXTRA_OPEN = "now_playing"` |
| Download progress notification | `sync` | **change**: the existing `PendingIntent` in `JellyDownloadService.getForegroundNotification` gains `EXTRA_OPEN = "sync"` |
| Tile ("Resume playback") | `HOME` page 1 | existing `LaunchAction` to `MainActivity` gains the same extra |
| Any sub-screen, queue present | `HOME` page 1 | `NowPlayingChip` (§8.1) |

**Manifest requirement.** `MainActivity` currently has no `launchMode` (default `standard`) and
`android:taskAffinity=""`. A deep link into a running app must not stack a second activity, so
`MainActivity` becomes `android:launchMode="singleTask"` and implements `onNewIntent`, routing
the extra into the coordinator below. This is the only manifest change the redesign requires.

**Deep-link plumbing.** An `@ActivityRetainedScoped HomeNavigator` holds a
`StateFlow<HomeRequest?>`, where `HomeRequest` is `NowPlaying` or `Sync`. `MainActivity` publishes
a request from `onCreate` / `onNewIntent`, and `CollectionDetailScreen`'s track tap publishes
`NowPlaying`. `JellyNav` observes the flow, performs the navigation, then calls `consume()`,
which sets the flow back to `null`. It must be **consume-once**: a flow that retained its last
value would re-force page 1 on every recomposition and on every return to `HOME`. `HomeNavigator`
holds no Android types and is JVM-tested (§13.1).

### 4.4 Back and dismiss semantics

- `HOME` is the start destination and is never popped.
- Every pushed screen dismisses to whatever is beneath it. Depth is at most 3
  (`HOME → browse → browse/{type} → collection/{id}`), and dismissing from a collection returns
  to the list the user came from, whichever it was.
- After authentication, `onboarding` and `onboarding/first-pick` are popped inclusively:
  a dismiss must never return the user to sign-in.
- Sign-out navigates to `onboarding` with `popUpTo(0) { inclusive = true }`, as today.
- Playing a track from `collection/{id}` does **not** push a screen. It pops back to `HOME` and
  requests page 1 through `HomeNavigator`. The library's scroll position survives, because
  `HOME` was never popped.

---

## 5. Screens

Each screen is specified as: route · purpose · primary action · sections · empty, loading and
error states · ViewModel. Every list is a `TransformingLazyColumn` inside a `ScreenScaffold`
wired to its scroll state, so rotary scrolling and the `ScrollIndicator` come for free
(§8.1, `JellyList`).

### 5.1 Onboarding — `onboarding`

**Purpose.** Connect the watch to a Jellyfin server.
**Primary action.** Per state: Connect · Retry · Trust · New code.
**Flows.** Unchanged from `OnboardingScreens.kt`: server entry → validating → Quick Connect code
→ (certificate prompt) → authenticated. Quick Connect only, exactly as shipped — `OnboardingUiState`
has no password state. The state machine, the `OnboardingViewModel`, and the certificate
fingerprint display are untouched.

> **Contradiction noted, not resolved here.** The code and `DEC-013` both refer to a "DEC-010"
> that establishes Quick Connect as the only auth flow, but no DEC-010 entry exists in
> `specs/DECISION_LOG.md`, and the log's DEC-002 (LOCKED) still records `AuthenticateByName` as a
> fallback that the watch does not implement. This redesign changes nothing about authentication
> and takes no position; the gap is reported so it can be closed separately.
**Restyling only.** `AuroraChip` becomes a Wear M3 `Button`; the single primary action of each
state becomes an `EdgeButton` carrying the gradient. `GradientText` is **kept** for the wordmark
and the Quick Connect code — these are the two places the brand should shout, and both are
non-interactive (§9.2).
**Error state.** The existing per-state `@StringRes` errors are kept; they are already classified,
not raw. The `recoveredCorruptStore` notice is unchanged.
**After authentication.** Navigates to `onboarding/first-pick`, not to `HOME` (§5.2).

### 5.2 First pick — `onboarding/first-pick` (new)

**Purpose.** Close `PLAN.md` §6's missing last step: end onboarding by putting something on the
watch. Fixes §1.9.
**Primary action.** **Keep on watch** (`EdgeButton`), enabled once a row is selected.
**Shown when.** Immediately after a successful authentication **and** only when no collection is
pinned. Skipped entirely when something is already pinned (re-onboarding after a server change).
It is reached only from onboarding's completion and is popped inclusively on leaving, so it can
never reappear within a session and no dismiss can return to it. **No new persisted setting is
introduced**: showing it again after a later sign-out and sign-in onto an empty watch is correct
behaviour, not a bug.
**Sections.**
1. `ListHeader` — "Put something on your watch".
2. Up to 5 suggested collections: the user's playlists first (`CollectionType.PLAYLIST`, server
   order), padded with albums if fewer than 5. Each row is a `CollectionRow` with a
   `RadioButton`-style single selection. Selecting a row only selects it.
3. A final, visually distinct row (`outlinedButtonColors`, trailing arrow): **Random mix**. It is
   a *navigation* row, not a selectable one — tapping it leaves this screen for
   `collection/__random__`, popping first-pick inclusively so a dismiss from there lands on
   `HOME`.
**Empty state.** Server has no playlists and no albums: a single line, "Nothing to suggest yet",
and the `EdgeButton` becomes **Skip**.
**Loading state.** `Placeholder` + `placeholderShimmer` rows while the first refresh runs.
**Error state.** `ErrorCard` with the classified `UiError`; `EdgeButton` becomes **Skip** so the
user is never trapped.
**Dismissal.** Swipe-dismiss is equivalent to Skip: first-pick is popped and `HOME` is shown.
**ViewModel.** `FirstPickViewModel` — observes `LibraryRepository.observeCollections`, delegates
pinning to `KeepOnWatchCoordinator` exactly as collection detail does.

### 5.3 Home page 0 — Library

**Purpose.** "What is on my watch, and how is it doing?" **Only that.** Browsing the server is a
different question and lives behind `browse`.
**Primary action.** **Add music** (`EdgeButton`) → `browse`.
**Sections.**

1. **Status card** (`TitleCard`, first item). Left: the quota gauge (§10) at 44 dp. Right: the
   **aggregate status line** (§6.6) — e.g. "Downloading 42 of 310", "Waiting for Wi-Fi",
   "6 collections on your watch". Tapping it opens `sync`. This is the single answer to "how is
   my music doing", and it is above the fold.
2. **Kept collections.** One `CollectionRow` per pinned collection, ordered: anything not settled
   first (by §6.3 priority), then alphabetically. Title, status sentence as the secondary label,
   status glyph or a determinate `CircularProgressIndicator` as the trailing slot. Tap →
   `collection/{id}`.
3. **Settings**, as the last row, `filledTonalButtonColors`, with a gear icon.

**On placing Settings last.** §1.1's complaint is that Settings sat behind *every song*. This list
contains only pinned collections — in practice single digits, and the user chose every one of
them. Settings at the end of a short, self-authored list is the standard Wear pattern. If this
list ever grows long, the fix is that the user pinned too much, and the status card at the top
says so.

**Empty state (nothing pinned).** The status card is omitted (there is nothing to report). In its
place, a centred `EmptyState`: "Nothing on your watch yet" plus one line of explanation. The
Settings row still renders beneath it, and the `EdgeButton` is still **Add music**.
**Loading state.** Room is the source, so the list is populated offline on the first frame. A
first-run install shows `Placeholder` rows only until the first `observePinned` emission.
**Error state.** Home never shows a refresh error: it reads Room, which cannot fail meaningfully
here. A failed background refresh surfaces on `sync` and in the log, never as a Home banner.
**ViewModel.** `HomeViewModel` (new) — combines `CollectionDao.observePinned()`, the new
`CollectionTrackDao.observeStateCounts()`, `SyncScheduler.observeSyncBlocker()`,
`NetworkAwareness.isUnmetered`, `NetworkAwareness.isCharging` and `SettingsRepository.settings`,
maps each collection through `SyncStatusMapper` (§6.3) and derives the aggregate line by the rule
in §6.6.

### 5.4 Home page 1 — Now Playing

**Purpose.** "What is playing, and control it."
**Primary action.** Play/pause — the 56 dp centre button, the only gradient control on the page.
**Content.** Unchanged from `PlayerScreen.kt`: faded artwork under a radial scrim, title, artist,
`AuroraSeekBar` with `m:ss` readouts, transport row, shuffle/repeat row, crown = volume.
**What changes.**
- It is a pager page, not a route. Its `ScreenScaffold` moves under `HorizontalPagerScaffold`.
- The rotary handler keeps volume. Because the page is not a list, there is no conflict with
  `TransformingLazyColumn`'s rotary scrolling — but page 0 *is* a list, and both pages are alive
  in the pager. **The rotary focus must follow the settled page**: the `FocusRequester` on Now
  Playing requests focus only while `pagerState.settledPage == 1`, and page 0's list requests it
  only while `settledPage == 0`. This is on-device verification check 1b (§13.3).
- The empty state ("no queue") points at page 0 with words rather than at a route that no longer
  exists.
**Loading state.** `PlayerController` connects asynchronously; until the first snapshot, the
existing default `PlayerUiSnapshot()` renders the empty state. Acceptable and unchanged.
**Error state.** None. Playback failures surface as the Bluetooth output guard (DEC-006), which
is unchanged.
**ViewModel.** `PlayerViewModel`, unchanged.

### 5.5 Browse chooser — `browse`

**Purpose.** "What kind of thing am I looking for?" This replaces the tab strip (§1.1) with a
destination, so the four categories stop competing with content for the same list.
**Primary action.** None — it is a chooser; every row is a peer.
**Sections.** Five rows: Playlists · Albums · Artists · Songs · Random mix. The first four push
`browse/{type}`; Random mix pushes `collection/__random__`.
**Empty / loading / error.** None — the rows are static.
**ViewModel.** None.

> **Refinement versus the original sketch.** The sketch said `browse/{type}` replaces the tab
> strip. Adding a chooser costs one tap on the "add music" path and buys a Home screen that
> answers exactly one question. Given that the stated problem is confusion, clarity wins over the
> tap. This is Open Question **OQ-1**.

### 5.6 Browse list — `browse/{type}`

**Purpose.** "Which playlist / album / artist / song?"
**Primary action.** None — a list of peers.
**Sections.** One flat list. For collection types: `CollectionRow` with cover art, name, and
`formatBytes(estimatedBytes)` + track count as the secondary label; pinned collections carry the
status sentence instead, so a user browsing sees at a glance what is already kept. Tap →
`collection/{id}`.
**Songs list.** Row tap behaviour is a single deterministic rule, replacing today's
`onClick = {}` (§1.10):
- `state == READY` → play it now as a one-track queue, pop to `HOME`, request page 1.
- otherwise, `albumId != null` → open `collection/{albumId}`.
- otherwise → the row is **rendered disabled**, not silently inert. Its track-status sentence
  (§6.5) explains why — "Not downloaded", "Can't be played on this watch", and so on.
**Empty state.** "Nothing here yet" plus, when the last refresh failed, the `ErrorCard` — a
genuinely empty library and an unreachable server must not look identical.
**Loading state.** `Placeholder` rows during the first refresh of a never-cached type.
**Error state.** `ErrorCard` as the first item, with a Retry action. Cached content stays
visible beneath it: a failed refresh never blanks the list.
**ViewModel.** `BrowseViewModel` (new) — takes `type` from `SavedStateHandle`; it is
`LibraryViewModel` minus the tab state, minus the random-sync state machine, and with
`refreshError` typed as `UiError?`.

### 5.7 Collection detail — `collection/{collectionId}`

**Purpose.** "What is in this, and is it on my watch?"
**Primary action.** `EdgeButton`: **Keep on watch** when not pinned, **Remove from watch** when
pinned. One button, two labels, the toggle semantics of today's `setPinned` unchanged. This
replaces the "Sync to watch" gradient pseudo-CTA (§1.2) with a labelled imperative whose label
always describes what the tap will do.
**Sections.**
1. Hero: cover art (first track's image, or the gradient fallback) with the name, total size and
   track count, exactly as today.
2. Status line: the collection's `CollectionSyncStatus` as a sentence, with a determinate
   `LinearProgressIndicator` beneath it while `Downloading`.
3. Unplayable notice, when `unsupported > 0`: "*n* tracks can't be played on this watch" —
   stated once, at collection level, not repeated as an alarm on every row (DEC-004; §6.4).
4. Track list. `TrackRow`: cover art when the track has an `imageTag`, otherwise its
   `indexNumber`; then title, artist, and a status glyph with an accessible description.
   `READY` tracks play on tap (pop to `HOME`, page 1); every other state renders the row disabled
   with its track-status sentence (§6.5) visible.
**Empty state.** "No tracks" — unchanged, plus the `ErrorCard` when the last refresh failed.
**Loading state.** `Placeholder` rows until the first `observeTracks` emission.
**Error state.** `ErrorCard`, replacing `collection_detail_refresh_error` and its `%1$s`
(§1.4). That string resource is deleted.
**Consent.** The `KeepOnWatchCoordinator.prompt` renders as a `ConsentDialog` **over** the screen
(§8.4). The `EdgeButton` stays where it is and stays visible behind the dialog. This is the fix
for §1.5.
**ViewModel.** `CollectionDetailViewModel` as it exists on the pre-merge development work, with
`refreshError: StateFlow<UiError?>` instead of `StateFlow<String?>`, plus a
`StateFlow<CollectionSyncStatus>`.

### 5.8 Collection detail — Random mix variant

Same route, same ViewModel; selected when `collection.localOnly == true`.

**Purpose.** "How many random songs do I want on my watch?"
**Primary action.** `EdgeButton`: **Keep on watch** when not yet created, **Reshuffle** when it
exists.
**Sections.**
1. Hero: "Random mix" over the gradient, with the current size and total bytes.
2. **Size chooser**: a `RadioButton` group — 50 · 100 · 200 · 500. Selecting a size only records
   the choice. **It does not pin and it does not sync.** Committing is the `EdgeButton`. This is
   the fix for §1.2's third verb: today a size pill pins *and* syncs in one tap, which is why
   nobody can predict what the pills do.
3. The track list of the current draw, when one exists, as in §5.7.
**Empty state.** No draw yet: sizes shown, list absent, `EdgeButton` = **Keep on watch**.
**Error state.** `ErrorCard` — replaces `RandomSyncState.Failed(e.message)` (§1.4).
**Reshuffle semantics.** Unchanged from `LibraryRepository.syncRandomSongs`: the draw is replaced
and orphaned tracks are purged by refcount on the next pass. The screen states this in one line
before the user taps: "Reshuffling replaces the current mix."
**Consent.** Identical `ConsentDialog` to §5.7. The `RandomSyncState` sealed class and its
hand-rolled battery-then-network ladder in `LibraryViewModel` are **deleted**; the coordinator
already implements exactly that ladder, once.

### 5.9 Sync — `sync`

Replaces the Downloads screen.

**Purpose.** "Why isn't my music here yet?" — a *diagnostic*, not an inventory.
**Primary action.** `EdgeButton`, label always **Sync now** — the label names the action, never
the state (principle 3). It is disabled while `SyncState` is `QUEUED` or `SYNCING`, and enabled
in `IDLE`, `PAUSED` and `FAILED`: a paused pass resumes on its own, but a user who wants it now
must not be blocked from asking. The *state* — "Queued", "Syncing…", "Paused — will resume" —
is a sentence in section 2, not a button label.
**Sections.**
1. Quota gauge (§10) — used of **quota**, with free disk as a caption.
2. **Status** — the aggregate status line (§6.6), plus the current `SyncState` sentence. When
   **both** the Wi-Fi and the charger constraint are unmet, this section names **both**; a
   collection row on Home shows only the higher-priority one (§6.3), because a row has space for
   one sentence and this screen does not have that constraint.
3. **Downloading** — one row per track in `TO_DOWNLOAD` / `DOWNLOADING`, with a determinate
   `LinearProgressIndicator` and a percentage.
4. **Waiting for space** — `PENDING_BUDGET` tracks, headed by an actionable line: "Increase the
   quota or remove a collection", with a row linking to `settings/quota`. Replaces the bare
   "Budget" badge (§1.3).
5. **Needs attention** — `ERROR` tracks with their classified `UiError` sentence, and a
   **View log** row → `settings/log`.
**Removed from the old Downloads screen.**
- The "Ready" list of every downloaded track. It duplicated the library, and it was the reason
  this screen scrolled forever. Downloaded content is discoverable through its collection.
- The per-track **remove** button. In a refcounted model (`CollectionTrack`, REQ-006) deleting a
  single track is incoherent: the next sync pass re-adds it, because the collection that
  references it is still pinned. Removal is done by removing a collection from the watch. This is
  a behaviour change and is Open Question **OQ-2**.
**Empty state.** "Everything is on your watch", plus the quota gauge, plus the `EdgeButton`.
**Loading state.** None needed — every source is a Room or Media3 flow.
**Error state.** A failed pass shows an `ErrorCard` with the classified reason and a **View log**
action. The `SyncOutcome.PAUSED` case is **never** shown as a failure (DEC-005).
**Consent.** `ConsentDialog`, same component as everywhere else (§1.5's second half).
**ViewModel.** `SyncViewModel`, renamed from `DownloadsViewModel`, with the hand-rolled
battery/metered ladder deleted in favour of `KeepOnWatchCoordinator`.

### 5.10 Settings — `settings`

**Purpose.** Change how the app behaves.
**Primary action.** **None.** Settings is a list of peers; forcing an `EdgeButton` onto it would
mean promoting one setting or, worse, sign-out. This is a deliberate, stated exception to
principle 4.
**Sections.**
1. `ListHeader` "Settings".
2. **Account** — a non-interactive `Card` with the user name and server name. Not a disabled
   chip pretending to be tappable (§1.10).
3. **Sync** — `ListSubHeader` then: `SwitchButton` "Only while charging" · `SwitchButton` "Only
   on Wi-Fi" · an in-place Wear M3 `Stepper` labelled "Check every *n* hours", cycling the
   existing 6 / 12 / 24 / 48 / 168 values. No dialog: it is one value with five stops.
4. **Storage** — one row "Storage" showing used-of-quota, → `settings/quota`.
5. **Sync log** — one row → `settings/log`.
6. **Sign out** — last row, `error` container, opens a `ConfirmationDialog` naming exactly what
   is erased. Today's inline spinner-replaces-button behaviour is replaced by a disabled row plus
   the dialog's own progress.
**Empty / loading / error.** None applicable; every value is a DataStore or Room read.
**ViewModel.** `SettingsViewModel`, with `SyncLogEntry.outcomeLabel` (the dead unicode glyph
property) deleted.

### 5.11 Storage quota — `settings/quota`

**Purpose.** "How much space may this app use?"
**Primary action.** **None.** The value is written on every change and the swipe-dismiss gesture
returns to Settings; a "Done" button would be a second way to do what the gesture already does.
**Content.** A Wear M3 `Stepper` in 1 GB increments with a `LevelIndicator` on the bezel showing
the quota against total disk, the quota value in the centre, and two captions: bytes currently
used, and free disk. Bounds are `SettingsViewModel.setQuotaBytes`'s existing clamp
(512 MB … available + used).
**Warning state.** When the chosen quota is **below** current usage, an inline line says so and
names the consequence: "Sync will remove content to fit." No silent truncation.
**ViewModel.** `SettingsViewModel`, shared.

### 5.12 Sync log — `settings/log`

**Purpose.** "What actually happened?" — and the one place raw technical text is allowed (§7.4).
**Primary action.** None.
**Content.** The existing 10 most recent `SyncLogEntity` rows: time, `+added −removed`, outcome
icon *with* an outcome word, and `message` in full (wrapped, `FadingExpandingLabel` for long
text). This is where `UnknownHostException` may legitimately appear.
**Empty state.** "No sync has run yet."
**ViewModel.** `SettingsViewModel`, shared.

---

## 6. One vocabulary

### 6.1 The verb

The act is **"Keep on watch"**. It is used for:
- the collection detail primary action (**Keep on watch** / **Remove from watch**);
- the first-pick primary action;
- the Home section, if it is ever labelled;
- the French copy: **« Garder sur la montre »** / **« Retirer de la montre »**.

**Retired words.** "Sync to watch" (the pin toggle, §1.2) and "Pin". "Sync" survives in exactly
one meaning: the *process* of making the watch match the server — hence the `sync` screen and its
**Sync now** button. Nothing else in the UI may use the word "sync" as a verb the user performs
on a collection.

### 6.2 `CollectionSyncStatus`

A sealed interface in `ui/status/`, with no Android dependencies:

| Case | Payload | Rendered sentence (en) |
|---|---|---|
| `NotOnWatch` | — | "Not on your watch" |
| `WaitingForWifi` | — | "Waiting for Wi-Fi" |
| `WaitingForCharger` | — | "Waiting for the charger" |
| `WaitingForConsent` | `prompt: ConsentPrompt` | "Waiting for your OK" |
| `Downloading` | `completed: Int, total: Int` | "Downloading *c* of *t*" — and, when `total == 0`, "Preparing…" |
| `WaitingForSpace` | `deferred: Int` | "Waiting for space (*n* tracks)" |
| `OnWatch` | `trackCount: Int` | "On your watch · *n* tracks" — and, when `trackCount == 0`, "Nothing in this collection" |
| `NeedsAttention` | `failed: Int` | "*n* tracks didn't download" |

Eight cases. Each renders as a **sentence**; a glyph may sit beside it (principle 3). The same
eight cases render on Home rows, the Home status card, browse rows, and collection detail. They
replace: the bare `Check` pin badge, the `"Budget"` label, the six untitled `TrackStatusIcon`
tints in their collection-level role, and all of `SyncState`'s leakage into screen copy.

`Downloading`'s `total == 0` case covers a collection pinned while offline, whose track list has
not been fetched yet. It is a rendering rule of the same status, recorded here so it is not
hidden in a string file.

### 6.3 `SyncStatusMapper`

A pure object with one function, no Android imports, fully JVM-testable:

```
SyncStatusMapper.map(input: CollectionSyncInput): CollectionSyncStatus
```

`CollectionSyncInput` carries only primitives and enums:

| Field | Source |
|---|---|
| `pinned: Boolean` | `CollectionEntity.pinned` |
| `total, ready, unsupported, downloading, toDownload, pendingBudget, failed: Int` | `CollectionTrackDao.observeStateCounts()` |
| `consentPending: ConsentPrompt?` | `SyncScheduler.observeSyncBlocker()` |
| `isUnmetered: Boolean` | `NetworkAwareness.isUnmetered` |
| `isCharging: Boolean` | `NetworkAwareness.isCharging` |
| `unmeteredOnly, requiresCharging: Boolean` | `SettingsRepository.settings` |

Derived inside the mapper:
`settled = ready + unsupported` · `outstanding = toDownload + downloading` ·
`hasWork = outstanding > 0 || total == 0`.

**Priority order — first match wins.** The order is the specification; the implementation is a
`when` in exactly this sequence.

| # | Condition | Result | Why here |
|---|---|---|---|
| 1 | `!pinned` | `NotOnWatch` | The collection is not kept, whatever its tracks are doing for someone else. |
| 2 | `hasWork && consentPending != null` | `WaitingForConsent(prompt)` | An explicit question is pending. It outranks every inferred condition, because the user can answer it right now. |
| 3 | `hasWork && unmeteredOnly && !isUnmetered` | `WaitingForWifi` | The most frequently unmet constraint, and the one users act on. |
| 4 | `hasWork && requiresCharging && !isCharging` | `WaitingForCharger` | Below Wi-Fi deliberately: on a watch, docking is the last resort, and this message must read as "tonight", not as an alarm. |
| 5 | `outstanding > 0` | `Downloading(settled, total)` | Bytes are moving. Progress outranks everything settled. |
| 6 | `total == 0` | `Downloading(0, 0)` → "Preparing…" | Pinned but not yet enumerated. |
| 7 | `pendingBudget > 0` | `WaitingForSpace(pendingBudget)` | Above `NeedsAttention`: it is self-describing, recoverable, and usually the cause of the bigger number. |
| 8 | `failed > 0` | `NeedsAttention(failed)` | Genuine failures, once nothing else explains the state. |
| 9 | otherwise | `OnWatch(settled)` | Settled. |

Two consequences worth stating outright:

- **Errors never hide behind progress, and progress is never hidden by errors.** `ERROR` is not an
  outstanding state, so a collection stuck at 38 of 40 with 2 failures has `outstanding == 0` and
  falls through to rule 8. It reads "2 tracks didn't download", not "Downloading 38 of 40"
  forever.
- **When both Wi-Fi and charger are unmet**, a row shows Wi-Fi. The `sync` screen shows both
  (§5.9 section 2). A row has space for one sentence; the diagnostic screen does not have that
  constraint.

### 6.4 Unsupported codecs are never "Needs attention"

`UNSUPPORTED` counts into `settled`, never into `failed`. DEC-004 means these tracks are
downloaded and permanently unplayable on this watch, with no user action available in v1. Raising
"Needs attention" for them would be a nag that can never be cleared. They are reported once, at
collection level, as an unplayable notice (§5.7 section 3), and per-track by glyph and sentence.
This is Open Question **OQ-3**.

### 6.5 Track-level status

`CollectionSyncStatus` describes a *collection*. A track row shows its `TrackState`, and the
sentences below are the track-level half of the same vocabulary. `TrackState` itself is
**unchanged** — this is a rendering table, not a data-model change.

| `TrackState` | Sentence (en) | Row |
|---|---|---|
| `READY` | "On your watch" | enabled — tap plays |
| `TO_DOWNLOAD` | "Not downloaded" | disabled |
| `DOWNLOADING` | "Downloading… *n* %" | disabled, with a determinate `LinearProgressIndicator` |
| `PENDING_BUDGET` | "Waiting for space" | disabled |
| `UNSUPPORTED` | "Can't be played on this watch" | disabled |
| `ERROR` | "Didn't download" | disabled |

The existing `TrackState.statusDescriptionRes()` and its six string resources are **promoted**
from accessibility-only content descriptions to visible labels, with copy revised to the wording
above. `StatusIconTest`, which already asserts the mapping is exhaustive, is kept.

### 6.6 The aggregate status line

Home's status card (§5.3) and the `sync` screen's status section (§5.9) show one line covering all
pinned collections. The rule:

1. Map every pinned collection through `SyncStatusMapper`.
2. Take the **highest-priority** resulting status by §6.3's order, ignoring `NotOnWatch` (nothing
   pinned maps to it) and ignoring `OnWatch`.
3. Render that status once, with its numeric payload **summed across the collections that
   produced it** — so five collections downloading show "Downloading 42 of 310", not five lines.
4. If every pinned collection is `OnWatch`, the line reads "*n* collections on your watch".
5. If nothing is pinned, there is no line and no status card.

### 6.7 The three additive inputs the status vocabulary needs

The mapper needs three things the codebase does not expose. All three are additive and read-only,
none changes existing behaviour, and **no other data source is introduced by this redesign** —
no new DataStore key, no new table, no new column.

**1. A grouped count query on `CollectionTrackDao`.**

```
SELECT ct.collectionId AS collectionId, t.state AS state, COUNT(*) AS count
FROM collection_tracks ct
JOIN tracks t ON t.id = ct.trackId
GROUP BY ct.collectionId, t.state
```

returned as `Flow<List<CollectionStateCount>>`. One query feeds every row on Home and every
status on browse, instead of N per-collection queries. It is a new `@Query` on an existing DAO
with a new projection data class — **no schema change, no Room migration** (DEC-009 is not
engaged).

**2. `SyncScheduler.observeSyncBlocker(): Flow<SyncBlocker?>`.**
`SyncScheduler._blocker` is already a `MutableStateFlow<String?>` fed by `ReconWorker` with the
`WireSyncBlockers` constants; only the snapshot accessor `currentSyncBlocker()` is public.
Exposing `asStateFlow()` and mapping the two wire constants to a typed
`SyncBlocker { MeteredConsent, LowBatteryConsent }` is a two-line addition.

> **Why not `KeepOnWatchCoordinator.prompt`?** Its KDoc is explicit: it is *deliberately not a
> singleton* — "the prompt belongs to the screen that raised it". Home therefore cannot observe
> another screen's coordinator. The **dialog** comes from the screen-local coordinator; the
> **status** comes from the scheduler's process-wide blocker. Conflating them would either make
> the coordinator a singleton (breaking its stated contract) or make Home blind to a pass blocked
> from elsewhere.

**3. `NetworkAwareness.isCharging: StateFlow<Boolean>`.**
`_isCharging` already exists and is already maintained by the `ACTION_BATTERY_CHANGED`
`callbackFlow`. Publishing `asStateFlow()` alongside the existing `isUnmetered` is a one-line
addition.

---

## 7. Errors

### 7.1 `UiError`

A sealed interface in `ui/error/`, no Android imports:

| Case | Meaning |
|---|---|
| `NoNetwork` | The watch has no usable connection. |
| `ServerUnreachable` | There is a connection, but the server did not answer. |
| `SessionExpired` | The server rejected the credentials (401). |
| `NotPermitted` | The account may not do this (403). |
| `NotFoundOnServer` | The item is gone server-side (404). |
| `ServerError` | The server answered with a failure (5xx, or any other unexpected status). |
| `CertificateNotTrusted` | TLS could not be validated. |
| `StorageFull` | The budget or the disk cannot hold this. |
| `Unexpected` | Anything unclassified. |

### 7.2 `UiAction`

`Retry` · `SignIn` · `OpenStorage` · `TrustCertificate` · `ViewLog` · `None`. At most **one**
action per error. A watch screen cannot afford a choice of remedies.

| `UiError` | Sentence (en) | Action |
|---|---|---|
| `NoNetwork` | "No connection" | `Retry` |
| `ServerUnreachable` | "Can't reach your server" | `Retry` |
| `SessionExpired` | "You've been signed out" | `SignIn` |
| `NotPermitted` | "Your account can't do this" | `ViewLog` |
| `NotFoundOnServer` | "This is gone from the server" | `None` |
| `ServerError` | "Your server returned an error" | `ViewLog` |
| `CertificateNotTrusted` | "This server's certificate isn't trusted" | `TrustCertificate` |
| `StorageFull` | "Not enough space" | `OpenStorage` |
| `Unexpected` | "Something went wrong" | `ViewLog` |

### 7.3 `ErrorMapper`

```
ErrorMapper.map(t: Throwable, hasNetwork: Boolean): UiError
```

Pure, JVM-testable. The second argument exists because an `IOException` alone cannot distinguish
"the watch is offline" from "the server is down" — the network state is known separately, from
`NetworkAwareness`, and mixing the two produced the useless "Refresh failed: UnknownHostException"
of §1.4.

| Throwable | `UiError` |
|---|---|
| `org.jellyfin.sdk.api.client.exception.InvalidStatusException`, `status == 401` | `SessionExpired` |
| … `status == 403` | `NotPermitted` |
| … `status == 404` | `NotFoundOnServer` |
| … `status in 500..599`, or any other status | `ServerError` |
| `javax.net.ssl.SSLHandshakeException`, `java.security.cert.CertificateException`, or any cause chain containing one | `CertificateNotTrusted` |
| `java.net.UnknownHostException`, `java.net.ConnectException`, `java.net.SocketTimeoutException`, any other `java.io.IOException` — with `hasNetwork == false` | `NoNetwork` |
| the same family with `hasNetwork == true` | `ServerUnreachable` |
| `kotlinx.coroutines.CancellationException` | never mapped — rethrown by the caller, as everywhere else in this codebase |
| anything else | `Unexpected` |

`StorageFull` is **not** produced by `ErrorMapper`. It is raised by the budget path
(`BudgetPlanner.plan`, `DeviceStorage`), which knows it without an exception.

### 7.4 Presentation rules

1. **No screen composes `Throwable.message`.** `refreshError: String?` becomes
   `refreshError: UiError?` on every ViewModel that has one. `RandomSyncState.Failed(String)` is
   deleted with the rest of `RandomSyncState`.
2. Errors render as an `ErrorCard` **inside** the content, above cached data. Cached content is
   never blanked by a refresh failure.
3. `SessionExpired` is the one error that changes navigation: it pops to `onboarding`, because
   nothing else in the app can work.
4. `SyncLogEntity.message` keeps the raw technical text, unchanged, and `settings/log` displays it
   in full. This is the deliberate escape hatch for a self-hoster, and the only one.
5. `SyncTrace` logging is unchanged.

---

## 8. Components

The rule is that a component exists because a *role* exists, and no two roles share one component
merely because they are the same height.

### 8.1 Added

| Component | Built on | Role |
|---|---|---|
| `JellyList` | `TransformingLazyColumn` + `rememberTransformationSpec()` + `ScreenScaffold(scrollState=…)` | The only list container in the app. Guarantees rotary scrolling, the `ScrollIndicator` and the Wear scaling transform on every screen — fixing §1.10's dead crown in one place. |
| `PrimaryAction` | `EdgeButton` with `containerPainter = BrushPainter(auroraGradient())` | The single primary action of a screen. The only interactive control carrying the gradient. |
| `CollectionRow` | Wear M3 `Button`, `filledTonalButtonColors` | Cover art + name + status sentence + trailing status. Used on Home, browse and first-pick. |
| `TrackRow` | Wear M3 `Button`, `filledTonalButtonColors` | Title + artist + status. Disabled state is visibly disabled. |
| `StatusLabel` | `Text` | Renders a `CollectionSyncStatus` as its sentence. |
| `StatusGlyph` | `Icon` | Renders a `CollectionSyncStatus` or a `TrackState` as an icon **with** a content description. Absorbs today's `TrackStatusIcon`; `statusDescriptionRes()` and its existing test are kept. |
| `NowPlayingChip` | Wear M3 `Button`, compact, 48 dp | Shown as the first item of `browse`, `browse/{type}`, `collection/{id}`, `sync` and `settings` **only while a queue exists**. One line: a small play-state icon plus the current title. Tapping pops to `HOME` and requests page 1. Never shown on `HOME` (one swipe away) or during onboarding. |
| `QuotaGauge` | `CircularProgressIndicator(startAngle/endAngle)` | Used **of quota**, always. See §10. |
| `ErrorCard` | Wear M3 `Card` | A `UiError` sentence plus at most one `UiAction`. |
| `ConsentDialog` | Wear M3 `AlertDialog` | Renders a `ConsentPrompt`. One component, one look, both prompts, every screen. |
| `EmptyState` | column | Icon + one sentence + optional hint. |
| `CountChoiceList` | Wear M3 `RadioButton` rows | The Random mix size (50/100/200/500). Selection only; commitment is the `EdgeButton`. |

### 8.2 Deleted

| Deleted | Replaced by | Reason |
|---|---|---|
| `AuroraChip`, `AuroraChipVariant` | `CollectionRow`, `TrackRow`, Wear M3 `Button`, `SwitchButton`, `Card` | One 52 dp control for eight roles is §1.8. |
| `AuroraButton` | Wear M3 `Button`/`EdgeButton` with `containerPainter` | The gradient container is first-party in 1.6.2. |
| `SectionHeader` | `ListHeader` / `ListSubHeader` | Takes the dead chevron (§1.10) with it. |
| `GradientProgressRing` | `CircularProgressIndicator` / `LinearProgressIndicator` | First-party, determinate, accessible. |
| `StorageGauge` | `QuotaGauge` | One component that meant two things (§1.7). |
| `SmallPill`, `AuroraTabRow` (private to `LibraryScreen`) | `browse` + `browse/{type}` | The tab strip is gone. |
| `PromptCard` (Library) and the two inline prompt columns (Downloads) | `ConsentDialog` | Two looks for one question, and a control that replaced itself (§1.5). |
| `RandomSyncState` and its ladder in `LibraryViewModel` | `KeepOnWatchCoordinator` | The coordinator already implements this ladder, correctly, once. |
| `SyncLogEntry.outcomeLabel` | `StatusGlyph` + an outcome word | Dead unicode glyphs. |
| `LibraryScreen`, `LibraryViewModel`, `LibraryUiState`, `LibraryTab` | `HomeViewModel` + `BrowseViewModel` | §1.1. |
| `DownloadsScreen` | `SyncScreen` | §5.9. |

### 8.3 Kept unchanged

`AuroraSeekBar` (and `AuroraSeekBarTest`) · `GradientText` (onboarding wordmark and Quick Connect
code only) · `JellyImage` · `formatBytes` / `formatDuration` · `TrackState.statusDescriptionRes()`
(and `StatusIconTest`) · the whole `ui/theme/` package.

### 8.4 Consent, precisely

`ConsentDialog` is a Wear M3 `AlertDialog` driven by `KeepOnWatchCoordinator.prompt`:

- `ConsentPrompt.Battery` → "Battery is low. Download anyway?" · Confirm / Cancel
- `ConsentPrompt.Metered` → "No Wi-Fi. This will use your phone's connection and be slow.
  Continue?" · Confirm / Cancel

Confirm calls `coordinator.confirm()`, Cancel calls `coordinator.dismiss()`. The ladder — battery
first, then network — is the coordinator's, unchanged. The dialog **overlays**; the control that
raised it stays in place and stays visible. Dismissing leaves the collection pinned and its status
becomes `WaitingForConsent`, so a refused prompt is never a dead end.

---

## 9. The gradient budget

### 9.1 The rule

Exactly **one control container per screen** may be filled with the brand gradient, and it is that
screen's primary action. No list row, no toggle, no navigation item, no secondary button, no
selected tab (there are no tabs) may carry it. That is the whole rule for *containers*.

### 9.2 Gradient as paint, not as a container

The gradient additionally paints four surfaces that are **not control containers**, where it
carries identity or magnitude rather than affordance:

1. the JellyWrist wordmark on onboarding (`GradientText` — glyph fill);
2. the Quick Connect code (`GradientText` — glyph fill);
3. the `QuotaGauge` arc (magnitude);
4. the `AuroraSeekBar`'s elapsed portion (magnitude).

The seek bar is draggable, so the distinction matters: what the gradient paints there is the
*elapsed magnitude*, not the control's surface. The same test settles every future case — if the
gradient would fill the tappable body of a control that is not the screen's primary action, it is
wrong.

> **Refinement versus the original sketch.** The sketch said "exactly one role". Taken literally
> that would strip the gradient from the wordmark, the Quick Connect code, the gauge and the seek
> bar — the four places DEC-011 and DEC-012 put the identity in the first place, and none of which
> is a control container competing with a call to action. The sharpened rule keeps the discipline
> where it matters (§1.8: six competing gradient CTAs) while preserving the brand. This is Open
> Question **OQ-5**.

### 9.3 Visual rank

Four tiers, so that rank is legible without reading:

| Tier | Control | Fill |
|---|---|---|
| Primary | one per screen — `PrimaryAction` (`EdgeButton`) everywhere, except Now Playing, whose primary action is its 56 dp circular play/pause | gradient |
| Standard | list rows, `CollectionRow`, `TrackRow`, `SwitchButton`, `NowPlayingChip` | `filledTonalButtonColors` |
| Quiet | the Settings row, "View log", the first-pick Random mix row, other secondary navigation | `outlinedButtonColors` |
| Inert | account card, log rows, notices | `Card`, no click target |

Heights vary by role rather than being uniformly 52 dp: `NowPlayingChip` is a compact 48 dp,
standard rows follow the Wear M3 `Button` defaults, and the Now Playing play/pause stays 56 dp.
**Every touch target is at least 48 dp**; there is no exception.

---

## 10. The storage gauge has one meaning

**Used of quota. Everywhere. Always.**

`QuotaGauge(usedBytes, quotaBytes)` has no `totalBytes` parameter, so the §1.7 mistake cannot be
made again. Free disk is genuinely useful information, but it is a *caption*, never the
denominator:

| Surface | Arc | Caption |
|---|---|---|
| Home status card | used / quota | — |
| `sync` | used / quota | "*n* free on the watch" |
| `settings/quota` | used / quota, plus a `LevelIndicator` showing quota against total disk | "*n* used · *n* free" |

The one place total disk appears as a *magnitude* is the quota stepper's `LevelIndicator`, where
the user is deciding how much of the disk to claim — which is exactly the question total disk
answers.

---

## 11. Theme and DEC-012 continuity

Nothing in `ui/theme/` changes. Specifically preserved:

- `Color.kt` — the Aurora palette, unchanged, including `GradientStart` / `GradientEnd`.
- `Theme.kt` — `jellyWristColorScheme()`, `JellyWristTheme`, `auroraGradient()`,
  `auroraScrimBottom()`, `auroraScrimCenter()`, unchanged.
- `Type.kt` — `JellyWristTypography`, unchanged.
- The Orbit Pulse launcher, round, monochrome themed and notification marks; `docs/brand/`;
  the JellyWrist product name and client identity — all untouched (DEC-012).
- `applicationId`, Room database name, encrypted preferences, DataStores, WorkManager names and
  tags, notification channel id — untouched (DEC-012 compatibility clause).

What DEC-014 changes is **where** the palette is applied and **which container components exist**,
not the palette itself. DEC-011's *screen design* is superseded; DEC-011's *design system*
(palette, typography, gradient, real vector icons, localized byte units, the finished player) is
kept.

**Localization.** `values/strings.xml` and `values-fr/strings.xml` are currently at parity (87
strings each). Parity is a merge requirement: every string added by this redesign ships in both,
and every string deleted is deleted from both. The French verb is
« Garder sur la montre » (§6.1).

---

## 12. Out of scope

Explicitly **not** part of this redesign:

- The `:mobile` phone companion — no file under `mobile/` is touched (owner decision, §2.1).
- The `:core` module, the wire protocol, and every companion message path.
- The sync engine, `SyncPlan.diff`, `BudgetPlanner.plan`, `SyncEngine`, `ReconWorker`,
  `DownloadQueueController`, `Media3TrackDownloader` — behaviour unchanged.
- `KeepOnWatchCoordinator`'s consent ladder — reused verbatim.
- Authentication, `TokenStore`, TLS and certificate pinning — untouched.
- The Room **schema**. The only database change is one additional read-only `@Query`, which needs
  no migration (DEC-009 is not engaged).
- Transcoding (DEC-004), per-track pinning, playlist editing, queue reordering, search, lyrics,
  and any new Jellyfin API surface.
- New Wear surfaces: no Ongoing Activity, no additional Tile, no complication. The existing
  "Resume playback" Tile keeps its current layout and launch behaviour and only gains the
  deep-link extra (§4.3). The "how do I get back to the player" problem is closed by
  `sessionActivity` plus the pager, so Ongoing Activity is not needed for it.
- New runtime dependencies. Zero (§2.2).
- Screenshot or visual-regression test infrastructure.
- The "lite" build flavor (DEC-007 / GAP-007).
- Anything that would change what the companion app sees in a `STATUS_REPLY`.

---

## 13. Verification plan

### 13.1 JVM unit tests (`app/src/test`, run by CI)

| Test class | Covers |
|---|---|
| `SyncStatusMapperTest` | One case per rule 1–9 of §6.3, plus the four ordering conflicts stated there: consent beats Wi-Fi; Wi-Fi beats charger; progress beats waiting-for-space; space beats needs-attention. Plus: `UNSUPPORTED` never yields `NeedsAttention` (§6.4); `total == 0` yields `Downloading(0, 0)`; `!pinned` short-circuits regardless of counts; a collection with 2 errors and 38 of 40 settled yields `NeedsAttention`, not `Downloading`. |
| `ErrorMapperTest` | Every row of §7.3: `InvalidStatusException` at 401 / 403 / 404 / 500 / 418; `SSLHandshakeException` direct and nested in a cause chain; `UnknownHostException` with `hasNetwork` true and false; a bare `IOException`; an unknown `Throwable`; `CancellationException` is rethrown, not mapped. |
| `HomeNavigatorTest` | A request is delivered exactly once; `consume()` clears it; a second observer after consumption sees nothing; concurrent requests keep the most recent. |
| `CollectionSyncStatusStringsTest` | Every `CollectionSyncStatus` case maps to a non-zero `@StringRes` — a compile-time-exhaustive `when` plus a runtime assertion, so adding a case cannot silently produce a blank row. |
| existing `StatusIconTest` | Kept; extended to cover `StatusGlyph`'s `CollectionSyncStatus` overload and the six §6.5 track sentences. |
| existing `AuroraSeekBarTest` | Kept unchanged. |

All new logic in this specification is deliberately placed in pure functions so that it lands in
this table. `SyncStatusMapper`, `ErrorMapper` and `HomeNavigator` reference no Android framework
types; the `@StringRes` mapping functions reference only generated `R` integers, exactly as
`StatusIconTest` already does today.

### 13.2 Instrumented test (`app/src/androidTest`, new source set)

| Test | Covers |
|---|---|
| `CollectionTrackDaoStateCountTest` | The new `GROUP BY collectionId, state` query against an in-memory Room database: empty collection, single-state collection, mixed states, a track shared by two collections counted in both, and a track deleted by cascade disappearing from the counts. |

`:app` has no `androidTest` source set today; this adds one. It requires an emulator and is not
part of the CI gate.

### 13.3 On-device checks (Wear emulator, round, API 34+; then a physical Pixel Watch)

1. **Crown scrolls every list** — Home, `browse`, `browse/{type}` (with 500 songs), `collection`,
   `sync`, `settings`, `settings/log`.
   1b. **Crown changes volume on Now Playing** and does not scroll page 0 behind it; switching
   pages transfers rotary focus.
2. **Gesture arbitration** — swipe left/right at `HOME` moves between the two pages and never
   exits the app; edge-swipe on every pushed screen returns to `HOME`; no pushed screen contains a
   pager.
3. **Media notification / system media control** opens Now Playing (page 1), not the launcher, and
   does not stack a second `MainActivity`.
4. **Download notification** opens `sync`.
5. **Keep on watch, happy path** — pin a collection on Wi-Fi: the row goes
   `Downloading n of m` → `On your watch` (passing through `Preparing…` only if the track list has
   not landed yet), and the Home status card agrees.
6. **Keep on watch, Wi-Fi off** — the row reads `Waiting for Wi-Fi`; the `sync` screen names
   Wi-Fi; enabling Wi-Fi resumes without another tap.
7. **Keep on watch, metered** — the `ConsentDialog` appears **over** the collection screen with
   the `EdgeButton` still visible behind it; cancelling leaves the collection pinned with status
   `Waiting for your OK`; confirming proceeds.
8. **Low battery** — below 20 %, the battery prompt precedes the metered prompt (the coordinator's
   ladder), and only one dialog is on screen at a time.
9. **Quota below usage** — `settings/quota` warns before the value is applied; `sync` then reports
   `Waiting for space` with the tracks listed and a link to `settings/quota`.
10. **Airplane mode** — refreshing any list shows "No connection" with Retry; the cached list stays
    visible; **no exception text appears anywhere on screen**.
11. **Invalidated token** — the server-side session is revoked: the app shows "You've been signed
    out" with a Sign in action and navigates to onboarding.
12. **Self-signed server** — the certificate prompt still renders the full SHA-256 fingerprint and
    both actions.
13. **First run** — a fresh install ends onboarding on `onboarding/first-pick`, not on an empty
    list; picking a suggestion downloads it; dismissing lands on `HOME`, and the step does not
    reappear within the session (§5.2).
14. **TalkBack** — every `StatusGlyph` announces its state; every row is one focusable target; the
    `EdgeButton` announces its current label; `ConsentDialog` takes focus.
15. **Locale** — switching to French shows no untranslated string on any screen (parity check,
    §11).
16. **Ambient / always-on** — Now Playing stays legible and the artwork stays dimmed.
17. **Process death mid-download** — kill the app during a download: reopening shows the same
    per-collection status and the download resumes (existing DEC-005 behaviour, re-verified
    through the new status surface).

### 13.4 Build gates

`./gradlew assembleDebug testDebugUnitTest` — the existing CI gate; must stay green.
`./gradlew lint` — run locally; GAP-009 records that it is already red on pre-existing issues, so
the requirement is *no new* lint findings attributable to this work.

---

## 14. Open questions for the owner

Five, each with a recommendation. None blocks writing this specification; all five should be
answered before implementation starts.

**OQ-1 — Does Home list only what is kept, with browsing one tap away?**
This design puts only pinned collections on Home and moves the four categories behind an
`EdgeButton` → `browse` chooser (§4.2, §5.3, §5.5). The alternative keeps the categories as rows
on Home, saving one tap on the "add music" path at the cost of Home answering two questions again.
*Recommendation: adopt as specified.* The stated problem is confusion; one extra tap is a cheap
price for a Home screen with one job.

**OQ-2 — May the per-track "remove" control disappear from the sync screen?**
§5.9 removes both the "Ready" list and the per-track remove button. In a refcounted model the
button is incoherent: the next pass re-adds the track, because its collection is still pinned.
*Recommendation: remove it.* Removal is expressed by removing a collection from the watch, which
is the only operation the data model actually supports.

**OQ-3 — Do unsupported codecs stay out of "Needs attention"?**
§6.4 counts `UNSUPPORTED` as settled and reports it once, at collection level. The alternative
raises "Needs attention", which in v1 can never be cleared (no transcoding, DEC-004).
*Recommendation: keep them out.* A permanent warning trains users to ignore warnings.

**OQ-4 — Sequencing against the pre-merge development work.**
This specification assumes `KeepOnWatchCoordinator`, `ConsentPrompt` and `SyncPreconditions`
exist; they are on that branch, not on `main` (§2.3). *Recommendation: merge
the pre-merge development work first, then implement this on top of it.* Implementing against `main`
would mean re-writing the consent ladder that branch already got right, and then merging two
versions of it.

**OQ-5 — Does the gradient keep its four painted surfaces?**
§9.1 restricts the gradient to exactly one *control container* per screen — the primary action —
and §9.2 keeps it painting four surfaces that are not control containers: the wordmark, the Quick
Connect code, the quota arc and the seek bar's elapsed portion. A stricter reading of "exactly one
role" would strip all four. *Recommendation: keep the four.* None of them is a control surface, so
none competes with the primary action, and they are where DEC-011 and DEC-012 put the identity. If
the owner prefers the strict reading, dropping them is a one-line change per site and costs the
app its only visible brand cue outside the launcher icon.

---

## 15. Traceability

| Audit finding | Addressed by |
|---|---|
| §1.1 Library is five products | §4.1, §5.3, §5.5, §5.6 |
| §1.2 Three verbs | §6.1, §5.7, §5.8 |
| §1.3 Unexplained glyphs, "Budget" | §6.2, §8.1 `StatusLabel`/`StatusGlyph`, §5.9 section 4 |
| §1.4 Raw exception strings | §7 |
| §1.5 Prompts replace controls | §8.4 |
| §1.6 One-way navigation, no route back to the player | §4.1, §4.3, §8.1 `NowPlayingChip` |
| §1.7 Two-meaning gauge | §10 |
| §1.8 Everything is an `AuroraChip` | §8, §9 |
| §1.9 Onboarding ends on an empty tab | §5.2 |
| §1.10 Dead affordances, dead crown | §5.6 songs rule, §5.10 account card, §8.1 `JellyList`, §8.2 `SectionHeader` |

| Requirement / decision | Effect on this design |
|---|---|
| REQ-006 (refcounted purge) | §5.9's removal of per-track delete |
| REQ-007 (consent before spending) | §8.4, `KeepOnWatchCoordinator` reused verbatim |
| REQ-008 (capacity, never silent) | §5.9 section 4, §5.11 warning, `WaitingForSpace` |
| REQ-009 (Wear media patterns) | §5.4 unchanged, plus `sessionActivity` |
| DEC-004 (no transcoding) | §6.4 |
| DEC-005 (paused, never failed) | §5.9 error state |
| DEC-006 (opt-in pinning, BT guard) | §5.1 certificate prompt, §5.4 unchanged |
| DEC-009 (migration discipline) | §6.7: no schema change, so not engaged |
| DEC-011 (Aurora) | screen design superseded; design system kept (§11) |
| DEC-012 (brand) | fully preserved (§11) |
| DEC-013 (companion) | untouched (§12) |
| PLAN.md §6 | §5.2 closes the missing "suggest a first pin" step |
