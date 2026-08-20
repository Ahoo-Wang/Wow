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
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
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
    fun `should preserve dynamic legacy projection and sort paths`() {
        backend.record = record().apply {
            (this["tags"] as ObjectNode).put("department", "sales")
        }
        adapter.dynamicSingle(
            SingleQuery(
                condition = Condition.ALL,
                projection = Projection(include = listOf("tags.department")),
                sort = listOf(Sort("tags.department", Sort.Direction.ASC))
            )
        ).test()
            .assertNext { document ->
                document.keys.assert().containsExactly("tags")
                document.getNestedDocument("tags")["department"].assert().isEqualTo("sales")
            }
            .verifyComplete()

        backend.lastQuery!!.sort.single().field.value.assert().isEqualTo("tags.department")
    }

    @Test
    fun `should apply both parts of a mixed legacy projection`() {
        adapter.dynamicSingle(
            SingleQuery(
                Condition.ALL,
                Projection(include = listOf("state"), exclude = listOf("state.data"))
            )
        ).test()
            .assertNext { document ->
                document.keys.assert().containsExactly("state")
                document.getNestedDocument("state").keys.assert().containsExactly("id")
            }
            .verifyComplete()

        (backend.lastQuery!!.projection as QueryProjection.Legacy).also { projection ->
            projection.include.assert().containsExactly("state")
            projection.exclude.assert().containsExactly("state.data")
        }
    }

    @Test
    fun `should preserve numeric legacy sort segments`() {
        adapter.dynamicSingle(
            SingleQuery(Condition.ALL, sort = listOf(Sort("state.items.0.price", Sort.Direction.DESC)))
        ).test().expectNextCount(1).verifyComplete()

        backend.lastQuery!!.sort.single().field.value.assert().isEqualTo("state.items.0.price")
    }

    @Test
    fun `should preserve legacy page sizes above the portable limit`() {
        adapter.dynamicPaged(PagedQuery(Condition.ALL, pagination = Pagination(index = 1, size = 1_001)))
            .test().expectNextCount(1).verifyComplete()

        backend.lastQuery!!.limit.assert().isEqualTo(1_001)
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
        backend.lastQuery!!.filter.legacyCondition().children.single()
            .deletionState().assert().isEqualTo(DeletionState.ALL)
    }

    @Test
    fun `should preserve one child nor`() {
        val lowered = LegacyConditionLowerer.lowerQuery(
            Condition.nor(Condition.eq("state.data", "secret"))
        ).first as LegacyConditionExpression

        val nor = lowered.condition.children.single { it.operator == me.ahoo.wow.api.query.Operator.NOR }
        nor.children.assert().hasSize(1)
    }

    @Test
    fun `should preserve legacy conditions inside the secured gateway`() {
        val compatible = LegacySnapshotQueryAdapter(
            SnapshotQueryGatewayFactory.create(
                JacksonQuerySchemaProvider(JsonSerializer),
                QueryRouter { backend },
                JsonSerializer
            ).create(metadata),
            metadata
        )
        val conditions = listOf(
            Condition.eq("state.data", "secret"),
            Condition.contains("state.data", "sec"),
            Condition.raw("{}"),
            Condition.beforeToday("eventTime", "08:00", "yyyy-MM-dd"),
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

    @Test
    fun `should not let legacy converter narrow deleted scope`() {
        backend.record = record(deleted = true)
        val condition = Condition.and(
            Condition.deleted(DeletionState.DELETED),
            Condition.raw("{}")
        )

        adapter.dynamicSingle(SingleQuery(condition)).test()
            .expectNextCount(1)
            .verifyComplete()

        backend.lastQuery!!.filter.deletionOperators().assert().containsExactly(PredicateOperator.IS_TRUE)
        val legacy = backend.lastQuery!!.filter.legacyCondition()
        legacy.children.single { it.operator == me.ahoo.wow.api.query.Operator.DELETED }
            .deletionState().assert().isEqualTo(DeletionState.ALL)
    }

    private fun QueryExpression.containsLegacyCondition(): Boolean = when (this) {
        is LegacyConditionExpression -> true
        is LogicalExpression -> operands.any { it.containsLegacyCondition() }
        is ElementMatchExpression -> predicate.containsLegacyCondition()
        else -> false
    }

    private fun QueryExpression.legacyCondition(): Condition = when (this) {
        is LegacyConditionExpression -> condition
        is LogicalExpression -> operands.firstNotNullOfOrNull { operand ->
            runCatching { operand.legacyCondition() }.getOrNull()
        } ?: error("Legacy condition is missing.")
        else -> error("Legacy condition is missing.")
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
