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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.LogicalExpression
import me.ahoo.wow.api.query.LogicalOperator
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

class LegacySnapshotQueryAdapterTest {
    private val metadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    private val backend = RecordingBackend()
    private val adapter = LegacySnapshotQueryAdapter(
        SnapshotQueryGatewayFactory.create(
            JacksonQuerySchemaProvider(JsonSerializer),
            QueryRouter { backend },
            JsonSerializer
        ).create(metadata),
        metadata
    )

    @Test
    fun `should apply legacy projection before typed materialization`() {
        val projection = Projection(exclude = listOf("state.data"))
        adapter.single(
            SingleQuery(Condition.ALL, projection)
        ).test()
            .assertNext { snapshot -> snapshot.state.data.assert().isEmpty() }
            .verifyComplete()

        adapter.list(ListQuery(Condition.ALL, projection)).test()
            .assertNext { snapshot -> snapshot.state.data.assert().isEmpty() }
            .verifyComplete()

        adapter.paged(PagedQuery(Condition.ALL, projection)).test()
            .assertNext { page -> page.list.single().state.data.assert().isEmpty() }
            .verifyComplete()
    }

    @Test
    fun `should reject mixed legacy projection before execution`() {
        val error = assertThrows<QueryException> {
            adapter.dynamicSingle(
                SingleQuery(
                    Condition.ALL,
                    Projection(include = listOf("state"), exclude = listOf("state.data"))
                )
            )
        }

        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
    }

    @Test
    fun `should keep zero list limit unbounded`() {
        backend.records = Flux.empty()

        adapter.list(ListQuery(Condition.ALL, limit = 0)).test().verifyComplete()

        backend.lastQuery!!.budget.maxRecords.assert().isNull()
    }

    @Test
    fun `should preserve legacy deletion scope`() {
        backend.record = record(deleted = true)

        adapter.dynamicSingle(SingleQuery(Condition.deleted(DeletionState.DELETED)))
            .test()
            .expectNextCount(1)
            .verifyComplete()
        backend.lastQuery!!.filter.deletionOperators().assert().containsExactly(PredicateOperator.IS_TRUE)

        adapter.count(Condition.deleted(DeletionState.ALL)).test().expectNext(0).verifyComplete()
        backend.lastQuery!!.filter.assert().isSameAs(MatchAll)
    }

    @Test
    fun `should preserve one child nor`() {
        val lowered = LegacyConditionLowerer.lower(
            Condition.nor(Condition.eq("state.data", "secret"))
        ) as LogicalExpression

        lowered.operator.assert().isEqualTo(LogicalOperator.NOR)
        lowered.operands.assert().hasSize(1)
    }

    @Test
    fun `should keep backend specific legacy conditions inside the secured gateway`() {
        val compatible = LegacySnapshotQueryAdapter(
            SnapshotQueryGatewayFactory.create(
                JacksonQuerySchemaProvider(JsonSerializer),
                QueryRouter { backend },
                JsonSerializer
            ).create(metadata),
            metadata
        )
        val conditions = listOf(
            Condition.raw("{}"),
            Condition.tomorrow("state.data", "yyyy-MM-dd"),
            Condition.exists("tags.department"),
            Condition.isNull("state.data")
        )

        conditions.forEach { condition ->
            compatible.dynamicSingle(SingleQuery(condition)).test()
                .expectNextCount(1)
                .verifyComplete()
            backend.lastQuery!!.filter.containsLegacyCondition().assert().isTrue()
        }
    }

    private fun QueryExpression.containsLegacyCondition(): Boolean = when (this) {
        is LegacyConditionExpression -> true
        is LogicalExpression -> operands.any { it.containsLegacyCondition() }
        is ElementMatchExpression -> predicate.containsLegacyCondition()
        else -> false
    }

    private fun QueryExpression.deletionOperators(): List<PredicateOperator> = when (this) {
        is PredicateExpression -> if (field.value == "deleted") listOf(operator) else emptyList()
        is LogicalExpression -> operands.flatMap { it.deletionOperators() }
        is ElementMatchExpression -> predicate.deletionOperators()
        else -> emptyList()
    }

    private fun record(deleted: Boolean = false): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("contextName", metadata.contextName)
        put("aggregateName", metadata.aggregateName)
        put("tenantId", "tenant-1")
        put("ownerId", "owner-1")
        put("spaceId", "space-1")
        put("version", 1)
        put("aggregateId", "aggregate-1")
        put("eventId", "event-1")
        put("firstOperator", "operator-1")
        put("operator", "operator-1")
        put("firstEventTime", "2026-08-19T00:00:00Z")
        put("eventTime", "2026-08-19T00:00:00Z")
        put("snapshotTime", "2026-08-19T00:00:00Z")
        set("tags", JsonNodeFactory.instance.objectNode())
        put("deleted", deleted)
        set(
            "state",
            JsonNodeFactory.instance.objectNode().apply {
                put("id", "aggregate-1")
                put("data", "secret")
            }
        )
    }

    private inner class RecordingBackend : QueryBackend {
        override val id: String = "recording"
        var record: ObjectNode = record()
        var records: Flux<ObjectNode>? = null
        var lastQuery: SecuredQuery? = null

        override fun validate(query: SecuredQuery) = Unit

        override fun stream(query: SecuredQuery): Flux<ObjectNode> {
            lastQuery = query
            return records ?: Flux.just(record)
        }

        override fun page(query: SecuredQuery): Mono<QueryPage<ObjectNode>> {
            lastQuery = query
            return Mono.just(QueryPage(listOf(record), 1))
        }

        override fun count(query: SecuredQuery): Mono<Long> {
            lastQuery = query
            return Mono.just(0)
        }
    }
}
