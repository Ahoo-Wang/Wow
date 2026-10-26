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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.dataset.AbstractDatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.memory.InMemoryDatasetStorage
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val LEDGER = AggregateType(BANK, "ledger")

/** A dataset file of `bank.ledger` over the in-memory table [table], titled [title]. */
private fun ledgerFile(table: String = "ledger", title: String = "Ledger"): String =
    """
    {
      "title": "$title",
      "identity": "id",
      "scope": { "tenant": "tenant_id" },
      "storage": { "table": "$table" },
      "fields": {
        "id": { "types": ["STRING"], "nullable": false },
        "tenant_id": { "types": ["STRING"], "nullable": false },
        "amount": { "types": ["INTEGER"], "nullable": false }
      }
    }
    """.trimIndent()

private fun writeDataset(directory: Path, name: String, document: String) {
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("$name.json"), document)
}

/** The application's dataset backend: the in-memory reference storage, with the ledger's rows. */
@Configuration(proxyBeanMethods = false)
class MemoryDatasets {
    @Bean
    fun memoryDatasetStorage(): InMemoryDatasetStorage = InMemoryDatasetStorage().also { storage ->
        storage.put(
            "ledger",
            listOf(
                WowJson.mapper.createObjectNode().put("id", "l-1").put("tenant_id", "(0)").put("amount", 3),
                WowJson.mapper.createObjectNode().put("id", "l-2").put("tenant_id", "(0)").put("amount", 4),
                WowJson.mapper.createObjectNode().put("id", "l-3").put("tenant_id", "acme").put("amount", 50),
            ),
        )
    }
}

