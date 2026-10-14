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

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import java.io.File
import java.util.Properties

/**
 * The Gradle property naming the repository root, as a path from the root build's directory.
 *
 * **The repository root and the Gradle root are two directories that happen to be one** (#519).
 * The Gradle builds read and write files that belong to the whole repository rather than to them —
 * the Swift manifests, the iOS fixtures, `docs/`, `config/`, `LICENSE`, `CHANGELOG.md`,
 * `third-party/` and `devicelab/` — and once the Android builds move under `android/` (#521) those
 * files stay where they are. Every such path is resolved through this one value, so that move — the
 * four builds moving together, so the links between them hold — is a change of `.` to `..` in the
 * root `gradle.properties` and nothing else among the Gradle paths to shared files, while
 * what is the Gradle build's own (the catalog, the modules, `api/released/`, the settings) stays on
 * the Gradle root.
 *
 * Why a Gradle property in `gradle.properties` rather than a constant here: four builds have to
 * agree on it, and only the root build and its modules can see this file. The root's settings script
 * runs before build-logic exists; `demo/` and `benchmark/` are separate builds that never load it;
 * and build-logic's own tests run outside any build. All four can read one properties file — Gradle
 * hands the root build and its settings the value as a property, and the other three load the same
 * file the way they already load the catalog beside it — so one written value is what each derives
 * from, and nothing restates it.
 */
const val REPOSITORY_ROOT_PROPERTY = "superplayer.repositoryRoot"

/**
 * The repository root of the Gradle build at [gradleRoot], read from that build's `gradle.properties`
 * as [repositoryRoot] reads it from Gradle — the form for a reader outside a build, which is
 * build-logic's tests.
 */
fun repositoryRootOf(gradleRoot: File): File {
    val properties = Properties()
    File(gradleRoot, "gradle.properties").takeIf { it.isFile }?.reader()?.use { properties.load(it) }
    return resolveRepositoryRoot(gradleRoot, properties.getProperty(REPOSITORY_ROOT_PROPERTY))
}

/**
 * The repository root this build belongs to, as a [Directory] a task can be handed.
 *
 * Read through the build's own providers rather than from the file by hand, so the configuration
 * cache tracks it as the Gradle property it is.
 */
val Project.repositoryRoot: Directory
    get() = rootProject.layout.projectDirectory.dir(
        resolveRepositoryRoot(rootDir, providers.gradleProperty(REPOSITORY_ROOT_PROPERTY).orNull).path
    )

/**
 * [declared], resolved against [gradleRoot] — refused when it is missing, and when it names a
 * directory that does not contain the Gradle build, because the Gradle build is part of the
 * repository: a value naming anywhere else is a typo, and the shared files would read as missing
 * rather than as misplaced.
 */
internal fun resolveRepositoryRoot(gradleRoot: File, declared: String?): File {
    if (declared.isNullOrBlank()) {
        throw GradleException(
            "The Gradle build at ${gradleRoot.canonicalPath} declares no repository root. Set " +
                "$REPOSITORY_ROOT_PROPERTY in its gradle.properties to the path from that directory " +
                "to the repository's top: `.` when the two are one directory."
        )
    }
    // Normalized and not canonical: the module scripts write fixture paths relative to their own
    // project directories, which Gradle does not canonicalize, and a symlink resolved on one side of
    // that subtraction only would leave it with no relative answer. The containment check is the one
    // place both sides are canonical.
    val repository = gradleRoot.absoluteFile.resolve(declared).normalize()
    val canonical = repository.canonicalFile
    if (generateSequence(gradleRoot.canonicalFile) { it.parentFile }.none { it == canonical }) {
        throw GradleException(
            "$REPOSITORY_ROOT_PROPERTY=$declared names ${repository.path}, which does not contain the " +
                "Gradle build at ${gradleRoot.absolutePath}. The repository root is that build's own " +
                "directory or one of its ancestors."
        )
    }
    return repository
}
