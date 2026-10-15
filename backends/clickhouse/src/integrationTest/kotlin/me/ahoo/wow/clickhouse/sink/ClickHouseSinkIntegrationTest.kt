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

import com.clickhouse.client.api.Client
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.sink.SinkFixtures.ORDER
import me.ahoo.wow.clickhouse.sink.SinkFixtures.recordOf
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.days

/**
 * The ClickHouse Sink on a ClickHouse server (decisions D142, D164): the base table, the views it fills, the
 * deduplication of retried inserts through them, and the typed flat view, with exact decimals (D122).
 */
class ClickHouseSinkIntegrationTest {
    private val database = "wow_sink_${databases.incrementAndGet()}"

    init {
        execute("CREATE DATABASE IF NOT EXISTS $database")
    }

    private fun sinks(options: ClickHouseSinkOptions = ClickHouseSinkOptions(database = database)) =
        ClickHouseSinks(client, SinkFixtures.MANIFEST, options)

    @Test
    fun `a commit should fill the history, the current state and the events, once though retried`(): Unit = runTest {
        val sink = sinks().sinkOf("shop", "order")
        sink.write(listOf(recordOf(version = 1)))
        sink.write(listOf(recordOf(version = 2, state = SinkFixtures.stateOf(2, total = "99.90"))))
        // A retry after a lost acknowledgement, and an old owner's late write.
        sink.write(listOf(recordOf(version = 1)))
        sink.write(listOf(recordOf(version = 2, state = SinkFixtures.stateOf(2, total = "99.90"))))
        sink.write(listOf(recordOf(id = "order-2", version = 1)))

        count("shop_order_state_events").assert().isEqualTo(3)
        count("shop_order_events").assert().isEqualTo(6)
        count("shop_order_state FINAL").assert().isEqualTo(2)
        val current = row("SELECT version, state FROM $database.shop_order_state FINAL WHERE aggregate_id = 'order-1'")
        current.getLong("version").assert().isEqualTo(2)
        current.getString("state").assert().isEqualTo(SinkFixtures.stateOf(2, total = "99.90"))

        val event = row(
            "SELECT id, sequence, event_name, event_revision, payload, causation_id FROM $database.shop_order_events " +
                "WHERE aggregate_id = 'order-1' AND version = 1 ORDER BY sequence LIMIT 1",
        )
        event.getString("id").assert().isEqualTo("shop.order/tenant-1/order-1@1")
        event.getLong("sequence").assert().isEqualTo(1)
        event.getString("event_name").assert().isEqualTo("order_placed")
        event.getLong("event_revision").assert().isEqualTo(1)
        event.getString("payload").assert().isEqualTo("""{"total":12.30,"lines":[{"price":0.10}]}""")
        event.getString("causation_id").assert().isEqualTo("upstream@1")
    }

    @Test
    fun `the flat view should hold the state's top-level scalars as typed columns`(): Unit = runTest {
        val sink = sinks().sinkOf("shop", "order")
        sink.write(listOf(recordOf(version = 1), recordOf(version = 2, state = SinkFixtures.stateOf(2, total = "0.10"))))

        val columns = ClientStatements(client).columns(database, "shop_order_state_flat").associate { it.name to it.type }
        columns.assert().containsEntry("total", "Decimal(38, 2)")
            .containsEntry("price", "Decimal(38, 0)")
            .containsEntry("discount", "Nullable(Decimal(38, 18))")
            .containsEntry("quantity", "Int64")
            .containsEntry("rate", "Float64")
            .containsEntry("paid", "Bool")
            .containsEntry("note", "Nullable(String)")
            .containsEntry("status", "String")
            .containsEntry("state_version", "Int64")
            .containsEntry("commit_time", "DateTime64(3, 'UTC')")
            .doesNotContainKeys("items", "address")
        val flat = row("SELECT * FROM $database.shop_order_state_flat")
        flat.getLong("version").assert().isEqualTo(2)
        flat.getBigDecimal("total").assert().isEqualTo(BigDecimal("0.10"))
        flat.getBigDecimal("price").assert().isEqualTo(BigDecimal("1500"))
        flat.getObject("discount").assert().isNull()
        flat.getLong("quantity").assert().isEqualTo(2)
        flat.getDouble("rate").assert().isEqualTo(0.5)
        flat.getBoolean("paid").assert().isFalse()
        flat.getString("status").assert().isEqualTo("CREATED")
        flat.getString("owner_id").assert().isEqualTo("owner-1")
        // What the view reads, the Sink validates.
        sinks(ClickHouseSinkOptions(database = database, ddl = DdlMode.VALIDATE)).sinkOf("shop", "order").prepare()
    }

