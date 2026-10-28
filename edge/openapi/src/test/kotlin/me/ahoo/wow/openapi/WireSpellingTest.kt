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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ManifestSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Wow spells every value of its own enumerations lower-kebab on the wire: the Manifest's JSON Schema, and in the
 * committed OpenAPI documents the `wow.*` components and each operation's `x-wow-command`. A domain's enumerations,
 * in its own components, are spelled as its Jackson writes them and are not checked.
 */
class WireSpellingTest {
    @Test
    fun `every enumeration of the Manifest's schema should be lower-kebab`() {
        val values = enums(ManifestSchema.schema, "manifest")
        values.assert().isNotEmpty()
        values.filterNot { (_, value) -> KEBAB.matches(value) }.assert().isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["catalog", "basket", "ordering", "inventory", "payment"])
    fun `every enumeration of Wow's components and commands should be lower-kebab in the committed documents`(
        context: String,
    ) {
        val document = OpenApiChecks.mapper.readValue(
            SNAPSHOTS.resolve("eshop-$context-service.json").readText(),
            Map::class.java,
        )
        val schemas = (document["components"] as Map<*, *>)["schemas"] as Map<*, *>
        val wow = schemas.filterKeys { (it as String).startsWith("wow.") }
            .flatMap { (name, schema) -> enums(schema, name as String) }
        val creations = (document["paths"] as Map<*, *>).values.flatMap { (it as Map<*, *>).values }
            .mapNotNull { ((it as? Map<*, *>)?.get("x-wow-command") as? Map<*, *>)?.get("creation") as String? }
            .map { "x-wow-command" to it }
        wow.assert().isNotEmpty()
        creations.assert().isNotEmpty()
        (wow + creations).filterNot { (_, value) -> KEBAB.matches(value) }.assert().isEmpty()
    }

    private fun enums(node: Any?, path: String): List<Pair<String, String>> = when (node) {
        is Map<*, *> -> node.flatMap { (key, value) ->
            if (key == "enum") {
                (value as List<*>).filterIsInstance<String>().map { path to it }
            } else {
                enums(value, "$path/$key")
            }
        }

        is List<*> -> node.flatMap { enums(it, path) }
        else -> emptyList()
    }

    private companion object {
        val KEBAB = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        val SNAPSHOTS: Path = Path.of("src/test/resources/openapi")
    }
}
