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

import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.Ddl
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test

/**
 * A store asks the server whether it shards once, as it is built: never lazily on its first write, where the round
 * trip would wait under a lock and a server that cannot answer would fail a command rather than the start.
 */
class MongoStoreShardingTest {
    /** The `hello` commands the admin database received. */
    private var hellos = 0
    private var unreachable = false

    private val admin: MongoDatabase = mockk {
        every { withTimeout(any(), any()) } answers { self as MongoDatabase }
        every { runCommand(any<Bson>()) } answers {
            hellos++
            if (unreachable) throw MongoTimeoutException("no server")
            Document("msg", "isdbgrid")
        }
    }
    private val database: MongoDatabase = mockk(relaxed = true) {
        every { withWriteConcern(any()) } answers { self as MongoDatabase }
        every { withTimeout(any(), any()) } answers { self as MongoDatabase }
    }
    private val client: MongoClient = mockk {
        every { getDatabase("admin") } returns admin
        every { getDatabase(DATABASE) } returns database
    }

    /** Each store of a context, built with [sharding] and [ddl]. */
    private val stores: List<(MongoSharding, Ddl) -> Any> = listOf(
        { sharding, ddl -> MongoEventLog(client, DATABASE, CONTEXT, 2, ddl = ddl, sharding = sharding) },
        { sharding, ddl -> MongoSnapshotStore(client, DATABASE, CONTEXT, ddl = ddl, sharding = sharding) },
        { sharding, ddl -> MongoTimerStore(client, DATABASE, CONTEXT, 2, ddl = ddl, sharding = sharding) },
        { sharding, ddl -> MongoReservationStore(client, DATABASE, ddl = ddl, sharding = sharding) },
    )

    @Test
    fun `each store should ask the server once, as it is built`() {
        stores.forEach { build ->
            hellos = 0
            build(MongoSharding.AUTO, Ddl.AUTO)
            hellos.assert().isEqualTo(1)
        }
    }

    @Test
    fun `a store that does not shard, or creates no schema, should not ask`() {
        stores.forEach { build ->
            build(MongoSharding.NONE, Ddl.AUTO)
            build(MongoSharding.AUTO, Ddl.VALIDATE)
            build(MongoSharding.AUTO, Ddl.NONE)
        }
        hellos.assert().isZero()
    }

    @Test
    fun `a server that cannot answer should fail the store as it is built`() {
        unreachable = true
        stores.forEach { build ->
            assertThrownBy<MongoTimeoutException> { build(MongoSharding.AUTO, Ddl.AUTO) }
        }
    }

    private companion object {
        const val DATABASE = "wow"
        const val CONTEXT = "shop"
    }
}
