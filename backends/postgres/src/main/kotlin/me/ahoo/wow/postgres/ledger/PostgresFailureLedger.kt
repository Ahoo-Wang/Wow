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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
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
 * The [FailureLedger] of one service on PostgreSQL (documentation/designs/04-subscriptions.md §2), in the
 * service's own database: one row per parked (subscription, aggregate). The key starts with [service], the service the
 * ledger is of: services that share a database, and subscriptions of one name, see and resolve only
 * their own entries. The table's layout is recorded as format 3 ([PostgresFormat]), with an index answering what is
 * due in a node's partitions and one answering a tenant's listing.
 *
 * Every write is one autocommitted statement, whatever the autocommit default of the [dataSource]'s pool.
 * A park is an upsert that replaces the row's where and why only with an earlier from version, so concurrent
 * or replayed parks converge on the earliest without a transaction; a repark is one update of an
 * existing row; a resolve is one delete returning the removed row; an amendment is one update returning the amended
 * row, conditional on the retries, the request or the schedule read when it claims, takes or schedules. Format 3
 * creates the table with the source partition and position of the entry and the retry state among its columns;
 * nothing adds columns to an older table: one of another format, or without a format record, is refused
 * ([PostgresFormat]).
 * Key columns use the "C" collation, so [list] orders by code point on every database, whatever its default collation.
 *
 * JDBC calls run on [dispatcher], virtual threads by default (JDK 21).
 */
