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

package me.ahoo.wow.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.reservation.PostgresReservationTables
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Every text column the store keys or indexes on compares by code point (`COLLATE "C"`), whatever the database's
 * default: here an ICU `en-US` database, which orders `a` before `B` where code points order `B` first.
 */
class PostgresCollationTest {
    private val container get() = WowContainers.postgres

    /** A database of the container whose default collation is ICU's `en-US`, created once, and a pool of it. */
    private val icu: HikariDataSource by lazy {
        PostgresFixture.dataSource.connection.use { connection ->
            val exists = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use {
                it.setString(1, DATABASE)
                it.executeQuery().use { rows -> rows.next() }
            }
            if (!exists) {
                connection.createStatement().use {
                    it.execute(
                        "CREATE DATABASE $DATABASE LOCALE_PROVIDER icu ICU_LOCALE 'en-US' LOCALE 'C' TEMPLATE template0",
                    )
                }
            }
        }
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${container.host}:${container.getMappedPort(POSTGRES_PORT)}/$DATABASE"
                username = container.username
                password = container.password
                maximumPoolSize = 2
                minimumIdle = 0
            },
        )
    }

    @AfterEach
    fun close() {
        icu.close()
    }

    private fun stream(type: AggregateType, tenant: String, id: String) = EventStreamRecord(
        ref = type.ref(tenant, id),
        version = 1,
        commandId = "command-$tenant-$id",
        requestId = "request-$tenant-$id",
        commandType = "${type.context}.${type.name}.Create",
        commitTime = 1L,
        events = listOf(EventRecord("${type.context}.${type.name}.Created", 1, "{}")),
        scope = Scope.NONE,
    )

    @Test
    fun `the access walk should page a log's aggregates by code point on a database of another collation`(): Unit =
        runBlocking {
            // The database's own order is not the code points'.
            icu.connection.use { connection ->
                connection.createStatement().use {
                    it.executeQuery("SELECT 'a' < 'B'").use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }.assert().isTrue()
            val context = PostgresFixture.newContext("icu")
            val log = PostgresEventLog(icu, context, 1).also { it.createSchema() }
            val type = AggregateType(context, "order")
            listOf("a" to "x", "B" to "x", "B" to "y", "_" to "x").forEach { (tenant, id) ->
                log.append(stream(type, tenant, id))
            }
            // By code point, `B` (66) before `_` (95) before `a` (97); ICU's en-US orders `_ a B`.
            val all = log.aggregates(type, null, 10).refs.map { it.tenantId to it.id }
            all.assert().containsExactly("B" to "x", "B" to "y", "_" to "x", "a" to "x")
            // Page by page, each cursor resumes in the same order.
            val first = log.aggregates(type, null, 2)
            val rest = log.aggregates(type, first.next, 10)
            (first.refs + rest.refs).map { it.tenantId to it.id }.assert().containsExactlyElementsOf(all)
        }

    @Test
    fun `no indexed text column of the store should compare by another collation than C`(): Unit = runBlocking {
        // Every structure of the store, created on a database whose default collation is not C.
        val context = PostgresFixture.newContext("icu")
        val type = AggregateType(context, "order")
        val owner = type.ref("t", "o-1")
        PostgresEventLog(icu, context, 1).also { it.createSchema() }.append(stream(type, "t", "o-1"))
        PostgresSnapshotStore(icu, context).save(
            SnapshotRecord(owner, 1, "fingerprint", "{}", firstEventTime = 1L, lastEventTime = 1L),
        )
        PostgresReservationStore(icu).reserve(ReservationScope.of(type, "t", "name"), "k", owner, "h", 1.minutes)
        PostgresTimerStore(icu, context).also { it.createSchema() }.put(
            Timer(
                id = "$owner#k@1",
                owner = owner,
                key = "k",
                version = 1,
                partition = 0,
                rule = ScheduleRule.At(5),
                dueAt = 5,
                target = owner,
                commandType = "$context.order.Cancel",
                body = "{}",
            ),
        )
        PostgresCheckpointStore(icu).createSchema()
        PostgresLeaseStore(icu).createSchema()
        PostgresFailureLedger(icu).createSchema()
        // Each text column of every index of a wow_* table, with its collation: none but C. The rule rather than a
        // list, so a table added later cannot forget it.
        val indexed = icu.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(INDEXED_TEXT).use { rows ->
                    generateSequence {
                        if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getString(3)) else null
                    }.toList()
                }
            }
        }
        indexed.map { it.first }.toSet().assert().contains(
            "wow_format",
            "wow_log_aggregate",
            "wow_timer_aggregate",
            "wow_snapshot_table",
            "wow_checkpoint",
            "wow_lease",
            "wow_lease_member",
            "wow_failure_ledger",
            PostgresEventLogTables(context).aggregate("order").base,
            PostgresSnapshotTables(context, "order").base,
            PostgresReservationTables(context, "order").name,
            PostgresTimerTables(context).aggregate("order").base,
        )
        indexed.filter { it.third != "C" }.assert().isEmpty()
    }

    private companion object {
        const val DATABASE = "wow_icu"

        /** Each collatable column of each index of a wow_* table: the table, the index, the collation. */
        val INDEXED_TEXT = """
            SELECT t.relname, i.relname, c.collname
            FROM pg_index x
            JOIN pg_class i ON i.oid = x.indexrelid
            JOIN pg_class t ON t.oid = x.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = current_schema()
            CROSS JOIN LATERAL unnest(x.indcollation::oid[]) AS k(coll)
            JOIN pg_collation c ON c.oid = k.coll
            WHERE t.relname LIKE 'wow\_%'
        """.trimIndent()
        const val POSTGRES_PORT = 5432
    }
}
