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
 * The entry points a service's contract jar links to (AGENTS.md, Binary Compatibility; D48, D483 ⑯): services on
 * adjacent minors use each other's contract jars, so the ABI lines of these entry points never change in a minor.
 * [LIST] holds them by dump: a class line, then the lines of its members. A line its dump no longer holds is a changed,
 * removed or renamed entry point.
 */
internal object ContractEntryPoints {
    /** The list, a resource of the architecture tests. */
    const val LIST = "/contract-entry-points.txt"

    /** Each line of [LIST] its dump under [repositoryRoot] no longer holds. */
    fun violations(repositoryRoot: File): List<String> =
        violations(checkNotNull(javaClass.getResource(LIST)) { "No $LIST on the test class path." }.readText()) {
            File(repositoryRoot, it).takeIf(File::isFile)?.readText()
        }

    /**
     * Each line of [listed] that its dump, read by [dump] from the dump's path, no longer holds, as
     * `<dump>: <class line>` or `<dump>: <class>: <member line>`.
     */
    fun violations(listed: String, dump: (String) -> String?): List<String> {
        val violations = mutableListOf<String>()
        var path = ""
        var classes: Map<String, Pair<String, Set<String>>> = emptyMap()
        var owner = ""
        for (line in listed.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }) {
            when {
                line.startsWith(DUMP) -> {
                    path = line.removePrefix(DUMP).trim()
                    val text = dump(path)
                    if (text == null) violations += "$path: no such dump"
                    classes = text?.let(::classesOf).orEmpty()
                    owner = ""
                }

                !line.startsWith("\t") -> {
                    owner = classOf(line)
                    if (classes[owner]?.first != line) violations += "$path: $line"
                }

                classes[owner]?.second?.contains(line) != true -> violations += "$path: $owner: ${line.trim()}"
            }
        }
        return violations
    }

    /** The classes of [dump] by binary name: each one's class line and member lines. */
    private fun classesOf(dump: String): Map<String, Pair<String, Set<String>>> {
        val classes = mutableMapOf<String, Pair<String, MutableSet<String>>>()
        var current: MutableSet<String>? = null
        dump.lineSequence().forEach { line ->
            when {
                !line.startsWith("\t") && line.endsWith("{") ->
                    current = mutableSetOf<String>().also { classes[classOf(line)] = line to it }

                line.startsWith("\t") -> current?.add(line)
                line == "}" -> current = null
            }
        }
        return classes
    }

    private fun classOf(line: String): String =
        checkNotNull(CLASS.find(line)) { "Not a class line: $line" }.groupValues[1]

    private const val DUMP = "== "
    private val CLASS = Regex("""class (\S+)""")
}
