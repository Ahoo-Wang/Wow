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

/** Design rule K1: each `wow.*` key has one reader, the properties bean that binds it ([ConfigurationReadersTest]). */
object ConfigurationReaderRules {
    /**
     * The production lines that read a `wow.*` key past its properties bean (design rule K1, one reader per key): an
     * `Environment` or `Binder` read (`getProperty`, `getRequiredProperty`, `containsProperty`, `bind`,
     * `bindOrCreate`) of a `"wow.` literal, of a constant whose value is one, or of a `String` parameter of a function of
     * the file (a key its callers give, which the rule cannot see), and any `${'$'}{wow.` placeholder, such as an
     * `@Value` or a SpEL expression's. Each is `path:line: text`; [allowed] maps a path to the reason it may.
     */
    fun wowKeyReaders(repositoryRoot: File, allowed: Map<String, String> = emptyMap()): List<String> {
        val sources = ArchitectureRules.productionSources(repositoryRoot)
        // A constant counts in its own file, or qualified by the class of the file that declares it.
        val qualified = sources.flatMap { file ->
            WOW_CONSTANT.findAll(file.readText()).map { "${file.nameWithoutExtension}.${it.groupValues[1]}" }.toList()
        }
        return sources.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            if (path in allowed) return@flatMap emptyList()
            val text = file.readText()
            val own = WOW_CONSTANT.findAll(text).map { it.groupValues[1] }.toList()
            val names = (own + qualified).map { Regex.escape(it) }
            val constantRead = names.takeIf { it.isNotEmpty() }
                ?.let { Regex("(?<![\\w.])(" + it.joinToString("|") + ")\\b") }
            val parameters = FUNCTION_PARAMETERS.findAll(text).flatMap { STRING_PARAMETER.findAll(it.groupValues[1]) }
                .map { Regex.escape(it.groupValues[1]) }.toSet()
            val parameterRead = parameters.takeIf { it.isNotEmpty() }
                ?.let {
                    Regex(
                        ENVIRONMENT_READ.pattern.let { read -> "(?:$read)\\s*(" + it.joinToString("|") + ")\\b" }
                    )
                }
            text.lines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                val reads = ENVIRONMENT_READ.containsMatchIn(code) && !code.contains("System.getProperty") &&
                    (
                        code.contains("\"wow.") || constantRead?.containsMatchIn(code) == true ||
                            parameterRead?.containsMatchIn(code) == true && ENVIRONMENT_RECEIVER.containsMatchIn(code)
                        )
                if (reads || code.contains("${'$'}{wow.") && !code.startsWith("*")) "$path:${index + 1}: $code" else null
            }
        }
    }

    /** The paths of [allowed] that no longer read a `wow.*` key past its bean, so their entries are stale. */
    fun staleKeyReaders(repositoryRoot: File, allowed: Map<String, String>): List<String> =
        wowKeyReaders(repositoryRoot).let { readers ->
            allowed.keys.filter { path -> readers.none { it.startsWith("$path:") } }
        }

    /**
     * The lines of the Kotlin and Java sources, the tests' included, and of the build scripts that read, set or forward a
     * `wow.*` system property: `System.getProperty` of a `"wow.` literal or of a constant whose value is one,
     * `systemProperty`, the `PrefixedBy` of the Gradle or system properties, a `-Dwow.` argument. A switch of the test
     * tooling named under `wow.` reaches the system properties of the test JVM, where a host refuses it as an unknown
     * configuration key, so the switches are named `wow-test.*`; a key of [runtimeKeys] is a configuration key a build
     * sets for its tests. Each is `path:line: text`.
     */
    fun wowSwitches(repositoryRoot: File, runtimeKeys: Set<String> = emptySet()): List<String> =
        switchSources(repositoryRoot).flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            val text = file.readText()
            val constants = WOW_CONSTANT.findAll(text).map { Regex.escape(it.groupValues[1]) }.toList()
            val constantRead = constants.takeIf { it.isNotEmpty() }
                ?.let { Regex("(?:System\\.getProperty|systemProperty)\\(\\s*(" + it.joinToString("|") + ")\\b") }
            text.lines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                val keys = WOW_SWITCH.findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                val switch = !code.startsWith("*") &&
                    (keys.any { it !in runtimeKeys } || constantRead?.containsMatchIn(code) == true)
                if (switch) "$path:${index + 1}: $code" else null
            }
        }

    /** The Kotlin and Java sources and the build scripts of the repository, past its documentation and Skills. */
    private fun switchSources(repositoryRoot: File): List<File> = repositoryRoot.walkTopDown()
        .onEnter { dir ->
            dir.name !in SKIPPED_DIRS &&
                dir.relativeTo(repositoryRoot).invariantSeparatorsPath !in setOf("documentation", "skills")
        }
        .filter { file ->
            file.isFile && (
                file.name.endsWith(".gradle.kts") ||
                    file.extension in setOf("kt", "java") && "/src/" in file.invariantSeparatorsPath
                )
        }
        .toList()

    private val SKIPPED_DIRS = setOf("build", "node_modules", ".git", ".gradle", ".kotlin", ".claude", ".idea")

    /** A `wow.*` system property read, set or forwarded by its literal name, the name in group 1 or 2. */
    private val WOW_SWITCH =
        Regex("(?:System\\.getProperty|systemProperty|PrefixedBy)\\(\\s*\"(wow\\.[\\w.$-]*)|-D(wow\\.[\\w.$-]*)")

    private val WOW_CONSTANT = Regex("const val (\\w+)\\s*(?::\\s*String)?\\s*=\\s*\"wow\\.")
    private val ENVIRONMENT_READ =
        Regex("\\.(getProperty|getRequiredProperty|containsProperty|bind|bindOrCreate)\\(|\\bgetProperty\\(")

    /** The parameter list of each function of a file, over several lines too. */
    private val FUNCTION_PARAMETERS = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?[\\w.]+\\s*\\(([^)]*)\\)")

    /** A read whose receiver is an environment or a binder, not another object's `bind`. */
    private val ENVIRONMENT_RECEIVER = Regex("\\b(Binder|[eE]nvironment)\\b")

    /** A `String` parameter of a parameter list. */
    private val STRING_PARAMETER = Regex("(\\w+)\\s*:\\s*String\\b(?!\\s*[<?])")
}