    @Test
    fun `a retried batch should be dropped as a whole`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(database = database, batchSize = 2)).sinkOf("shop", "order")
        val records = (1..3).map { recordOf(version = it) }
        sink.write(records)
        sink.write(records)
        count("shop_order_state_events").assert().isEqualTo(3)
        count("shop_order_events").assert().isEqualTo(6)
    }

    @Test
    fun `a replay beyond the deduplication window should be counted once`(): Unit = runTest {
        // Finding A2-10: the tokens only cover the window; a replay in other batches left duplicate history rows.
        val sink = sinks(ClickHouseSinkOptions(database = database, batchSize = 3)).sinkOf("shop", "order")
        val records = (1..3).map { recordOf(version = it) }
        sink.write(records)
        records.forEach { sink.write(listOf(it)) }
        count("shop_order_state_events FINAL").assert().isEqualTo(3)
        count("shop_order_events FINAL").assert().isEqualTo(6)
        client.queryAll("OPTIMIZE TABLE $database.shop_order_state_events FINAL")
        count("shop_order_state_events").assert().isEqualTo(3)
    }

    @Test
    fun `without the history only the current state should be kept`(): Unit = runTest {
        val options = ClickHouseSinkOptions(
            database = database,
            aggregates = mapOf(ORDER to AggregateSinkOptions(history = false)),
        )
        val sink = sinks(options).sinkOf("shop", "order")
        sink.write(listOf(recordOf(version = 1)))
        sink.write(listOf(recordOf(version = 2)))
        sink.write(listOf(recordOf(version = 2)))
        count("shop_order_state").assert().isEqualTo(2)
        count("shop_order_state FINAL").assert().isEqualTo(1)
        row("SELECT version FROM $database.shop_order_state_flat").getLong("version").assert().isEqualTo(2)
        ClientStatements(client).exists(database, "shop_order_state_events").assert().isFalse()
        ClientStatements(client).exists(database, "shop_order_events").assert().isFalse()
    }

    @Test
    fun `the history should be kept for its TTL`(): Unit = runTest {
        val options = ClickHouseSinkOptions(
            database = database,
            aggregates = mapOf(ORDER to AggregateSinkOptions(historyTtl = 30.days)),
        )
        sinks(options).sinkOf("shop", "order").prepare()
        listOf("shop_order_state_events", "shop_order_events").forEach { table ->
            row("SELECT engine_full FROM system.tables WHERE database = '$database' AND name = '$table'")
                .getString("engine_full").assert().contains("TTL toDateTime(commit_time) + toIntervalSecond(2592000)")
        }
    }

    @Test
    fun `validate should refuse tables that are not there`(): Unit = runTest {
        val sink = sinks(ClickHouseSinkOptions(database = database, ddl = DdlMode.VALIDATE)).sinkOf("shop", "order")
        val error = runCatching { sink.write(listOf(recordOf())) }.exceptionOrNull()
        error?.message.assert().contains("shop_order_state_events does not exist")
    }

    private fun count(table: String): Long = row("SELECT count() AS n FROM $database.$table").getLong("n")

    private fun row(sql: String) = client.queryAll(sql).single()

    companion object {
        private val databases = AtomicInteger()

        private val container get() = WowContainers.clickHouse

        val client: Client by lazy {
            Client.Builder()
                .addEndpoint(container.httpUrl)
                .setUsername(container.username)
                .setPassword(container.password)
                .build()
        }

        fun execute(sql: String) {
            client.execute(sql).get(1, TimeUnit.MINUTES).close()
        }
    }
}
