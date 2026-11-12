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

import co.elastic.clients.transport.rest5_client.low_level.Request
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import com.mongodb.client.MongoClient
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spring.boot.query.SnapshotQueryBackend
import me.ahoo.wow.spring.boot.store.HostBackends
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import javax.sql.DataSource

/**
 * An aggregate type whose log is on MongoDB or PostgreSQL and whose only snapshot store is Elasticsearch, at the
 * default `refresh`: its state is stored once, on Elasticsearch. Without a state cache every command loads the state
 * from there by id, with the log's tail from the log's store; a command's default wait ends once Elasticsearch holds
 * its snapshot, so the state read next has it; the snapshot queries read the same index; and a new node goes on from
 * what the index holds.
 */
class ElasticsearchOwnSnapshotsTest {
    private val prefix = "own" + UUID.randomUUID().toString().take(SUFFIX)
    private val database = "own_es_$prefix"

    @ParameterizedTest
    @ValueSource(strings = [MONGO, POSTGRES])
    fun `a log beside Elasticsearch as the only snapshot store should load, save and read the state there`(
        log: String
    ) {
        val id = startHost(*properties(log), sources = sources(log)).use { host ->
            host.context.getBean(HostBackends::class.java).snapshotStoreOf(ACCOUNT)!!.name.assert().isEqualTo("es")
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(ACCOUNT).assert().isEqualTo("es")
            val opened = host.post("/bank/account/open-account", """{"owner":"own-es"}""")
            opened.status.assert().isEqualTo(200)
            opened.body!!["hop"]["subscription"].asString().assert().isEqualTo("wow.snapshot")
            val id = opened.body!!["ref"]["id"].asString()
            // 1. Each command's default wait ends once Elasticsearch holds its snapshot: the state read next has it.
            repeat(DEPOSITS) { round ->
                host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200)
                host.balance(id).assert().describedAs("deposit ${round + 1}").isEqualTo(round + 1L)
            }
            // 2. The state is stored once: the log's store holds the log and no snapshot of the aggregate.
            host.snapshotsOnTheLog(log, id).assert().isZero()
            // 3. A command loads its state from Elasticsearch: a balance written there behind Wow's back is the one
            // the next deposit adds to.
            val index = "/${prefix}_wow_snapshot__bank__account"
            val client = host.context.getBean(Rest5Client::class.java)
            // Refreshed first, so that the update finds the document as it is.
            client.performRequest(Request("POST", "$index/_refresh")).statusCode.assert().isEqualTo(200)
            client.performRequest(
                Request("POST", "$index/_update_by_query").apply {
                    setJsonEntity("""{"script":{"source":"ctx._source.state.balance = 1000"}}""")
                },
            ).statusCode.assert().isEqualTo(200)
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200)
            host.balance(id).assert().isEqualTo(1001L)
            // 4. The snapshot queries read the same index.
            host.eventually {
                host.post(
                    "/bank/account/snapshot/query",
                    """{"kind":"list","filter":{"op":"eq","field":"state.owner","value":"own-es"}}""",
                ).body?.values()?.map { it["aggregateId"].asString() } == listOf(id)
            }
            id
        }
        // 5. A new node loads the state from Elasticsearch and commits on top of it.
        startHost(*properties(log), sources = sources(log)).use { host ->
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200)
            host.balance(id).assert().isEqualTo(1002L)
        }
    }

    /** The snapshots of [id] the log's store holds: none, when Elasticsearch is the only snapshot store. */
    private fun Host.snapshotsOnTheLog(log: String, id: String): Long = when (log) {
        MONGO -> context.getBean(MongoClient::class.java).getDatabase(database)
            .getCollection("wow_snapshot__bank__account").countDocuments()
        // The shared database may hold the table from other tests; this aggregate has no row in it.
        else -> context.getBean(DataSource::class.java).connection.use { connection ->
            val table = connection.prepareStatement("SELECT to_regclass('wow_snapshot__bank__account') IS NOT NULL")
                .executeQuery().use { it.next() && it.getBoolean(1) }
            if (!table) {
                0L
            } else {
                connection.prepareStatement("SELECT count(*) FROM wow_snapshot__bank__account WHERE aggregate_id = ?")
                    .apply { setString(1, id) }.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
            }
        }
    }

    private fun Host.balance(id: String): Long {
        val state = get("/bank/account/$id/state")
        state.status.assert().isEqualTo(200)
        return state.body!!["state"]["balance"].asLong()
    }

    private fun Host.eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Not reached in time." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun properties(log: String): Array<Pair<String, Any>> = arrayOf<Pair<String, Any>>(
        "wow.log.partitions" to 2,
        "wow.state-cache.capacity" to 0,
        "wow.stores.$log.type" to log,
        "wow.log.store" to log,
        "wow.stores.es.type" to "elasticsearch",
        "wow.stores.es.index-prefix" to prefix,
        "wow.aggregates.[$BANK.account].snapshot.store" to "es",
    ) + when (log) {
        MONGO -> arrayOf<Pair<String, Any>>(
            "wow.stores.mongo.database" to database,
            "test.mongo.url" to WowContainers.mongoUrl,
        )
        else -> arrayOf<Pair<String, Any>>(
            "spring.datasource.url" to WowContainers.postgres.jdbcUrl,
            "spring.datasource.username" to WowContainers.postgres.username,
            "spring.datasource.password" to WowContainers.postgres.password,
            "spring.datasource.hikari.maximum-pool-size" to 8,
        )
    }

    private fun sources(log: String): List<Class<*>> = listOfNotNull(
        HostTestApp::class.java,
        ElasticsearchClientConfiguration::class.java,
        MongoClientConfiguration::class.java.takeIf { log == MONGO },
    )

    /** The application's Elasticsearch client, over the shared container. */
    @Configuration(proxyBeanMethods = false)
    class ElasticsearchClientConfiguration {
        @Bean(destroyMethod = "close")
        fun elasticsearchClient(): Rest5Client =
            Rest5Client.builder(HttpHost.create("http://" + WowContainers.elasticsearch.httpHostAddress)).build()
    }

    private companion object {
        val ACCOUNT = AggregateType(BANK, "account")
        const val MONGO = "mongo"
        const val POSTGRES = "postgres"
        const val DEPOSITS = 10
        const val SUFFIX = 8
        const val TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 200L
    }
}
