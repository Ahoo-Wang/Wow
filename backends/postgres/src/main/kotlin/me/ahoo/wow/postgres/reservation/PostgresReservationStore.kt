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

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationStore
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * The [ReservationStore] on PostgreSQL (documentation/designs/v10/01-programming-model.md §7, decision D38), in the
 * same database as the log: one row per reserved key.
 *
 * Every write is one autocommitted statement; a reserve is an upsert whose conflict condition admits only the key's
 * owner or an expired pending reservation (see [PostgresReservationTables.reserve]). Expiry is judged by [clock], not
 * by the database's clock, so it behaves the same in memory and here.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
@InternalWowApi
class PostgresReservationStore(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : ReservationStore {
    private val tables = PostgresReservationTables(table)

    /** Creates the reservations' table when missing. */
    fun createSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(tables.ddl) }
        }
    }

    override suspend fun reserve(namespace: String, key: String, owner: String, ttl: Duration): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        return jdbc { connection ->
            val now = clock.millis()
            connection.prepareStatement(tables.reserve).use { reserve ->
                reserve.setString(1, namespace)
                reserve.setString(2, key)
                reserve.setString(3, owner)
                reserve.setLong(4, now + ttl.inWholeMilliseconds)
                reserve.setLong(5, now)
                reserve.executeQuery().use { rows -> if (rows.next()) reservationOf(namespace, key, rows) else null }
            }
        }
    }

    override suspend fun confirm(namespace: String, key: String, owner: String): Boolean =
        update(tables.confirm, namespace, key, owner)

    override suspend fun release(namespace: String, key: String, owner: String): Boolean =
        update(tables.release, namespace, key, owner)

    override suspend fun find(namespace: String, key: String): Reservation? = jdbc { connection ->
        connection.prepareStatement(tables.find).use { query ->
            query.setString(1, namespace)
            query.setString(2, key)
            query.setLong(3, clock.millis())
            query.executeQuery().use { rows -> if (rows.next()) reservationOf(namespace, key, rows) else null }
        }
    }

    private suspend fun update(sql: String, namespace: String, key: String, owner: String): Boolean =
        jdbc { connection ->
            connection.prepareStatement(sql).use { update ->
                update.setString(1, namespace)
                update.setString(2, key)
                update.setString(3, owner)
                update.executeUpdate() == 1
            }
        }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.connection.use(block) }

    private companion object {
        const val DEFAULT_TABLE = "wow_reservation"

        fun reservationOf(namespace: String, key: String, row: ResultSet) = Reservation(
            namespace = namespace,
            key = key,
            owner = row.getString("owner"),
            expiresAt = row.getObject("expires_at") as Long?,
        )
    }
}