@InternalWowApi
class PostgresFailureLedger(
    private val dataSource: DataSource,
    table: String = DEFAULT_TABLE,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** The service whose ledger this is: its entries alone are seen. */
    private val service: String = DEFAULT_SERVICE,
    /** How the store's tables are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
) : FailureLedger {
    private val tables = PostgresFailureLedgerTables(table)

    /** Claims the table's format ([PostgresFormat]) and creates it when missing. */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            PostgresFormat.schema(connection, ddl, listOf(tables.name to PostgresFailureLedgerTables.FORMAT)) {
                PostgresSchema.execute(connection, tables.ddls)
            }
        }
    }

    override suspend fun park(entry: ParkedAggregate): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.park).use { park ->
            park.setKey(entry.subscription, entry.ref)
            park.setInt(KEY_COLUMNS + 1, entry.partition)
            park.setInt(KEY_COLUMNS + 2, entry.fromVersion)
            park.setString(KEY_COLUMNS + 3, entry.reason)
            park.setLong(KEY_COLUMNS + 4, entry.failedAt)
            park.setNullableLong(KEY_COLUMNS + 5, entry.fromPosition)
            park.setInt(KEY_COLUMNS + 6, entry.retries)
            park.setNullableLong(KEY_COLUMNS + 7, entry.retryAt)
            park.setSpec(KEY_COLUMNS + 8, entry.retrySpec)
            park.setString(KEY_COLUMNS + 11, entry.recoverable.name)
            park.setString(KEY_COLUMNS + 12, entry.request?.name)
            park.setNullableLong(KEY_COLUMNS + 13, entry.nextAttemptAt)
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

    override suspend fun list(
        subscription: String,
        after: AggregateRef?,
        limit: Int,
        tenantId: String?,
    ): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        val sql = when {
            tenantId == null -> if (after == null) tables.listFirst else tables.listAfter
            else -> if (after == null) tables.listTenantFirst else tables.listTenantAfter
        }
        return page(sql, subscription, after) { query, next ->
            tenantId?.let { query.setString(next, it) }
            query.setInt(next + if (tenantId == null) 0 else 1, limit)
        }
    }

    override suspend fun due(
        subscription: String,
        partitions: Collection<Int>,
        now: Long,
        after: AggregateRef?,
        limit: Int,
    ): List<ParkedAggregate> {
        require(limit >= 1) { "A page holds at least one entry." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return page(if (after == null) tables.dueFirst else tables.dueAfter, subscription, after) { query, next ->
            query.setArray(next, query.connection.createArrayOf("integer", partitions.toTypedArray()))
            query.setLong(next + 1, now)
            query.setInt(next + 2, limit)
        }
    }

    override suspend fun count(subscription: String, max: Int): Int {
        require(max >= 1) { "At least one parked entry is counted." }
        return jdbc { connection ->
            connection.prepareStatement(tables.count).use { query ->
                query.setString(1, service)
                query.setString(2, subscription)
                query.setInt(3, max)
                query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    /**
     * The rows [sql] selects: it starts with the service and the subscription, or with the key of [after] when given,
     * and [bind] binds the rest from the parameter it is given.
     */
    private suspend fun page(
        sql: String,
        subscription: String,
        after: AggregateRef?,
        bind: (PreparedStatement, Int) -> Unit,
    ): List<ParkedAggregate> = jdbc { connection ->
        connection.prepareStatement(sql).use { query ->
            if (after == null) {
                query.setString(1, service)
                query.setString(2, subscription)
                bind(query, 3)
            } else {
                query.setKey(subscription, after)
                bind(query, KEY_COLUMNS + 1)
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
        connection.prepareStatement(sqlOf(amendment)).use { update ->
            update.bind(amendment, subscription, ref)
            update.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
        }
    }

    /** The statement of [amendment]. */
    private fun sqlOf(amendment: ParkedAmendment): String = when (amendment) {
        is ParkedAmendment.ClaimRetry -> tables.claimRetry
        is ParkedAmendment.MarkRecoverable -> tables.markRecoverable
        is ParkedAmendment.ApplyRetrySpec -> tables.applyRetrySpec
        is ParkedAmendment.Request -> tables.request
        is ParkedAmendment.Schedule -> tables.schedule
        is ParkedAmendment.TakeRequest -> tables.takeRequest
    }

    /** Binds [amendment] of [subscription]'s entry of [ref] to its statement ([sqlOf]). */
    private fun PreparedStatement.bind(amendment: ParkedAmendment, subscription: String, ref: AggregateRef) {
        when (amendment) {
            is ParkedAmendment.ClaimRetry -> {
                setLong(1, amendment.at)
                setKey(subscription, ref, first = 2)
                setInt(KEY_COLUMNS + 2, amendment.retries)
            }

            is ParkedAmendment.MarkRecoverable -> {
                setString(1, amendment.recoverable.name)
                setKey(subscription, ref, first = 2)
            }

            is ParkedAmendment.ApplyRetrySpec -> {
                setSpec(1, amendment.spec)
                setKey(subscription, ref, first = SPEC_COLUMNS + 1)
            }

            is ParkedAmendment.Request -> {
                setString(1, amendment.request.name)
                setKey(subscription, ref, first = 2)
            }

            is ParkedAmendment.Schedule -> {
                setLong(1, amendment.at)
                setKey(subscription, ref, first = 2)
                setInt(KEY_COLUMNS + 2, amendment.retries)
                setNullableLong(KEY_COLUMNS + 3, amendment.from)
            }

            is ParkedAmendment.TakeRequest -> {
                setKey(subscription, ref)
                setString(KEY_COLUMNS + 1, amendment.request.name)
            }
        }
    }

    /**
     * Binds the key (service, subscription, context, aggregate name, tenant, id) to the [KEY_COLUMNS] parameters from
     * [first].
     */
    private fun PreparedStatement.setKey(subscription: String, ref: AggregateRef, first: Int = 1) =
        bindKey(service, subscription, ref, first)

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.autoCommitted(block) }

    private companion object {
        const val DEFAULT_TABLE = "wow_failure_ledger"

        /** The service of a ledger built without one: a database of one service. */
        const val DEFAULT_SERVICE = ""
        const val KEY_COLUMNS = 6
        const val REPARK_VALUES = 4
        const val SPEC_COLUMNS = 3

        /** Binds [service]'s key of [subscription] and [ref] to the [KEY_COLUMNS] parameters from [first]. */
        fun PreparedStatement.bindKey(service: String, subscription: String, ref: AggregateRef, first: Int) {
            setString(first, service)
            setString(first + 1, subscription)
            setString(first + 2, ref.type.context)
            setString(first + 3, ref.type.name)
            setString(first + 4, ref.tenantId)
            setString(first + 5, ref.id)
        }

        fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
            if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
        }

        /** Binds [spec] to the [SPEC_COLUMNS] parameters from [first]: its retries and its durations in ms. */
        fun PreparedStatement.setSpec(first: Int, spec: ParkedRetrySpec?) {
            if (spec == null) setNull(first, Types.INTEGER) else setInt(first, spec.maxRetries)
            setNullableLong(first + 1, spec?.initialBackoff?.inWholeMilliseconds)
            setNullableLong(first + 2, spec?.claimTtl?.inWholeMilliseconds)
        }

        fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

        fun entryOf(row: ResultSet) = ParkedAggregate(
            subscription = row.getString("subscription"),
            ref = AggregateType(row.getString("context"), row.getString("aggregate_name"))
                .ref(row.getString("tenant_id"), row.getString("aggregate_id")),
            partition = row.getInt("partition"),
            fromVersion = row.getInt("from_version"),
            reason = row.getString("reason"),
            failedAt = row.getLong("failed_at"),
            fromPosition = row.nullableLong("from_position"),
            retries = row.getInt("retries"),
            retryAt = row.nullableLong("retry_at"),
            retrySpec = row.getInt("retry_max_retries").takeUnless { row.wasNull() }?.let { maxRetries ->
                ParkedRetrySpec(
                    maxRetries = maxRetries,
                    initialBackoff = row.getLong("retry_initial_backoff_millis").milliseconds,
                    claimTtl = row.getLong("retry_claim_ttl_millis").milliseconds,
                )
            },
            recoverable = Recoverable.valueOf(row.getString("recoverable")),
            request = row.getString("request")?.let { LedgerRequest.valueOf(it) },
            nextAttemptAt = row.nullableLong("next_attempt_at"),
        )
    }
}
