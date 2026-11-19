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

package me.ahoo.wow.spring.boot

import com.clickhouse.client.api.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkProvider
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.sink.SinkBatches
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The ClickHouse Sink's inserts and the parts they make for one fixed load, as its subscription's writes batch and pace
 * them ([SinkBatches]), counted from ClickHouse's own logs (`system.query_log`, `system.part_log`): unpaced, a fast
 * insert is followed at once by the next, carrying what arrived meanwhile; paced, one insert per interval carries
 * everything that arrived (#6494).
 */
class ClickHouseSinkPacingTest {
    /** What a Sink paced at its interval did under the load: its inserts' starts (µs), new parts, how long it took. */
    private class Counted(val inserts: List<Long>, val parts: Long, val elapsed: Duration)

    private fun load(interval: Duration): Counted {
        val database = "wow_pacing_${databases.incrementAndGet()}"
        execute("CREATE DATABASE IF NOT EXISTS $database")
        val config = SinkConfig.of(
            "clickhouse",
            "clickhouse",
            mapOf("database" to database, "sink.insert-interval" to interval.toIsoString()),
            StoreResources.of(mapOf("manifest" to MANIFEST, "client" to client)),
        )
        val sinks = ClickHouseSinkProvider().open(config)
        val batches = SinkBatches(sinks.sinkOf(ORDER.context, ORDER.name))
        // AGGREGATES aggregates, each committing PERIOD after its last commit was written, COMMITS times: as a
        // subscription's lane does, an aggregate's next commit goes once its previous one's write returned.
        val elapsed = measureTime {
            runBlocking(Dispatchers.Default) {
                repeat(AGGREGATES) { aggregate ->
                    launch {
                        delay(PERIOD * aggregate / AGGREGATES)
                        repeat(COMMITS) { commit ->
                            batches.write(listOf(recordOf("order-$aggregate", commit + 1)))
                            delay(PERIOD)
                        }
                    }
                }
            }
        }
        sinks.close()
        execute("SYSTEM FLUSH LOGS")
        val inserts = client.queryAll(
            "SELECT toUnixTimestamp64Micro(query_start_time_microseconds) AS start FROM system.query_log " +
                "WHERE type = 'QueryFinish' AND query_kind = 'Insert' " +
                "AND has(tables, '$database.shop_order_state_events') ORDER BY start",
        ).map { it.getLong("start") }
        // The parts of the Sink's three tables: its catalog row adds one to wow_catalog besides.
        val parts = count(
            "SELECT count() AS n FROM system.part_log WHERE database = '$database' AND event_type = 'NewPart' " +
                "AND table IN ('shop_order_state_events', 'shop_order_state', 'shop_order_events')",
        )
        count("SELECT count() AS n FROM $database.shop_order_state_events FINAL")
            .assert().isEqualTo((AGGREGATES * COMMITS).toLong())
        return Counted(inserts, parts, elapsed)
    }

    @Test
    fun `paced, the same load should take one insert per interval instead of one per insert's return`() {
        val unpaced = load(Duration.ZERO)
        val paced = load(1.seconds)
        println(
            "#6494 load of ${AGGREGATES * COMMITS} commits: unpaced ${unpaced.inserts.size} inserts, " +
                "${unpaced.parts} new parts in ${unpaced.elapsed}; paced at 1s ${paced.inserts.size} inserts, " +
                "${paced.parts} new parts in ${paced.elapsed}",
        )
        // Paced, two inserts start a second apart at least (by the server's clock: a client's start is within the HTTP
        // round trip of it), so the load had at most one a second of its length, and one at its start.
        paced.inserts.zipWithNext { earlier, later -> later - earlier }
            .forEach { it.assert().isGreaterThanOrEqualTo(SPACING_MICROS) }
        paced.inserts.size.toLong().assert().isLessThanOrEqualTo(paced.elapsed.inWholeSeconds + 1)
        // Each insert makes a part in each of the three tables (the base table and the two views'), one month each.
        paced.parts.assert().isEqualTo(paced.inserts.size * 3L)
        unpaced.parts.assert().isEqualTo(unpaced.inserts.size * 3L)
        unpaced.inserts.size.assert().isGreaterThan(paced.inserts.size * 10)
    }

    private fun count(sql: String): Long = client.queryAll(sql).single().getLong("n")

    private fun execute(sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    private companion object {
        val databases = AtomicInteger()
        val ORDER = AggregateType("shop", "order")
        const val AGGREGATES = 64
        const val COMMITS = 10
        val PERIOD = 0.5.seconds

        /** A second, less what the HTTP round trip before an insert's start may take off the server's spacing. */
        const val SPACING_MICROS = 900_000L

        val MANIFEST = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = ORDER.context,
                    aggregates = listOf(AggregateManifest(ORDER.name, state = StateManifest("#/\$defs/OrderState"))),
                ),
            ),
            defs = mapOf(
                "OrderState" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("quantity" to mapOf("type" to "integer")),
                ),
            ),
        )

        val client: Client by lazy {
            val container = WowContainers.clickHouse
            Client.Builder()
                .addEndpoint(container.httpUrl)
                .setUsername(container.username)
                .setPassword(container.password)
                .build()
        }

        fun recordOf(id: String, version: Int): StateEventRecord = StateEventRecord(
            ref = ORDER.ref("tenant-1", id),
            version = version,
            commandId = "command-$id-$version",
            requestId = "request-$id-$version",
            commandType = "shop.order.PlaceOrder",
            commitTime = 1_760_000_000_000L + version,
            events = listOf(StateEventPayload("order_placed", 1, WowJson.mapper.readTree("""{"quantity":$version}"""))),
            state = PublishedState(version, "fingerprint-1", WowJson.mapper.readTree("""{"quantity":$version}""")),
            scope = Scope("owner-1", null),
            deleted = false,
            access = mapOf("owner" to "owner-1", "space" to null),
        )
    }
}
