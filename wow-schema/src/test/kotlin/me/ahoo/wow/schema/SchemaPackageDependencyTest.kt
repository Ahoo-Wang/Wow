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
 * The packages below `me.ahoo.wow.schema` must form a DAG. The root package holds the builder and the modules that
 * assemble every other package, so it is left out of the graph.
 */
class SchemaPackageDependencyTest {
    @Test
    fun `implementation packages form a DAG`() {
        val graph = implementationGraph()
        // An unresolved source root would yield an empty graph that is trivially acyclic.
        graph.keys.assert().contains("definition", "kotlin", "naming", "query", "typed")
        cycles(graph).assert().isEqualTo(KNOWN_CYCLES)
    }

    private fun implementationGraph(): Map<String, Set<String>> {
        val files = SOURCE_ROOT.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val sources = files.map { file ->
            val code = file.readText()
            requireNotNull(PACKAGE.find(code)).groupValues[1] to code
        }
        val packages = sources.mapTo(sortedSetOf()) { (name, _) -> name }
        return sources.groupBy({ it.first }, { it.second })
            .filterKeys { name -> name != ROOT }
            .mapKeys { (name, _) -> name.relative() }
            .mapValues { (name, codes) ->
                codes.flatMap { code -> IMPORT.findAll(code).map { it.groupValues[1] } }
                    .mapNotNull { reference ->
                        packages.filter { reference.startsWith("$it.") }.maxByOrNull(String::length)
                    }
                    .filter { target -> target != ROOT }
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

    private fun String.relative(): String = removePrefix("$ROOT.")

    private companion object {
        const val ROOT = "me.ahoo.wow.schema"
        val SOURCE_ROOT = File("src/main/kotlin")
        val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^\s*import\s+(me\.ahoo\.wow\.schema\.[\w.]+)""", RegexOption.MULTILINE)

        val KNOWN_CYCLES: Set<Set<String>> = emptySet()
    }
}
