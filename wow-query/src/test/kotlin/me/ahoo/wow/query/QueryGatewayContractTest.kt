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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class QueryGatewayContractTest {
    @Test
    fun `snapshot and event gateways should materialize model types after raw backend results`() {
        val snapshot = snapshotGateway.single(singleQuery { }).block()!!
        snapshot.assert().isInstanceOf(MaterializedSnapshot::class.java)
        snapshot.state.assert().isEqualTo(State("raw"))
        eventGateway.single(singleQuery { }).block().assert().isInstanceOf(DomainEventStream::class.java)
        snapshotGateway.cursor(CursorQuery(MatchAllFilter)).block()!!.let { page ->
            page.nextCursor.assert().isEqualTo("next")
            page.list.single().assert().isInstanceOf(MaterializedSnapshot::class.java)
        }
        eventGateway.cursor(CursorQuery(MatchAllFilter)).block()!!.let { page ->
            page.nextCursor.assert().isEqualTo("next")
            page.list.single().assert().isInstanceOf(DomainEventStream::class.java)
        }
    }

    private val snapshotGateway = DefaultSnapshotQueryGateway<State>(
        MOCK_AGGREGATE_METADATA,
        SnapshotBackend,
        JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, State::class.java),
        errorHandler = ErrorHandler { _, error -> Mono.error(error) },
    )
    private val eventGateway = DefaultEventStreamQueryGateway(
        MOCK_AGGREGATE_METADATA,
        EventBackend,
        errorHandler = ErrorHandler { _, error -> Mono.error(error) },
    )

    private data class State(val value: String)

    private object SnapshotBackend : SnapshotQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "contract"
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.just(snapshotNode())
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
            Mono.fromSupplier { CursorPage(listOf(snapshotNode()), "next") }
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private object EventBackend : EventStreamQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.just(eventNode())
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
            Mono.fromSupplier { CursorPage(listOf(eventNode()), "next") }
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private companion object {
        fun snapshotNode(): ObjectNode = """
            {"contextName":"mock","aggregateName":"mock","tenantId":"tenant","ownerId":"_default_",
             "spaceId":"_default_","aggregateId":"aggregate","version":1,"eventId":"event",
             "firstOperator":"operator","operator":"operator","firstEventTime":1,"eventTime":1,
             "state":{"value":"raw"},"snapshotTime":1,"tags":{},"deleted":false}
        """.toJsonNode()

        fun eventNode(): ObjectNode = generateEventStream(
            MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()),
        ).toJsonNode()
    }
}
