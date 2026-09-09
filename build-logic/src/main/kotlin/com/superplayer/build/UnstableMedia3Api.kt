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

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile

/**
 * ADR-0001 rule 2, made mechanical: no `@UnstableApi` Media3 type may appear in SuperPlayer's
 * public API — not as a parameter, a return type, a supertype, a type argument, or a public field.
 *
 * The tracked API surface alone would only make such a leak *visible*, as a diff someone has to
 * notice. This turns it into a failure, which is what the ADR says the rule needs, because a
 * leaked unstable type forces every consumer to opt in to instability SuperPlayer chose.
 *
 * The functions here are plain functions over strings and files so they can be unit-tested
 * without a Gradle build, the same way [findHardcodedMedia3Versions] is.
 */

/** Media3's own opt-in marker. `@Retention(CLASS)`, so it lands in RuntimeInvisibleAnnotations. */
private const val UNSTABLE_API_DESCRIPTOR = "Landroidx/media3/common/util/UnstableApi;"

/**
 * The one exception ADR-0001 rule 2 names: the wrapped engine, exposed as the escape hatch.
 *
 * It is a set of exactly one, and it stays that way. A second entry is a decision that belongs in
 * a superseding ADR, not in this file.
 */
internal val ADR_0001_UNSTABLE_EXCEPTIONS = setOf("androidx/media3/exoplayer/ExoPlayer")

/**
 * Media3's stable `Player` interface, which SuperPlayer implements.
 *
 * Every type this interface names in its own signatures is a type any implementation of it must
 * name too — `Player.getSurfaceSize()` returns `androidx.media3.common.util.Size`, which Media3
 * annotates `@UnstableApi` even though `Player` itself is stable. That is Media3's own contract
 * reaching a consumer, not instability SuperPlayer chose to take on, and rule 2 is written about the
 * latter: "Consumers are never forced to opt in to instability that SuperPlayer *chose*." Refusing
 * these would mean refusing to implement `Player`, which is the whole premise of the facade.
 *
 * Derived from the pinned Media3 rather than listed by hand, so it cannot drift from what `Player`
 * actually declares.
 */
private const val PLAYER = "androidx/media3/common/Player"

/** Any Media3 type named in a JVM signature, in the internal form the API dump is written in. */
private val MEDIA3_TYPE = Regex("""androidx/media3/[A-Za-z0-9/_${'$'}]+""")

/**
 * Returns one `type: signature` entry per `@UnstableApi` Media3 type appearing in [apiSurface],
 * excluding [allowed], sorted so the failure message is stable from run to run.
 *
 * [isUnstable] answers whether an internal class name is annotated; it is a parameter rather than
 * a classpath lookup so that the rule and the bytecode reading are testable apart from each other.
 */
internal fun findUnstableMedia3TypesInApiSurface(
    apiSurface: String,
    allowed: Set<String>,
    contractMembers: Set<String>,
    isUnstable: (String) -> Boolean,
): List<String> =
    apiSurface.lineSequence()
        // A member Media3's own `Player` declares is one every implementation must declare, with
        // the types Media3 chose. Permission is granted to *that member*, not to the type at large:
        // `getSurfaceSize()` may return an unstable `Size`, while a SuperPlayer-invented method
        // returning the same type is still the leak the rule is about.
        .filterNot { it.memberSignature() in contractMembers }
        .flatMap { line ->
            MEDIA3_TYPE.findAll(line)
                .map { it.value }
                .filterNot { it in allowed }
                .filter(isUnstable)
                .map { "$it: ${line.trim()}" }
        }
        .distinct()
        .sorted()
        .toList()

/**
 * The `name descriptor` of the member a dump line declares, or null for anything else — a class
 * header, a brace, a blank line. The dump writes members as `public fun <name> <descriptor>`, which
 * is the same pair a class file carries, so the two can be compared directly.
 */
private fun String.memberSignature(): String? =
    DUMP_MEMBER.find(this)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }

private val DUMP_MEMBER = Regex("""\bfun (\S+) (\S+)""")

/**
 * Returns the members Media3's own `Player` declares, as `name descriptor` pairs, or null when
 * `Player` cannot be read off [classpath].
 *
 * Used to work out which of SuperPlayer's members exist only because the interface it implements
 * says so. Null is not "none": a caller that treated an unreadable classpath as an empty contract
 * would silently pass everything, so the task fails instead.
 */
internal fun playerContractMembers(classpath: Iterable<File>): Set<String>? =
    membersOf(PLAYER, classpath)

/**
 * Returns the `name descriptor` of every member [internalName] declares, or null when the class
 * cannot be found on [classpath] — which the caller must treat as a failure rather than as "no
 * members", since the two are indistinguishable in a set and only one of them is safe.
 */
private fun membersOf(internalName: String, classpath: Iterable<File>): Set<String>? {
    val bytes = classpath.filter { it.exists() }
        .firstNotNullOfOrNull { it.readClassBytes("$internalName.class") }
        ?: return null

    val members = mutableSetOf<String>()
    ClassReader(bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                members += "$name $descriptor"
                return null
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )

    return members
}

/** Whether [apiSurface] names any Media3 type at all, and therefore needs the classpath read. */
internal fun namesAnyMedia3Type(apiSurface: String): Boolean = MEDIA3_TYPE.containsMatchIn(apiSurface)

/**
 * Answers whether a class on [classpath] carries Media3's `@UnstableApi`, reading the annotation
 * off the class file rather than maintaining a list of unstable types by hand — the ADR is explicit
 * that the per-class status must be read off the pinned Media3 version, because annotations move
 * between releases.
 *
 * A type that cannot be found on the classpath is reported as stable: the check exists to fail on
 * what it can prove, and a missing class is a resolution problem that some other task will report
 * far more clearly than this one would.
 */
internal fun unstableTypeDetector(classpath: Iterable<File>): (String) -> Boolean {
    val cache = mutableMapOf<String, Boolean>()
    val roots = classpath.filter { it.exists() }

    return { internalName ->
        cache.getOrPut(internalName) {
            val bytes = roots.firstNotNullOfOrNull { it.readClassBytes("$internalName.class") }
            bytes != null && bytes.hasUnstableApiAnnotation()
        }
    }
}

private fun File.readClassBytes(entryPath: String): ByteArray? = when {
    isDirectory -> resolve(entryPath).takeIf { it.isFile }?.readBytes()

    extension == "jar" || extension == "zip" ->
        JarFile(this).use { jar -> jar.getJarEntry(entryPath)?.let { jar.getInputStream(it).readBytes() } }

    else -> null
}

private fun ByteArray.hasUnstableApiAnnotation(): Boolean {
    var found = false
    ClassReader(this).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor == UNSTABLE_API_DESCRIPTOR) found = true
                return null
            }
        },
        // Only the class header and its annotations are read; skipping the rest is what keeps this
        // affordable across a whole compile classpath.
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return found
}
