import com.superplayer.build.declareKotlinFriendModule

plugins {
    id("superplayer.android.library")
}

// The realtime source seam's Media3 half: the `MediaSource`/`MediaPeriod` that writes a
// `FrameSource`'s frames into Media3's own `SampleQueue`s (ADR-0018). The seam a transport
// implements is core's, so that its conformance suite can be testkit's (rule 2's #356 addendum).
//
// Phase 13. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
dependencies {
    api(project(":superplayer-core"))

    // Robolectric, for the platform a player needs: this module's tests drive a real `SuperPlayer`
    // whose realtime slot they fill, with no device, no network and no transport (ADR-0018 rule 2).
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils.robolectric)

    // The deterministic playback harness, which builds the player the frames are pushed into and
    // owns the clock the assertions advance. Phase 2 on phase 13, tests only.
    testImplementation(project(":superplayer-testkit"))

    // The session trace the golden file pins, and the collector that derives it. Phases 2 on
    // phase 13, tests only.
    testImplementation(project(":superplayer-telemetry"))
}

// The one slot this module fills, `SuperPlayer.Builder.setRealtime`, is taken as core's public
// `RealtimeSources` — whose internal half carries a Media3 `MediaSource`, an `@UnstableApi` type that
// ADR-0001 rule 2 keeps out of public API. So a consumer names a `FrameSource` and no Media3 type,
// and this module is core's **tenth** Kotlin friend (ADR-0018 rule 11), the first since ADR-0015
// rule 3 took the ninth. `superplayer-moq` and `superplayer-whep` are deliberately **not** friends:
// they implement core's public `FrameSource`, which is the test that the seam is real.
declareKotlinFriendModule(":superplayer-core")
