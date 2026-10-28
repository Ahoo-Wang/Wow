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
 * a second time. A line that matches [allowed] is the one place a forbidden member may stand: a parameter's default,
 * say.
 */
data class SourceTokenRule(
    val name: String,
    val modules: List<String>,
    val forbidden: List<String>,
    val allowed: Regex? = null,
) {
    private val pattern = Regex("""(?<![\w$])(${forbidden.joinToString("|") { Regex.escape(it) }})(?![\w$])""")

    fun violations(repositoryRoot: File): List<String> =
        modules.flatMap { module ->
            listOf("src/main/kotlin", "src/main/java").map { File(File(repositoryRoot, module), it) }
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") } }
        }.sortedBy { it.path }.flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            stripComments(file.readText()).lines().flatMapIndexed { index, line ->
                pattern.findAll(
                    allowed?.let { line.replace(it, "") } ?: line
                ).map { "$path:${index + 1}: ${it.value}" }.toList()
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

/** The source token rules over the production sources. */
object SourceTokenRules {
    /**
     * The read side's scope authorization has one home, `:query`'s `QueryTargets` (review R5.C-1): which scope a
     * caller may name (`Caller.crossScope`), the cross-tenant switch and the fixed tenants. wow-http passes on what a
     * request names; it reads none of the members those rules rest on, and builds no `QueryCaller` itself: its callers
     * come from `QueryTargets.of`.
     */
    val HTTP_DECIDES_NO_READ_SCOPE = SourceTokenRule(
        name = "wow-http reads no crossScope, decides no tenant and builds no QueryCaller",
        modules = listOf(ArchitectureRules.HTTP.dir),
        forbidden = listOf("crossScope", "fixedTenant", "tenantOf", "QueryCaller("),
    )

    /** The core modules name no host: a configuration rule carries its key as data, which a host renders. */
    val CORE_NAMES_NO_HOST = SourceTokenRule(
        name = "wow-spi, wow-kernel and wow-runtime name no host",
        modules = listOf(ArchitectureRules.SPI.dir, ArchitectureRules.KERNEL.dir, ArchitectureRules.RUNTIME.dir),
        forbidden = listOf("Spring"),
    )

    /**
     * A backend decides what a `wow.ddl` mode does through `Ddl.ensure` or `Ddl.apply` (and `Ddl.creates`,
     * `Ddl.checks`), so each mode means the same in every store: its code names no mode, but for a parameter's default.
     */
    val BACKENDS_BRANCH_NO_DDL_MODE = SourceTokenRule(
        name = "the backends name no ddl mode outside Ddl.ensure and Ddl.apply",
        modules = listOf(
            ArchitectureRules.POSTGRES.dir,
            ArchitectureRules.MONGO.dir,
            ArchitectureRules.ELASTICSEARCH.dir,
            ArchitectureRules.CLICKHOUSE.dir,
            ArchitectureRules.KAFKA.dir,
        ),
        forbidden = listOf("Ddl.AUTO", "Ddl.VALIDATE", "Ddl.NONE"),
        allowed = Regex(""":\s*Ddl\s*=\s*Ddl\.\w+"""),
    )

    /**
     * `UNKNOWN` of a result's status, a hop's stage or a function's kind is a reader's only (D348 H4): a reader older
     * than its writer reads a value it does not know as it. No production source produces one, so no Wow writer ever
     * writes it; a source names it only to compare a value it read with it, or in a `when` branch that handles one.
     */
    val NO_WRITER_PRODUCES_UNKNOWN = SourceTokenRule(
        name = "no production source produces UNKNOWN of a status, stage or function kind",
        modules = (ArchitectureRules.MODULES + ArchitectureRules.PLATFORM_SERVERS).map { it.dir },
        forbidden = listOf("Status.UNKNOWN", "Stage.UNKNOWN", "FunctionKind.UNKNOWN"),
        allowed = Regex(
            """(==|!=)\s*(\w+\.)*(Status|Stage|FunctionKind)\.UNKNOWN|(Status|Stage|FunctionKind)\.UNKNOWN\s*(,|->)"""
        ),
    )

    val ALL = listOf(
        HTTP_DECIDES_NO_READ_SCOPE,
        CORE_NAMES_NO_HOST,
        BACKENDS_BRANCH_NO_DDL_MODE,
        NO_WRITER_PRODUCES_UNKNOWN
    )
}
