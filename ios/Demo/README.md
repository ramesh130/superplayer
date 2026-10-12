# The iOS and tvOS demo

An Xcode project that plays public HLS through `SuperPlayer`, resolving the package **by path**
(`../..`, the repository root) so it compiles against the products as an adopter's app does — the
iOS twin of the Android `demo/`, which resolves the library from published coordinates for the
same reason. `ios/check` builds it for the iOS and tvOS simulators and runs nothing; whether it
plays a real stream over a real CDN needs a network, which no check here has (ADR-0021).

| Screen | What it shows |
| --- | --- |
| `PlaybackScreen` | the player on SwiftUI's stock `VideoPlayer(player: player.avPlayer)`, with no adapter |
| `FrameOutputScreen` | the same stream through a pass-through `FrameOutput` on the library's `PlayerView`, counting the frames it is handed |
| `TvScreen` | `tvLeanback` through `TvOutput` on `AVPlayerViewController`'s system controls, with AVKit's own criteria matching off — what the app opens on under tvOS |
| `MoqScreen` | a live broadcast over Media over QUIC on a `RealtimePlayer` and `MoqFrameSource`, the session's round trip and receive rate over the picture — **iOS only** (below) |
| `FeedScreen` | 200 rows over a `PlayerPool` and a `PreloadCoordinator`, one row playing at a time, a header sampling the pool and the coordinator, and each row's time to first frame from `declarePlaybackIntent()` |
| `DebugHudScreen` | the playback screen with `SuperPlayerDiagnostics`' `DebugHud` over it, fed by a `DebugHudTelemetry` composed beside the log sink — the recipe a consumer copies (#429); nothing shows in a Release build |

`DemoPlayback`'s doc comment is the lifecycle a consumer copies for one player, and `FeedScreen`'s
for a feed: the pool with a collector per player, the coordinator attached before the pool's first
player, and the two released in reverse. The single player's is: configure, build, publish a
`PlaybackSession`, declare intent, set a `MediaRequest`, and release once. The stream is Apple's
public *Advanced stream (HEVC/H.264, fMP4)* example, named in `DemoStream.swift` and nowhere else. A
new screen is a case of `DemoScreen` and a file beside the others; the project lists no source
file, because its sources are a folder Xcode keeps in step with the disk (a
`PBXFileSystemSynchronizedRootGroup`), so adding one needs no edit to the project.

## Running it on a simulator

```sh
xcodebuild build -project ios/Demo/SuperPlayerDemo.xcodeproj -scheme SuperPlayerDemo \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/DerivedData-demo
xcrun simctl install booted .build/DerivedData-demo/Build/Products/Debug-iphonesimulator/SuperPlayerDemo.app
xcrun simctl launch booted com.superplayer.demo -SPDemoScreen playback   # or frameOutput, feed, tv, debugHud, moq, or nothing for the list (tv on tvOS)
xcrun simctl spawn booted log show --last 1m --style compact --predicate 'subsystem == "com.superplayer.demo"'
```

