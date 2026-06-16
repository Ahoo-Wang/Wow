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

package me.ahoo.wow.openapi.snapshot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.test.asserts.assert
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal object OpenApiSnapshotSupport {
    private val mapper = ObjectMapperFactory.createJson()
    private val updateSnapshots: Boolean
        get() = System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)

    fun assertOpenApiSnapshot(openAPI: OpenAPI, snapshotPath: Path) {
        val canonical = mapper.writeValueAsString(canonicalize(mapper.valueToTree<JsonNode>(openAPI)))
        assertSnapshot(canonical, snapshotPath)
    }

    fun assertContractSnapshot(contractJson: String, snapshotPath: Path) {
        val canonical = mapper.writeValueAsString(canonicalize(mapper.readTree(contractJson)))
        assertSnapshot(canonical, snapshotPath)
    }

    private fun assertSnapshot(canonical: String, snapshotPath: Path) {
        snapshotPath.parent.createDirectories()
        val actual = pretty(canonical)
        if (updateSnapshots) {
            snapshotPath.writeText(actual)
            return
        }
        if (!snapshotPath.exists()) {
            throw AssertionError(
                "Missing OpenAPI compatibility snapshot: $snapshotPath. " +
                    "Run with -Dwow.snapshot.update=true to create or update snapshots."
            )
        }
        actual.assert().isEqualTo(snapshotPath.readText())
    }

    private fun pretty(json: String): String {
        val prettyJson = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapper.readTree(json))
        return prettyJson + System.lineSeparator()
    }

    private fun canonicalize(node: JsonNode): JsonNode {
        return when (node) {
            is ObjectNode -> canonicalizeObject(node)
            is ArrayNode -> canonicalizeArray(node)
            else -> node
        }
    }

    private fun canonicalizeObject(node: ObjectNode): ObjectNode {
        val result = mapper.createObjectNode()
        node.fieldNames().asSequence().sorted().forEach { fieldName ->
            if (!isNoisyField(fieldName)) {
                result.set<JsonNode>(fieldName, canonicalize(node.get(fieldName)))
            }
        }
        return result
    }

    private fun canonicalizeArray(node: ArrayNode): ArrayNode {
        val result = mapper.createArrayNode()
        val values = node.map(::canonicalize)
        val sortedValues = if (values.all { it is ObjectNode && sortableObject(it) }) {
            values.sortedBy { sortableKey(it as ObjectNode) }
        } else {
            values
        }
        sortedValues.forEach(result::add)
        return result
    }

    private fun isNoisyField(fieldName: String): Boolean {
        return fieldName == "description" || fieldName == "summary"
    }

    private fun sortableObject(node: ObjectNode): Boolean {
        return node.has("name") || node.has("operationId") || node.has("\$ref") || node.has("in")
    }

    private fun sortableKey(node: ObjectNode): String {
        return listOf("name", "operationId", "\$ref", "in")
            .mapNotNull { fieldName -> node.get(fieldName)?.asText() }
            .joinToString("|")
    }

    fun resourcePath(relativePath: String): Path {
        val currentDirectory = Path.of("").toAbsolutePath().normalize()
        val testResources = if (currentDirectory.fileName.toString() == "wow-openapi") {
            currentDirectory.resolve("src/test/resources")
        } else {
            currentDirectory.resolve("wow-openapi/src/test/resources")
        }
        return testResources.resolve(relativePath)
    }

    fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}
