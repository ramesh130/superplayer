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

package com.superplayer.build

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Lets this module see another SuperPlayer module's `internal` declarations, the way that module's
 * own unit tests do.
 *
 * ## Why this exists at all
 *
 * `docs/testing.md` describes one seam that is deliberately not public:
 * `SuperPlayer.Builder.setEngineConfigurator`, which is how a test substitutes a fake clock and a
 * fake data source into the engine. It cannot become public — its parameter carries `ExoPlayer.Builder`
 * and `DataSource.Factory`, `@UnstableApi` types, so `verifyNoUnstableMedia3InPublicApi` would fail it under ADR-0001
 * rule 2 — and Kotlin `internal` means *one compilation*, which is `superplayer-core`'s own.
 *
 * `superplayer-testkit` exists to give every other module the deterministic playback harness that
 * seam makes possible (`docs/modules.md`), so it needs exactly the access core's own tests have. A
 * friend path is the mechanism Kotlin provides for that, and it is the honest description of the
 * relationship: these are two compilations of one library, shipped together in one phase, not a
 * consumer reaching into an implementation.
 *
 * `superplayer-abr` is the second friend, and the argument holds for it unchanged with one
 * difference worth stating: testkit is the same phase as the modules it serves, and abr is a later
 * phase than core. That is fine under `docs/modules.md`'s rule, because a friend path is a compiler
 * flag and not a Gradle dependency, and the dependency it does not create is the one the rule
 * forbids. What abr reaches is a core-internal extension interface on its policy object, through
 * which its engine components — `@UnstableApi` types, every one — fill the same `EngineConfiguration`
 * the test seam fills (ADR-0009 rule 7).
 *
 * ## The sixth, and the ceiling it makes explicit
 *
 * `superplayer-drm` is the sixth friend, and ADR-0012 rule 4 requires the argument to be made here
 * rather than only in a build file. It is the same argument and it has not weakened: what the module
 * reaches is one core-internal extension interface on the object the consumer already passes to
 * `SuperPlayer.Builder.setDrm`, through which it fills one slot core declared, and the Gradle
 * dependency it does not create is the one `docs/modules.md` forbids. What is specific to this one is
 * that *every* Media3 type DRM needs carries `@UnstableApi` — `ExoMediaDrm`, `DrmSessionManager`,
 * `DrmSessionManagerProvider`, `MediaItem.DrmConfiguration` — so there is no version of this module
 * whose engine-facing half could have been public API instead.
 *
 * Since #210 the same friendship also carries one *read* back the other way — `SuperPlayer`'s
 * internal `licenceSessions`, which hands the module the protection object it itself put in core's
 * DRM slot — so that an offline licence store can find *this player's* session graph and perform its
 * licence exchange over it (ADR-0012 rule 9's addendum). It is the same shape rather than a second
 * one: nothing new is configurable, no helper of core's is reached, and what comes back is the
 * module's own object out of the slot it filled. The alternative was a registry keyed by player kept
 * in the module, which is the same fact recorded twice and leaked on every player nobody released.
 *
 * The count is the thing worth watching, and the sixth is where the ceiling gets written down:
 * **a friend path is right for a later phase of this library filling a slot core declared, and for
 * nothing else.** A module wanting friendship for any other reason — reaching a helper, avoiding an
 * interface, testing an internal — wants a public API or a test source set instead. Six is not a
 * budget that has run out; it is six instances of one shape, and a seventh that is not that shape
 * needs a superseding ADR rather than a line in a build file.
 *
 * ## The seventh, and the one shape added
 *
 * ADR-0013 rule 4 is that decision, for `superplayer-offline`, and it refines ADR-0012 rule 4 rather
 * than superseding it. The shape it adds is a later phase *built from* core's seam, filling no slot:
 * it composes a download's chain through `TransferChain`, the one place a chain is assembled, and reads
 * what other friends filled into core's slots — the download half `superplayer-cache` puts behind
 * `ContentCache`, and the licence exchange `superplayer-drm` puts behind `PlaybackDrm`. The ADR argues
 * why that is the seam rather than a helper beside it. An eighth of neither shape needs an ADR of its
 * own.
 *
 * ## Why it is not the second seam `docs/testing.md` warns about
 *
 * That warning is about *widening what is configurable*. Nothing is widened here: the same one
 * configurator is reached by one more of the library's own modules. A consumer's compilation is
 * never a friend of anything, and `internal` remains invisible outside this repository.
 *
 * ## The fragile part, stated
 *
 * `-Xfriend-paths` takes directories of compiled classes, and where AGP puts them is AGP's business
 * rather than a contract. [friendClassDirectories] encodes the layout AGP 9's built-in Kotlin
 * support uses. A path that no longer exists is silently ignored by the compiler, so the symptom of
 * an AGP upgrade moving it is not a build failure here but an `internal` resolution error in the
 * module that asked for friendship — which names the declaration it could not see, and is therefore
 * a legible failure rather than a silent one.
 */
fun Project.declareKotlinFriendModule(friendProjectPath: String) {
    val friend = project(friendProjectPath)
    val friendPaths: Provider<String> = friend.layout.buildDirectory.map { buildDirectory ->
        friendClassDirectories(buildDirectory.asFile.absolutePath).joinToString(",")
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add(friendPaths.map { "-Xfriend-paths=$it" })
    }
}

/**
 * The friend module's compiled output, as the Kotlin compiler will see it on the classpath.
 *
 * A friend path is matched against classpath entries, so it has to name the artifact the consuming
 * compilation actually resolves — for an Android library that is the packaged
 * `compile_library_classes_jar`, not the raw class directory the Kotlin task wrote. Both are listed:
 * the directory covers a compilation that resolves project output directly, the jar covers the
 * ordinary case, and a path that matches nothing costs nothing.
 *
 * Both variants are offered rather than the one matching the consuming compilation, because
 * selecting one would mean reaching into another project's task graph, which is what makes a build
 * configuration-cache-hostile.
 */
private fun friendClassDirectories(buildDirectory: String): List<String> =
    listOf("debug" to "Debug", "release" to "Release").flatMap { (variant, capitalized) ->
        listOf(
            "$buildDirectory/intermediates/built_in_kotlinc/$variant/compile${capitalized}Kotlin/classes",
            "$buildDirectory/intermediates/compile_library_classes_jar/$variant/bundleLibCompileToJar$capitalized/classes.jar",
        )
    }
