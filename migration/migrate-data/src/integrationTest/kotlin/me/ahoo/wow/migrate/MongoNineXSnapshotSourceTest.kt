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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

class MongoNineXSnapshotSourceTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val database = NineXMongoStore.example()
    private val source = MongoNineXSnapshotSource(database)

    @Test
    fun `the snapshots read as the 9x example wrote them, decimals exact`() {
        val snapshot = source.snapshots(order, null, 10).single()
        snapshot.assert().isEqualTo(NineXExample.snapshot("order-snapshot.json"))
        snapshot.state.get("paidAmount").decimalValue().assert().isEqualTo("25.00".toBigDecimal())
        source.snapshots(cart, null, 10).single().aggregateId.id.assert().isEqualTo("0VY3GwKq4OmV001")
        source.instances(order).assert().isEqualTo(1)
    }

    @Test
    fun `snapshots page in aggregate id order, within their context`() {
        val text = NineXExample.resource("order-snapshot.json")
        NineXMongoStore.saveSnapshot(database, text.replace("0VY3GwaK4OmV00E", "z-order"))
        NineXMongoStore.saveSnapshot(
            database,
            text.replace("0VY3GwaK4OmV00E", "other-context").replace("example-service", "other-service"),
        )
        val first = source.snapshots(order, null, 1)
        first.single().aggregateId.id.assert().isEqualTo("0VY3GwaK4OmV00E")
        source.snapshots(order, first.single().aggregateId, 10).map { it.aggregateId.id }.assert()
            .containsExactly("z-order")
        source.snapshots(order, NineXAggregateId("z-order", "(0)"), 10).assert().isEmpty()
        source.instances(NineXAggregate("other-service", "order")).assert().isEqualTo(0)
    }
}
