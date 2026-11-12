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

package me.ahoo.wow.mongo.snapshot

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.event.ConnectionCheckedInEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import com.mongodb.event.ConnectionPoolListener
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A load its caller gives up on, the kernel past its snapshot load timeout, ends with its caller on a server that
 * stopped answering: the thread in the driver is interrupted, and its connection leaves the pool, instead of both
 * waiting out the store's operation timeout (30 seconds).
 */
class MongoSnapshotLoadCancelTest {
    @Test
    fun `a load given up on a paused server should give its connection back at once`(): Unit = runBlocking {
        val mongo = WowContainers.newMongo().also { it.start() }
        val inUse = AtomicInteger()
        val client = MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(mongo.replicaSetUrl))
                .applyToConnectionPoolSettings {
                    it.addConnectionPoolListener(
                        object : ConnectionPoolListener {
                            override fun connectionCheckedOut(event: ConnectionCheckedOutEvent) {
                                inUse.incrementAndGet()
                            }

                            override fun connectionCheckedIn(event: ConnectionCheckedInEvent) {
                                inUse.decrementAndGet()
                            }
                        },
                    )
                }
                .build(),
        )
        try {
            val store = MongoSnapshotStore(client, "load_cancel", CONTEXT).also { it.createCollection("account") }
            val ref = AggregateType(CONTEXT, "account").ref("tenant", "a-1")
            store.save(
                SnapshotRecord.of(ref, 1, "fingerprint", "{}", firstEventTime = 0, lastEventTime = 0)
            ).assert().isTrue()
            store.load(ref)?.version.assert().isEqualTo(1)
            inUse.get().assert().isZero()

            mongo.dockerClient.pauseContainerCmd(mongo.containerId).exec()
            try {
                val started = TimeSource.Monotonic.markNow()
                withTimeoutOrNull(GIVE_UP) { store.load(ref) }.assert().isNull()
                started.elapsedNow().assert().isLessThan(2.seconds)
                // The interrupted read closed its connection: none is held for the store's 30 seconds.
                eventually { inUse.get() == 0 }
            } finally {
                mongo.dockerClient.unpauseContainerCmd(mongo.containerId).exec()
            }
            // Back, the pool gives the next load a working connection.
            store.load(ref)?.version.assert().isEqualTo(1)
        } finally {
            client.close()
            mongo.stop()
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < 2.seconds) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val CONTEXT = "load_cancel"
        val GIVE_UP = 500.milliseconds
        const val POLL_MILLIS = 50L
    }
}
