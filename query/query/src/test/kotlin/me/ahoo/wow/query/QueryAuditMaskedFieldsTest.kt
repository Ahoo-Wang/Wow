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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryAudit
import me.ahoo.wow.query.observe.QueryAuditTrail
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test

class QueryAuditMaskedFieldsTest {
    private val schema = gatewaySchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(
            QueryField("aggregateId") to QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING)
            ),
            // A map of masked values: every map key's value is masked.
            QueryField("state.contacts") to QueryValueSchema(
                QueryValueKind.OBJECT,
                additionalProperties = QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING),
                    maskRule = MaskRule(SensitivityLevel.DISPLAY),
                ),
            ),
        ),
    )

    private fun maskedFields(projection: Projection, queryType: QueryType = QueryType.LIST): List<String> {
        val query = ListQuery(MatchAllFilter, projection, limit = 10)
        val trail = QueryAuditTrail(AggregateType("audit", "order"), queryType, query, QueryCaller.IN_PROCESS)
        trail.schema(schema)
        trail.admitted(query)
        return trail.audit(QueryAudit.Outcome.COMPLETE).maskedFields
    }

    @Test
    fun `a map key of a masked map value matches its key template`() {
        schema.maskedFields.assert().containsExactly("state.contacts.{key}")
        maskedFields(Projection(include = listOf(QueryField("state.contacts.home"))))
            .assert().containsExactly("state.contacts.{key}")
        maskedFields(Projection(include = listOf(QueryField("state"))))
            .assert().containsExactly("state.contacts.{key}")
        maskedFields(Projection(include = listOf(QueryField("state.contacts.home.detail"))))
            .assert().containsExactly("state.contacts.{key}")
        // Excluding one key leaves the others masked; excluding the map removes them all.
        maskedFields(Projection(exclude = listOf(QueryField("state.contacts.home"))))
            .assert().containsExactly("state.contacts.{key}")
        maskedFields(Projection(exclude = listOf(QueryField("state.contacts")))).assert().isEmpty()
        maskedFields(Projection(include = listOf(QueryField("aggregateId")))).assert().isEmpty()
    }

    @Test
    fun `counts and aggregations carry no masked fields`() {
        maskedFields(Projection.ALL).assert().containsExactly("state.contacts.{key}")
        maskedFields(Projection.ALL, QueryType.COUNT).assert().isEmpty()
        maskedFields(Projection.ALL, QueryType.AGGREGATION).assert().isEmpty()
    }
}
