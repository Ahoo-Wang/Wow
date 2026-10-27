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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.EnsuredTables
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.inTransaction
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.Reservation
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * The [ReservationStore] on PostgreSQL (documentation/designs/01-programming-model.md §7), in the
 * same database as the log: one table per aggregate type, `wow_reservation__<context>__<aggregate>`,
 * created with its format record on the type's first use, as the log's and the snapshots' tables are; in it one row per
 * reserved key, keyed by `(tenant_id, namespace, key)`, its owner in two columns and the holds of a pending reservation
 * in a `text[]` ([PostgresReservationTables]).
 *
 * A reserve, a confirm and a release are each one autocommitted statement; a reserve or a confirm is an upsert whose
 * conflict condition admits only the key's owner or an expired pending reservation. An abandon removes its hold and
 * frees a reservation left without holds in one short transaction, the row locked by the first statement. Expiry is
 * judged by [clock], not by the database's clock, so it behaves the same in memory and here.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21).
 */
@InternalWowApi
class PostgresReservationStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's tables are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : ReservationStore {
    private val created = EnsuredTables<PostgresReservationTables>()

    /**
     * Nothing to create up front: each aggregate type's table is created on its first use, since the store does not
     * know the aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /**
     * The table of [type]'s reservations, created with its owner index when this store has not yet made sure of it;
     * refuses one recorded in another format, or one without a record ([PostgresFormat]).
     */
    private fun tablesOf(type: AggregateType): PostgresReservationTables = created.get(type.toString()) {
        PostgresReservationTables(type.context, type.name).also { tables ->
            dataSource.autoCommitted { connection ->
                PostgresFormat.schema(connection, ddl, listOf(tables.name to PostgresReservationTables.FORMAT)) {
                    PostgresSchema.execute(connection, tables.ddl)
                }
            }
        }
    }

    override suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation? {
        require(ttl.isPositive()) { "A reservation's time to live is positive." }
        require(hold.isNotBlank()) { "A reservation's hold is not blank." }
        requireOwnType(scope, owner)
        return jdbc { connection ->
            val now = clock.millis()
            connection.prepareStatement(tablesOf(scope.type).reserve).use { reserve ->
                var index = reserve.setKey(1, scope, key)
                index = reserve.setOwner(index, owner)
                reserve.setLong(index++, now + ttl.inWholeMilliseconds)
                reserve.setString(index++, hold)
                repeat(NOW_PARAMETERS) { reserve.setLong(index++, now) }
                reserve.executeQuery().use { rows -> if (rows.next()) reservationOf(scope, key, rows) else null }
            }
        }
    }

    override suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean =
        jdbc { connection ->
            inTransaction(connection) {
                val left = connection.prepareStatement(tablesOf(scope.type).abandon).use { abandon ->
                    abandon.setString(1, hold)
                    val index = abandon.setOwner(abandon.setKey(2, scope, key), owner)
                    abandon.setString(index, hold)
                    abandon.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
                }
                if (left == 0) {
                    connection.prepareStatement(tablesOf(scope.type).abandoned).use { free ->
                        free.setOwner(free.setKey(1, scope, key), owner)
                        free.executeUpdate()
                    }
                }
                left != null
            }
        }

    override suspend fun confirm(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        version: Int,
    ): ConfirmOutcome {
        requireOwnType(scope, owner)
        return jdbc { connection ->
            // A holder that went away between the upsert and the read is retried: the next upsert takes the key.
            repeat(CONFIRM_ATTEMPTS) {
                val now = clock.millis()
                val confirmed = connection.prepareStatement(tablesOf(scope.type).confirm).use { confirm ->
                    val index = confirm.setOwner(confirm.setKey(1, scope, key), owner)
                    confirm.setInt(index, version)
                    confirm.setLong(index + 1, now)
                    confirm.executeQuery().use { it.next() }
                }
                if (confirmed) {
                    return@jdbc ConfirmOutcome.Confirmed
                }
                val holder = connection.prepareStatement(tablesOf(scope.type).held).use { held ->
                    held.setKey(1, scope, key)
                    held.executeQuery().use { rows -> if (rows.next()) reservationOf(scope, key, rows) else null }
                }
                if (holder != null && holder.owner != owner && holder.isLive(now)) {
                    return@jdbc ConfirmOutcome.Taken(holder)
                }
            }
            error("Confirming $key in $scope for $owner kept racing other writers.")
        }
    }

    override suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef, version: Int): Boolean =
        jdbc { connection ->
            connection.prepareStatement(tablesOf(scope.type).release).use { release ->
                release.setInt(release.setOwner(release.setKey(1, scope, key), owner), version)
                release.executeUpdate() == 1
            }
        }

    override suspend fun find(scope: ReservationScope, key: String): Reservation? = jdbc { connection ->
        connection.prepareStatement(tablesOf(scope.type).find).use { query ->
            query.setLong(query.setKey(1, scope, key), clock.millis())
            query.executeQuery().use { rows -> if (rows.next()) reservationOf(scope, key, rows) else null }
        }
    }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.autoCommitted(block) }

    private companion object {
        const val NOW_PARAMETERS = 3
        const val CONFIRM_ATTEMPTS = 8

        fun requireOwnType(scope: ReservationScope, owner: AggregateRef) =
            require(owner.type == scope.type) { "$owner reserves keys of its own aggregate type only." }

        /** Sets the key's three columns from [index]; the index after them. */
        fun PreparedStatement.setKey(index: Int, scope: ReservationScope, key: String): Int {
            setString(index, scope.tenantId)
            setString(index + 1, scope.namespace)
            setString(index + 2, key)
            return index + 3
        }

        /** Sets the owner's two columns from [index]; the index after them (its type is the table's). */
        fun PreparedStatement.setOwner(index: Int, owner: AggregateRef): Int {
            setString(index, owner.tenantId)
            setString(index + 1, owner.id)
            return index + 2
        }

        fun reservationOf(scope: ReservationScope, key: String, row: ResultSet): Reservation {
            @Suppress("UNCHECKED_CAST") // A text[] column reads as a String array.
            val holds = row.getArray("holds").array as Array<String>
            return Reservation(
                scope = scope,
                key = key,
                owner = scope.type.ref(row.getString("owner_tenant_id"), row.getString("owner_id")),
                expiresAt = row.getObject("expires_at") as Long?,
                holds = holds.toSet(),
                version = row.getInt("version"),
            )
        }
    }
}