/** The datasets of a host (work package 6.4c, decision D145): read from files, served under `/wow/dataset`. */
class HostDatasetQueryTest {
    @Test
    fun `the datasets of the config directory should be served by the application's dataset backend`(
        @TempDir config: Path,
    ) {
        writeDataset(config.resolve("wow/dataset"), "bank.ledger", ledgerFile())
        startHost(
            "wow.query.dataset.config-directory" to config.toString(),
            sources = listOf(HostTestApp::class.java, MemoryDatasets::class.java),
        ).use { host ->
            host.context.getBean(QueryDatasets::class.java).all.map { it.name }.assert().containsExactly(LEDGER)
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.datasets[LEDGER].assert().isNotNull()

            val listed = host.get("/wow/dataset")
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["name"].asString() }.assert().containsExactly("bank.ledger")
            listed.body!![0]["path"].asString().assert().isEqualTo("/wow/dataset/bank/ledger/query")

            // The anonymous caller is in the default tenant: the other tenant's row is not read.
            val sum = host.post(
                "/wow/dataset/bank/ledger/query",
                """{"kind":"aggregate","filter":{"op":"MATCH_ALL"},"metrics":[{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"amount"},"alias":"total"}]}""",
            )
            sum.status.assert().isEqualTo(200)
            sum.body!![0]["total"].asLong().assert().isEqualTo(7)
            val described = host.get("/wow/dataset/bank/ledger/query")
            described.status.assert().isEqualTo(200)
            described.headers.eTag.assert().isEqualTo("\"${described.body!!["version"].asString()}\"")

            // A dataset that leaves is no longer served, and its binding is evicted.
            host.context.getBean(QueryDatasets::class.java).remove(LEDGER).assert().isTrue()
            host.post("/wow/dataset/bank/ledger/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
                .status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `the dataset queries should run under their own bulkhead, eight at once by default`(@TempDir config: Path) {
        writeDataset(config.resolve("wow/dataset"), "bank.ledger", ledgerFile())
        startHost(
            "wow.query.dataset.config-directory" to config.toString(),
            sources = listOf(HostTestApp::class.java, MemoryDatasets::class.java),
        ).use { host ->
            val bulkhead = checkNotNull(host.context.getBean(QueryStores::class.java).datasets)
            bulkhead.store.assert().isEqualTo(QueryStores.DATASETS)
            bulkhead.maxConcurrent.assert().isEqualTo(QueryProperties.DEFAULT_DATASET_MAX_CONCURRENT)
            bulkhead.maxQueued.assert().isEqualTo(
                QueryProperties.DEFAULT_DATASET_MAX_CONCURRENT * QueryBulkhead.DEFAULT_QUEUE_FACTOR
            )
            host.context.getBean(QueryStores::class.java).all.assert().contains(bulkhead)
        }
        startHost(
            "wow.query.dataset.config-directory" to config.toString(),
            "wow.query.dataset.max-concurrent" to 1,
            "wow.query.dataset.max-wait" to "0ms",
            "wow.query.dataset.max-queued" to 0,
            sources = listOf(HostTestApp::class.java, MemoryDatasets::class.java),
        ).use { host ->
            val bulkhead = checkNotNull(host.context.getBean(QueryStores::class.java).datasets)
            val count = """{"kind":"count","filter":{"op":"MATCH_ALL"}}"""
            host.post("/wow/dataset/bank/ledger/query", count).status.assert().isEqualTo(200)
            // Its one permit taken: the next dataset query is refused at once.
            runBlocking { bulkhead.acquire() }
            try {
                val busy = host.post("/wow/dataset/bank/ledger/query", count)
                busy.status.assert().isEqualTo(429)
                busy.body!!["code"].asString().assert().isEqualTo("wow.query.busy")
            } finally {
                bulkhead.release()
            }
        }
        startHost(
            "wow.query.dataset.max-concurrent" to 0,
            "wow.query.max-wait" to "1s",
            "wow.query.max-queued" to 3,
        ).use { host ->
            host.context.getBean(QueryStores::class.java).datasets.assert().isNull()
        }
        startHost("wow.query.max-wait" to "1s", "wow.query.max-queued" to 3).use { host ->
            val bulkhead = checkNotNull(host.context.getBean(QueryStores::class.java).datasets)
            bulkhead.maxWait.assert().isEqualTo(1.seconds)
            bulkhead.maxQueued.assert().isEqualTo(3)
        }
    }

    @Test
    fun `without a dataset backend the datasets should not be served`(@TempDir config: Path) {
        writeDataset(config.resolve("wow/dataset"), "bank.ledger", ledgerFile())
        startHost("wow.query.dataset.config-directory" to config.toString()).use { host ->
            host.context.getBean(QueryDatasets::class.java).all.map { it.name }.assert().containsExactly(LEDGER)
            host.context.getBean(DatasetQueryGateways::class.java).assert().isSameAs(DatasetQueryGateways.NONE)
            host.get("/wow/dataset").status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `the files of the config directory should take precedence over the classpath's`(@TempDir root: Path) {
        val classpath = root.resolve("classes")
        writeDataset(classpath.resolve("META-INF/wow/dataset"), "bank.ledger", ledgerFile(title = "Classpath"))
        writeDataset(classpath.resolve("META-INF/wow/dataset"), "bank.audit", ledgerFile(title = "Audit"))
        val config = root.resolve("config")
        writeDataset(config.resolve("wow/dataset"), "bank.ledger", ledgerFile(title = "Config"))
        writeDataset(config.resolve("wow/dataset"), "bank.notes", ledgerFile(title = "Notes"))
        URLClassLoader(arrayOf(classpath.toUri().toURL()), null).use { loader ->
            val datasets = HostDatasets.load(config, loader)
            datasets.all.map { it.name.toString() to it.title }.assert().containsExactly(
                "bank.audit" to "Audit",
                "bank.ledger" to "Config",
                "bank.notes" to "Notes",
            )
        }
        HostDatasets.load(root.resolve("none"), URLClassLoader(emptyArray(), null)).all.assert().isEmpty()
        writeDataset(config.resolve("wow/dataset"), "bank.broken", "{")
        assertThrows<QuerySchemaUnavailableException> { HostDatasets.load(config, URLClassLoader(emptyArray(), null)) }
    }

    @Test
    fun `a dataset that leaves should be evicted from an abstract factory`() {
        var bindings = 0
        val storage = InMemoryDatasetStorage()
        val factory = object : AbstractDatasetQueryBackendFactory() {
            override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
                bindings++
                return storage.create(dataset)
            }
        }
        val ledger = QueryDataset.parse(LEDGER, ledgerFile())
        val datasets = QueryDatasets(listOf(ledger))
        HostDatasets.evictOnRemoval(datasets, factory)
        factory.create(ledger)
        factory.create(ledger)
        bindings.assert().isEqualTo(1)
        datasets.remove(LEDGER)
        factory.create(ledger)
        bindings.assert().isEqualTo(2)
        // Neither a plain factory nor the empty catalog is followed.
        HostDatasets.evictOnRemoval(datasets, DatasetQueryBackendFactory { storage.create(it) })
        HostDatasets.evictOnRemoval(QueryDatasets.NONE, factory)
    }

    @Test
    fun `an application's dataset visibility should be the routes'`(@TempDir config: Path) {
        writeDataset(config.resolve("wow/dataset"), "bank.ledger", ledgerFile())
        startHost(
            "wow.query.dataset.config-directory" to config.toString(),
            sources = listOf(HostTestApp::class.java, MemoryDatasets::class.java, HiddenDatasets::class.java),
        ).use { host ->
            host.get("/wow/dataset").body!!.values().assert().isEmpty()
            host.get("/wow/dataset/bank/ledger/query").status.assert().isEqualTo(404)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class HiddenDatasets {
        @Bean
        fun hiddenDatasets(): DatasetVisibility = DatasetVisibility { _, _ -> false }
    }
}
