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

package me.ahoo.wow.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class QueryJsonTest {
    @Test
    fun `should round trip the shared HTTP query model`() {
        val query = Query(
            filter = PredicateExpression(
                LogicalField("state.status"),
                PredicateOperator.EQ,
                listOf(JsonNodeFactory.instance.textNode("ACTIVE"))
            ),
            projection = QueryProjection.Include(setOf(LogicalField("state.status")))
        )

        val json = JsonSerializer.writeValueAsString(query)
        val decoded = JsonSerializer.readValue(json, Query::class.java)

        decoded.assert().isEqualTo(query)
    }
}
