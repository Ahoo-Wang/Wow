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
import me.ahoo.wow.api.query.LogicalField
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.module.kotlin.jsonMapper

class QuerySchemaMetadataTest {
    private val jsonMapper = jsonMapper()

    @Test
    fun `field schema exposes logical metadata without storage details`() {
        val field = QueryFieldSchemaMetadata(
            field = LogicalField("status"),
            title = "Status",
            description = "Current order status.",
            enumValues = listOf(JsonNodeFactory.instance.stringNode("OPEN")),
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            capabilities = setOf(QueryCapability.EXACT_MATCH),
            masked = true,
        )

        val json = jsonMapper.readTree(jsonMapper.writeValueAsString(field))

        json.get("field").asString().assert().isEqualTo("status")
        json.get("enumValues")[0].asString().assert().isEqualTo("OPEN")
        json.get("masked").booleanValue().assert().isTrue()
        json.has("physicalPath").assert().isFalse()
        json.has("storageType").assert().isFalse()
    }

    @Test
    fun `model schema retains its capabilities and field list`() {
        val field = QueryFieldSchemaMetadata(
            field = LogicalField("createdAt"),
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.INTEGER),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = Temporal.Epoch(),
            dynamicChildren = false,
            capabilities = setOf(QueryCapability.RANGE),
            masked = false,
        )
        val metadata = QueryModelSchemaMetadata(
            model = QueryModel.SNAPSHOT,
            capabilities = setOf(QueryCapability.SORT),
            fields = listOf(field),
        )

        metadata.model.assert().isEqualTo(QueryModel.SNAPSHOT)
        metadata.capabilities.assert().containsExactly(QueryCapability.SORT)
        metadata.fields.assert().containsExactly(field)
    }
}
