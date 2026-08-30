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
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger

class SnapshotQueryBackendFactoryTest {
    @Test
    fun `should cache backend by materialized aggregate`() {
        val created = AtomicInteger()
        val factory = object : AbstractSnapshotQueryBackendFactory() {
            override fun createBackend(namedAggregate: NamedAggregate): SnapshotQueryBackend {
                created.incrementAndGet()
                return StubSnapshotQueryBackend(namedAggregate)
            }
        }

        factory.create<Any>(ORDER).assert().isSameAs(factory.create<Any>(DecoratedNamedAggregate(ORDER)))
        factory.create<Any>(CART).assert().isNotSameAs(factory.create<Any>(ORDER))
        created.get().assert().isEqualTo(2)
    }

    private class StubSnapshotQueryBackend(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryBackend {
        override val name: String = NoOpSnapshotStore.NAME
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.fromSupplier(JsonSerializer::createObjectNode)
        override fun list(
            query: IListQuery,
        ): Flux<ObjectNode> = Flux.defer {
            Flux.just(JsonSerializer.createObjectNode())
        }
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> =
            Mono.fromSupplier { PagedList(1, listOf(JsonSerializer.createObjectNode())) }

        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
            Mono.just(CursorPage(emptyList(), null))
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0L)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate,
    ) : NamedAggregateDecorator

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
    }
}
