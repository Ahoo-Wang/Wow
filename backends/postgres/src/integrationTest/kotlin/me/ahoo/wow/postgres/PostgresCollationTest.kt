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
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.reservation.PostgresReservationTables
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The keys of the log, the reservations and the timers compare by code point (`COLLATE "C"`), whatever the database's
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
    fun `the key columns of the log, the reservations and the timers should declare the C collation`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext("icu")
            val type = AggregateType(context, "order")
            PostgresEventLog(icu, context, 1).also { it.createSchema() }.append(stream(type, "t", "o-1"))
            val owner = type.ref("t", "o-1")
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
            val keys = mapOf(
                PostgresEventLogTables(context).aggregate("order").base to
                    listOf("tenant_id", "aggregate_id", "request_id"),
                PostgresReservationTables(context, "order").name to listOf("tenant_id", "namespace", "key"),
                PostgresTimerTables(context).aggregate("order").base to
                    listOf("owner_tenant_id", "owner_id", "key", "timer_id"),
            )
            keys.forEach { (table, columns) -> collations(table, columns).assert().containsOnly("C") }
        }

    /** The collation each of [columns] of [table] declares. */
    private fun collations(table: String, columns: List<String>): List<String> = icu.connection.use { connection ->
        connection.prepareStatement(
            "SELECT c.collname FROM pg_attribute a JOIN pg_class t ON t.oid = a.attrelid " +
                "JOIN pg_collation c ON c.oid = a.attcollation WHERE t.relname = ? AND a.attname = ANY (?)",
        ).use { query ->
            query.setString(1, table)
            query.setArray(2, connection.createArrayOf("text", columns.toTypedArray()))
            query.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }.also { it.size.assert().isEqualTo(columns.size) }
    }

    private companion object {
        const val DATABASE = "wow_icu"
        const val POSTGRES_PORT = 5432
    }
}
