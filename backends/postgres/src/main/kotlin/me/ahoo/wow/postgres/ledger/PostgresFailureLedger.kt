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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [FailureLedger] of one service on PostgreSQL (documentation/designs/v10/04-subscriptions.md §2), in the
 * service's own database: one row per parked (subscription, aggregate).
 *
 * Every write is one autocommitted statement. A park is an upsert that replaces the row's where and why only with an
 * earlier from version, so concurrent or replayed parks converge on the earliest (decision D45) without a
 * transaction; a repark is one update of an existing row; a resolve is one delete returning the removed row; an
 * amendment is one update returning the amended row, conditional on the retries or the request read when it claims or
 * takes one. The source position of a Kafka-sourced subscription's entry (decision D134) and the retry state (roadmap
 * P.1) are columns added to a table created before them. Key columns use the "C" collation, so [list] orders by code
 * point on every database, whatever its default collation.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21, decision D29).
 */
class PostgresFailureLedger(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : FailureLedger {
    private val tables = PostgresFailureLedgerTables(table)

    /** Creates the ledger's table when missing, and the columns of later formats when it lacks them. */
    fun createSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(tables.ddl)
                tables.migrations.forEach { statement.execute(it) }
            }
        }
    }

    override suspend fun park(entry: ParkedAggregate): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.park).use { park ->
            park.setKey(entry.subscription, entry.ref)
            park.setInt(KEY_COLUMNS + 1, entry.fromVersion)
            park.setString(KEY_COLUMNS + 2, entry.reason)
            park.setLong(KEY_COLUMNS + 3, entry.failedAt)
            park.setNullableLong(KEY_COLUMNS + 4, entry.fromPosition)
            park.setInt(KEY_COLUMNS + 5, entry.retries)
            park.setNullableLong(KEY_COLUMNS + 6, entry.retryAt)
            park.setSpec(KEY_COLUMNS + 7, entry.retrySpec)
            park.setString(KEY_COLUMNS + 10, entry.recoverable.name)
            park.setString(KEY_COLUMNS + 11, entry.request?.name)
            park.executeQuery().use { rows -> rows.next() }
        }
    }

    override suspend fun repark(entry: ParkedAggregate): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.repark).use { repark ->
            repark.setInt(1, entry.fromVersion)
            repark.setString(2, entry.reason)
            repark.setLong(3, entry.failedAt)
            repark.setNullableLong(4, entry.fromPosition)
            repark.setKey(entry.subscription, entry.ref, first = REPARK_VALUES + 1)
            repark.executeUpdate() > 0
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

    override suspend fun amend(
        subscription: String,
        ref: AggregateRef,
        amendment: ParkedAmendment,
    ): ParkedAggregate? = jdbc { connection ->
        val sql = when (amendment) {
            is ParkedAmendment.ClaimRetry -> tables.claimRetry
            is ParkedAmendment.MarkRecoverable -> tables.markRecoverable
            is ParkedAmendment.ApplyRetrySpec -> tables.applyRetrySpec
            is ParkedAmendment.Request -> tables.request
            is ParkedAmendment.TakeRequest -> tables.takeRequest
        }
        connection.prepareStatement(sql).use { update ->
            when (amendment) {
                is ParkedAmendment.ClaimRetry -> {
                    update.setLong(1, amendment.at)
                    update.setKey(subscription, ref, first = 2)
                    update.setInt(KEY_COLUMNS + 2, amendment.retries)
                }

                is ParkedAmendment.MarkRecoverable -> {
                    update.setString(1, amendment.recoverable.name)
                    update.setKey(subscription, ref, first = 2)
                }

                is ParkedAmendment.ApplyRetrySpec -> {
                    update.setSpec(1, amendment.spec)
                    update.setKey(subscription, ref, first = SPEC_COLUMNS + 1)
                }

                is ParkedAmendment.Request -> {
                    update.setString(1, amendment.request.name)
                    update.setKey(subscription, ref, first = 2)
                }

                is ParkedAmendment.TakeRequest -> {
                    update.setKey(subscription, ref)
                    update.setString(KEY_COLUMNS + 1, amendment.request.name)
                }
            }
            update.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
        }
    }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        withContext(dispatcher) { dataSource.connection.use(block) }

    private companion object {
        const val DEFAULT_TABLE = "wow_failure_ledger"
        const val KEY_COLUMNS = 5
        const val REPARK_VALUES = 4
        const val SPEC_COLUMNS = 3

        /**
         * Binds the key (subscription, context, aggregate name, tenant, id) to the [KEY_COLUMNS] parameters from
         * [first].
         */
        fun PreparedStatement.setKey(subscription: String, ref: AggregateRef, first: Int = 1) {
            setString(first, subscription)
            setString(first + 1, ref.type.context)
            setString(first + 2, ref.type.name)
            setString(first + 3, ref.tenantId)
            setString(first + 4, ref.id)
        }

        fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
            if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
        }

        /** Binds [spec] to the [SPEC_COLUMNS] parameters from [first]: its retries and its durations in ms. */
        fun PreparedStatement.setSpec(first: Int, spec: ParkedRetrySpec?) {
            if (spec == null) setNull(first, Types.INTEGER) else setInt(first, spec.maxRetries)
            setNullableLong(first + 1, spec?.minBackoff?.inWholeMilliseconds)
            setNullableLong(first + 2, spec?.executionTimeout?.inWholeMilliseconds)
        }

        fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

        fun entryOf(row: ResultSet) = ParkedAggregate(
            subscription = row.getString("subscription"),
            ref = AggregateType(row.getString("context"), row.getString("aggregate_name"))
                .ref(row.getString("tenant_id"), row.getString("aggregate_id")),
            fromVersion = row.getInt("from_version"),
            reason = row.getString("reason"),
            failedAt = row.getLong("failed_at"),
            fromPosition = row.nullableLong("from_position"),
            retries = row.getInt("retries"),
            retryAt = row.nullableLong("retry_at"),
            retrySpec = row.getInt("retry_max_retries").takeUnless { row.wasNull() }?.let { maxRetries ->
                ParkedRetrySpec(
                    maxRetries = maxRetries,
                    minBackoff = row.getLong("retry_min_backoff_millis").milliseconds,
                    executionTimeout = row.getLong("retry_execution_timeout_millis").milliseconds,
                )
            },
            recoverable = Recoverable.valueOf(row.getString("recoverable")),
            request = row.getString("request")?.let { LedgerRequest.valueOf(it) },
        )
    }
}
