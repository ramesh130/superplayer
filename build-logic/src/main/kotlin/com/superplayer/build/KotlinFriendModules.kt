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
 * fake data source into the engine. It cannot become public — its parameter is `ExoPlayer.Builder`,
 * an `@UnstableApi` type, so `verifyNoUnstableMedia3InPublicApi` would fail it under ADR-0001
 * rule 2 — and Kotlin `internal` means *one compilation*, which is `superplayer-core`'s own.
 *
 * `superplayer-testkit` exists to give every other module the deterministic playback harness that
 * seam makes possible (`docs/modules.md`), so it needs exactly the access core's own tests have. A
 * friend path is the mechanism Kotlin provides for that, and it is the honest description of the
 * relationship: these are two compilations of one library, shipped together in one phase, not a
 * consumer reaching into an implementation.
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
