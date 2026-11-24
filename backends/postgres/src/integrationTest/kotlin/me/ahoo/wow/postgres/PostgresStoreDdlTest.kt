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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.postgres.timer.PostgresTimerTables
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import javax.sql.DataSource

/**
 * The stores' DDL modes (review A5-10): `auto` creates, `validate` only checks, `none` leaves the schema alone. Both of
 * the last still record an existing log or timer table in its registry (#6751): the tails and the due read find a
 * table only there, and a record is data an account without DDL rights may insert.
 */
class PostgresStoreDdlTest {
    private val dataSource = PostgresFixture.dataSource

    private fun tableExists(table: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
        ).use { query ->
            query.setString(1, table)
            query.executeQuery().use { it.next() }
        }
    }

    @Test
    fun `validate refuses a missing log, and passes once auto created it`() {
        val context = PostgresFixture.newContext("ddl")
        val validating = PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))
        assertThrows<IllegalStateException> { validating.createSchema() }.message.assert()
            .contains("does not exist, and the store only validates its schema")

        PostgresEventLog(dataSource, context, 2).also {
            it.createSchema()
            it.createTable("order")
        }
        validating.createSchema()
        validating.createTable("order")
        assertThrows<IllegalStateException> { validating.createTable("cart") }.message.assert()
            .contains("does not exist, and the store only validates its schema")
    }

    @Test
    fun `validate refuses a structure recorded in another format`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresEventLog(dataSource, context, 2).also {
            it.createSchema()
            it.createTable("order")
        }
        PostgresFixture.bumpFormat(PostgresEventLogTables(context).aggregate("order").base)
        assertThrows<IllegalStateException> {
            PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))
                .createTable("order")
        }.message.assert().contains("reads format 1", "start from an empty store")
    }

    @Test
    fun `validate refuses a table without a format record`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresEventLog(dataSource, context, 2).createSchema()
        PostgresFixture.createUnrecorded(PostgresEventLogTables(context).aggregate("order").base)
        assertThrows<IllegalStateException> {
            PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))
                .createTable("order")
        }.message.assert().contains("exists without a format record", "Drop it, or restore its record with it.")
    }

    @Test
    fun `none creates and checks nothing`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresTimerStore(dataSource, context, ddl = Ddl.NONE).createSchema()
        PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.NONE)).createTable("order")
        tableExists(PostgresTimerTables(context).aggregate("order").base).assert().isFalse()
        tableExists(PostgresEventLogTables(context).aggregate("order").base).assert().isFalse()
    }

    @Test
    fun `none records no table that does not exist, whose record would fail every tail of the context`() {
        val context = PostgresFixture.newContext("ddl")
        PostgresEventLog(dataSource, context, 2).createSchema()
        PostgresEventLog(dataSource, context, 2, options = PostgresEventLogOptions(ddl = Ddl.NONE)).createTable("order")
        records(PostgresEventLogTables.REGISTRY, context).assert().isEmpty()
    }

    @Test
    fun `none records a log table its schema's owner created without its record, and the tail reads its entries`() =
        tailedOnceRecorded(Ddl.NONE)

    @Test
    fun `validate records a log table without its record, and the tail reads its entries`() =
        tailedOnceRecorded(Ddl.VALIDATE)

    /**
     * A log table as a schema-only copy leaves it, its registry record missing, opened under [ddl]: its first use
     * records it, so the tail reads its entries rather than wait at the first of them for good.
     */
    private fun tailedOnceRecorded(ddl: Ddl): Unit = runBlocking {
        val context = PostgresFixture.newContext("ddl")
        PostgresFixture.log(PARTITIONS, context, aggregates = listOf("order"))
        deleteRecords(PostgresEventLogTables.REGISTRY, context)

        val log = PostgresEventLog(dataSource, context, PARTITIONS, options = PostgresEventLogOptions(ddl = ddl))
        log.createSchema()
        val stream = stream(AggregateType(context, "order").ref("tenant", "o-1"))
        log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        records(PostgresEventLogTables.REGISTRY, context).assert().containsExactly("order")
        val partition = Partitioner.partitionOf(stream.ref, PARTITIONS)
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(partition).first() }.stream.ref.assert().isEqualTo(stream.ref)
    }

    @Test
    fun `none records a timer table its schema's owner created without its record, and its timers are due`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext("ddl")
            val owner = AggregateType(context, "order").ref("tenant", "o-1")
            PostgresTimerStore(dataSource, context).also { it.createSchema() }.put(timer(owner, "first"))
            deleteRecords(PostgresTimerTables.REGISTRY, context)

            val store = PostgresTimerStore(dataSource, context, ddl = Ddl.NONE).also { it.createSchema() }
            store.put(timer(owner, "second")).assert().isTrue()
            records(PostgresTimerTables.REGISTRY, context).assert().containsExactly("order")
            store.due(setOf(partitionOf(owner)), until = Long.MAX_VALUE, perPartition = 10).map { it.key }
                .assert().containsExactly("first", "second")
        }

    @Test
    fun `validate refuses a log table whose record it cannot write, naming it, and passes once it is there`() {
        val context = PostgresFixture.newContext("ddl")
        val base = PostgresEventLogTables(context).aggregate("order").base
        PostgresFixture.log(PARTITIONS, context, aggregates = listOf("order"))
        deleteRecords(PostgresEventLogTables.REGISTRY, context)
        val account = accountWithout(PostgresEventLogTables.REGISTRY)
        fun validating() =
            PostgresEventLog(account, context, PARTITIONS, options = PostgresEventLogOptions(ddl = Ddl.VALIDATE))

        validating().createSchema()
        assertThrows<IllegalStateException> { validating().createTable("order") }.message.assert().contains(
            "The log table $base of order in $context has no record in wow_log_aggregate",
            "ddl validate could not write it",
            "Grant INSERT on wow_log_aggregate, or insert its record: ('$context', 'order', '$base').",
        )
        PostgresEventLog(dataSource, context, PARTITIONS).createTable("order")
        validating().createTable("order")
        records(PostgresEventLogTables.REGISTRY, context).assert().containsExactly("order")
    }

    @Test
    fun `validate refuses a timer table whose record it cannot write, naming it, and passes once it is there`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext("ddl")
            val base = PostgresTimerTables(context).aggregate("order").base
            val owner = AggregateType(context, "order").ref("tenant", "o-1")
            PostgresTimerStore(dataSource, context).also { it.createSchema() }.put(timer(owner, "first"))
            deleteRecords(PostgresTimerTables.REGISTRY, context)
            val account = accountWithout(PostgresTimerTables.REGISTRY)
            fun validating() = PostgresTimerStore(account, context, ddl = Ddl.VALIDATE).also { it.createSchema() }

            assertThrows<IllegalStateException> { runBlocking { validating().find(owner, "first") } }.message.assert()
                .contains(
                    "The timer table $base of order in $context has no record in wow_timer_aggregate",
                    "ddl validate could not write it",
                    "Grant INSERT on wow_timer_aggregate, or insert its record: ('$context', 'order', '$base').",
                )
            PostgresTimerStore(dataSource, context).find(owner, "first")
            validating().find(owner, "first")?.key.assert().isEqualTo("first")
        }

    @Test
    fun `an aggregate recorded with another table than its own is refused, naming it`(): Unit = runBlocking {
        val context = PostgresFixture.newContext("ddl")
        val elsewhere = "elsewhere_" + context.replace('-', '_')
        PostgresEventLog(dataSource, context, PARTITIONS).createSchema()
        PostgresTimerStore(dataSource, context).createSchema()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO ${PostgresEventLogTables.REGISTRY} (context, aggregate, log_table) " +
                        "VALUES ('$context', 'order', '$elsewhere')",
                )
                statement.execute(
                    "INSERT INTO ${PostgresTimerTables.REGISTRY} (context, aggregate, timer_table) " +
                        "VALUES ('$context', 'order', '$elsewhere')",
                )
            }
        }
        val log = PostgresEventLogTables(context).aggregate("order").base
        assertThrows<IllegalStateException> { PostgresEventLog(dataSource, context, PARTITIONS).createTable("order") }
            .message.assert()
            .isEqualTo(
                "The aggregate order of $context is recorded in wow_log_aggregate with another log table than $log.",
            )
        val timers = PostgresTimerTables(context).aggregate("order").base
        val owner = AggregateType(context, "order").ref("tenant", "o-1")
        assertThrows<IllegalStateException> { runBlocking { PostgresTimerStore(dataSource, context).find(owner, "k") } }
            .message.assert()
            .isEqualTo(
                "The aggregate order of $context is recorded in wow_timer_aggregate with another timer table than $timers.",
            )
    }

    /** The aggregates [registry] records for [context]. */
    private fun records(registry: String, context: String): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT aggregate FROM $registry WHERE context = ? ORDER BY aggregate").use {
            it.setString(1, context)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    /** Deletes [context]'s records from [registry], as a schema-only copy of the database leaves the registry. */
    private fun deleteRecords(registry: String, context: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM $registry WHERE context = ?").use {
                it.setString(1, context)
                it.executeUpdate().assert().isPositive()
            }
        }
    }

    /**
     * The data source of a new account without DDL rights that reads and writes every table there is now, but may not
     * insert into [registry].
     */
    private fun accountWithout(registry: String): DataSource {
        val role = "wow_ddl_" + UUID.randomUUID().toString().replace("-", "").take(ROLE_SUFFIX)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE $role LOGIN PASSWORD '$role'")
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $role")
                statement.execute("REVOKE INSERT ON $registry FROM $role")
            }
        }
        val container = WowContainers.postgres
        return PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = role
            password = role
        }
    }

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    private fun partitionOf(ref: AggregateRef) = Partitioner.partitionOf(ref, PARTITIONS)

    private fun timer(owner: AggregateRef, key: String) = Timer(
        id = "$owner#$key@1",
        owner = owner,
        key = key,
        version = 1,
        partition = partitionOf(owner),
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = owner,
        commandType = "${owner.type.context}.order.Cancel",
        body = "{}",
    )

    private companion object {
        const val PARTITIONS = 4
        const val TAIL_TIMEOUT_MS = 30_000L
        const val ROLE_SUFFIX = 12
    }
}
