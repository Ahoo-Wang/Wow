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
