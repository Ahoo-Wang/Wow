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
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class StandaloneSchemaEmbeddingRebaserTest {
    private val objectMapper = JsonMapper.builder().build()

    @Test
    fun `should rebase supported local references`() {
        val schema = objectMapper.createObjectNode()
            .put("\$id", "urn:root")
            .put("\$ref", "#/definitions/node")
        val oneOf = schema.putObject("definitions").putObject("node").putArray("oneOf")
        oneOf.addObject().put("\$ref", "#")
        oneOf.addObject().put("\$ref", "#/components/schemas/existing")

        StandaloneSchemaEmbeddingRebaser.rebase(
            schema = schema,
            schemaName = "wow.schema.Node~/Leaf",
            definitionPath = "components/schemas",
        )

        val componentPath = "#/components/schemas/wow.schema.Node~0~1Leaf"
        schema["\$id"].assert().isNull()
        schema["\$ref"].stringValue().assert().isEqualTo("$componentPath/definitions/node")
        schema.path("definitions").path("node").path("oneOf").path(0).path("\$ref").stringValue()
            .assert().isEqualTo(componentPath)
        schema.path("definitions").path("node").path("oneOf").path(1).path("\$ref").stringValue()
            .assert().isEqualTo("#/components/schemas/existing")
    }

    @Test
    fun `should only rebase local references matching custom definition path`() {
        val schema = objectMapper.createObjectNode()
            .put("\$id", "urn:root")
            .put("\$ref", "#/\$defs/node")
        val oneOf = schema.putObject("\$defs").putObject("node").putArray("oneOf")
        oneOf.addObject().put("\$ref", "#/\$defs/node")
        oneOf.addObject().put("\$ref", "#/\$defs/existing")

        StandaloneSchemaEmbeddingRebaser.rebase(
            schema = schema,
            schemaName = "wow.schema.Node~/Leaf",
            definitionPath = "\$defs",
        )

        val componentPath = "#/\$defs/wow.schema.Node~0~1Leaf"
        schema["\$id"].assert().isNull()
        schema["\$ref"].stringValue().assert().isEqualTo("$componentPath/\$defs/node")
        schema.path("\$defs").path("node").path("oneOf").path(0).path("\$ref").stringValue()
            .assert().isEqualTo("$componentPath/\$defs/node")
        schema.path("\$defs").path("node").path("oneOf").path(1).path("\$ref").stringValue()
            .assert().isEqualTo("#/\$defs/existing")
    }

    @Test
    fun `should preserve nested schema resources`() {
        val schema = objectMapper.createObjectNode()
            .put("\$id", "urn:root")
            .put("\$ref", "#/definitions/node")
        schema.putObject("definitions").putObject("node")
            .put("\$id", "urn:nested")
            .put("\$ref", "#")

        StandaloneSchemaEmbeddingRebaser.rebase(schema, "wow.schema.Node", "components/schemas")

        schema["\$id"].stringValue().assert().isEqualTo("urn:root")
        schema["\$ref"].stringValue().assert().isEqualTo("#/definitions/node")
        schema.path("definitions").path("node").path("\$ref").stringValue().assert().isEqualTo("#")
    }

    @Test
    fun `should preserve unsupported references`() {
        val schema = objectMapper.createObjectNode()
            .put("\$id", "urn:root")
            .put("\$ref", "other.schema.json")

        StandaloneSchemaEmbeddingRebaser.rebase(schema, "wow.schema.Node", "components/schemas")

        schema["\$id"].stringValue().assert().isEqualTo("urn:root")
        schema["\$ref"].stringValue().assert().isEqualTo("other.schema.json")
    }
}
