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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The aggregate tables a log makes sure of: only those of its own context. */
class PostgresLogCatalogTest : PostgresLogFixtures() {
    @Test
    fun `an aggregate type of another context is refused by a read, an append and the table checks`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(1, context)
            val elsewhere = AggregateType(PostgresFixture.newContext(), "order")
            val ref = elsewhere.ref("tenant", "a")
            val stream = EventStreamRecord(
                ref,
                1,
                "c-a-1",
                "r-a-1",
                "$elsewhere.Act",
                1,
                listOf(EventRecord("$elsewhere.Acted", 1, "{}")),
                Scope.NONE,
            )
            val refused = "$elsewhere is not in context $context."
            // A read makes sure of the type's table; an append refuses the stream before it takes a connection.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.read(ref, 1, 1).toList() } }
                .hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.readTail(ref, 1, 0) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.append(stream, null) } }
                .hasMessage("$ref is not in context $context.")
            // The checks of a type's table, which create none.
            assertThrownBy<IllegalArgumentException> { runBlocking { log.holds(elsewhere) } }.hasMessage(refused)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.aggregates(elsewhere, null, 1) } }
                .hasMessage(refused)
        }

    @Test
    fun `creating the schema twice is harmless`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order"))
        PostgresFixture.log(4, context, listOf("order")).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context)
        PostgresFixture.bumpFormat(table(context, "partition"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context) }
    }

    @Test
    fun `an aggregate table recorded in another format version is refused`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order"))
        PostgresFixture.bumpFormat(table(context, "order"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context, listOf("order")) }
        val log = PostgresFixture.log(4, context)
        assertThrownBy<IllegalStateException> {
            runBlocking { log.read(AggregateType(context, "order").ref("tenant", "a")).toList() }
        }
    }

    @Test
    fun `a log table without a format record is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.createUnrecorded(table(context, "partition"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context) }
    }

    @Test
    fun `a different partition count is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context)
        assertThrownBy<IllegalStateException> { PostgresFixture.log(8, context) }
    }

    /**
     * Of two first starts with different counts, only the one whose count is stored opens the log (#6750): the
     * counters are filled under the creation lock, so the second start's insert finds the first's counters. The
     * interleaving is held, not timed: a `BEFORE INSERT` trigger on the counters holds the second start's insert (its
     * session carries `wow.review.slow=on`) until the first start has tried, which on `main` let both open.
     */
    @Test
    fun `two first starts with different partition counts never both open the log`() {
        val context = PostgresFixture.newContext("count-race")
        val tables = PostgresEventLogTables(context)
        // The tables and their format records as a first start makes them, then emptied: what two first starts both
        // find once one of them has created the tables under the creation lock.
        PostgresEventLog(PostgresFixture.dataSource, context, SMALL).createSchema()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM ${tables.partitions}")
                statement.execute(
                    """
                    CREATE OR REPLACE FUNCTION wow_review_hold_insert() RETURNS trigger AS ${'$'}${'$'}
                    BEGIN
                        IF current_setting('wow.review.slow', true) = 'on' AND NEW.partition = 0 THEN
                            PERFORM pg_sleep($HOLD_SECONDS);
                        END IF;
                        RETURN NEW;
                    END
                    ${'$'}${'$'} LANGUAGE plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TRIGGER wow_review_hold BEFORE INSERT ON ${tables.partitions} " +
                        "FOR EACH ROW EXECUTE FUNCTION wow_review_hold_insert()",
                )
            }
        }
        val container = WowContainers.postgres
        val held = PGSimpleDataSource().apply {
            setURL(container.jdbcUrl)
            user = container.username
            password = container.password
            options = "-c wow.review.slow=on"
        }

        // The second start: its insert takes its snapshot (the table empty), then waits in the trigger.
        val large = CompletableFuture.supplyAsync {
            runCatching { PostgresEventLog(held, context, LARGE).createSchema() }
        }
        awaitHeldInsert()
        // The first start, meanwhile: it finds the table empty too, fills 16 counters, and counts 16.
        val small = runCatching { PostgresEventLog(PostgresFixture.dataSource, context, SMALL).createSchema() }
        val second = large.get(HOLD_SECONDS * 4L, TimeUnit.SECONDS)

        val stored = PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM ${tables.partitions}").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
        // At most one of the two counts may open a log over the one counter table, and it is the count stored.
        val opened = listOf(SMALL to small, LARGE to second)
            .filter { (_, outcome) -> outcome.isSuccess }
            .map { it.first }
        opened.assert().describedAs("the partition counts whose log opened, over $stored counter rows").hasSize(1)
        opened.single().assert().isEqualTo(stored)
    }

    /** Waits until a session of this database sleeps in the trigger: the held insert has its snapshot. */
    private fun awaitHeldInsert() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_SECONDS.toLong())
        while (System.nanoTime() < deadline) {
            val sleeping = PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep' AND query LIKE '%generate_series%'",
                    ).use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            if (sleeping > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("The second start's insert never reached the trigger.")
    }

    @Test
    fun `an aggregate named like the partition counters is refused`() {
        val context = PostgresFixture.newContext()
        assertThrownBy<IllegalArgumentException> { PostgresFixture.log(4, context, listOf("Partition")) }
    }

    @Test
    fun `two aggregates that take one table name are refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order-item"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context, listOf("order_item")) }
    }

    @Test
    fun `a long aggregate name is cut and hashed within the identifier limit`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val long = AggregateType(context, "a".repeat(80))
        val log = PostgresFixture.log(1, context, listOf(long.name))
        log.append(stream("a", 1, aggregate = long)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, aggregate = long)).assert().isEqualTo(AppendOutcome.Conflict(1))
        val name = PostgresIdentifiers.of("wow_log", context, long.name)
        name.length.assert().isEqualTo(PostgresIdentifiers.MAX_LENGTH)
        name.assert().isEqualTo(PostgresIdentifiers.limited(table(context, long.name)))
    }

    @Test
    fun `concurrent first reads of a table make sure of it once`(): Unit = runBlocking {
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val type = AggregateType(context, "order")
        PostgresFixture.log(1, context).append(stream("a", 1, aggregate = type))
        val reader = PostgresEventLog(counted.dataSource, context, 1).also { it.createSchema() }
        val mark = counted.begin()
        coroutineScope {
            (1..FIRST_READS).map { async(Dispatchers.IO) { reader.read(type.ref("tenant", "a")).toList() } }.awaitAll()
        }
        // One ensure (its registry record), then one read each.
        val statements = counted.since(mark)
        statements.count { it.startsWith("INSERT INTO ${PostgresEventLogTables.REGISTRY}") }.assert().isEqualTo(1)
        statements.count { it.startsWith("SELECT ${AggregateLogTable.FACTS}") }.assert().isEqualTo(FIRST_READS)
    }

    @Test
    fun `a table dropped since this log made sure of it is made sure of again on its next use`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = PostgresFixture.log(1, context)
        val type = AggregateType(context, "order")
        val drop = "DROP TABLE \"${table(context, "order")}\""
        log.append(stream("a", 1, aggregate = type))
        execute(drop)
        log.read(type.ref("tenant", "a")).toList().assert().isEmpty()
        execute(drop)
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        execute(drop)
        log.appendAll(listOf(Append.of(stream("a", 1, aggregate = type)), Append.of(stream("b", 1, aggregate = type))))
            .forEach { it.assert().isInstanceOf(AppendOutcome.Committed::class.java) }
        log.positionOf(type.ref("tenant", "b"), 1).assert().isNotNull()
    }

    @Test
    fun `a node creating its schema while others append neither deadlocks them nor fails`(): Unit = runBlocking {
        // A node starting up (createSchema) while others commit groups and single appends to the same tables.
        val context = PostgresFixture.newContext()
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        PostgresFixture.log(PARTITIONS, context, listOf(order.name, cart.name))
        val outcomes = ConcurrentLinkedQueue<AppendOutcome>()
        val writing = AtomicBoolean(true)
        coroutineScope {
            for (writer in 0 until WRITERS) {
                val log = PostgresFixture.log(PARTITIONS, context)
                launch(Dispatchers.IO) {
                    var version = 0
                    while (writing.get()) {
                        version++
                        val refs = (1..GROUP).map {
                            (if (it % 2 == writer % 2) order else cart).ref("tenant", "w$writer-$it")
                        }
                        refs.groupBy { Partitioner.partitionOf(it, PARTITIONS) }.values.forEach { inPartition ->
                            val appends = inPartition.map { Append.of(stream(it.id, version, aggregate = it.type)) }
                            outcomes += if (writer == 0) appends.map { log.append(it.stream) } else log.appendAll(appends)
                        }
                    }
                }
            }
            launch(Dispatchers.IO) {
                try {
                    repeat(STARTS) { PostgresFixture.log(PARTITIONS, context) }
                } finally {
                    writing.set(false)
                }
            }
        }
        outcomes.filterNot { it is AppendOutcome.Committed }.assert().isEmpty()
    }

    private companion object {
        const val FIRST_READS = 32
        const val WRITERS = 4
        const val STARTS = 200
        const val SMALL = 16
        const val LARGE = 32
        const val HOLD_SECONDS = 3
        const val POLL_MILLIS = 20L
    }
}
