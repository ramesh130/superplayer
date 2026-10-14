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

/**
 * Whether a line of a Gradle script is a comment, and so not a declaration.
 *
 * Every check that scans build scripts for a forbidden shape needs this, and needs all of them
 * to agree: a rule's own documentation has to be able to show what a violation looks like
 * without tripping the rule. One definition, so two copies cannot drift.
 *
 * It is a line test rather than a parse, so it does not see a comment opened mid-line, nor the
 * interior of a block comment whose lines carry no prefix. Both are shapes a violation would have
 * to be deliberately hidden inside, which is not the failure mode these checks exist for.
 */
internal fun isCommentLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("#") ||
        trimmed.startsWith("*") || trimmed.startsWith("/*")
}
