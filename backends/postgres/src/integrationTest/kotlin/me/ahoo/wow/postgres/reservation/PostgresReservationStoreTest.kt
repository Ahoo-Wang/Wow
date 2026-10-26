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
package me.ahoo.wow.postgres.reservation

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.tck.reservation.ReservationStoreSpec
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

class PostgresReservationStoreTest : ReservationStoreSpec() {
    override fun createStore(clock: Clock): ReservationStore =
        PostgresReservationStore(PostgresFixture.dataSource, clock = clock).also { it.createSchema() }

    private suspend fun touch(store: ReservationStore, type: AggregateType) =
        store.reserve(ReservationScope.of(type, "t", "ns"), "k", type.ref("t", "o"), "h", 10.seconds)

    @Test
    fun `an aggregate type's table is created on its first use, and again harmlessly`(): Unit = runBlocking {
        // Decision D307: one table per aggregate type, wow_reservation__<context>__<aggregate>.
        val type = AggregateType(PostgresFixture.newContext("reservation"), "user")
        touch(PostgresReservationStore(PostgresFixture.dataSource), type).assert().isNotNull()
        PostgresReservationTables(type.context, type.name).name.assert()
            .isEqualTo("wow_reservation__${type.context.replace('-', '_')}__user")
        touch(PostgresReservationStore(PostgresFixture.dataSource), type).assert().isNotNull()
    }

    @Test
    fun `a table of another format, or without a record, is refused`() {
        val unrecorded = AggregateType(PostgresFixture.newContext("reservation"), "user")
        PostgresFixture.createUnrecorded(PostgresReservationTables(unrecorded.context, unrecorded.name).name)
        assertThrownBy<IllegalStateException> {
            runBlocking { touch(PostgresReservationStore(PostgresFixture.dataSource), unrecorded) }
        }
        val newer = AggregateType(PostgresFixture.newContext("reservation"), "user")
        runBlocking { touch(PostgresReservationStore(PostgresFixture.dataSource), newer) }
        PostgresFixture.bumpFormat(PostgresReservationTables(newer.context, newer.name).name)
        assertThrownBy<IllegalStateException> {
            runBlocking { touch(PostgresReservationStore(PostgresFixture.dataSource), newer) }
        }
    }
}
