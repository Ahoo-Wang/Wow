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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger

class SnapshotQueryBackendFactoryTest {
    private val schemaProvider = object : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.just(SCHEMA)

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    @Test
    fun `should cache binding by materialized aggregate`() {
        val created = AtomicInteger()
        val factory = object : AbstractSnapshotQueryBackendFactory() {
            override fun createBinding(namedAggregate: NamedAggregate) = QueryBackendBinding(
                backend = StubSnapshotQueryBackend(namedAggregate),
                schemaProvider = schemaProvider,
            ).also {
                created.incrementAndGet()
            }
        }

        val first = factory.create(ORDER)
        factory.create(DecoratedNamedAggregate(ORDER)).assert().isSameAs(first)
        first.backend.namedAggregate.assert().isEqualTo(ORDER)
        first.schemaProvider.assert().isSameAs(schemaProvider)
        factory.create(CART).assert().isNotSameAs(first)
        created.get().assert().isEqualTo(2)
    }

    private class StubSnapshotQueryBackend(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryBackend {
        override val name: String = NoOpSnapshotStore.NAME
        override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> =
            Mono.fromSupplier(JsonSerializer::createObjectNode)
        override fun list(
            query: ResolvedQuery<IListQuery>,
        ): Flux<ObjectNode> = Flux.defer {
            Flux.just(JsonSerializer.createObjectNode())
        }
        override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> =
            Mono.fromSupplier { PagedList(1, listOf(JsonSerializer.createObjectNode())) }

        override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> =
            Mono.just(CursorPage(emptyList(), null))
        override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> = Mono.just(0L)
        override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> = Flux.empty()
    }

    private class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate,
    ) : NamedAggregateDecorator

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
        private val SCHEMA = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), emptyMap())
    }
}
