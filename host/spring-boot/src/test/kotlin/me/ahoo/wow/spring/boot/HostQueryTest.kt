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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import kotlin.time.toKotlinDuration

private val ACCOUNT = AggregateType(BANK, "account")

/** A snapshot store that also stores each snapshot it writes into the in-memory reference query backend. */
class QueriedSnapshots(private val store: SnapshotStore, private val queries: InMemoryQueryStorage) :
    SnapshotStore by store {
    override suspend fun save(snapshot: SnapshotRecord): Boolean =
        store.save(snapshot).also { written -> if (written) queries.save(snapshot) }
}

/**
 * The memory backend with its snapshots queried: the application's [me.ahoo.wow.query.QueryBackendFactory], the
 * in-memory reference backend, fed by each context's snapshot store.
 */
@Configuration(proxyBeanMethods = false)
class MemorySnapshotQueries {
    @Bean
    fun memoryQueryStorage(): InMemoryQueryStorage = InMemoryQueryStorage()

    @Bean
    fun memoryBackends(properties: WowProperties, queries: InMemoryQueryStorage): HostBackends =
        HostBackends.memory(properties.log.partitions) { QueriedSnapshots(InMemorySnapshotStore(), queries) }
}

/** The query plane of a host on the memory backend (work package 6.2c). */
class HostQueryTest {
    private fun Host.openAccount(owner: String): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""")
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    @Test
    fun `on the memory backend the events should be queried, the snapshots not served`() {
        startHost().use { host ->
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.events(ACCOUNT).assert().isNotNull()
            queries.snapshots(ACCOUNT).assert().isNull()
            host.context.getBean(SnapshotQueryBackend::class.java).assert().isSameAs(SnapshotQueryBackend.NONE)
            val id = host.openAccount("mona")
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)

            val events = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"ID","value":"$id"}}"""
            )
            events.status.assert().isEqualTo(200)
            events.body!!.values().map { it["version"].asInt() }.assert().containsExactly(1, 2)
            events.body!![1]["events"][0]["body"]["amount"].asLong().assert().isEqualTo(3)
            host.get("/bank/account/events/query").status.assert().isEqualTo(200)

            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
                .status.assert().isEqualTo(404)
            host.context.getBean(QueryIndexesLifecycle::class.java).reports.assert().isEmpty()
        }
    }

    @Test
    fun `an application's query backend should serve the snapshots under the configured budget`() {
        startHost(
            "wow.query.http.max-list-size" to 7,
            "wow.query.http.default-list-size" to 2,
            "wow.query.http.count-limit" to 50,
            "wow.query.http.timeout" to "5s",
            "wow.query.abac.enabled" to true,
            "wow.query.index.ddl" to "validate",
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java),
        ).use { host ->
            listOf("mona", "lisa", "nina").forEach { host.openAccount(it) }
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.snapshots(ACCOUNT).assert().isNotNull()
            queries.defaultListSize.assert().isEqualTo(2)

            val listed = host.post(
                "/bank/account/snapshot/query",
                """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.owner","direction":"ASC"}]}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("lisa", "mona")
            val counted = host.post("/bank/account/snapshot/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
            counted.body!!["count"].asLong().assert().isEqualTo(3)
            counted.body!!["exact"].asBoolean().assert().isTrue()
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":8}""")
                .status.assert().isEqualTo(400)

            val described = host.get("/bank/account/snapshot/query")
            described.status.assert().isEqualTo(200)
            described.body!!["limits"]["maxListSize"].asInt().assert().isEqualTo(7)
            described.headers.eTag.assert().isEqualTo("\"${described.body!!["version"].asString()}\"")
            host.get("/bank/account/snapshot/query", mapOf("If-None-Match" to described.headers.eTag!!))
                .status.assert().isEqualTo(304)

            val policy = host.context.getBean(QueryEntryPolicy::class.java)
            policy.http.maxListSize.assert().isEqualTo(7)
            policy.http.countLimit.assert().isEqualTo(50)
            policy.http.timeout.assert().isEqualTo(Duration.ofSeconds(5).toKotlinDuration())
            host.context.getBean(QueryProperties::class.java).index.ddl.assert().isEqualTo(QueryIndexDdl.VALIDATE)
            // The application's backend manages its own indexes.
            host.context.getBean(QueryIndexesLifecycle::class.java).reports.assert().isEmpty()
            host.context.getBean(QuerySchemaCatalog::class.java).let { catalog ->
                // The snapshot model and, on the same engine, the events model (decision D235).
                val versions = runBlocking { catalog.versions() }
                versions.map { it.model }.assert().containsExactly(QueryModel.SNAPSHOT, QueryModel.EVENTS)
                versions.forEach { it.error.assert().isNull() }
            }
            // In process, the same gateway answers without the HTTP budget.
            runBlocking {
                queries.events(ACCOUNT)!!.list(
                    ListQuery(AggregateIdFilter(listed.body!![0]["aggregateId"].asString())),
                    QueryCaller.IN_PROCESS.copy(caller = Caller()),
                ).toList()
            }.assert().hasSize(1)
        }
    }

    @Test
    fun `a tag-based query policy should follow the tags the state derives`() {
        startHost(
            "wow.query.abac.enabled" to true,
            "wow.query.abac.match-missing-tag-key" to false,
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val snapshots = host.context.getBean(HttpQueries::class.java).snapshots(ACCOUNT)!!
            fun visibleTo(tier: String): Boolean = runBlocking {
                val caller = QueryCaller(Caller(attributes = mapOf("abac.$TIER" to listOf(tier))))
                snapshots.list(ListQuery(AggregateIdFilter(id)), caller).toList().isNotEmpty()
            }
            eventually { visibleTo("standard") }
            visibleTo("gold").assert().isFalse()

            host.post("/bank/account/$id/deposit", """{"amount":$GOLD_BALANCE}""").status.assert().isEqualTo(200)

            // The deposit flips the derived tier: the account leaves the standard callers' view for the gold ones'.
            eventually { visibleTo("gold") }
            visibleTo("standard").assert().isFalse()
            host.context.getBean(InMemoryQueryStorage::class.java).records(ACCOUNT).single()["tags"][TIER]
                .values().map { it.asString() }.assert().containsExactly("gold")
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(20)
        }
    }

    @Test
    fun `wow-query-memory-enabled should serve the snapshots of the memory backend`() {
        startHost("wow.query.memory.enabled" to true).use { host ->
            listOf("mona", "lisa").forEach { host.openAccount(it) }
            host.context.getBean(HttpQueries::class.java).snapshots(ACCOUNT).assert().isNotNull()

            val listed = host.post(
                "/bank/account/snapshot/query",
                """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.owner","direction":"ASC"}]}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("lisa", "mona")
        }
    }

    @Test
    fun `a queried memory store should write each snapshot written, saved or rebuilt, into its query storage`() {
        runBlocking {
            val queries = InMemoryQueryStorage()
            val store = MemoryStore("memory", queries = queries).snapshots(BANK)
            val ref = AggregateRef(ACCOUNT, "t-1", "a-1")
            fun record(version: Int, owner: String) = SnapshotRecord(
                ref = ref,
                version = version,
                schemaFingerprint = "f",
                state = """{"owner":"$owner"}""",
                firstEventTime = 1,
                lastEventTime = 1,
            )
            fun owner() = queries.records(ACCOUNT).single()["state"]["owner"].asString()

            store.save(record(2, "mona")).assert().isTrue()
            store.save(record(1, "older")).assert().isFalse()
            owner().assert().isEqualTo("mona")
            store.rewrite(record(2, "rebuilt")).assert().isTrue()
            owner().assert().isEqualTo("rebuilt")
            store.rewrite(record(1, "older")).assert().isFalse()
            owner().assert().isEqualTo("rebuilt")
            MemoryStore("plain").snapshots(BANK).assert().isInstanceOf(InMemorySnapshotStore::class.java)
        }
    }

    @Test
    fun `the query plane should be off when wow-query-enabled is false`() {
        startHost("wow.query.enabled" to false).use { host ->
            host.context.getBeanNamesForType(HttpQueries::class.java).assert().isEmpty()
            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"a"}}""")
                .status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `a fixed tenant should restrict the queries of its aggregate type`() {
        startHost("wow.aggregates.[bank.account].tenant" to "platform").use { host ->
            val tenants = host.context.getBean(QueryTenantPolicy::class.java)
            tenants.tenantOf(ACCOUNT, QueryCaller(Caller(tenantId = "acme"))).assert().isEqualTo("platform")
            tenants.tenantOf(
                AggregateType(BANK, "other"),
                QueryCaller(Caller(tenantId = "acme"))
            ).assert().isEqualTo("acme")
        }
    }

    @Test
    fun `the host tenant policy should fix a tenant or keep the caller's`() {
        val tenants = HostQueryTenants(TenantPolicy.fixed(mapOf(ACCOUNT to "platform")))
        tenants.tenantOf(ACCOUNT, QueryCaller()).assert().isEqualTo("platform")
        tenants.tenantOf(
            AggregateType(BANK, "other"),
            QueryCaller(Caller(tenantId = "acme"))
        ).assert().isEqualTo("acme")
        tenants.tenantOf(AggregateType(BANK, "other"), QueryCaller()).assert().isNull()
    }

    @Test
    fun `the properties should default to the query engine's defaults`() {
        val properties = QueryProperties()
        val policy = properties.entryPolicy()
        policy.http.maxListSize.assert().isEqualTo(QueryBudget.HTTP_DEFAULT.maxListSize)
        policy.http.countLimit.assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        policy.http.allowExactCount.assert().isFalse()
        policy.inProcessTimeout.assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        policy.requireExplicitEntry.assert().isFalse()
        properties.abac.options().assert().isEqualTo(AbacQueryOptions.DEFAULT)
        properties.sensitivity.policy().displayComparable.assert().isTrue()
        properties.index.ddl.assert().isEqualTo(QueryIndexDdl.AUTO)
        properties.memory.enabled.assert().isFalse()
        properties.http.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        properties.inProcessTimeout = Duration.ZERO
        properties.entryPolicy().inProcessTimeout.assert().isNull()
    }

    @Test
    fun `a backend without index management should report nothing`() {
        SnapshotQueryBackend.NONE.managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage()).managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage())
            .manageIndexes(
                ACCOUNT,
                runBlocking { catalogSchema() },
                emptyList()
            ).assert().isEqualTo(QueryIndexReport.NONE)
    }

    @Test
    fun `the Manifest's state schemas should declare the aggregates that carry one, no other`() {
        fun manifest(defs: Map<String, Any?>) = Manifest(
            contexts = listOf(
                ContextManifest(
                    BANK,
                    aggregates = listOf(AggregateManifest("account", state = StateManifest("#/\$defs/Account"))),
                ),
            ),
            defs = defs,
        )
        val context = QuerySchemaContext(ACCOUNT, QueryModel.SNAPSHOT)
        val declared = ManifestStateSchemas(
            manifest(
                mapOf(
                    "Account" to mapOf("type" to "object", "properties" to mapOf("owner" to mapOf("type" to "string")))
                )
            ),
        )
        declared.declares(ACCOUNT).assert().isTrue()
        runBlocking { declared.load(context) }.assert().hasSize(1)
        val undeclared = ManifestStateSchemas(manifest(emptyMap()))
        undeclared.declares(ACCOUNT).assert().isFalse()
        undeclared.declares(AggregateType(BANK, "other")).assert().isFalse()
        runBlocking { undeclared.load(context) }.assert().isEmpty()
        runBlocking { undeclared.load(context) }.assert().isEmpty()
    }

    private suspend fun catalogSchema() =
        QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage())).schema(ACCOUNT, QueryModel.SNAPSHOT)
}
