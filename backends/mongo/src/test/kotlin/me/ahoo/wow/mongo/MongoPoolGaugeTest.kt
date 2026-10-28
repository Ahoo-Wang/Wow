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

package me.ahoo.wow.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.client.MongoClient
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ConnectionId
import com.mongodb.connection.ConnectionPoolSettings
import com.mongodb.connection.ServerId
import com.mongodb.event.ConnectionCheckOutFailedEvent
import com.mongodb.event.ConnectionCheckOutStartedEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import com.mongodb.event.ConnectionPoolCreatedEvent
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.store.StoreQueryBounds
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.store.facet
import org.junit.jupiter.api.Test

/** A MongoDB store's pool, as the client's gauge says it: the bound of its queries and its waiting check-outs. */
class MongoPoolGaugeTest {
    private val server = ServerId(ClusterId(), ServerAddress())

    private fun started() = ConnectionCheckOutStartedEvent(server, 1)

    private fun checkedOut() = ConnectionCheckedOutEvent(ConnectionId(server), 1, 1)

    private fun failed() = ConnectionCheckOutFailedEvent(server, 1, ConnectionCheckOutFailedEvent.Reason.TIMEOUT, 1)

    @Test
    fun `a gauge no client was built with says nothing`() {
        val gauge = MongoPoolGauge()
        gauge.maxConnections.assert().isNull()
        gauge.connectionCheckOutStarted(started())
        gauge.waiting().assert().isNull()
    }

    @Test
    fun `a gauge applied to a client's settings reads their size and counts the check-outs that wait`() {
        val gauge = MongoPoolGauge()
        MongoClientSettings.builder().applyToConnectionPoolSettings { builder ->
            builder.maxSize(40)
            gauge.applyTo(builder)
        }.build().connectionPoolSettings.connectionPoolListeners.assert().contains(gauge)
        gauge.maxConnections.assert().isEqualTo(40)
        gauge.waiting().assert().isZero()
        repeat(3) { gauge.connectionCheckOutStarted(started()) }
        gauge.waiting().assert().isEqualTo(3)
        gauge.connectionCheckedOut(checkedOut())
        gauge.connectionCheckOutFailed(failed())
        gauge.waiting().assert().isEqualTo(1)
        gauge.toString().assert().isEqualTo("MongoPoolGauge(maxConnections=40, waiting=1)")
    }

    @Test
    fun `a gauge applied after a connection string reads the pool size it sets`() {
        val gauge = MongoPoolGauge()
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("mongodb://mongo:27017/?maxPoolSize=7"))
            .applyToConnectionPoolSettings(gauge::applyTo)
            .build()
        gauge.maxConnections.assert().isEqualTo(7)
    }

    @Test
    fun `a pool the driver creates says its size`() {
        val gauge = MongoPoolGauge()
        gauge.connectionPoolCreated(
            ConnectionPoolCreatedEvent(server, ConnectionPoolSettings.builder().maxSize(8).build())
        )
        gauge.maxConnections.assert().isEqualTo(8)
    }

    private fun store(resources: Map<String, Any>) = MongoStoreProvider().open(
        StoreConfig.of(
            "main",
            MongoStoreProvider.TYPE,
            resources = StoreResources.of(mapOf<String, Any>("client" to mockk<MongoClient>()) + resources),
        ),
    )

    @Test
    fun `a store's pool is its client's gauge, which its queries share, and none without a gauge`() {
        val gauge = MongoPoolGauge()
        gauge.applyTo(ConnectionPoolSettings.builder().maxSize(100))
        val pool = checkNotNull(store(mapOf("gauge" to gauge)).facet<StorePool>())
        pool.queryConnections.assert().isEqualTo(100)
        pool.sharedWithAppends.assert().isTrue()
        pool.waitingForConnection().assert().isZero()
        // The queries take a quarter of the pool the appends share.
        StoreQueryBounds.maxConcurrent(pool).assert().isEqualTo(25)
        store(emptyMap()).facet<StorePool>().assert().isNull()
        // A gauge no client was built with is no pool.
        store(mapOf("gauge" to MongoPoolGauge())).facet<StorePool>().assert().isNull()
    }
}
