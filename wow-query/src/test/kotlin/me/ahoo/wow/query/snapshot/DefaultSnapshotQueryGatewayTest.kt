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

package me.ahoo.wow.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList

class DefaultSnapshotQueryGatewayTest {
    @Test
    fun `typed and dynamic single should share object-node chain`() {
        val backendCalls = CopyOnWriteArrayList<QueryType>()
        val order = CopyOnWriteArrayList<String>()
        val backend = RecordingSnapshotBackend(MOCK_AGGREGATE_METADATA, backendCalls, order)
        val gateway = gateway(backend, listOf(around("a", order), around("b", order)))

        gateway.dynamicSingle(singleQuery { }).block()!!.path("state").path("value").textValue()
            .assert().isEqualTo("state-value")
        gateway.single(singleQuery { }).block()!!.state.value.assert().isEqualTo("state-value")

        backendCalls.assert().isEqualTo(listOf(QueryType.SINGLE, QueryType.SINGLE))
        order.take(5).assert().isEqualTo(listOf("a-request", "b-request", "backend", "b-result", "a-result"))
    }

    @Test
    fun `gateway should forward every operation to its bound backend`() {
        val calls = CopyOnWriteArrayList<QueryType>()
        val backend = RecordingSnapshotBackend(MOCK_AGGREGATE_METADATA, calls)
        val gateway = gateway(backend)

        gateway.dynamicList(listQuery { }).collectList().block()!!.assert().hasSize(1)
        gateway.list(listQuery { }).collectList().block()!!.single().state.value.assert().isEqualTo("state-value")
        gateway.dynamicPaged(pagedQuery { }).block()!!.total.assert().isOne()
        gateway.paged(pagedQuery { }).block()!!.list.single().state.value.assert().isEqualTo("state-value")
        gateway.count(MatchAllFilter).block().assert().isOne()
        gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
            .single().block()!!.path("count").longValue().assert().isOne()

        calls.assert().isEqualTo(
            listOf(
                QueryType.LIST,
                QueryType.LIST,
                QueryType.PAGED,
                QueryType.PAGED,
                QueryType.COUNT,
                QueryType.AGGREGATION
            ),
        )
    }

    private fun gateway(
        backend: SnapshotQueryBackend,
        filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
    ): DefaultSnapshotQueryGateway<TestState> = DefaultSnapshotQueryGateway(
        namedAggregate = MOCK_AGGREGATE_METADATA,
        backend = backend,
        targetType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            TestState::class.java,
        ),
        filters = filters,
        errorHandler = ErrorHandler { _, error -> Mono.error(error) },
    )

    private fun around(name: String, order: MutableList<String>) = object : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            order += "$name-request"
            return next.filter(context).then(Mono.fromRunnable { order += "$name-result" })
        }
    }

    private class RecordingSnapshotBackend(
        override val namedAggregate: NamedAggregate,
        private val calls: MutableList<QueryType>,
        private val order: MutableList<String>? = null,
    ) : SnapshotQueryBackend {
        override val name: String = "recording"

        override fun single(query: ISingleQuery): Mono<ObjectNode> {
            calls += QueryType.SINGLE
            order?.add("backend")
            return Mono.fromSupplier(::snapshotNode)
        }

        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.defer {
            Flux.just(record(QueryType.LIST, snapshotNode()))
        }

        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.fromSupplier {
            PagedList(1, listOf(record(QueryType.PAGED, snapshotNode())))
        }

        override fun count(filter: FilterExpression): Mono<Long> = Mono.fromSupplier {
            calls += QueryType.COUNT
            1L
        }

        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.defer {
            calls += QueryType.AGGREGATION
            Flux.just("""{"count":1}""".toJsonNode())
        }

        private fun record(queryType: QueryType, node: ObjectNode): ObjectNode {
            calls += queryType
            order?.add("backend")
            return node
        }
    }

    private data class TestState(val value: String)

    private companion object {
        fun snapshotNode(): ObjectNode = """
            {
              "contextName":"mock",
              "aggregateName":"mock",
              "tenantId":"tenant",
              "ownerId":"_default_",
              "spaceId":"_default_",
              "aggregateId":"aggregate",
              "version":1,
              "eventId":"event",
              "firstOperator":"operator",
              "operator":"operator",
              "firstEventTime":1,
              "eventTime":1,
              "state":{"value":"state-value"},
              "snapshotTime":1,
              "tags":{},
              "deleted":false
            }
        """.toJsonNode()
    }
}
