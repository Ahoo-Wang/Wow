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

import me.ahoo.wow.architecture.ArchitectureRules.EXAMPLES
import me.ahoo.wow.architecture.ArchitectureRules.OPS_DOMAIN
import me.ahoo.wow.architecture.ArchitectureRules.PLATFORM_SERVERS
import me.ahoo.wow.architecture.ArchitectureRules.TOOLS
import me.ahoo.wow.architecture.ArchitectureRules.VIEW_STORE_DOMAIN
import java.io.File

/** Who may opt in to `InternalWowApi`: no application ([USER_PROJECTS]), but the declarations listed with a reason. */
object InternalOptInRules {
    /** The [TOOLS] that are applications: they use Wow as a user does. */
    private val APPLICATION_TOOLS = setOf("benchmark-server", "migration-rehearsal", "spi-consumer")

    /**
     * The projects that use Wow as an application does (decision D216; `userProjects` of the root build): the
     * examples, the platform's domains and services, the end-to-end benchmark and the migration rehearsal's host. They
     * compile against the public API as a user does, so they never opt in to `InternalWowApi` (an `ERROR`-level
     * opt-in).
     */
    val USER_PROJECTS: List<WowProject> =
        EXAMPLES + listOf(OPS_DOMAIN, VIEW_STORE_DOMAIN) + PLATFORM_SERVERS + TOOLS.filter { it.name in APPLICATION_TOOLS }

    private val INTERNAL_OPT_IN = Regex("""\bInternalWowApi\b""")

    /**
     * The lines of [USER_PROJECTS]' build scripts and production sources that name `InternalWowApi` (review R2.5-7):
     * an `@OptIn(InternalWowApi::class)` or a compiler `optIn`, which would build an application on a declaration Wow
     * promises nothing about. Their tests may opt in, to build a fixture as a module does. A platform server's
     * declaration in [allowed] (`path#declaration`, with its reason) may opt in on itself, and its file import the
     * marker: one that reads a host setting no public type gives (design rule K1); a file-level opt-in never may.
     */
    fun internalOptIns(repositoryRoot: File, allowed: Map<String, String> = emptyMap()): List<String> =
        USER_PROJECTS.flatMap { module ->
            val build = File(repositoryRoot, "${module.dir}/build.gradle.kts")
            val sources = listOf("src/main/kotlin", "src/main/java")
                .map { File(repositoryRoot, "${module.dir}/$it") }
                .filter { it.isDirectory }
                .flatMap { root ->
                    root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                }
            (listOf(build).filter { it.isFile } + sources).flatMap { file ->
                val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                val lines = file.readLines()
                val excused = allowedOptInLines(path, lines, allowed)
                lines.mapIndexedNotNull { index, line ->
                    if (INTERNAL_OPT_IN.containsMatchIn(line) && index !in excused) {
                        "$path:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
        }

    /** The entries of [allowed] whose declaration no longer opts in, so they are stale. */
    fun staleOptIns(repositoryRoot: File, allowed: Map<String, String>): List<String> = allowed.keys.filter { entry ->
        val file = File(repositoryRoot, entry.substringBefore('#'))
        !file.isFile || file.readLines().let { lines ->
            allowedOptInLines(entry.substringBefore('#'), lines, mapOf(entry to ""))
                .none { index -> DECLARATION_OPT_IN.matches(lines[index].trim()) }
        }
    }

    /**
     * The indexes of [lines] of the file at [path] that [allowed] excuses: each `@OptIn(InternalWowApi::class)` right
     * above (annotations between) the declaration an entry names, and the marker's import when every opt-in is excused.
     */
    private fun allowedOptInLines(path: String, lines: List<String>, allowed: Map<String, String>): Set<Int> {
        val names = allowed.keys.filter { it.substringBefore('#') == path }.map { it.substringAfter('#') }.toSet()
        if (names.isEmpty()) return emptySet()
        val excused = lines.indices.filter { index ->
            DECLARATION_OPT_IN.matches(lines[index].trim()) &&
                lines.drop(index + 1).firstOrNull { !it.trim().startsWith("@") }
                    ?.let { DECLARED.find(it)?.groupValues?.get(1) } in names
        }.toSet()
        val optIns = lines.indices.filter { DECLARATION_OPT_IN.matches(lines[it].trim()) }.toSet()
        val import = lines.indexOfFirst { it.trim() == "import me.ahoo.wow.api.annotation.InternalWowApi" }
        return if (optIns.isNotEmpty() && optIns == excused && import >= 0) excused + import else excused
    }

    private val DECLARATION_OPT_IN = Regex("""@OptIn\(InternalWowApi::class\)""")
    private val DECLARED = Regex("""\b(?:fun|val|var|class)\s+(\w+)""")
}
