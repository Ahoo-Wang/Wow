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
                session.touch("wow_lease_member", Filters.and(Filters.eq(SERVICE, group), Filters.eq("node", "node-a")))
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

    @Test
    fun `a heartbeat whose delete hangs still returns its member within a quarter of its ttl`(): Unit =
        runBlocking {
            // A crashed node's record, written by a clock a minute behind: expired for more than ten TTLs of 4 s.
            val group = "expired-" + UUID.randomUUID()
            val behind = Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(-1))
            MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE, behind)
                .heartbeat(group, "node-crashed", "10.0.0.9:9090", HEARTBEAT_TTL)
            val store = MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE, operationTimeout = OPERATION)
            MongoLogFixture.client.startSession().use { session ->
                session.startTransaction()
                try {
                    // The transaction holds the crashed node's record, so the heartbeat's delete waits for it.
                    val crashed = Filters.and(Filters.eq(SERVICE, group), Filters.eq("node", "node-crashed"))
                    session.touch("wow_lease_member", crashed)
                    val started = TimeSource.Monotonic.markNow()
                    store.heartbeat(group, "node-a", "10.0.0.1:9090", HEARTBEAT_TTL).node.assert().isEqualTo("node-a")
                    // A quarter of the TTL for the delete, not the rest of it: under 2.5 s where the TTL is 4 s.
                    started.elapsedNow().assert().isLessThan(HEARTBEAT_TTL / 4 + MARGIN)
                } finally {
                    session.abortTransaction()
                }
            }
            store.members(group).map { it.node }.assert().containsExactly("node-a")
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
        /** A lease's group, which a lease document is keyed by. */
        const val GROUP = "subscription_group"

        /** A member's service, which a member document is keyed by with its node. */
        const val SERVICE = "service"
        val TTL = 10.seconds

        /** The TTL of the calls a transaction holds, and the margin they may take past it. */
        val SHORT = 1.seconds
        val MARGIN = 1.5.seconds

        /** The store's own operation timeout in the bounded test: what a call without the bound would wait. */
        val OPERATION = 5.seconds

        /** The TTL of the heartbeat whose delete hangs: long enough that its quarter and the rest of it differ. */
        val HEARTBEAT_TTL = 4.seconds

        init {
            MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE).createSchema()
        }
    }
}
