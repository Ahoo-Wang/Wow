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
import com.mongodb.client.MongoClient
import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkSchema
import me.ahoo.wow.spring.boot.transport.HostClickHouseSinkTest
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A table that lacks a column the ClickHouse Sink writes, under `ddl: none`, which checks nothing: the insert is
 * refused, naming the field, rather than taken without it; the Sink's subscription retries it, then parks the
 * aggregate, its reason the server's error code.
 */
class ClickHouseSinkRefusalTest {
    private val run = UUID.randomUUID().toString().replace("-", "").take(SUFFIX)
    private val database = "refusal_$run"
    private val client: Client = Client.Builder()
        .addEndpoint(WowContainers.clickHouse.httpUrl)
        .setUsername(WowContainers.clickHouse.username)
        .setPassword(WowContainers.clickHouse.password)
        .build()

    @Test
    fun `an insert a table has no column for should be refused, retried, then parked, naming the field`() {
        execute("CREATE DATABASE IF NOT EXISTS $database")
        // The Sink's own tables, the history without its headers, which neither of its views reads: the insert alone
        // names them. Made so rather than altered, since an ALTER of a view's source makes ClickHouse analyze the view
        // again, which it refuses for the events view's ARRAY JOIN.
        ClickHouseSinkSchema(AggregateType(BANK, "account"), database, AggregateSinkOptions(), emptyList()).ddl
            .map { it.replace("    `headers` Map(String, String) CODEC(ZSTD(1)),\n", "") }
            .forEach { execute(it) }
        startHost(*properties("none"), sources = SOURCES).use { host ->
            val opened = host.post("/bank/account/open-account", """{"owner":"refusal"}""")
            opened.status.assert().isEqualTo(OK)
            val id = opened.body!!["ref"]["id"].asString()
            // The context's failure ledger, in its log's MongoDB store: the Sink's subscription parked the account.
            val ledger = host.context.getBean(MongoClient::class.java).getDatabase("refusal_$run")
                .getCollection("wow_failure_ledger")
            val sink = Filters.and(Filters.eq("subscription_context", BANK), Filters.eq("subscription", SINK))
            eventually { ledger.find(sink).first() != null }
            val parked = ledger.find(sink).toList().single()
            parked.getString("aggregate_id").assert().isEqualTo(id)
            // The server's error by its code (ClickHouseFailureDescriber), never its message, which names the field
            // and may quote the row: that is in the node's log line of the park.
            parked.getString("reason").assert()
                .contains("com.clickhouse.client.api.ServerException: clickhouse server error code 117")
                .doesNotContain("headers")
            count().assert().isZero()
        }
    }

    private fun count(): Long = if (!tableExists()) {
        0
    } else {
        client.queryAll("SELECT count() AS n FROM $database.bank_account_state_events FINAL").single().getLong("n")
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

    private fun properties(ddl: String): Array<Pair<String, Any>> = arrayOf(
        "wow.log.partitions" to 2,
        "wow.stores.mongo.type" to "mongo",
        "wow.stores.mongo.database" to "refusal_$run",
        "test.mongo.url" to WowContainers.mongoUrl,
        "wow.log.store" to "mongo",
        "wow.clickhouse.sink.enabled" to "true",
        "wow.clickhouse.endpoint" to WowContainers.clickHouse.httpUrl,
        "wow.clickhouse.username" to WowContainers.clickHouse.username,
        "wow.clickhouse.password" to WowContainers.clickHouse.password,
        "wow.clickhouse.database" to database,
        "wow.clickhouse.ddl" to ddl,
        // Inserted at once, retried twice quickly, then parked.
        "wow.clickhouse.sink.insert-interval" to "0s",
        "wow.subscription.retry.max-attempts" to 2,
        "wow.subscription.retry.initial-backoff" to "10ms",
    )

    private companion object {
        val SOURCES = listOf(
            HostTestApp::class.java,
            HostClickHouseSinkTest.PublishingManifest::class.java,
            MongoClientConfiguration::class.java,
        )
        const val OK = 200

        /** The ClickHouse Sink's subscription of the account's streams. */
        const val SINK = "wow.clickhouse-sink.account"
        const val SUFFIX = 8
        const val TIMEOUT_SECONDS = 30L
        const val TIMEOUT_NANOS = 60_000_000_000L
        const val POLL_MILLIS = 250L
    }
}
