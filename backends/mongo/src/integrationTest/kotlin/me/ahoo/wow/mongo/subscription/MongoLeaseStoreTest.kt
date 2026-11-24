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

package me.ahoo.wow.mongo.subscription

import com.mongodb.client.ClientSession
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.tck.MutableClock
import me.ahoo.wow.tck.subscription.LeaseStoreSpec
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class MongoLeaseStoreTest : LeaseStoreSpec() {
    override fun createStore(clock: Clock): LeaseStore =
        MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE, clock)

    @Test
    fun `an owner name that looks like a field path is stored as is`(): Unit = runBlocking {
        val store = createStore(MutableClock())
        val group = "field-path-" + System.nanoTime()
        store.acquire(group, 0, "\$owner", 10.seconds)?.owner.assert().isEqualTo("\$owner")
        store.acquire(group, 0, "\$owner", 10.seconds)?.epoch.assert().isEqualTo(1L)
    }

    @Test
    fun `a lease call blocked behind a transaction fails within its TTL`(): Unit = runBlocking {
        // The store waits 5 s for a reply; the calls under test give their TTL, a second. A write outside a
        // transaction waits for a transaction that wrote the same document, as one waiting for a majority does.
        val store = MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE, operationTimeout = OPERATION)
        val group = "bounded-" + UUID.randomUUID()
        store.acquire(group, 0, "node-a", TTL).assert().isNotNull()
        store.heartbeat(group, "node-a", "10.0.0.1:9090", TTL)
        MongoLogFixture.client.startSession().use { session ->
            session.startTransaction()
            try {
                session.touch("wow_lease", Filters.and(Filters.eq(GROUP, group), Filters.eq("partition", 0)))
                session.touch("wow_lease_member", Filters.and(Filters.eq(GROUP, group), Filters.eq("node", "node-a")))
                within(SHORT) { store.acquire(group, 0, "node-a", SHORT) }
                within(SHORT) { store.renew(group, listOf(0), "node-a", SHORT) }
                within(SHORT) { store.heartbeat(group, "node-a", "10.0.0.1:9090", SHORT) }
                // A call no transaction holds still answers.
                store.acquire(group, 1, "node-a", TTL)?.owner.assert().isEqualTo("node-a")
            } finally {
                session.abortTransaction()
            }
        }
    }

    /** Writes the document of [collection] that [filter] finds in this session's transaction, which holds it. */
    private fun ClientSession.touch(collection: String, filter: Bson) {
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection)
            .updateOne(this, filter, Updates.set("touched", true))
            .matchedCount.assert().isEqualTo(1L)
    }

    /** [call] fails as the store unavailable within [ttl] and a margin, long before the store's own timeout. */
    private fun within(ttl: Duration, call: suspend () -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        assertThrownBy<StorageUnavailableException> { runBlocking { call() } }
        started.elapsedNow().assert().isLessThan(ttl + MARGIN)
    }

    private companion object {
        const val GROUP = "subscription_group"
        val TTL = 10.seconds

        /** The TTL of the calls a transaction holds, and the margin they may take past it. */
        val SHORT = 1.seconds
        val MARGIN = 1.5.seconds

        /** The store's own operation timeout in the bounded test: what a call without the bound would wait. */
        val OPERATION = 5.seconds

        init {
            MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE).createSchema()
        }
    }
}
