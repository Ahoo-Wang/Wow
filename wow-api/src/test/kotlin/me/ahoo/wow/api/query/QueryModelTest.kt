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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

class QueryModelTest {
    @Test
    fun `should serialize ObjectNode page without record wrapper`() {
        val record = JsonNodeFactory.instance.objectNode().put("aggregateId", "order-1")
        val json = JsonMapper.builder().build().writeValueAsString(QueryPage(listOf(record), 1))

        json.assert().isEqualTo("{\"items\":[{\"aggregateId\":\"order-1\"}],\"total\":1}")
    }

    @Test
    fun `should distinguish missing from explicit null`() {
        val record = JsonNodeFactory.instance.objectNode().putNull("explicitNull")

        (record["missing"] == null).assert().isTrue()
        record["explicitNull"].isNull.assert().isTrue()
    }

    @Test
    fun `should reject invalid query values`() {
        assertThrows<IllegalArgumentException> { LogicalField("state..name") }
        assertThrows<IllegalArgumentException> { QueryScope(tenantId = " ") }
        assertThrows<IllegalArgumentException> { QueryPage(emptyList<String>(), -1) }
    }

    @Test
    fun `should preserve compatibility-only field and projection shapes`() {
        LogicalField("state.items.0.price").value.assert().isEqualTo("state.items.0.price")
        QueryProjection.Legacy(include = listOf("state"), exclude = listOf("state.secret")).assert().isNotNull()
    }
}
