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

package me.ahoo.wow.postgres.ledger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * The [FailureLedger] of one service on PostgreSQL (documentation/designs/v10/04-subscriptions.md §2), in the
 * service's own database: one row per parked (subscription, aggregate).
 *
 * Every write is one autocommitted statement. A park is an upsert that replaces the row only with an earlier from
 * version, so concurrent or replayed parks converge on the earliest (decision D45) without a transaction; a resolve is
 * one delete returning the removed row. Key columns use the "C" collation, so [list] orders by code point on every
 * database, whatever its default collation.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
class PostgresFailureLedger(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    private val dispatcher: CoroutineDispatcher = VIRTUAL_THREADS,
) : FailureLedger {
    private val tables = PostgresFailureLedgerTables(table)

    /** Creates the ledger's table when missing. */
    fun createSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(tables.ddl) }
        }
    }

    override suspend fun park(entry: ParkedAggregate): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.park).use { park ->
            park.setKey(entry.subscription, entry.ref)
            park.setInt(KEY_COLUMNS + 1, entry.fromVersion)
            park.setString(KEY_COLUMNS + 2, entry.reason)
            park.setLong(KEY_COLUMNS + 3, entry.failedAt)
            park.executeQuery().use { rows -> rows.next() }
        }
    }

    override suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate? = jdbc { connection ->
        connection.prepareStatement(tables.parked).use { query ->
            query.setKey(subscription, ref)
            query.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
        }
    }

    override suspend fun list(subscription: String, after: AggregateRef?, limit: Int): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        return jdbc { connection ->
            val sql = if (after == null) tables.listFirst else tables.listAfter
            connection.prepareStatement(sql).use { query ->
                if (after == null) {
                    query.setString(1, subscription)
                    query.setInt(2, limit)
                } else {
                    query.setKey(subscription, after)
                    query.setInt(KEY_COLUMNS + 1, limit)
                }
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(entryOf(rows))
                        }
                    }
                }
            }
        }
    }

    override suspend fun resolve(subscription: String, ref: AggregateRef): ParkedAggregate? = jdbc { connection ->
        connection.prepareStatement(tables.resolve).use { delete ->
            delete.setKey(subscription, ref)
            delete.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
        }
    }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        withContext(dispatcher) { dataSource.connection.use(block) }

    private companion object {
        const val DEFAULT_TABLE = "wow_failure_ledger"
        const val KEY_COLUMNS = 5
        val VIRTUAL_THREADS: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

        /** Binds the key (subscription, context, aggregate name, tenant, id) to parameters 1 to [KEY_COLUMNS]. */
        fun PreparedStatement.setKey(subscription: String, ref: AggregateRef) {
            setString(1, subscription)
            setString(2, ref.type.context)
            setString(3, ref.type.name)
            setString(4, ref.tenantId)
            setString(5, ref.id)
        }

        fun entryOf(row: ResultSet) = ParkedAggregate(
            subscription = row.getString("subscription"),
            ref = AggregateType(row.getString("context"), row.getString("aggregate_name"))
                .ref(row.getString("tenant_id"), row.getString("aggregate_id")),
            fromVersion = row.getInt("from_version"),
            reason = row.getString("reason"),
            failedAt = row.getLong("failed_at"),
        )
    }
}
