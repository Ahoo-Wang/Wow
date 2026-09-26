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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger

class SnapshotQueryBackendFactoryTest {
    private val storage = object : QueryStorageAdapter {
        override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> =
            Mono.just(QueryStorageFacts(emptyMap()))
    }

    @Test
    fun `should cache binding by materialized aggregate`() {
        val created = AtomicInteger()
        val factory = object : AbstractSnapshotQueryBackendFactory() {
            override fun createBinding(namedAggregate: NamedAggregate) = QueryBackendBinding(
                backend = StubSnapshotQueryBackend(namedAggregate),
                storage = storage,
            ).also {
                created.incrementAndGet()
            }
        }

        val first = factory.create(ORDER)
        factory.create(DecoratedNamedAggregate(ORDER)).assert().isSameAs(first)
        first.backend.namedAggregate.assert().isEqualTo(ORDER)
        first.storage.assert().isSameAs(storage)
        factory.create(CART).assert().isNotSameAs(first)
        created.get().assert().isEqualTo(2)
    }

    private class StubSnapshotQueryBackend(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryBackend {
        override val name: String = NoOpSnapshotStore.NAME
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.defer {
            Flux.just(JsonSerializer.createObjectNode())
        }
        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
            Mono.fromSupplier { BackendPage(listOf(JsonSerializer.createObjectNode()), 1) }
        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0L)
        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.empty()
    }

    private class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate,
    ) : NamedAggregateDecorator

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
    }
}
