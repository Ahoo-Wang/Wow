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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.tck.reservation.ReservationStoreSpec
import org.junit.jupiter.api.Test
import java.time.Clock

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
    fun `a collection of an earlier layout or another format is refused`() {
        // Format 1 had no record: its collection must be dropped (decision D59).
        MongoLogFixture.createUnrecorded("reservation_unrecorded")
        assertThrownBy<IllegalStateException> { store("reservation_unrecorded") }
        store("reservation_newer")
        MongoLogFixture.bumpFormat("reservation_newer")
        assertThrownBy<IllegalStateException> { store("reservation_newer") }
    }

    @Test
    fun `an owner, a key and a hold that look like field paths are stored as they are`(): Unit = runBlocking {
        val store = store("wow_reservation")
        val scope = ReservationScope("ctx", "\$tenant", "field-path-" + System.nanoTime())
        val owner = AggregateType("ctx", "\$aggregate").ref("\$tenant.x", "\$id")
        store.reserve(scope, "\$key", owner, "\$hold", TTL)?.owner.assert().isEqualTo(owner)
        store.reserve(scope, "\$key", owner, "\$hold.2", TTL)?.holds.assert().isEqualTo(setOf("\$hold", "\$hold.2"))
        store.abandon(scope, "\$key", owner, "\$hold").assert().isTrue()
        store.confirm(scope, "\$key", owner).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.reserve(scope, "\$key", owner, "\$hold", TTL)?.confirmed.assert().isEqualTo(true)
        store.find(scope, "\$key")?.owner.assert().isEqualTo(owner)
    }
}
