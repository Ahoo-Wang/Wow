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
package me.ahoo.wow.spring.boot.starter.eventsourcing.routing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment

class StorageRoutingPropertiesTest {

    @Test
    fun `should bind aggregate event and snapshot routes`() {
        val environment = MockEnvironment()
            .withProperty(
                "${StorageRoutingProperties.AGGREGATES}.order.event.storage",
                StorageType.REDIS.name.lowercase()
            )
            .withProperty(
                "${StorageRoutingProperties.AGGREGATES}.cart.snapshot.storage",
                StorageType.REDIS.name.lowercase()
            )
            .withProperty(
                "${StorageRoutingProperties.AGGREGATES}.audit.event.binding",
                "archive-event-store"
            )
            .withProperty(
                "${StorageRoutingProperties.AGGREGATES}.audit.snapshot.binding",
                "archive-snapshot-store"
            )

        val properties = Binder.get(environment)
            .bind(StorageRoutingProperties.PREFIX, StorageRoutingProperties::class.java)
            .get()

        val orderRoute = properties.aggregates.getValue("order")
        orderRoute.event!!.storage.assert().isEqualTo(StorageType.REDIS)
        orderRoute.snapshot.assert().isNull()

        val cartRoute = properties.aggregates.getValue("cart")
        cartRoute.event.assert().isNull()
        cartRoute.snapshot!!.storage.assert().isEqualTo(StorageType.REDIS)

        val auditRoute = properties.aggregates.getValue("audit")
        auditRoute.event!!.binding.assert().isEqualTo("archive-event-store")
        auditRoute.snapshot!!.binding.assert().isEqualTo("archive-snapshot-store")
    }

    @Test
    fun `should bind full aggregate key with bracket notation`() {
        val environment = MockEnvironment()
            .withProperty(
                "${StorageRoutingProperties.AGGREGATES}[order-service.order].event.storage",
                StorageType.REDIS.name.lowercase()
            )

        val properties = Binder.get(environment)
            .bind(StorageRoutingProperties.PREFIX, StorageRoutingProperties::class.java)
            .get()

        properties.aggregates.keys.assert().contains("order-service.order")
        properties.aggregates.getValue("order-service.order").event!!.storage
            .assert().isEqualTo(StorageType.REDIS)
    }
}
