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

package me.ahoo.wow.schema.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.schema.WowSchemaLoader
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class OpenAPISchemaConverterTest {

    @Test
    fun `should convert json schema node to swagger schema`() {
        val jsonNode = WowSchemaLoader.load("AggregateId")

        val schema = OpenAPISchemaConverter().toSchema(jsonNode)

        schema.types.assert().contains("object")
        schema.properties.assert().containsKey("aggregateId")
    }

    @Test
    fun `should convert textual defaults to declared json types`() {
        val jsonNode = JsonMapper.builder().build().readTree(
            """
            {
              "type": "object",
              "properties": {
                "integer": { "type": "integer", "default": "0" },
                "array": { "type": "array", "default": "[]" },
                "object": { "type": "object", "default": "{}" },
                "boolean": { "type": "boolean", "default": "true" },
                "string": { "type": "string", "default": "true" }
              }
            }
            """.trimIndent(),
        )

        val properties = OpenAPISchemaConverter().toSchema(jsonNode).properties

        properties["integer"]?.default.assert().isEqualTo(0)
        properties["array"]?.default.assert().isEqualTo(emptyList<Any>())
        properties["object"]?.default.assert().isEqualTo(emptyMap<String, Any>())
        properties["boolean"]?.default.assert().isEqualTo(true)
        properties["string"]?.default.assert().isEqualTo("true")
    }
}
