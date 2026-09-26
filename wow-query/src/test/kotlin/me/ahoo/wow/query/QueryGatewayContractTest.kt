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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
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
        snapshotGateway.cursor(CursorQuery(MatchAllFilter, size = 1)).block()!!.let { page ->
            page.nextCursor.assert().isNotNull()
            page.list.single().assert().isInstanceOf(MaterializedSnapshot::class.java)
        }
        eventGateway.cursor(CursorQuery(MatchAllFilter, size = 1)).block()!!.let { page ->
            page.nextCursor.assert().isNotNull()
            page.list.single().assert().isInstanceOf(DomainEventStream::class.java)
        }
    }

    private val snapshotGateway = DefaultSnapshotQueryGateway<State>(
        MOCK_AGGREGATE_METADATA,
        SnapshotBackend,
        SnapshotSchemaProvider,

        JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, State::class.java),
    )
    private val eventGateway = DefaultEventStreamQueryGateway(
        MOCK_AGGREGATE_METADATA,
        EventBackend,
        EventSchemaProvider,

    )

    private data class State(val value: String)

    private object SnapshotBackend : SnapshotQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "contract"
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()
        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
            Mono.fromSupplier { twoRows(snapshotNode(), snapshotNode()) }
        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.empty()
    }

    private object EventBackend : EventStreamQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()
        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> {
            val sort = query.query.sort
            return Mono.fromSupplier {
                if (window is PageWindow.Keyset) {
                    sort.assert().containsExactly(
                        me.ahoo.wow.api.query.Sort(
                            me.ahoo.wow.api.query.QueryField("id"),
                            me.ahoo.wow.api.query.Sort.Direction.ASC
                        )
                    )
                }
                twoRows(eventNode(), eventNode())
            }
        }
        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.empty()
    }

    private object SnapshotSchemaProvider : QueryModelSchemaProvider {
        private val schema = gatewaySchema(QueryModel.SNAPSHOT)
        override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private object EventSchemaProvider : QueryModelSchemaProvider {
        private val schema = gatewaySchema(QueryModel.EVENT_STREAM)
        override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private companion object {
        /** Two rows with positions: one page of one row plus the look-ahead, so a next cursor exists. */
        fun twoRows(first: ObjectNode, second: ObjectNode): BackendPage = BackendPage(
            listOf(first, second),
            2,
            listOf(CursorPosition(listOf("first")), CursorPosition(listOf("second"))),
        )

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