**A screenshot is weak evidence on its own** — it cannot tell a rendered frame from a stalled one
(the root `CLAUDE.md`). The demo logs `content=… position=<ms> dropped=<n>` every two seconds — the
access log's dropped video frames — with `frames=<n> missing=<n> runs=<n> repeated=<n>` before
`dropped=` on the frame-output screen (`FrameTally`, which devicelab's `frames` scenario reads), and a
position that advances between two lines is what to look for.
Telemetry goes to the same log under the subsystem `com.superplayer`, category `SuperPlayerQoE`.

Two things a simulator run does not show: whether the pass-through drops a frame an `AVPlayerLayer`
would have shown, which is a device count — `devicelab/lab run frames --platform ios` (#435,
`devicelab/README.md`'s *The frames scenario*) — and whether a television's panel switched modes
for the TV screen, which is an Apple TV's (`docs/testing.md`, *A television on tvOS*).

## MoQ

`MoqScreen` (#434) is the Android `MoqScreen`'s twin and its doc comment the lifecycle to copy:
`Realtime.transport(scheme: MoqFrameSource.scheme)`, a `MediaRequest` naming a `moq://` URL, play,
and release with the screen. It plays a public relay's broadcast, MoQ's own `moq://cdn.moq.dev/demo/bbb.hang`
by default — the Android demo's `anon/catshark.hang` was no longer announced when #438 ran, and this one
may go too — and `-SPMoqBroadcast moq://…` names another without a rebuild; a broadcast that is not
there fails with MoQ's own words over the picture, `unroutable` for every reason at once. Every two
seconds it logs `content=demo:moq-live position=<ms> rendering=<bool> received=<bytes> rtt=<ms>`,
the line its overlay also carries as its accessibility value.

The project resolves a **second local package** for it, `../MoQ` (`SuperPlayerMoQ` is not a product of
the root manifest — `ios/MoQ/Package.swift` says why), and links that product **on iOS only**: its
native library carries no tvOS slice (`third-party/moq/README.md`, *The Swift bindings*), so the
dependency is platform-filtered and the screen compiles to nothing on tvOS. The library's simulator
slice is arm64 alone, so the project excludes `x86_64` from its simulator builds, and the demo no
longer builds for an Intel Mac's simulator.

**`SuperPlayerDemoUITests`** is the project's one test target (#438), iOS only: the XCUITest
`MoqLiveSessionSmokeTests`, which opens the transport in its runner and then has the demo play the
same broadcast. It needs a phone and the internet, so `ios/check` builds it and never runs it;
`devicelab/lab run moq-smoke --platform ios` runs it (`devicelab/README.md`, *The moq-smoke
scenario*), and the phone needs *Settings > Developer > Enable UI Automation* on first. It links
`MoqFFI` directly, through a third local package reference, for the relay probes it asks the
bindings for.

## The television

On tvOS the app opens on `TvScreen` (#426), whose doc comment is the recipe a tvOS app copies:
`tvLeanback`, `TvOutput.standard()`, and `AVPlayerViewController` with the system's own controls —
no `TvPlaybackControls` port, as `docs/ios-plan.md` §8 decided — and AVKit's
`appliesPreferredDisplayCriteriaAutomatically` off, so the output is the one owner of the display's
criteria. Run it on a tvOS simulator as above, with `-destination 'generic/platform=tvOS Simulator'`
and the `Debug-appletvsimulator` products; the Menu button goes back to the list.

**It stays one target.** #407 left splitting it to i7 if tvOS needed a recipe of its own, and it
does not: what differs is a screen and the launch default, both in Swift — one `#if os(tvOS)` for
the default and one for the controller property only tvOS declares — while every build setting, the
Info.plist keys and the linked products are the same on both platforms. A second target would be a
second copy of all of that kept in step by hand.

## The feed and the leak hunt

`-SPDemoScreen feed` opens the feed. Its rows cycle through `DemoStream`, as the Android feed's do,
each under a content id of its own (`demo:feed-<n>`), so today every row is Apple's public example
stream. `-SPFeedRows <n>` changes the count, and `-SPFeedScroll <passes>` makes the feed scroll
itself down to the last row and back, `-SPFeedDwellMs` apart (1000 by default), after
`-SPFeedLeadInMs` on the first row (none by default; devicelab's feed scenario asks for five
seconds, so the first row's position is logged twice before the scroll moves it). The watched row logs
`row=… contentId=… positionMs=…` every second under the category `Feed`, and the scroll logs its
passes under `FeedScroll`.

The scroll drives itself because a simulator takes no scripted touch: `simctl` has no input
command, which `adb shell input swipe` is to the Android hunt. A step moves the list's scroll
position, the same binding a finger moves, so a scripted row and a swiped one are handed their
player by one function.

`ios/hunt-leaks` is the iOS `./gradlew huntLeaks`. On a simulator of its own
(`superplayer-leak-hunt`), it builds the demo in Release, launches the feed scrolling itself and
attaches an `xctrace` recording with the Leaks template (the Leaks and Allocations instruments). It
samples the process's footprint every two seconds and at the end of each pass, and writes
`report.md` under `.build/leak-hunt/<run>/`. The report gives the leaks with the frame that allocated
each where the recording holds it, the high-water mark (the kernel's `phys_footprint_peak`), the
app's footprint while each pass scrolled, with Instruments' own buffers taken out. That footprint is
a spread over a few hundred samples rather than one sample, because at any moment it is mostly how
much the watched row has buffered. The first pass is the baseline, as the Android hunt's untimed
first run is.

```sh
ios/hunt-leaks                                     # 200 rows, two passes, a row a second: about 15 minutes
LEAK_FEED_ROWS=20 LEAK_FEED_PASSES=4 ios/hunt-leaks  # a quicker look
```

It is **not** in `ios/check`, for the Android hunt's reasons (`devicelab/leak/README.md`, *Why it
sits outside the test suite*): it needs a network and real streams, it takes minutes, and it is a
measurement to judge rather than a check to pass. Four things it does not show:

- **A device.** A simulator's memory is the host's, so the hunt says whether the app keeps what a
  scroll made, not what a phone would report. Memory flat over 200 rows on an iPhone, and TTFF
  there, are `devicelab/lab run feed --platform ios`'s (#435, `devicelab/README.md`'s *The feed
  scenario*).
- **The first moments of a launch.** The recording attaches once the feed starts scrolling,
  because an attach in a launch's first moments is refused often enough to fail a run, and a launch
  under `xctrace` fails the Leaks instrument on a simulator. A leak from before that moment has no
  allocating frame in the report and says so.
- **Freed blocks and reference counts.** Both are switched off in the recording, which otherwise
  runs to gigabytes over a long scroll, and neither is a leak or the live set.
- **The trace itself.** It is deleted once its tables are exported, unless `LEAK_KEEP_TRACE=1`.
  The hunt refuses to start under 3 GB free and stops recording early if the disk falls under 1 GB.

## How the project was produced

`project.pbxproj` and the shared scheme were **written by hand**, kept to what Xcode needs: one
application target supporting both iOS and tvOS (`SDKROOT = auto`), the synchronized source folder,
a local package reference and the four products it links, `SuperPlayerCore`,
`SuperPlayerTelemetry`, `SuperPlayerPreload` and `SuperPlayerTV`. No generator is involved, so none is a dependency. The Info.plist is
generated from build settings, merged with `Info.plist` beside the project for the one key no build
setting names — `CADisableMinimumFrameDurationOnPhone`, without which an iPhone holds the frame
output's display link at 60 Hz and the frames screen loses a frame every minute or two (#491). That
file sits outside `SuperPlayerDemo/` because a synchronized folder would copy it into the bundle a
second time. No signing team is set, which a simulator build does not need;
running on a device means choosing a team in Xcode. The app declares no `audio` background mode, so
playback stops when it is backgrounded — that entry is the app's to make (ADR-0007 rule 4).
