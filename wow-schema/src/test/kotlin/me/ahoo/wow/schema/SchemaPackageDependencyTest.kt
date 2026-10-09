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

package me.ahoo.wow.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The packages of `wow-schema` must form a DAG, the root package included: the root holds the builder and the modules
 * that assemble every other package, so no other package may depend on a root helper, only `query` on the builder.
 * Edges come from `import` lines; a fully qualified reference in code is not seen.
 */
class SchemaPackageDependencyTest {
    @Test
    fun `implementation packages form a DAG`() {
        val graph = implementationGraph()
        // An unresolved source root would yield an empty graph that is trivially acyclic.
        graph.keys.assert().contains(ROOT_NODE, "definition", "kotlin", "naming", "query", "typed")
        cycles(graph).assert().isEqualTo(KNOWN_CYCLES)
    }

    /** OpenAPI models and Jackson 2 belong to `wow-openapi`; this module reads only Swagger annotations. */
    @Test
    fun `main code references neither OpenAPI models nor Jackson 2 databind`() {
        val offenders = SOURCE_ROOT.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> FOREIGN_IMPORT.containsMatchIn(file.readText()) }
            .map { it.name }
            .toList()
        offenders.assert().isEmpty()
    }

    private fun implementationGraph(): Map<String, Set<String>> {
        val files = SOURCE_ROOT.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val sources = files.map { file ->
            val code = file.readText()
            requireNotNull(PACKAGE.find(code)).groupValues[1] to code
        }
        val packages = sources.mapTo(sortedSetOf()) { (name, _) -> name }
        return sources.groupBy({ it.first }, { it.second })
            .mapKeys { (name, _) -> name.relative() }
            .mapValues { (name, codes) ->
                codes.flatMap { code -> IMPORT.findAll(code).map { it.groupValues[1] } }
                    .mapNotNull { reference ->
                        packages.filter { reference.startsWith("$it.") }.maxByOrNull(String::length)
                    }
                    .map { target -> target.relative() }
                    .filterTo(sortedSetOf()) { target -> target != name }
            }
    }

    /** Strongly connected components with more than one package. */
    private fun cycles(graph: Map<String, Set<String>>): Set<Set<String>> {
        fun reachable(from: String): Set<String> {
            val seen = linkedSetOf<String>()
            val pending = ArrayDeque(graph[from].orEmpty())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (seen.add(next)) {
                    pending.addAll(graph[next].orEmpty())
                }
            }
            return seen
        }
        val reach = graph.keys.associateWith(::reachable)
        return graph.keys.map { node ->
            graph.keys.filterTo(
                sortedSetOf()
            ) { other -> other == node || (other in reach.getValue(node) && node in reach.getValue(other)) }
        }.filterTo(linkedSetOf()) { component -> component.size > 1 }
    }

    private fun String.relative(): String = if (this == ROOT) ROOT_NODE else removePrefix("$ROOT.")

    private companion object {
        const val ROOT = "me.ahoo.wow.schema"
        const val ROOT_NODE = "(root)"
        val SOURCE_ROOT = File("src/main/kotlin")
        val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^\s*import\s+(me\.ahoo\.wow\.schema\.[\w.]+)""", RegexOption.MULTILINE)

        val KNOWN_CYCLES: Set<Set<String>> = emptySet()
        val FOREIGN_IMPORT = Regex(
            """^\s*import\s+(io\.swagger\.v3\.oas\.models|com\.fasterxml\.jackson\.databind)\.""",
            RegexOption.MULTILINE
        )
    }
}
