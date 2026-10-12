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

package me.ahoo.wow.mongo.reservation

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.reservation.ReservationStoreSpec
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.time.Duration

class MongoReservationStoreTest : ReservationStoreSpec() {
    override fun createStore(clock: Clock): ReservationStore = store("wow_reservation", clock)

    private fun store(collection: String, clock: Clock = this.clock) =
        MongoReservationStore(MongoLogFixture.client, MongoLogFixture.DATABASE, collection, clock)
            .also { it.createSchema() }

    @Test
    fun `creating the schema twice is harmless`() {
        store("reservation_twice")
        store("reservation_twice")
    }

    @Test
    fun `a time to live that is not positive is refused`() {
        val store = store("wow_reservation")
        assertThrownBy<IllegalArgumentException> {
            runBlocking { store.reserve("ns-ttl", "alice", "user-1", Duration.ZERO) }
        }
    }

    @Test
    fun `an owner name that looks like a field path is stored as is`(): Unit = runBlocking {
        val store = store("wow_reservation")
        val namespace = "field-path-" + System.nanoTime()
        store.reserve(namespace, "alice", "\$owner", TTL)?.owner.assert().isEqualTo("\$owner")
        store.confirm(namespace, "alice", "\$owner").assert().isTrue()
        store.reserve(namespace, "alice", "\$owner", TTL)?.confirmed.assert().isEqualTo(true)
    }
}
