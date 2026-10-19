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

package me.ahoo.wow.clickhouse.sink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.SinkFixtures.ORDER
import me.ahoo.wow.clickhouse.sink.SinkFixtures.recordOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class ClickHouseSinkTest {
    private val statements = RecordingStatements()

    private fun sinks(options: ClickHouseSinkOptions = ClickHouseSinkOptions()) =
        ClickHouseSinks(statements, SinkFixtures.MANIFEST, options)

    @Test
    fun `auto should create the tables once, then insert each record deduplicated by its id`(): Unit = runTest {
        val sink = sinks().sinkOf("shop", "order")
        statements.create(sink.schema)
        sink.write(listOf(recordOf(version = 1)))
        sink.write(listOf(recordOf(version = 2)))
        sink.write(emptyList())
        statements.executed.assert()
            .isEqualTo(sink.schema.ddl + sink.schema.flatView(FlatLineage(listOf(sink.schema.flatFingerprint))))
        statements.inserts.map { it.first }.assert().containsOnly("`default`.`shop_order_state_events`")
        statements.inserts.map { it.third }.assert()
            .containsExactly("shop.order/tenant-1/order-1@1", "shop.order/tenant-1/order-1@2")
        statements.inserts.first().second.assert().isEqualTo(
            ClickHouseRows.bodyOf(listOf(recordOf())).toString(Charsets.UTF_8)
        )
        sink.toString().assert().isEqualTo("ClickHouseSink(default.shop_order_state_events)")
    }

    @Test
    fun `a batch should be split by the batch size, each part deduplicated by a digest of its ids`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(ddl = DdlMode.NONE, batchSize = 2)).sinkOf("shop", "order")
        val records = (1..5).map { recordOf(version = it) }
        sink.write(records)
        sink.write(records)
        statements.executed.assert().isEmpty()
        statements.inserts.size.assert().isEqualTo(6)
        statements.inserts.map { it.second.trimEnd().lines().size }.assert().containsExactly(2, 2, 1, 2, 2, 1)
        val tokens = statements.inserts.map { it.third }
        tokens.take(3).assert().isEqualTo(tokens.drop(3))
        tokens[0].assert().startsWith("batch:").hasSize("batch:".length + 64)
        tokens[2].assert().isEqualTo("shop.order/tenant-1/order-1@5")
        ClickHouseSink.tokenOf(records.take(2)).assert().isNotEqualTo(ClickHouseSink.tokenOf(records.drop(1).take(2)))
    }

    @Test
    fun `writes queued while an insert runs should go together in the next one, up to the batch size`(): Unit =
        runTest {
            // Finding A2-10: one INSERT per commit, three parts each with the views, overran ClickHouse's merges.
            val sink = sinks(ClickHouseSinkOptions(ddl = DdlMode.NONE, batchSize = 3)).sinkOf("shop", "order")
            val gate = CompletableDeferred<Unit>().also { statements.gate = it }
            val first = async { sink.write(listOf(recordOf(version = 1))) }
            statements.waiting.await()
            val queued = (2..6).map { version -> async { sink.write(listOf(recordOf(version = version))) } }
            testScheduler.runCurrent()
            gate.complete(Unit)
            (listOf(first) + queued).awaitAll()
            statements.inserts.map { it.second.trimEnd().lines().size }.assert().containsExactly(1, 3, 2)
            statements.inserts[1].third.assert().startsWith("batch:")
        }

    @Test
    fun `a failed insert should fail every write it carried, and the next writes go on`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(ddl = DdlMode.NONE)).sinkOf("shop", "order")
        val gate = CompletableDeferred<Unit>().also { statements.gate = it }
        val first = async { runCatching { sink.write(listOf(recordOf(version = 1))) } }
        statements.waiting.await()
        val queued = (2..3).map { version -> async { runCatching { sink.write(listOf(recordOf(version = version))) } } }
        testScheduler.runCurrent()
        statements.failInsert = IllegalStateException("down")
        gate.complete(Unit)
        (listOf(first) + queued).awaitAll().forEach { it.exceptionOrNull()?.message.assert().isEqualTo("down") }
        statements.failInsert = null
        sink.write(listOf(recordOf(version = 4)))
        statements.inserts.size.assert().isEqualTo(1)
    }

    @Test
    fun `without the history the sink should insert into the current state`(): Unit = runTest {
        val options = ClickHouseSinkOptions(
            ddl = DdlMode.NONE,
            aggregates = mapOf(ORDER to AggregateSinkOptions(history = false)),
        )
        sinks(options).sinkOf("shop", "order").write(listOf(recordOf()))
        statements.inserts.single().first.assert().isEqualTo("`default`.`shop_order_state`")
    }

    @Test
    fun `validate should create nothing and pass over the expected tables`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(database = "analytics", ddl = DdlMode.VALIDATE)).sinkOf("shop", "order")
        statements.create(sink.schema)
        sink.write(listOf(recordOf()))
        statements.executed.assert().isEmpty()
        statements.inserts.size.assert().isEqualTo(1)
    }

    @Test
    fun `validate should name what is missing or different and write nothing`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(ddl = DdlMode.VALIDATE)).sinkOf("shop", "order")
        statements.create(sink.schema)
        statements.tables.remove("default.shop_order_events_mv")
        statements.tables.remove("default.shop_order_events")
        statements.tables["default.shop_order_state"] = listOf(ClickHouseColumn("id", "String"))
        val error = runCatching { sink.write(listOf(recordOf())) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        error!!.message.assert()
            .contains("shop_order_events does not exist")
            .contains("shop_order_events_mv does not exist")
            .contains("shop_order_state has the columns [ClickHouseColumn(name=id, type=String)]")
        statements.inserts.assert().isEmpty()
        // Once the tables are there, the next write prepares again and succeeds.
        statements.create(sink.schema)
        sink.write(listOf(recordOf()))
        statements.inserts.size.assert().isEqualTo(1)
    }

    @Test
    fun `a failed insert should fail the write`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(ddl = DdlMode.NONE)).sinkOf("shop", "order")
        statements.failInsert = IllegalStateException("down")
        runCatching { sink.write(listOf(recordOf())) }.exceptionOrNull()?.message.assert().isEqualTo("down")
    }

    @Test
    fun `the sinks should take each aggregate's schema and options from the Manifest`() {
        val options = ClickHouseSinkOptions(
            database = "analytics",
            deduplicationWindow = 10,
            aggregates = mapOf(ORDER to AggregateSinkOptions(historyTtl = 7.days)),
        )
        val sinks = sinks(options)
        val schema = sinks.schemaOf(ORDER)
        schema.database.assert().isEqualTo("analytics")
        schema.aggregate.historyTtl.assert().isEqualTo(7.days)
        schema.flatColumns.map { it.name }.assert().contains("total", "state_version")
        schema.ddl.first().assert().contains("non_replicated_deduplication_window = 10")
        sinks.options.assert().isSameAs(options)
        sinks.toString().assert().isEqualTo("ClickHouseSinks(analytics)")
        assertThrows<IllegalArgumentException> { sinks.sinkOf("shop", "cart") }
        assertThrows<IllegalArgumentException> { sinks.schemaOf(AggregateType("billing", "order")) }
        ClickHouseSinks.PROCESSOR.assert().isEqualTo("clickhouse-sink")
    }

    @Test
    fun `options should refuse what cannot be kept`() {
        assertThrows<IllegalArgumentException> { AggregateSinkOptions(history = false, historyTtl = 1.days) }
        assertThrows<IllegalArgumentException> { AggregateSinkOptions(historyTtl = 10.milliseconds) }
        assertThrows<IllegalArgumentException> { ClickHouseSinkOptions(database = " ") }
        assertThrows<IllegalArgumentException> { ClickHouseSinkOptions(batchSize = 0) }
        assertThrows<IllegalArgumentException> { ClickHouseSinkOptions(deduplicationWindow = 0) }
        assertThrows<IllegalArgumentException> {
            ClickHouseSink(sinks().schemaOf(ORDER), statements, DdlMode.NONE, batchSize = 0)
        }
        ClickHouseSinkOptions().aggregate(ORDER).assert().isEqualTo(AggregateSinkOptions())
    }
}
