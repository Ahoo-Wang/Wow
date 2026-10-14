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

package me.ahoo.wow.routing.stage2

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.routing.AccountOpened
import me.ahoo.wow.routing.OpenAccount
import me.ahoo.wow.routing.accountModel
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/** What PostgreSQL executed between two reads of `pg_stat_statements`, by the kind of statement. */
data class StatementCounts(
    /** Reads of an aggregate's stream: its version, its events, a request id (deduplication). */
    val logReads: Long,
    /** Loads of a snapshot. */
    val snapshotReads: Long,
    /** Reads of a partition's tail by the subscriptions (the snapshot subscription here). */
    val tailReads: Long,
    val appends: Long,
    val snapshotWrites: Long,
    /** Lease, membership and checkpoint statements, and anything else. */
    val other: Long,
) {
    operator fun minus(earlier: StatementCounts) = StatementCounts(
        logReads - earlier.logReads,
        snapshotReads - earlier.snapshotReads,
        tailReads - earlier.tailReads,
        appends - earlier.appends,
        snapshotWrites - earlier.snapshotWrites,
        other - earlier.other,
    )

    companion object {
        val NONE = StatementCounts(0, 0, 0, 0, 0, 0)
    }
}

/** The driver's side of the database: seeding, the leases, the statement counters and the audit of the log. */
class Database(private val postgres: PostgreSQLContainer, private val partitions: Int) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = POOL
        },
    )
    private val model = accountModel(H2_CONTEXT, 0)
    private val logTable = "wow_log__${H2_CONTEXT}__account"
    private val snapshotTable = "wow_snapshot__${H2_CONTEXT}__account"
    private val counterTable = "wow_log__${H2_CONTEXT}__partition"

    /** Whether `pg_stat_statements` could be created; the reads per command are unknown without it. */
    val statementsAvailable: Boolean = try {
        update("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
        true
    } catch (failure: java.sql.SQLException) {
        println("[h2 stage 2] pg_stat_statements is not available ($failure): reads per command are not measured")
        false
    }

    /**
     * Opens [aggregates] accounts as stage 1's small state does: version 1 holds the opening, with a snapshot of it.
     * Written straight to the log and the snapshot store, as stage 1 seeded, before any node starts.
     */
    @Suppress("InjectDispatcher") // The seeding workers run on the shared pool.
    fun seed(aggregates: Int) {
        PostgresEventLog(pool, H2_CONTEXT, partitions).createSchema()
        PostgresSnapshotStore(pool, H2_CONTEXT).createSchema()
        val log = PostgresEventLog(pool, H2_CONTEXT, partitions)
        val snapshots = PostgresSnapshotStore(pool, H2_CONTEXT)
        val codec = EventCodec()
        val next = AtomicInteger()
        val now = System.currentTimeMillis()
        runBlocking(Dispatchers.Default) {
            repeat(SEED_WORKERS) {
                launch {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= aggregates) break
                        val ref = model.type.ref(Caller.DEFAULT_TENANT, accountId(index))
                        val opened = AccountOpened("owner-$index", INITIAL_BALANCE)
                        val outcome = log.append(
                            EventStreamRecord(
                                ref = ref,
                                version = 1,
                                commandId = "open-$index",
                                requestId = "open-$index",
                                commandType = model.commandType(OpenAccount::class.java).name,
                                commitTime = now,
                                events = listOf(codec.encode(model, opened)),
                                scope = Scope.NONE,
                            ),
                        )
                        check(outcome is AppendOutcome.Committed) { "Seeding $ref failed: $outcome" }
                        val state = model.evolve(model.initialState(ref), opened)
                        snapshots.save(codec.snapshotOf(model, ref, state, 1, Scope.NONE, now, now))
                    }
                }
            }
        }
    }

    /** The live owner of each partition of the commands' lease group, by partition. */
    fun owners(): Map<Int, String> = connection { connection ->
        connection.prepareStatement(
            "SELECT partition, owner FROM wow_lease WHERE subscription_group = ? AND expires_at > ?",
        ).use { statement ->
            statement.setString(1, "$H2_SERVICE/$H2_CONTEXT")
            statement.setLong(2, System.currentTimeMillis())
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getInt(1), rows.getString(2)) }
            }
        }
    }

    /**
     * How far the snapshot subscription trails the log: the partitions' heads less its checkpoints, in entries; null
     * while either table is missing.
     */
    fun snapshotLag(): Long? = try {
        connection { connection ->
            val heads = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT coalesce(sum(sequence), 0) FROM \"$counterTable\"").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
            val positions = connection.prepareStatement(
                // The database holds this one context, so its one snapshot subscription.
                "SELECT coalesce(sum(position), 0) FROM wow_checkpoint WHERE subscription = ?",
            ).use { statement ->
                statement.setString(1, "snapshot")
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
            heads - positions
        }
    } catch (_: java.sql.SQLException) {
        null
    }

    fun resetStatements() {
        if (statementsAvailable) query("SELECT pg_stat_statements_reset()")
    }

    /** The statements executed since the last [resetStatements], by kind. */
    @Suppress("CyclomaticComplexMethod") // One branch per kind of statement.
    fun statements(): StatementCounts {
        if (!statementsAvailable) return StatementCounts.NONE
        val rows = connection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT query, calls FROM pg_stat_statements").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1).lowercase() to rows.getLong(2)) }
                }
            }
        }
        var logReads = 0L
        var snapshotReads = 0L
        var tailReads = 0L
        var appends = 0L
        var snapshotWrites = 0L
        var other = 0L
        rows.forEach { (query, calls) ->
            val writes = "insert " in query || "update " in query || "delete " in query
            when {
                query.contains(logTable) && "insert into" in query -> appends += calls
                query.contains(counterTable) && " as head" in query -> tailReads += calls
                query.contains(snapshotTable) && writes -> snapshotWrites += calls
                query.contains(snapshotTable) -> snapshotReads += calls
                query.contains(logTable) && !writes -> logReads += calls
                else -> other += calls
            }
        }
        return StatementCounts(logReads, snapshotReads, tailReads, appends, snapshotWrites, other)
    }

    /** How many log entries each request id of [prefix] has. */
    fun committedRequests(prefix: String): Map<String, Int> = connection { connection ->
        connection.prepareStatement(
            "SELECT request_id, count(*) FROM \"$logTable\" WHERE tenant_id = ? AND request_id LIKE ? GROUP BY request_id",
        ).use { statement ->
            statement.setString(1, Caller.DEFAULT_TENANT)
            statement.setString(2, "$prefix-%")
            statement.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
            }
        }
    }

    private fun update(sql: String) {
        connection { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun query(sql: String) {
        connection { connection -> connection.createStatement().use { it.executeQuery(sql).close() } }
    }

    private fun <T> connection(block: (Connection) -> T): T = pool.connection.use(block)

    override fun close() {
        pool.close()
    }

    companion object {
        const val INITIAL_BALANCE = 1_000_000_000L
        private const val POOL = 32

        /** Half the pool: an append may hold a second connection while the format of its table is checked. */
        private const val SEED_WORKERS = 16

        fun accountId(index: Int) = "account-$index"
    }
}
