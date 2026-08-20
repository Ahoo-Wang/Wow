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

package me.ahoo.wow.query.policy

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

class CompositeQueryPolicyTest {
    private val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    private val schema = JacksonQuerySchemaProvider(JsonSerializer).getSchema(metadata)
    private val fieldId = LogicalField("state.id")
    private val fieldData = LogicalField("state.data")

    @Test
    fun `should merge authorization independently of policy order`() {
        val first = policy(fieldId, setOf(fieldId, fieldData), 100)
        val second = policy(fieldData, setOf(fieldData), 50)

        val forward = CompositeQueryPolicy(listOf(first, second)).evaluate(context()).block()!!
        val reverse = CompositeQueryPolicy(listOf(second, first)).evaluate(context()).block()!!

        forward.decision.assert().isEqualTo(reverse.decision)
        forward.fieldAccess.assert().isEqualTo(QueryFieldAccess.Restricted(setOf(fieldData)))
        forward.maximumBudget.maxRecords.assert().isEqualTo(50)
        (forward.mandatoryFilter as LogicalExpression).operands.toSet().assert()
            .isEqualTo((reverse.mandatoryFilter as LogicalExpression).operands.toSet())
    }

    private fun policy(field: LogicalField, access: Set<LogicalField>, maximum: Long): QueryPolicy = QueryPolicy {
        Mono.just(
            QueryAuthorization(
                decision = QueryDecision.GRANT,
                mandatoryFilter = PredicateExpression(
                    field,
                    PredicateOperator.EQ,
                    listOf(JsonNodeFactory.instance.textNode("value"))
                ),
                fieldAccess = QueryFieldAccess.Restricted(access),
                maximumBudget = QueryBudget(maxRecords = maximum)
            )
        )
    }

    private fun context(): QueryPolicyContext = QueryPolicyContext(
        target = metadata.namedAggregate,
        stateType = MockStateAggregate::class.java,
        operation = QueryOperation.STREAM,
        resultKind = QueryResultKind.RECORD,
        query = Query(),
        authority = QueryAuthority.ANONYMOUS,
        schema = schema,
        subscribedAt = Instant.EPOCH,
        deadline = null
    )
}
