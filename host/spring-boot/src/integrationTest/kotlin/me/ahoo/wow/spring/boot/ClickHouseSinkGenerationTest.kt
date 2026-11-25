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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spring.boot.transport.HostClickHouseSinkTest
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The warehouse is filled again from the log by a new generation of the ClickHouse Sink (#7097): once its tables are
 * dropped, a host started with `wow.clickhouse.sink.generation` raised runs the Sink as
 * `wow.clickhouse-sink-g<n>.<aggregate>`, new subscriptions, which write every commit from the beginning of the log.
 * Raised without dropping the tables, the next generation writes every commit again, and the tables'
 * `ReplacingMergeTree` engines, keyed by a commit's `ref@version`, keep each once for a read with `FINAL`.
 */
class ClickHouseSinkGenerationTest {
    private val run = UUID.randomUUID().toString().replace("-", "").take(SUFFIX)
    private val database = "generation_$run"
    private val client: Client = Client.Builder()
        .addEndpoint(WowContainers.clickHouse.httpUrl)
        .setUsername(WowContainers.clickHouse.username)
        .setPassword(WowContainers.clickHouse.password)
        .build()

    @Test
    fun `a warehouse dropped and its sink's generation raised should be filled again from the log`() {
        execute("CREATE DATABASE IF NOT EXISTS $database")
        val id = startHost(*properties(1), sources = SOURCES).use { host ->
            val opened = host.post("/bank/account/open-account", """{"owner":"generation"}""")
            opened.status.assert().isEqualTo(OK)
            val id = opened.body!!["ref"]["id"].asString()
            repeat(DEPOSITS) {
                host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            }
            eventually { versions() == (1..DEPOSITS + 1).toList() }
            id
        }
        // The page's way to empty the warehouse: drop the Sink's tables, here with their database.
        execute("DROP DATABASE $database")
        execute("CREATE DATABASE $database")
        startHost(*properties(2), sources = SOURCES).use { host ->
            host.node.context(BANK).views.readers.keys.assert().contains("wow.clickhouse-sink-g2.account")
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            // Its latest commit written: the Sink, started from the beginning, wrote every earlier one first.
            eventually { versions().contains(DEPOSITS + 2) }
            versions().assert().describedAs("the versions the warehouse holds after a new generation")
                .isEqualTo((1..DEPOSITS + 2).toList())
        }
        // Raised again without dropping the tables: every commit is written once more, and kept once.
        startHost(*properties(3), sources = SOURCES).use { host ->
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(OK)
            eventually { versions().contains(DEPOSITS + 3) }
            versions().assert().describedAs("the versions a read with FINAL counts, the tables not dropped")
                .isEqualTo((1..DEPOSITS + 3).toList())
        }
    }

    /** Every commit's version the Sink's base table holds, in a read with `FINAL`, each as often as it reads it. */
    private fun versions(): List<Int> = if (!tableExists()) {
        emptyList()
    } else {
        client.queryAll("SELECT version FROM $database.bank_account_state_events FINAL ORDER BY version")
            .map { it.getInteger("version") }
    }

    private fun tableExists(): Boolean = client.queryAll(
        "SELECT count() AS n FROM system.tables WHERE database = '$database' AND name = 'bank_account_state_events'",
    ).single().getLong("n") > 0

    private fun execute(sql: String) {
        client.execute(sql).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).close()
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun properties(generation: Int): Array<Pair<String, Any>> = arrayOf(
        "wow.log.partitions" to 2,
        "wow.stores.mongo.type" to "mongo",
        "wow.stores.mongo.database" to "generation_$run",
        "test.mongo.url" to WowContainers.mongoUrl,
        "wow.log.store" to "mongo",
        "wow.clickhouse.sink.enabled" to "true",
        "wow.clickhouse.sink.generation" to generation,
        "wow.clickhouse.endpoint" to WowContainers.clickHouse.httpUrl,
        "wow.clickhouse.username" to WowContainers.clickHouse.username,
        "wow.clickhouse.password" to WowContainers.clickHouse.password,
        "wow.clickhouse.database" to database,
        // Inserted at once: no pacing between a test's commits.
        "wow.clickhouse.sink.insert-interval" to "0s",
    )

    private companion object {
        val SOURCES = listOf(
            HostTestApp::class.java,
            HostClickHouseSinkTest.PublishingManifest::class.java,
            MongoClientConfiguration::class.java,
        )
        const val OK = 200
        const val DEPOSITS = 2
        const val SUFFIX = 8
        const val TIMEOUT_SECONDS = 30L
        const val TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
