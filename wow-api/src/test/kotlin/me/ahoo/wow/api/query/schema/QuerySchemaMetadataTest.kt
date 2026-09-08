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

package me.ahoo.wow.api.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.module.kotlin.jsonMapper

class QuerySchemaMetadataTest {
    private val mapper = jsonMapper()

    @Test
    fun `metadata publishes recursive logical values without native details`() {
        val scalar = QueryValueSchemaMetadata(
            kind = QueryValueKind.SCALAR,
            title = "Status",
            enumValues = listOf(JsonNodeFactory.instance.stringNode("OPEN")),
            valueTypes = setOf(QueryValueType.STRING),
            capabilities = setOf(QueryCapability.EXACT_MATCH),
            masked = true,
        )
        val root = QueryValueSchemaMetadata(
            kind = QueryValueKind.OBJECT,
            properties = mapOf(
                "statuses" to QueryValueSchemaMetadata(QueryValueKind.ARRAY, items = scalar),
                "attributes" to QueryValueSchemaMetadata(QueryValueKind.OBJECT, additionalProperties = scalar),
                "unknown" to QueryValueSchemaMetadata(QueryValueKind.UNKNOWN),
                "choice" to QueryValueSchemaMetadata(QueryValueKind.UNION, alternatives = listOf(scalar, QueryValueSchemaMetadata(QueryValueKind.NULL))),
            ),
        )
        val model = QueryModelSchemaMetadata(QueryModel.SNAPSHOT, setOf(QueryCapability.FULL_TEXT_TERMS), root)
        val json = mapper.readTree(mapper.writeValueAsString(model))
        json["root"]["properties"]["statuses"]["items"]["masked"].booleanValue().assert().isTrue()
        json["root"]["properties"]["attributes"]["additionalProperties"]["enumValues"][0].asString().assert().isEqualTo(
            "OPEN"
        )
        json["root"]["properties"]["unknown"]["kind"].asString().assert().isEqualTo("UNKNOWN")
        json["root"]["properties"]["choice"]["alternatives"].size().assert().isEqualTo(2)
        json.toString().assert().doesNotContain("physicalPath", "storageType", "dynamicChildren", "bindings")
        mapper.readValue(
            mapper.writeValueAsString(model),
            QueryModelSchemaMetadata::class.java
        ).assert().isEqualTo(model)
    }
}
