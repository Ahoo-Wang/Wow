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
 * The system commands are paired with their classes by their owners' registries alone (API-15): `Lifecycle`,
 * `Schedules`, `ScheduleJobs`, `Ledger` and `SnapshotRebuilds`, each two-way (`COMMANDS`, `nameOf`). A place that takes
 * the commands of more than one of them takes a set of `:spi`'s `SystemCommandGroups`, named for what it means, so two
 * places of one meaning cannot know different commands (RT-26 was a peer path that did not know a name the local path
 * knew).
 *
 * The rule, over the production sources of the V10 modules and the platform's services:
 *
 * - no source reads the commands of two owners (`<Owner>.COMMANDS` or `<Owner>.nameOf`), but `SystemCommandGroups`
 *   and the files [ALLOWED] names, each with its reason;
 * - no source pairs an owner's name with a class by hand (`<Owner>.<NAME> to <Class>::class.java`).
 *
 * Its limit: it holds the commands the registries list. A system command class that no registry lists, and that no
 * place pairs by an owner's name, is not seen: no marker tells a system command class apart from any other.
 */
object SystemCommandRules {
    private val OWNERS = listOf("Lifecycle", "Schedules", "ScheduleJobs", "Ledger", "SnapshotRebuilds")
    private val READ = Regex("""(?<![\w$.])(${OWNERS.joinToString("|")})\.(COMMANDS|nameOf)(?![\w$])""")
    private val PAIRED = Regex("""(?<![\w$.])(${OWNERS.joinToString("|")})\.[A-Z][A-Z_]*\s+to\s+[\w.]+::class\.java""")

    /** The files that may read two owners, by their paths, each with its reason. */
    val ALLOWED: Map<String, String> = mapOf(
        "core/spi/src/main/kotlin/me/ahoo/wow/spi/SystemCommandGroups.kt" to "the registry of the groups and meanings",
        "compiler/compiler/src/main/kotlin/me/ahoo/wow/compiler/model/symbol/ModelNames.kt" to
            "the compiler sees no :spi; ModelNamesTest pins its names to DECIDED_BY_EVERY_AGGREGATE",
        "compiler/compiler/src/main/kotlin/me/ahoo/wow/compiler/model/aggregate/RetiredEvents.kt" to
            "the compiler sees no :spi; it reads names, the events' with the commands', which no meaning holds",
    )

    fun violations(repositoryRoot: File): List<String> =
        (ArchitectureRules.MODULES + ArchitectureRules.PLATFORM_SERVERS).map { it.dir }.distinct().flatMap { module ->
            listOf("src/main/kotlin", "src/main/java").map { File(File(repositoryRoot, module), it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }
        }.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            val code = stripComments(file.readText())
            val owners = READ.findAll(code).map { it.groupValues[1] }.toSortedSet()
            val read = if (owners.size > 1 && path !in ALLOWED) {
                listOf("$path: reads the commands of ${owners.joinToString()}: take a set of SystemCommandGroups")
            } else {
                emptyList()
            }
            read + code.lines().flatMapIndexed { index, line ->
                PAIRED.findAll(line).map { "$path:${index + 1}: pairs ${it.value}" }.toList()
            }
        }

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .replace(LINE_COMMENT, "$1")
}
