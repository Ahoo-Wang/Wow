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

package me.ahoo.wow.query.event.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.EventStreamObjectNodeMasker
import me.ahoo.wow.query.mask.EventStreamObjectNodeMaskerRegistry
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MaskingEventStreamQueryFilterTest {
    @Test
    fun `event stream mask should run once for typed and dynamic document operations only`() {
        val calls = AtomicInteger()
        val gateway = gateway(
            masker { node ->
                calls.incrementAndGet()
                node
            },
        )

        gateway.single(singleQuery { }).block().assert().isNotNull()
        gateway.dynamicSingle(singleQuery { }).block().assert().isNotNull()
        gateway.list(listQuery { }).single().block().assert().isNotNull()
        gateway.dynamicList(listQuery { }).single().block().assert().isNotNull()
        gateway.paged(pagedQuery { }).block()!!.list.assert().hasSize(1)
        gateway.dynamicPaged(pagedQuery { }).block()!!.list.assert().hasSize(1)
        calls.get().assert().isEqualTo(6)

        gateway.count(MatchAllFilter).block().assert().isOne()
        gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))).single().block()
        calls.get().assert().isEqualTo(6)
    }

    @Test
    fun `invalid event envelope mask should fail typed materialization`() {
        val handled = CopyOnWriteArrayList<Throwable>()
        val gateway = gateway(
            masker { node ->
                node.remove("body")
                node
            },
            ErrorHandler { _, error ->
                handled += error
                Mono.empty()
            },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!.has("body").assert().isFalse()
        StepVerifier.create(gateway.single(singleQuery { })).expectError().verify()
        handled.assert().hasSize(1)
    }

    private fun gateway(
        masker: EventStreamObjectNodeMasker,
        errorHandler: ErrorHandler<QueryContext<*, *>> = ErrorHandler { _, error -> Mono.error(error) },
    ): DefaultEventStreamQueryGateway {
        val registry = EventStreamObjectNodeMaskerRegistry().apply { register(masker) }
        return DefaultEventStreamQueryGateway(
            MOCK_AGGREGATE_METADATA,
            Backend,
            listOf(MaskingEventStreamQueryFilter(registry)),
            errorHandler,
        )
    }

    private fun masker(mask: (ObjectNode) -> ObjectNode) = object : EventStreamObjectNodeMasker {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun mask(node: ObjectNode): ObjectNode = mask(node)
    }

    private object Backend : EventStreamQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.fromSupplier(::eventNode)
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.defer { Flux.just(eventNode()) }
        override fun paged(
            query: IPagedQuery
        ): Mono<PagedList<ObjectNode>> = Mono.fromSupplier { PagedList(1, listOf(eventNode())) }
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(1)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.just("""{"count":1}""".toJsonNode())
    }

    private companion object {
        fun eventNode(): ObjectNode = generateEventStream(
            MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()),
        ).toJsonNode()
    }
}
