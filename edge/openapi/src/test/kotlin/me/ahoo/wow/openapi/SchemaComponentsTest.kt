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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.components
import me.ahoo.wow.openapi.OpenApiFixtures.ref
import org.junit.jupiter.api.Test

/** The Manifest's schemas as the document's components ([SchemaComponents]), named as OpenAPI allows. */
class SchemaComponentsTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `the Manifest's schemas should become components, named as OpenAPI allows`() {
        val schemas = document.components("schemas")
        schemas.keys.assert().contains("sample.Cart_State", "sample.Cart_State_2", "wow.CommandResult_2", "sample.Item")
        schemas["sample.Cart_State"].assert().isEqualTo(
            mapOf("type" to "object", "properties" to mapOf("item" to ref("schemas", "sample.Item"))),
        )
        schemas["wow.CommandResult_2"].assert().isEqualTo(mapOf("type" to "string", "minLength" to 1))
        val unresolved = SchemaComponents(emptyMap(), emptySet())
        unresolved.ref(Manifest.schemaRef("a.B\$C")).assert().isEqualTo(ref("schemas", "a.B_C"))
        // Only a reference into $defs moves: not another document's, nor a property named $ref.
        val kept = mapOf(
            "\$ref" to "https://example.com/schema",
            "properties" to mapOf("\$ref" to mapOf("type" to "string")),
        )
        SchemaComponents(mapOf("a.B" to kept), emptySet()).schemas.assert().isEqualTo(mapOf("a.B" to kept))
    }

    @Test
    fun `a nullable type argument should name a component apart from its non-null sibling`() {
        val strict = "a.Box(java.lang.String)"
        val nullable = "a.Box(java.lang.String?)"
        SchemaComponents(mapOf(strict to emptyMap<String, Any?>(), nullable to emptyMap<String, Any?>()), emptySet())
            .names.assert().isEqualTo(
                mapOf(strict to "a.Box_java.lang.String_", nullable to "a.Box_java.lang.String__")
            )
    }
}
