/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.architecture

import java.io.File

/**
 * A rule over what production sources of [modules] do, not what they import: no code outside comments names any of
 * the [forbidden] members (`crossScope`, `fixedTenant`, ...), whichever receiver they are read on. Where a
 * responsibility has one home in a core module (G3), an edge that reads the members the rule rests on is deciding it
 * a second time.
 */
data class SourceTokenRule(val name: String, val modules: List<String>, val forbidden: List<String>) {
    private val pattern = Regex("""(?<![\w$])(${forbidden.joinToString("|") { Regex.escape(it) }})(?![\w$])""")

    fun violations(repositoryRoot: File): List<String> =
        modules.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java").map { File(File(repositoryRoot, module), it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }
        }.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            stripComments(file.readText()).lines().flatMapIndexed { index, line ->
                pattern.findAll(line).map { "$path:${index + 1}: ${it.value}" }.toList()
            }
        }

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

        fun stripComments(source: String): String =
            source
                .replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
                .replace(LINE_COMMENT, "$1")
    }
}
