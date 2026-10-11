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

`DemoPlayback`'s doc comment is the lifecycle a consumer copies: configure, build, publish a
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
xcrun simctl launch booted com.superplayer.demo -SPDemoScreen playback   # or frameOutput, or nothing for the list
xcrun simctl spawn booted log show --last 1m --style compact --predicate 'subsystem == "com.superplayer.demo"'
```

**A screenshot is weak evidence on its own** — it cannot tell a rendered frame from a stalled one
(the root `CLAUDE.md`). The demo logs `content=… position=<ms>` every two seconds, with `frames=<n>`
on the frame-output screen, and a position that advances between two lines is what to look for.
Telemetry goes to the same log under the subsystem `com.superplayer`, category `SuperPlayerQoE`.

Two things a simulator run does not show: whether the pass-through drops a frame an `AVPlayerLayer`
would have shown, which is a device count (#435), and anything about tvOS beyond the build — one
target serves both platforms with the same screens, and tvOS's own recipe is i7's.

## How the project was produced

`project.pbxproj` and the shared scheme were **written by hand**, kept to what Xcode needs: one
application target supporting both iOS and tvOS (`SDKROOT = auto`), the synchronized source folder,
a local package reference and the two products it links, `SuperPlayerCore` and
`SuperPlayerTelemetry`. No generator is involved, so none is a dependency. The Info.plist is
generated from build settings, and no signing team is set, which a simulator build does not need;
running on a device means choosing a team in Xcode. The app declares no `audio` background mode, so
playback stops when it is backgrounded — that entry is the app's to make (ADR-0007 rule 4).
