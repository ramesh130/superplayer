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

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/**
 * What a configuration resolved for one dependency group, as `group:module:version`, sorted.
 *
 * It exists for `superplayer-moq`, where *which artifact was picked* is the thing under test rather
 * than a detail of the build: `dev.moq:moq-ffi` is published to Maven Central too, built with the
 * codec features that carry MPL-2.0 code, and `settings.gradle.kts` scopes the group to
 * `third-party/moq/m2` so Central cannot answer for it. A test can only check that the scoping held
 * if it is told what was resolved, and the test classpath cannot tell it: AGP's AAR transform
 * renames `moq-ffi-android-<version>.aar` to `moq-ffi-release/jars/classes.jar`, losing exactly the
 * version the question is about.
 *
 * It reads the **dependency graph** (`resolutionResult`) rather than the artifacts, which is not an
 * optimization: asking a unit test runtime classpath for its artifacts without an artifact view
 * fails on variant ambiguity, because AGP's own consumers always ask through one. The graph needs
 * no artifact selection and answers the question asked.
 *
 * It lives in `build-logic` rather than in a build script for the reason every lambda in a script
 * does: the configuration cache cannot serialize a lambda that captures the script object, so a
 * `map { }` written in `build.gradle.kts` fails the build the moment a task holds it.
 */
fun resolvedModuleComponents(configuration: Configuration, group: String): Provider<List<String>> =
    configuration.incoming.resolutionResult.rootComponent.map { root ->
        collectComponents(root, mutableSetOf())
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .filter { it.group == group }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
            .sorted()
    }

/**
 * Every component reachable from [component], the root included.
 *
 * Depth-first with a seen set, because a dependency graph is a graph: a diamond would otherwise be
 * walked twice and a cycle would not terminate.
 */
private fun collectComponents(
    component: ResolvedComponentResult,
    seen: MutableSet<ResolvedComponentResult>
): List<ResolvedComponentResult> {
    if (!seen.add(component)) return emptyList()
    return listOf(component) +
        component.dependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .flatMap { collectComponents(it.selected, seen) }
}

/**
 * Hands a list of strings to a test JVM as one comma-separated system property.
 *
 * `Test.systemProperty` takes a value rather than a `Provider`, so a value that is only known once
 * the build runs — a resolution result, here — cannot travel that way without resolving
 * dependencies while the build is still being configured. A `CommandLineArgumentProvider` is the
 * channel Gradle provides for exactly that, and being a class rather than a script lambda is what
 * lets the configuration cache store it.
 */
abstract class SystemPropertyArgumentProvider : CommandLineArgumentProvider {

    /** The property name, without the `-D`. */
    @get:Input
    abstract val propertyName: Property<String>

    /** The values, joined with commas. An empty list is a legitimate answer and says so. */
    @get:Input
    abstract val values: ListProperty<String>

    override fun asArguments(): Iterable<String> = listOf("-D${propertyName.get()}=${values.get().joinToString(",")}")
}
