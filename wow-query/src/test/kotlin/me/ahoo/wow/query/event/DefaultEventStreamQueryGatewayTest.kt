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

package me.ahoo.wow.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList

class DefaultEventStreamQueryGatewayTest {
    @Test
    fun `typed result should materialize after the event filter chain`() {
        val eventStream = generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId()))
        val calls = CopyOnWriteArrayList<String>()
        val gateway = DefaultEventStreamQueryGateway(
            MOCK_AGGREGATE_METADATA,
            backend { Mono.fromSupplier { eventStream.toJsonNode<ObjectNode>() } },
            listOf(generic(calls), event(calls), snapshot(calls)),
            ErrorHandler { _, error -> Mono.error(error) },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!.path("id").textValue().assert().isEqualTo(eventStream.id)
        gateway.single(singleQuery { }).block()!!.id.assert().isEqualTo(eventStream.id)
        calls.assert().isEqualTo(listOf("generic", "event", "generic", "event"))
    }

    private fun generic(calls: MutableList<String>) = object : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "generic"
            return next.filter(context)
        }
    }

    private fun event(calls: MutableList<String>) = object : EventStreamQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "event"
            return next.filter(context)
        }
    }

    private fun snapshot(calls: MutableList<String>) = object : SnapshotQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls += "snapshot"
            return next.filter(context)
        }
    }

    private fun backend(single: () -> Mono<ObjectNode>) = object : EventStreamQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun single(query: ISingleQuery): Mono<ObjectNode> = single()
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }
}
