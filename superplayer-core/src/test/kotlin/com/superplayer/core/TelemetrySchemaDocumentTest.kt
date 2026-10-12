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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * `docs/telemetry-schema.md`'s *The events* table against [TelemetryEvent], in both directions: every
 * event the table names is a subclass carrying exactly the fields its row lists, and every subclass
 * is a row (#408).
 *
 * The Swift package's `TelemetrySchemaDocumentTests` holds the iOS vocabulary to the same table, so
 * the two platforms agree because each agrees with the document — ADR-0021's rule that a shared
 * document is tested on both platforms, and the reason neither test compares against the other
 * platform's source. `DoctorDocumentTest`'s model: the document is read, the code is the authority
 * for what exists, and a difference fails with what to write.
 *
 * The fields are read off the classes by Java reflection rather than Kotlin's, which would need
 * `kotlin-reflect` on the test classpath for one test; a data class's properties are its non-static
 * fields under the same names.
 */
class TelemetrySchemaDocumentTest {

    @Test
    fun everyDocumentedEventAndFieldIsInTheVocabularyAndNothingElse() {
        val documented = documentedEvents()
        // A guard on the parser rather than a second authority on the count: a table it read none of
        // would otherwise compare as "nothing documented" and fail less legibly.
        assertWithMessage("the parser found ${documented.keys}; the table has seventeen events")
            .that(documented).hasSize(17)

        assertThat(differences(documented, carriedEvents())).isEmpty()
    }

    /** The control: the comparison reports a difference in each direction, so empty is a reading. */
    @Test
    fun theComparisonReportsAFieldOrAnEventMissingFromEitherSide() {
        assertThat(
            differences(
                documented = mapOf("SessionEnded" to setOf("droppedEventCount"), "Invented" to emptySet()),
                carried = mapOf("SessionEnded" to setOf("droppedEventCount", "securityLevel")),
            ),
        ).containsExactly(
            "Invented is documented and not in the vocabulary",
            "SessionEnded.securityLevel is carried and not documented",
        ).inOrder()
    }

    @Test
    fun theSchemaVersionIsTheOneTheDocumentStates() {
        val stated = Regex("""The current version is \*\*(\d+)\*\*""").find(document().readText())
        assertWithMessage("no sentence stating the current version in $DOCUMENT").that(stated).isNotNull()
        assertThat(stated!!.groupValues[1].toInt()).isEqualTo(TelemetryEvent.SCHEMA_VERSION)
    }

    /** Each row's event and the backticked names its *Carries* cell lists. */
    private fun documentedEvents(): Map<String, Set<String>> {
        val lines = document().readLines()
        val heading = lines.indexOf("## The events")
        check(heading >= 0) { "No '## The events' heading in $DOCUMENT" }
        val table = lines.drop(heading).dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") }
        return table.drop(2).associate { row ->
            // A row opens and closes with a pipe: cell 1 is the event, cell 3 what it carries.
            val cells = row.split("|").map { it.trim() }
            backticked(cells[1]).single() to backticked(cells[3]).toSet()
        }
    }

    /** Each subclass of [TelemetryEvent] by its simple name, with its own fields. */
    private fun carriedEvents(): Map<String, Set<String>> = TelemetryEvent::class.java.declaredClasses
        .filter { TelemetryEvent::class.java.isAssignableFrom(it) }
        .associate { event ->
            event.simpleName to event.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet() - COMMON
        }

    private fun differences(documented: Map<String, Set<String>>, carried: Map<String, Set<String>>): List<String> {
        val found = mutableListOf<String>()
        documented.forEach { (event, fields) ->
            val kotlin = carried[event]
            if (kotlin == null) {
                found += "$event is documented and not in the vocabulary"
            } else {
                found += (fields - kotlin).map { "$event.$it is documented and not carried" }
                found += (kotlin - fields).map { "$event.$it is carried and not documented" }
            }
        }
        found += (carried.keys - documented.keys).map { "$it is carried and not documented" }
        return found.sorted()
    }

    private fun backticked(cell: String): List<String> = Regex("`([^`]+)`").findAll(cell).map { it.groupValues[1] }.toList()

    private fun document(): File = File(DOCUMENT).also {
        check(it.isFile) { "No document at ${it.absolutePath}" }
    }

    private companion object {

        /** Relative to this module's directory, which is where Gradle runs a test from. */
        const val DOCUMENT = "../docs/telemetry-schema.md"

        /** The four fields every event carries, which the table leaves out of each row. */
        val COMMON = setOf("sessionId", "contentId", "timestampMs", "monotonicTimeMs")
    }
}
