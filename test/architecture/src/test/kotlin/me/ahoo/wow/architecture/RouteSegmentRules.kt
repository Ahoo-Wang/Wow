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
 * Wow's own routes hold no data in their paths but an aggregate's id (#6626): a tenant, an owner, a space, a
 * subscription or any other value whose length the data decides travels in the body, so every route a value may exist
 * for is one a gateway carries. An aggregate id is bounded for its routes (`AggregateIdLimits`, #6628), and the names a
 * path may hold besides are the Manifest's, matched against it.
 *
 * - [placeholders]: a path variable written in a string of `wow-http`'s sources, where Wow's routes are declared, as a
 *   constant (`{$TENANT}`) or as itself (`{tenant}`), is one of [SEGMENTS], or one of [NOT_ROUTES] in its file.
 * - [reads]: a path variable read (`pathVariable(...)`) in the sources of the modules that serve Wow's routes is one of
 *   [SEGMENTS], or one of [DECLARED]: the path variables an application declares on its own service routes, which are
 *   the application's to bound.
 */
object RouteSegmentRules {
    /** The segments a path of Wow's own may hold, by the constant or the name a route declares it with: why each. */
    val SEGMENTS: Map<String, String> = mapOf(
        "ID" to "the aggregate id, bounded for its routes (AggregateIdLimits)",
        "id" to "the aggregate id, as CommandRoutes.ID writes it",
        "AGGREGATE" to "an aggregate's name, matched against the Manifest",
        "COMMAND" to "a command's name, matched against the Manifest",
        "DATASET_CONTEXT" to "a dataset's context, a name matched against the Manifest",
        "DATASET_NAME" to "a dataset's name, matched against the Manifest",
    )

    /** The braces in a string of `wow-http` that are no path variable, by file and name: what each is. */
    val NOT_ROUTES: Map<Pair<String, String>, String> = mapOf(
        ("EntityTags.kt" to "DIGEST_HEX_LENGTH") to "a regular expression's quantifier",
    )

    /** The reads of a path variable no route of Wow's own declares, by file and argument: whose variables they are. */
    val DECLARED: Map<Pair<String, String>, String> = mapOf(
        ("route/ServiceRoutes.kt" to "name") to "the path variables an application declares on its own service routes",
    )

    /** The modules whose sources serve Wow's routes, and whose reads of a path variable are checked. */
    private val SERVING: List<String> = listOf(ArchitectureRules.HTTP.dir, ArchitectureRules.SPRING_BOOT.dir)

    private val PLACEHOLDER = Regex("""\{\$([A-Z][A-Z0-9_]*)\}|\{([a-z][A-Za-z0-9]*)\}""")
    private val STRING = Regex(""""(?:[^"\\]|\\.)*"""")
    private val READ = Regex("""pathVariable\(\s*([^)]*?)\s*\)""")

    /** The path variables of `wow-http`'s strings outside [SEGMENTS] and [NOT_ROUTES], as `file:line: {name}`. */
    fun placeholders(repositoryRoot: File): List<String> =
        sources(repositoryRoot, ArchitectureRules.HTTP.dir).flatMap { (path, lines) ->
            lines.flatMapIndexed { index, line ->
                STRING.findAll(line).flatMap { PLACEHOLDER.findAll(it.value) }.mapNotNull { match ->
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    "$path:${index + 1}: {$name}".takeIf {
                        name !in SEGMENTS && (path.substringAfterLast('/') to name) !in NOT_ROUTES
                    }
                }.toList()
            }
        }

    /**
     * The reads of a path variable in the serving modules outside [SEGMENTS] and [DECLARED] (or every one, [listedToo]),
     * as `file:line: argument`, the file under the module's sources.
     */
    fun reads(repositoryRoot: File, listedToo: Boolean = false): List<String> = SERVING.flatMap { module ->
        sources(repositoryRoot, module).flatMap { (path, lines) ->
            lines.flatMapIndexed { index, line ->
                READ.findAll(line).mapNotNull { match ->
                    val argument = match.groupValues[1]
                    "$path:${index + 1}: $argument".takeIf {
                        listedToo || argument.substringAfterLast('.') !in SEGMENTS && (path to argument) !in DECLARED
                    }
                }.toList()
            }
        }
    }

    private const val SOURCES = "src/main/kotlin"

    /** A module's Kotlin sources, each by its path under its package root, with its lines, comments blanked. */
    private fun sources(repositoryRoot: File, module: String): List<Pair<String, List<String>>> {
        val root = File(repositoryRoot, "$module/$SOURCES")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map { file ->
            packagePath(file.relativeTo(root).invariantSeparatorsPath) to blankComments(file.readText()).lines()
        }.toList()
    }

    /** [path] under the module's package root, `me/ahoo/wow/<module>/`, so a rule names a file as its package does. */
    private fun packagePath(path: String): String = path.split('/').drop(PACKAGE_DEPTH).joinToString("/")

    /** `me/ahoo/wow/<module>`. */
    private const val PACKAGE_DEPTH = 4

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""(?m)(^|[^:"])//.*$""")

    private fun blankComments(source: String): String =
        source.replace(BLOCK_COMMENT) { match -> "\n".repeat(match.value.count { it == '\n' }) }
            .replace(LINE_COMMENT, "$1")
}
