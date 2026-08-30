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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class RoutingSnapshotQueryBackendFactoryTest {
    @Test
    fun `should route aggregate once to configured factory`() {
        val defaultFactory = RecordingSnapshotQueryBackendFactory()
        val orderFactory = RecordingSnapshotQueryBackendFactory()
        val routing = RoutingSnapshotQueryBackendFactory(defaultFactory, mapOf(ORDER to orderFactory))

        routing.create<Any>(DecoratedNamedAggregate(ORDER)).assert().isSameAs(orderFactory.create<Any>(ORDER))
        routing.create<Any>(CART).assert().isSameAs(defaultFactory.create<Any>(CART))
        orderFactory.created.get().assert().isEqualTo(1)
        defaultFactory.created.get().assert().isEqualTo(1)
    }

    private class RecordingSnapshotQueryBackendFactory : AbstractSnapshotQueryBackendFactory() {
        val created = java.util.concurrent.atomic.AtomicInteger()

        override fun createBackend(namedAggregate: NamedAggregate): SnapshotQueryBackend {
            created.incrementAndGet()
            return NoOpSnapshotQueryBackend(namedAggregate)
        }
    }

    private class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate,
    ) : NamedAggregateDecorator

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
    }
}
