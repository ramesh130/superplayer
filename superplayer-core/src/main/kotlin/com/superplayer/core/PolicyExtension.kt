/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.core

/**
 * The seam by which an engine built elsewhere reaches `SuperPlayer.Builder.build()`: a
 * [PlaybackPolicy] that *also* implements this configures the engine it will be consulted for.
 *
 * Internal, and reachable from `superplayer-abr` because that module compiles as the second Kotlin
 * friend of core (ADR-0009 rule 7; `build-logic`'s `KotlinFriendModules.kt` carries the argument for
 * why a friend path is not a widening of the seam). The public half of the same call is
 * `setPolicy`, whose parameter is the public interface: a consumer names a `PlaybackPolicy` on
 * both sides and no Media3 type appears in the signature. Inside `build()`, core checks whether the
 * policy it was handed is one of these and, if so, lets it fill [EngineConfiguration]'s slots before
 * the test configurator runs — so a test's configuration still wins over the policy's exactly as it
 * wins over the profile's.
 *
 * Why an interface on the policy object rather than a second builder call: the components an
 * adaptive policy installs — a load control, a selection factory, a meter — are `@UnstableApi`
 * Media3 types, and a public call that took them would either fail `checkApiSurface` or take an
 * opaque handle that reflection unwrapped. `internal` plus a friend path is the same access with
 * the compiler enforcing who has it. And why not `ServiceLoader`: adding a dependency would then
 * change a player's behaviour with no line of code in the app, and a pool could not build two
 * players with different policies in one process. ADR-0009's alternatives section has both.
 *
 * A policy that is *not* one of these takes the path that exists without it: no class is looked
 * up, no observer is registered, and the policy is consulted once — which is what "a consumer who
 * never adds `superplayer-abr` pays nothing" means, and `SuperPlayerPolicyTest` counts it.
 */
internal interface EnginePolicyExtension : PlaybackPolicy {

    /**
     * Fills the slots of [configuration] this policy's engine needs: at least
     * [EngineConfiguration.decisionTarget], or nothing here has a reason to exist. Called once per
     * `build()`, on the thread building the player, before the test configurator.
     */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * Where a [PlaybackDecision] goes after construction: the engine components that can honour one
 * whole (ADR-0009 rule 5).
 *
 * Installed by an [EnginePolicyExtension] into [EngineConfiguration.decisionTarget], and called by
 * core — never by the policy itself — with the first decision at construction and with every
 * changed decision after it, on the player's application thread. The target is responsible for
 * *both* halves: the load control it re-targets and the selection whose ceiling it moves are its
 * own, and core lays no ceiling into the engine's `TrackSelectionParameters` on its behalf.
 */
internal fun interface DecisionTarget {

    /** Honours [decision], both halves, from now until the next call. */
    fun apply(decision: PlaybackDecision)
}

/**
 * A bandwidth meter core can read a [ThroughputEstimate] from, and be told by when the estimate
 * has moved materially (ADR-0009 rule 3).
 *
 * A *reading* seam in `docs/testing.md`'s sense: it substitutes no behaviour, it is how the one
 * observation core cannot make itself reaches [PlaybackConditions.throughput]. The meter installed
 * in [EngineConfiguration.bandwidthMeter] implements it or it does not; Media3's own does not, and
 * on a player built with that one the throughput stays unobserved.
 *
 * What "materially" means is the meter's: it applies a threshold to its own estimate before it
 * says anything, so that a policy is consulted on a change worth naming rather than once per
 * sample (ADR-0009 rule 4). A listener may be called on any thread; core posts the consultation to
 * the application thread itself.
 */
internal interface ThroughputSource {

    /** The current estimate, or null before the meter has one. */
    fun currentEstimate(): ThroughputEstimate?

    /** Starts telling [listener] about material moves. */
    fun addListener(listener: Listener)

    /** Stops telling [listener]. A no-op for a listener never added. */
    fun removeListener(listener: Listener)

    /** Told when the estimate has moved materially. */
    fun interface Listener {
        fun onEstimateMovedMaterially()
    }
}
