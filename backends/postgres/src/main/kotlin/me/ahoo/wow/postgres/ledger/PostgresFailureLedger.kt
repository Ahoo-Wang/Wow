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
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.amendByRevision
import me.ahoo.wow.spi.subscription.SubscriptionId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [FailureLedger] of one service on PostgreSQL (documentation/designs/04-subscriptions.md §2), in the
 * service's own database: one row per parked (subscription, aggregate). The key starts with [service], the service the
 * ledger is of, then the subscription's context and name ([SubscriptionId]): services that share a database, and
 * subscriptions of one name in two contexts, see and resolve only their own entries. The table's layout is recorded as
 * format [PostgresFailureLedgerTables.FORMAT] ([PostgresFormat]), with an index answering what is due in a node's
 * partitions and one answering a tenant's listing.
 *
 * Every write is one autocommitted statement, whatever the autocommit default of the [dataSource]'s pool.
 * A park is an upsert that replaces the row's where and why only with an earlier from version, so concurrent
 * or replayed parks converge on the earliest without a transaction; a repark is one update of an
 * existing row; a resolve is one delete returning the removed row; an amendment with a statement of its own is one
 * update returning the amended row at the next revision, conditional on the revision read when it claims, takes or
 * schedules, and any other one ([amendByRevision]) a read and an update of the whole row conditional on the revision
 * it read. The table holds the
 * source partition and position of the entry, the retry state and the revision among its columns. A table of
 * another format, or without a format record, is refused ([PostgresFormat]): nothing is converted.
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
            park.setPlace(KEY_COLUMNS + 1, entry)
            park.setRetryState(KEY_COLUMNS + PLACE_COLUMNS + 1, entry)
            park.executeQuery().use { rows -> rows.next() }
        }
    }

    override suspend fun repark(entry: ParkedAggregate, from: Int): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.repark).use { repark ->
            repark.setPlace(1, entry)
            repark.setLong(PLACE_COLUMNS + 1, entry.failedAt)
            repark.setKey(entry.subscription, entry.ref, first = PLACE_COLUMNS + 2)
            repark.setInt(PLACE_COLUMNS + KEY_COLUMNS + 2, from)
            repark.executeUpdate() > 0
        }
    }

    override suspend fun parked(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
        jdbc { connection ->
            connection.prepareStatement(tables.parked).use { query ->
                query.setKey(subscription, ref)
                query.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
            }
        }

    override suspend fun list(
        subscription: SubscriptionId,
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
        subscription: SubscriptionId,
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

    override suspend fun count(subscription: SubscriptionId, max: Int): Int {
        require(max >= 1) { "At least one parked entry is counted." }
        return jdbc { connection ->
            connection.prepareStatement(tables.count).use { query ->
                query.setSubscription(subscription)
                query.setInt(SUBSCRIPTION_COLUMNS + 1, max)
                query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    /**
     * The rows [sql] selects: it starts with the service and the subscription ([setSubscription]), or with the key of
     * [after] when given,
     * and [bind] binds the rest from the parameter it is given.
     */
    private suspend fun page(
        sql: String,
        subscription: SubscriptionId,
        after: AggregateRef?,
        bind: (PreparedStatement, Int) -> Unit,
    ): List<ParkedAggregate> = jdbc { connection ->
        connection.prepareStatement(sql).use { query ->
            if (after == null) {
                query.setSubscription(subscription)
                bind(query, SUBSCRIPTION_COLUMNS + 1)
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

    override suspend fun resolveAnywhere(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
        jdbc { connection ->
            connection.prepareStatement(tables.resolve).use { delete ->
                delete.setKey(subscription, ref)
                delete.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
            }
        }

    override suspend fun resolve(subscription: SubscriptionId, ref: AggregateRef, partition: Int): ParkedAggregate? =
        jdbc { connection ->
            connection.prepareStatement(tables.resolveIn).use { delete ->
                delete.setKey(subscription, ref)
                delete.setInt(KEY_COLUMNS + 1, partition)
                delete.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
            }
        }

    override suspend fun amend(
        subscription: SubscriptionId,
        ref: AggregateRef,
        amendment: ParkedAmendment,
    ): ParkedAggregate? {
        val (sql, bind) = statementOf(amendment, subscription, ref)
            ?: return amendByRevision(amendment, { parked(subscription, ref) }, ::replace)
        return jdbc { connection ->
            connection.prepareStatement(sql).use { update ->
                update.bind()
                update.executeQuery().use { rows -> if (rows.next()) entryOf(rows) else null }
            }
        }
    }

    /**
     * The statement of [amendment] of [subscription]'s entry of [ref], with what binds its parameters; null for one
     * that takes the generic path ([amendByRevision]): an operator's retry spec, sent for one entry at a time, and any
     * amendment a later version adds.
     */
    private fun statementOf(
        amendment: ParkedAmendment,
        subscription: SubscriptionId,
        ref: AggregateRef,
    ): Pair<String, PreparedStatement.() -> Unit>? = when (amendment) {
        is ParkedAmendment.ClaimRetry -> tables.claimRetry to {
            setLong(1, amendment.at)
            setKey(subscription, ref, first = 2)
            setLong(KEY_COLUMNS + 2, amendment.revision)
        }

        is ParkedAmendment.MarkRecoverable -> tables.markRecoverable to {
            setString(1, amendment.recoverable.name)
            setKey(subscription, ref, first = 2)
            setNullableLong(KEY_COLUMNS + 2, amendment.failedAt)
        }

        is ParkedAmendment.Request -> tables.request to {
            setString(1, amendment.request.name)
            setKey(subscription, ref, first = 2)
            setNullableLong(KEY_COLUMNS + 2, amendment.failedAt)
        }

        is ParkedAmendment.Schedule -> tables.schedule to {
            setLong(1, amendment.at)
            setKey(subscription, ref, first = 2)
            setLong(KEY_COLUMNS + 2, amendment.revision)
        }

        is ParkedAmendment.TakeRequest -> tables.takeRequest to {
            setKey(subscription, ref)
            setLong(KEY_COLUMNS + 1, amendment.revision)
        }

        is ParkedAmendment.Restate -> tables.restate to {
            setString(1, amendment.reason)
            setLong(2, amendment.failedAt)
            setInt(3, amendment.retries)
            setNullableLong(4, amendment.retryAt)
            setSpec(RESTATE_SPEC, amendment.retrySpec)
            setString(RESTATE_SPEC + SPEC_COLUMNS, amendment.recoverable.name)
            setKey(subscription, ref, first = RESTATE_SPEC + SPEC_COLUMNS + 1)
            setLong(RESTATE_SPEC + SPEC_COLUMNS + KEY_COLUMNS + 1, amendment.revision)
        }

        else -> null
    }

    /** Stores [amended] whole while the stored entry is at [revision]: the generic path's write. */
    private suspend fun replace(amended: ParkedAggregate, revision: Long): Boolean = jdbc { connection ->
        connection.prepareStatement(tables.replace).use { update ->
            update.setPlace(1, amended)
            update.setRetryState(PLACE_COLUMNS + 1, amended)
            update.setLong(PLACE_COLUMNS + RETRY_STATE_COLUMNS + 1, amended.revision)
            update.setKey(amended.subscription, amended.ref, first = PLACE_COLUMNS + RETRY_STATE_COLUMNS + 2)
            update.setLong(PLACE_COLUMNS + RETRY_STATE_COLUMNS + KEY_COLUMNS + 2, revision)
            update.executeUpdate() > 0
        }
    }

    /** Binds where [entry] is parked and why to the [PLACE_COLUMNS] parameters from [first]. */
    private fun PreparedStatement.setPlace(first: Int, entry: ParkedAggregate) {
        setInt(first, entry.partition)
        setInt(first + 1, entry.fromVersion)
        setString(first + 2, entry.reason)
        setLong(first + 3, entry.failedAt)
        setNullableLong(first + 4, entry.fromPosition)
        setBoolean(first + 5, entry.skipOnly)
    }

    /** Binds [entry]'s retry state to the [RETRY_STATE_COLUMNS] parameters from [first]. */
    private fun PreparedStatement.setRetryState(first: Int, entry: ParkedAggregate) {
        setInt(first, entry.retries)
        setNullableLong(first + 1, entry.retryAt)
        setSpec(first + 2, entry.retrySpec)
        setString(first + 2 + SPEC_COLUMNS, entry.recoverable.name)
        setString(first + 3 + SPEC_COLUMNS, entry.request?.name)
        setNullableLong(first + 4 + SPEC_COLUMNS, entry.nextAttemptAt)
    }

    /**
     * Binds the key (service, subscription context and name, context, aggregate name, tenant, id) to the [KEY_COLUMNS]
     * parameters from [first].
     */
    private fun PreparedStatement.setKey(subscription: SubscriptionId, ref: AggregateRef, first: Int = 1) =
        bindKey(service, subscription, ref, first)

    /** Binds the service and [subscription]'s context and name to the first [SUBSCRIPTION_COLUMNS] parameters. */
    private fun PreparedStatement.setSubscription(subscription: SubscriptionId) {
        setString(1, service)
        setString(2, subscription.context)
        setString(SUBSCRIPTION_COLUMNS, subscription.name)
    }

    private suspend fun <T> jdbc(block: (Connection) -> T): T =
        dispatcher.jdbc { dataSource.autoCommitted(block) }

    private companion object {
        const val DEFAULT_TABLE = "wow_failure_ledger"

        /** The service of a ledger built without one: a database of one service. */
        const val DEFAULT_SERVICE = ""
        const val SUBSCRIPTION_COLUMNS = 3
        const val KEY_COLUMNS = 7
        const val SPEC_COLUMNS = 3

        /** Where an entry is parked and why: its partition, from version, reason, time, position, skip-only. */
        const val PLACE_COLUMNS = 6

        /** Its retry state: the retries, the last one's start, the spec's columns, the mark, the request, the next. */
        const val RETRY_STATE_COLUMNS = 5 + SPEC_COLUMNS

        /** The first parameter of a restate's spec: after its reason, failure time, retries and last retry's start. */
        const val RESTATE_SPEC = 5

        /** Binds [service]'s key of [subscription] and [ref] to the [KEY_COLUMNS] parameters from [first]. */
        fun PreparedStatement.bindKey(service: String, subscription: SubscriptionId, ref: AggregateRef, first: Int) {
            setString(first, service)
            setString(first + 1, subscription.context)
            setString(first + 2, subscription.name)
            setString(first + 3, ref.type.context)
            setString(first + 4, ref.type.name)
            setString(first + 5, ref.tenantId)
            setString(first + 6, ref.id)
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
            subscription = SubscriptionId(row.getString("subscription_context"), row.getString("subscription")),
            ref = AggregateType(row.getString("context"), row.getString("aggregate_name"))
                .ref(row.getString("tenant_id"), row.getString("aggregate_id")),
            partition = row.getInt("partition"),
            fromVersion = row.getInt("from_version"),
            reason = row.getString("reason"),
            failedAt = row.getLong("failed_at"),
            fromPosition = row.nullableLong("from_position"),
            skipOnly = row.getBoolean("skip_only"),
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
            revision = row.getLong("revision"),
        )
    }
}
