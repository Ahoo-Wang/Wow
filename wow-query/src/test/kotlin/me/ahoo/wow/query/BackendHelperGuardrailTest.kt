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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The storage backends read operands and paths through wow-query's shared helpers ([operandValue],
 * [requiredOperandValue], [AdmittedQuery.physicalPath], [AdmittedQuery.systemPath]) instead of private copies that
 * drift apart. This scans the MongoDB and Elasticsearch main sources for the private declarations the helpers
 * replace.
 *
 * [ALLOWED] lists tolerated occurrences by file and pattern, with their count. Audit wave 4 moved both backends over,
 * so it is empty; it can only shrink: a new occurrence fails, and so does an allowlisted one that has gone.
 */
class BackendHelperGuardrailTest {
    @Test
    fun `backends use the shared operand and path helpers`() {
        val found = occurrences()
        val unexpected = found.filter { (key, count) -> count > (ALLOWED[key] ?: 0) }
        unexpected.assert().describedAs("Use the wow-query helpers instead of: $unexpected").isEmpty()
        val stale = ALLOWED.filter { (key, count) -> (found[key] ?: 0) < count }
        stale.assert().describedAs("Lower the allowlist to what remains: $stale (found $found)").isEmpty()
    }

    private fun occurrences(): Map<Pair<String, String>, Int> = buildMap {
        SOURCE_ROOTS.forEach { root ->
            val directory = File(REPOSITORY, root)
            check(directory.isDirectory) { "Missing source root [$directory]." }
            directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val path = file.relativeTo(REPOSITORY).invariantSeparatorsPath
                val text = file.readText()
                PATTERNS.forEach { (name, regex) ->
                    val count = regex.findAll(text).count()
                    if (count > 0) put(path to name, count)
                }
            }
        }
    }

    private companion object {
        val REPOSITORY = File("..").canonicalFile

        val SOURCE_ROOTS = listOf("wow-mongo/src/main/kotlin", "wow-elasticsearch/src/main/kotlin")

        const val NATIVE_VALUE = "fun JsonNode.nativeValue"
        const val PHYSICAL_PATH = "fun QueryField.physicalPath"
        const val PHYSICAL_FIELD_PATH = ".physicalField.path"

        val PATTERNS = mapOf(
            NATIVE_VALUE to Regex("""fun\s+(tools\.jackson\.databind\.)?JsonNode\.nativeValue\b"""),
            PHYSICAL_PATH to Regex("""fun\s+QueryField\.physicalPath\b"""),
            PHYSICAL_FIELD_PATH to Regex("""\.physicalField\.path\b"""),
        )

        val ALLOWED: Map<Pair<String, String>, Int> = emptyMap()
    }
}
