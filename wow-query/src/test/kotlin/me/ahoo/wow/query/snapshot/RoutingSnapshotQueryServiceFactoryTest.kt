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
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class RoutingSnapshotQueryServiceFactoryTest {

    @Test
    fun `should route snapshot query service factory by named aggregate`() {
        val defaultFactory = RecordingSnapshotQueryServiceFactory()
        val routedFactory = RecordingSnapshotQueryServiceFactory()
        val routingFactory = RoutingSnapshotQueryServiceFactory(
            defaultSnapshotQueryServiceFactory = defaultFactory,
            routes = mapOf(CART to routedFactory),
        )

        routingFactory.create<Any>(CART)

        routedFactory.lastNamedAggregate.assert().isEqualTo(CART)
        defaultFactory.lastNamedAggregate.assert().isNull()
    }

    @Test
    fun `should use default snapshot query service factory when route is missing`() {
        val defaultFactory = RecordingSnapshotQueryServiceFactory()
        val routedFactory = RecordingSnapshotQueryServiceFactory()
        val routingFactory = RoutingSnapshotQueryServiceFactory(
            defaultSnapshotQueryServiceFactory = defaultFactory,
            routes = mapOf(CART to routedFactory),
        )

        routingFactory.create<Any>(ORDER)

        defaultFactory.lastNamedAggregate.assert().isEqualTo(ORDER)
        routedFactory.lastNamedAggregate.assert().isNull()
    }

    private class RecordingSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
        var lastNamedAggregate: NamedAggregate? = null

        override fun <S : Any> create(namedAggregate: NamedAggregate): NoOpSnapshotQueryService<S> {
            lastNamedAggregate = namedAggregate
            return NoOpSnapshotQueryService(namedAggregate)
        }
    }

    companion object {
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
        private val CART = MaterializedNamedAggregate("order-service", "cart")
    }
}
