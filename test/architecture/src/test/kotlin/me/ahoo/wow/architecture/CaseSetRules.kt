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

import me.ahoo.wow.architecture.ArchitectureRules.MODULES
import java.io.File

/**
 * Whether a public enum or sealed type may gain a case within 10.x (D418 ⑥): a case added to a type a caller matches
 * exhaustively breaks the caller's sources, so each type says which it is, in its KDoc, in one of two sentences.
 *
 * - [OPEN]: a minor release may add a case. Every type whose set is not complete by its nature is open, whichever way
 *   its values flow: those Wow gives a user (a notice's kind, a failure class), whom the sentence asks for an `else`,
 *   and those a user gives Wow (a setting, an authorizer's answer), on which no user matches, so opening costs nothing.
 * - [CLOSED]: the set is complete by its nature, so a caller may name every case; a new one waits for 11.0.
 *
 * The sentences speak to code outside Wow. Wow's modules are released together, so Wow's own `when` over an open type
 * stays exhaustive, with no `else`: the compiler then points at each of them when a case is added.
 *
 * A type is public when its module's ABI dump (`<module>/api/<module>.api`) declares it, so an `internal` or an
 * `@InternalWowApi` type is left out, as a module without a dump is.
 *
 * The types are found by reading the sources line by line, which misses a declaration of an unusual layout (an
 * annotation on the same line, say). An enum cannot be missed so: the dump marks it as extending `java/lang/Enum`, and
 * one the sources' reading did not find is a violation of its own. A sealed type has no such mark in the dump, so its
 * detection rests on the reading of the sources alone.
 */
object CaseSetRules {
    /** The sentence of an open type. */
    const val OPEN = "Cases may be added in a minor release, so a `when` over it keeps an `else` branch."

    /** The sentence of a closed type. */
    const val CLOSED = "No case is added within 10.x, so a `when` over it may name every case."

    /**
     * The modules whose types do not carry the sentences yet, by directory, each with the part of SRC-1 that adds them.
     * Each part removes its line when it lands, so the list ends empty; an entry whose module has no type without a
     * sentence any more is stale and fails the rule.
     */
    val PENDING: Map<String, String> = mapOf(
        "api/query-api" to "the query protocol's types",
    )

    private val DECLARATION = Regex("""^(\s*)((?:[a-z]+ )*)(class|interface|object)(?: (\w+))?""")
    private val CASE_SET = Regex("""\b(?:enum|sealed)\b""")
    private val ABI_DECLARED_CLASS = Regex("""^(?:public|protected) [^{]*?\bclass ([\w/$]+)""")
    private val ABI_ENUM = Regex("""^(?:public|protected) [^{]*?\bclass ([\w/$]+) : java/lang/Enum\b""")

    /**
     * The public enums and sealed types of [MODULES], outside [pending], whose KDoc carries neither sentence or both, as
     * `file:line: Type: why`; each enum of an ABI dump the sources' reading did not find, as `dump: class: why`; and
     * each pending module that has neither any more (a stale entry).
     */
    fun violations(repositoryRoot: File, pending: Map<String, String> = PENDING): List<String> {
        val found = MODULES.filter { it.dir !in pending }.flatMap { unmarked(repositoryRoot, it) }
        val stale = MODULES.filter { it.dir in pending && unmarked(repositoryRoot, it).isEmpty() }.map { it.dir }
        return found + (pending.keys - MODULES.map { it.dir }.toSet() + stale).sorted().map { "stale entry: $it" }
    }

    private fun unmarked(repositoryRoot: File, module: WowProject): List<String> {
        val dumpPath = "${module.dir}/api/${module.name}.api"
        val dump = File(repositoryRoot, dumpPath).takeIf { it.isFile } ?: return emptyList()
        val dumpLines = dump.readLines()
        val public = dumpLines.mapNotNull { ABI_DECLARED_CLASS.find(it)?.groupValues?.get(1) }.toSet()
        val enums = dumpLines.mapNotNull { ABI_ENUM.find(it)?.groupValues?.get(1) }
        val read = mutableSetOf<String>()
        val root = File(repositoryRoot, "${module.dir}/src/main/kotlin")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
        val unmarked = sources.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            val types = caseSets(file.readLines()).onEach { read += it.jvmName }
            types.filter { it.jvmName in public }.mapNotNull { type ->
                val open = OPEN in type.kdoc
                val closed = CLOSED in type.kdoc
                when {
                    open && closed -> "$path:${type.line}: ${type.name}: both sentences"
                    open || closed -> null
                    else -> "$path:${type.line}: ${type.name}: neither sentence"
                }
            }
        }.toList()
        val unread = (enums - read).map { "$dumpPath: $it: declared in the ABI dump, not found in the sources" }
        return unmarked + unread
    }

    private class CaseSet(val jvmName: String, val name: String, val line: Int, val kdoc: String)

    /** The enums and sealed types of a source, each with its JVM name and its KDoc on one line. */
    private fun caseSets(lines: List<String>): List<CaseSet> {
        val packagePath = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            ?.replace('.', '/').orEmpty()
        val outer = ArrayDeque<Pair<Int, String>>()
        return lines.mapIndexedNotNull { index, line ->
            val match = DECLARATION.find(line) ?: return@mapIndexedNotNull null
            val indent = match.groupValues[1].length
            // A companion object without a name of its own is `Companion` to the JVM.
            val name = match.groupValues[4].ifEmpty { "Companion" }
            while (outer.isNotEmpty() && outer.last().first >= indent) outer.removeLast()
            val nested = (outer.map { it.second } + name).joinToString("$")
            outer.addLast(indent to name)
            if (!CASE_SET.containsMatchIn(match.groupValues[2])) return@mapIndexedNotNull null
            val dotted = (outer.map { it.second }).joinToString(".")
            CaseSet("$packagePath/$nested", dotted, index + 1, kdocAbove(lines, index))
        }
    }

    private fun kdocAbove(lines: List<String>, index: Int): String {
        var end = index - 1
        while (end >= 0 && lines[end].trim().startsWith("@")) end--
        if (end < 0 || !lines[end].trim().endsWith("*/")) return ""
        var start = end
        while (start >= 0 && !lines[start].trim().startsWith("/**")) start--
        if (start < 0) return ""
        return lines.subList(start, end + 1).joinToString(" ") {
            it.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim()
        }
    }
}
