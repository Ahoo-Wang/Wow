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
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.jupiter.api.Test

/**
 * Deep review r1 (PostgreSQL backend): a log's tail and a timer store's due read find a context's aggregate tables
 * through their registries (`wow_log_aggregate`, `wow_timer_aggregate`), which only `ddl: auto` writes, when it creates
 * a table. Under `ddl: none` ("the schema is someone else's, and a missing structure fails at its first use") a table
 * its schema's owner created without its registry row takes appends and timers, and fails nothing; but the tail never
 * reads past its first entry, so the partition's subscriptions stop there, and its timers are never due.
 *
 * Each test makes the tables with `auto`, then deletes their registry rows: the schema a schema-only dump or a
 * migration that copied the `CREATE` statements leaves (the registries' tables, empty).
 */
class PostgresUnregisteredTablesTest {
    private val dataSource = PostgresFixture.dataSource

    @Test
    fun `a log table its schema's owner created without its registry row is tailed, or refused`() =
        tailedOrRefused(Ddl.NONE)

    /** `validate` checks the table and its format record, not its registry row: it passes over the same state. */
    @Test
    fun `under validate, a log table without its registry row is tailed, or refused`() = tailedOrRefused(Ddl.VALIDATE)

    private fun tailedOrRefused(ddl: Ddl): Unit = runBlocking {
        val context = PostgresFixture.newContext("unregistered")
        val type = AggregateType(context, "order")
        PostgresFixture.log(PARTITIONS, context, aggregates = listOf(type.name))
        delete("DELETE FROM ${PostgresEventLogTables.REGISTRY} WHERE context = ?", context)

        val log = PostgresEventLog(dataSource, context, PARTITIONS, options = PostgresEventLogOptions(ddl = ddl))
        log.createSchema()
        val stream = stream(type.ref("tenant", "o-1"))
        val outcome = runCatching { log.append(stream) }
        if (outcome.getOrNull() !is AppendOutcome.Committed) {
            // Refused at its first use, as `none` promises of a missing structure: the expected behaviour.
            return@runBlocking
        }
        val partition = Partitioner.partitionOf(stream.ref, PARTITIONS)
        log.head(partition).assert().describedAs("the partition's head after the commit").isEqualTo(1L)
        val tailed = withTimeoutOrNull(TAIL_WAIT_MS) { log.tail(partition).first() }
        tailed?.stream?.ref.assert().describedAs("the committed entry, as the tail reads it").isEqualTo(stream.ref)
    }

    @Test
    fun `a timer table its schema's owner created without its registry row is read, or refused`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext("unregistered-timers")
            val owner = AggregateType(context, "order").ref("tenant", "o-1")
            PostgresTimerStore(dataSource, context).also { it.createSchema() }.put(timer(owner, "first"))
            delete("DELETE FROM ${PostgresTimerTables.REGISTRY} WHERE context = ?", context)

            val store = PostgresTimerStore(dataSource, context, ddl = Ddl.NONE).also { it.createSchema() }
            val put = runCatching { store.put(timer(owner, "second")) }
            if (put.isFailure) {
                return@runBlocking
            }
            store.find(owner, "second").assert().describedAs("the timer as the store found it").isNotNull()
            val due = store.due(setOf(partitionOf(owner)), until = Long.MAX_VALUE, perPartition = 10)
            due.map { it.key }.assert().describedAs("the due timers of the owner's partition")
                .contains("first", "second")
        }

    private fun delete(sql: String, context: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, context)
                statement.executeUpdate().assert().isPositive()
            }
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
        const val TAIL_WAIT_MS = 3_000L
    }
}
