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
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class RoutingEventStreamQueryBackendFactoryTest {
    @Test
    fun `should route aggregate once to configured factory`() {
        val defaultFactory = RecordingEventStreamQueryBackendFactory()
        val orderFactory = RecordingEventStreamQueryBackendFactory()
        val routing = RoutingEventStreamQueryBackendFactory(defaultFactory, mapOf(ORDER to orderFactory))

        routing.create(DecoratedNamedAggregate(ORDER)).assert().isSameAs(orderFactory.create(ORDER))
        routing.create(CART).assert().isSameAs(defaultFactory.create(CART))
        orderFactory.created.get().assert().isEqualTo(1)
        defaultFactory.created.get().assert().isEqualTo(1)
    }

    private class RecordingEventStreamQueryBackendFactory : AbstractEventStreamQueryBackendFactory() {
        val created = java.util.concurrent.atomic.AtomicInteger()

        override fun createBinding(namedAggregate: NamedAggregate): QueryBackendBinding<EventStreamQueryBackend> {
            created.incrementAndGet()
            return QueryBackendBinding(NoOpEventStreamQueryBackend(namedAggregate), schemaProvider)
        }

        private val schemaProvider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(SCHEMA)

            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
    }

    private class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate,
    ) : NamedAggregateDecorator

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
        private val SCHEMA = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
    }
}
