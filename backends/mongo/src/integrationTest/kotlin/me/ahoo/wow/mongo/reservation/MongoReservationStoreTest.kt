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
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.tck.reservation.ReservationStoreSpec
import org.junit.jupiter.api.Test
import java.time.Clock

class MongoReservationStoreTest : ReservationStoreSpec() {
    override fun createStore(clock: Clock): ReservationStore = store(clock)

    private fun store(clock: Clock = this.clock) =
        MongoReservationStore(MongoLogFixture.client, MongoLogFixture.DATABASE, clock = clock)

    @Test
    fun `a type's collection is created on its first use, once`(): Unit = runBlocking {
        val type = AggregateType("reservation_first", "user")
        val scope = ReservationScope(type, "t", "names")
        store().reserve(scope, "k", type.ref("t", "u-1"), "h", TTL).assert().isNotNull()
        store().find(scope, "k")?.owner.assert().isEqualTo(type.ref("t", "u-1"))
    }

    @Test
    fun `a collection of an earlier layout or another format is refused`() {
        // Format 1 had no record: its collection must be dropped (decision D59).
        val unrecorded = AggregateType("reservation_unrecorded", "user")
        MongoLogFixture.createUnrecorded(MongoNames.of("wow_reservation", unrecorded.context, unrecorded.name))
        assertThrownBy<IllegalStateException> {
            runBlocking { store().find(ReservationScope(unrecorded, "t", "names"), "k") }
        }
        val newer = AggregateType("reservation_newer", "user")
        runBlocking { store().find(ReservationScope(newer, "t", "names"), "k") }
        MongoLogFixture.bumpFormat(MongoNames.of("wow_reservation", newer.context, newer.name))
        assertThrownBy<IllegalStateException> {
            runBlocking { store().find(ReservationScope(newer, "t", "names"), "k") }
        }
    }

    @Test
    fun `an owner, a key and a hold that look like field paths are stored as they are`(): Unit = runBlocking {
        val store = store()
        val type = AggregateType("ctx", "\$aggregate")
        val scope = ReservationScope(type, "\$tenant", "field-path-" + System.nanoTime())
        val owner = type.ref("\$tenant.x", "\$id")
        store.reserve(scope, "\$key", owner, "\$hold", TTL)?.owner.assert().isEqualTo(owner)
        store.reserve(scope, "\$key", owner, "\$hold.2", TTL)?.holds.assert().isEqualTo(setOf("\$hold", "\$hold.2"))
        store.abandon(scope, "\$key", owner, "\$hold").assert().isTrue()
        store.confirm(scope, "\$key", owner, 1).assert().isEqualTo(ConfirmOutcome.Confirmed)
        store.reserve(scope, "\$key", owner, "\$hold", TTL)?.confirmed.assert().isEqualTo(true)
        store.find(scope, "\$key")?.owner.assert().isEqualTo(owner)
    }
}
