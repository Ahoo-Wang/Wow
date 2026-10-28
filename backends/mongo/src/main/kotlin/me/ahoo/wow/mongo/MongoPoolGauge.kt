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

import com.mongodb.connection.ConnectionPoolSettings
import com.mongodb.event.ConnectionCheckOutFailedEvent
import com.mongodb.event.ConnectionCheckOutStartedEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import com.mongodb.event.ConnectionPoolCreatedEvent
import com.mongodb.event.ConnectionPoolListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a `MongoClient`'s connection pool says of itself, for a MongoDB store's pool ([me.ahoo.wow.spi.store.StorePool]):
 * the most connections a server's pool opens ([maxConnections]) and how many check-outs wait now ([waiting]). The node
 * bounds the store's queries by the first and reads the store's write pressure from the second. One gauge listens to
 * every server pool the client opens: [waiting] sums their check-outs, [maxConnections] is one pool's size, the same
 * for each.
 *
 * It is a [ConnectionPoolListener] the client is built with: the host adds it to the `MongoClient` it or Spring Boot
 * makes. An application that builds its own client adds it itself, with [applyTo] after the settings that size the
 * pool (a connection string's `maxPoolSize`, say), and gives it to the store as a
 * bean (the store's `pool-gauge` option names it when there are several):
 *
 * ```kotlin
 * val gauge = MongoPoolGauge()
 * val settings = MongoClientSettings.builder()
 *     .applyConnectionString(uri)
 *     .applyToConnectionPoolSettings(gauge::applyTo)
 *     .build()
 * val client = MongoClients.create(settings)
 * ```
 *
 * A store without one, or with one no client was built with, says nothing of its pool: its queries take the default
 * bound.
 */
class MongoPoolGauge : ConnectionPoolListener {
    @Volatile
    private var maxSize: Int? = null

    private val pending = AtomicInteger()

    /** The most connections a server's pool opens; null until a client was built with this gauge. */
    val maxConnections: Int? get() = maxSize

    /** How many check-outs of the client's pools wait now for a connection; null until a client was built with it. */
    fun waiting(): Int? = maxSize?.let { pending.get().coerceAtLeast(0) }

    /** Adds this gauge to the pool settings [builder] makes, and reads the size they have now. */
    fun applyTo(builder: ConnectionPoolSettings.Builder) {
        builder.addConnectionPoolListener(this)
        maxSize = builder.build().maxSize.takeIf { it > 0 }
    }

    override fun connectionPoolCreated(event: ConnectionPoolCreatedEvent) {
        maxSize = event.settings.maxSize.takeIf { it > 0 }
    }

    override fun connectionCheckOutStarted(event: ConnectionCheckOutStartedEvent) {
        pending.incrementAndGet()
    }

    override fun connectionCheckedOut(event: ConnectionCheckedOutEvent) {
        pending.decrementAndGet()
    }

    override fun connectionCheckOutFailed(event: ConnectionCheckOutFailedEvent) {
        pending.decrementAndGet()
    }

    override fun toString(): String = "MongoPoolGauge(maxConnections=$maxSize, waiting=${pending.get()})"
}
