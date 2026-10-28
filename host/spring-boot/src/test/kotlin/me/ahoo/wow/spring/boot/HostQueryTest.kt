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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.engine.ManifestStateSchemas
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.state.SnapshotPolicyGateway
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

private val ACCOUNT = AggregateType(BANK, "account")

/** A snapshot store that also stores each snapshot it writes into the in-memory reference query backend. */
class QueriedSnapshots(private val store: SnapshotStore, private val queries: InMemoryQueryStorage) :
    SnapshotStore by store {
    override suspend fun save(snapshot: SnapshotRecord): Boolean =
        store.save(snapshot).also { written -> if (written) queries.save(snapshot) }

    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
        store.saveAll(snapshots).also { outcomes ->
            outcomes.forEachIndexed { index, outcome -> if (outcome.written) queries.save(snapshots[index]) }
        }
}

/**
 * The memory backend with its snapshots queried: the application's [me.ahoo.wow.query.backend.QueryBackendFactory], the
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

/**
 * The application's own `events` backend, without a snapshot query backend: each context's log scanned in memory, as
 * an application with a [me.ahoo.wow.runtime.node.BackendFactory] of its own would give one.
 */
@Configuration(proxyBeanMethods = false)
class ScannedEvents {
    @Bean
    fun applicationEvents(node: ServiceNode): EventQueryBackend {
        val storages = ConcurrentHashMap<String, InMemoryEventQueryStorage>()
        return EventQueryBackend { type ->
            node.contexts[type.context]?.let { context ->
                storages.computeIfAbsent(type.context) { InMemoryEventQueryStorage.of(context.log) }.create(type)
            }
        }
    }
}

/** An application query policy that hides every aggregate from `mallory`. */
@Configuration(proxyBeanMethods = false)
class HideFromMallory {
    @Bean
    fun malloryPolicy(): QueryPolicy =
        QueryPolicy { caller, _ -> if (caller.subject == "mallory") MatchNoneFilter else MatchAllFilter }
}

/** The application's own [me.ahoo.wow.runtime.node.BackendFactory]: in memory, opened by no layout of the host's. */
@Configuration(proxyBeanMethods = false)
class ApplicationBackends {
    @Bean
    fun ownBackends(properties: WowProperties): HostBackends =
        HostBackends.of(HostBackends.memory(properties.log.partitions).factory)
}

/** The query plane of a host on the memory backend (work package 6.2c). */
class HostQueryTest {
    private fun Host.openAccount(owner: String): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""")
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    @Test
    fun `on the memory backend neither the snapshots nor the events should be served when turned off`() {
        startHost("wow.query.memory.enabled" to false).use { host ->
            val queries = host.context.getBean(HttpQueries::class.java)
            // The in-memory events scan follows wow.query.memory.enabled, as the snapshots do (review R3.4-1).
            queries.events(ACCOUNT).assert().isNull()
            queries.snapshots(ACCOUNT).assert().isNull()
            host.context.getBean(SnapshotQueryBackend::class.java).assert().isSameAs(SnapshotQueryBackend.NONE)
            host.context.getBean(EventQueryBackend::class.java).binding(ACCOUNT).assert().isNull()
            val id = host.openAccount("mona")

            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}""")
                .status.assert().isEqualTo(404)
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
                .status.assert().isEqualTo(404)
            host.context.getBean(QueryIndexesLifecycle::class.java).reports.assert().isEmpty()
        }
    }

    @Test
    fun `without a snapshot query gateway the events model should stay under ABAC`() {
        startHost(
            "wow.query.memory.enabled" to false,
            "wow.query.abac.enabled" to true,
            "wow.query.abac.match-missing-tag-key" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.snapshots(ACCOUNT).assert().isNull()
            queries.visibility(ACCOUNT).assert().isInstanceOf(SnapshotPolicyGateway::class.java)
            val events = queries.events(ACCOUNT)!!
            fun eventsOf(tier: String) = runBlocking {
                val caller = QueryCaller(Caller(attributes = mapOf("abac.$TIER" to listOf(tier))))
                events.list(ListQuery(AggregateIdFilter(id), limit = 10), caller).toList()
            }
            // Review R3.3-3: a caller whose tags the account's do not match reads none of its events.
            eventsOf("gold").assert().isEmpty()
            eventsOf("standard").map { it["version"].asInt() }.assert().containsExactly(1)
        }
    }

    @Test
    fun `an application's own backend factory should get no events scan, though memory queries are on`() {
        startHost(sources = listOf(HostTestApp::class.java, ApplicationBackends::class.java)).use { host ->
            host.context.getBean(HostBackends::class.java).layout.assert().isNull()
            host.context.getBean(EventQueryBackend::class.java).binding(ACCOUNT).assert().isNull()
            host.context.getBean(HttpQueries::class.java).events(ACCOUNT).assert().isNull()
            val id = host.openAccount("mona")
            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}""")
                .status.assert().isEqualTo(404)
        }
    }

    @Test
    fun `the memory events scan should serve only the node's own contexts`() {
        startHost().use { host ->
            val events = host.context.getBean(EventQueryBackend::class.java)
            events.binding(ACCOUNT).assert().isNotNull()
            events.binding(AggregateType("elsewhere", "thing")).assert().isNull()
        }
    }

    @Test
    fun `an application's query policy should hide events without a snapshot query gateway, ABAC off`() {
        startHost(
            "wow.query.memory.enabled" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java, HideFromMallory::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.visibility(ACCOUNT).assert().isInstanceOf(SnapshotPolicyGateway::class.java)
            val events = queries.events(ACCOUNT)!!
            fun eventsOf(subject: String, aggregate: String = id) = runBlocking {
                events.list(
                    ListQuery(AggregateIdFilter(aggregate), limit = 10),
                    QueryCaller(Caller(subject = subject))
                ).toList()
            }
            eventsOf("mallory").assert().isEmpty()
            eventsOf("alice").map { it["version"].asInt() }.assert().containsExactly(1)
            // An aggregate with no committed state has no streams.
            eventsOf("alice", "no-such-account").assert().isEmpty()
        }
    }

    @Test
    fun `without ABAC or a query policy no snapshot policy gateway is built`() {
        startHost(
            "wow.query.memory.enabled" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.visibility(ACCOUNT).assert().isNull()
            val events = host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
            events.status.assert().isEqualTo(200)
            events.body!!.values().map { it["aggregateId"].asString() }.assert().containsExactly(id)
        }
    }

    @Test
    fun `an application's query backend should serve the snapshots under the configured budget`() {
        startHost(
            "wow.query.budgets.http.max-list-size" to 7,
            "wow.query.budgets.http.default-list-size" to 2,
            "wow.query.budgets.http.count-limit" to 50,
            "wow.query.budgets.http.timeout" to "5s",
            "wow.query.abac.enabled" to true,
            // The events of the log in memory are scanned (review R3.4-1).
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
                    ListQuery(AggregateIdFilter(listed.body!![0]["aggregateId"].asString()), limit = 10),
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
                snapshots.list(ListQuery(AggregateIdFilter(id), limit = 10), caller).toList().isNotEmpty()
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
    fun `the memory backend should serve its snapshots and its events by default`() {
        startHost().use { host ->
            val id = listOf("mona", "lisa").map { host.openAccount(it) }.first()
            host.context.getBean(HttpQueries::class.java).snapshots(ACCOUNT).assert().isNotNull()

            val listed = host.post(
                "/bank/account/snapshot/query",
                """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.owner","direction":"ASC"}]}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("lisa", "mona")

            // Its log is scanned in memory for the events model (review R3.4-1).
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
            val events = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"ID","value":"$id"}}"""
            )
            events.status.assert().isEqualTo(200)
            events.body!!.values().map { it["version"].asInt() }.assert().containsExactly(1, 2)
            events.body!![1]["events"][0]["body"]["amount"].asLong().assert().isEqualTo(3)
            host.get("/bank/account/events/query").status.assert().isEqualTo(200)
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
            store.rebuilds!!.rewrite(record(2, "rebuilt")).assert().isTrue()
            owner().assert().isEqualTo("rebuilt")
            store.rebuilds!!.rewrite(record(1, "older")).assert().isFalse()
            owner().assert().isEqualTo("rebuilt")
            MemoryStore("plain").snapshots(BANK).assert().isInstanceOf(InMemorySnapshotStore::class.java)
        }
    }

    @Test
    fun `the node's QueryApi should read the snapshots, events and states of its models as a caller`() {
        startHost("wow.query.budgets.in-process.max-list-size" to 5).use { host ->
            val id = host.openAccount("mona")
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
            val api = host.context.getBean(QueryApi::class.java)
            val accounts = api.of(Caller.ANONYMOUS).snapshots<Account>()
            accounts.type.assert().isEqualTo(ACCOUNT)
            eventually {
                runBlocking { accounts.single(SingleQuery(AggregateIdFilter(id)))?.state?.balance == 3L }
            }
            runBlocking {
                accounts.state(id)!!.state.owner.assert().isEqualTo("mona")
                api.of(Caller.of(tenantId = "other")).snapshots<Account>().state(id).assert().isNull()
                val events = api.trusted().events(ACCOUNT).list(ListQuery(AggregateIdFilter(id))).toList()
                events.map { it.events.single().body }.assert().containsExactly(AccountOpened("mona"), Deposited(3))
                // wow.query.budgets.in-process.*: the in-process budget, trusted code's too.
                runCatching { api.trusted().snapshots(ACCOUNT).list(ListQuery(MatchAllFilter, limit = 6)).toList() }
                    .exceptionOrNull().assert().isInstanceOf(QueryRequestException::class.java)
                api.trusted().unbounded().snapshots(ACCOUNT).list(ListQuery(MatchAllFilter, limit = 6)).toList()
                    .assert().hasSize(1)
            }
        }
    }

    @Test
    fun `the handlers' queries should read the whole tenant of a commit through the node`() {
        startHost().use { host ->
            val id = host.openAccount("mona")
            val node = host.context.getBean(ServiceNode::class.java)
            // The node resolves the query plane's ContextQueries when a handler first reads (decision D259).
            val queries = node.contexts.getValue(BANK).queries.of(Caller.DEFAULT_TENANT)
            queries.tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
            eventually { runBlocking { queries.snapshots<Account>().single(SingleQuery(AggregateIdFilter(id))) != null } }
            runBlocking {
                queries.snapshots<Account>().state(id)!!.state.owner.assert().isEqualTo("mona")
                node.contexts.getValue(BANK).queries.of("other").snapshots<Account>()
                    .single(SingleQuery(AggregateIdFilter(id))).assert().isNull()
                queries.trusted().snapshots(ACCOUNT).count(CountQuery(MatchAllFilter)).count.assert().isEqualTo(1)
            }
        }
    }

    @Test
    fun `an application's own QueryApi should serve the handlers as a caller of the commit's tenant`() {
        val trusted = mockk<TrustedQueries>()
        val api = mockk<QueryApi> { every { trusted() } returns trusted }
        QueryAutoConfiguration.NodeQueries().wowContextQueries(api).of("acme").trusted().assert().isSameAs(trusted)
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
            // The engine's tenants are the node's, which commands and state reads act in (decision D116).
            val tenants = host.context.getBean(QueryEngine::class.java).tenants
            tenants.assert().isSameAs(host.context.getBean(ServiceNode::class.java).gateway.tenants)
            tenants.fixedTenant(ACCOUNT).assert().isEqualTo("platform")
            tenants.fixedTenant(AggregateType(BANK, "other")).assert().isNull()
        }
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
        // Unset: on wherever the store is in memory.
        properties.memory.enabled.assert().isNull()
        properties.budgets.http.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        policy.inProcess!!.maxListSize.assert().isEqualTo(QueryBudget.IN_PROCESS_DEFAULT.maxListSize)
        policy.inProcess!!.label.assert().isEqualTo(QueryBudget.IN_PROCESS_LABEL)
        properties.budgets.inProcess.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        properties.unboundedTimeout = Duration.ZERO
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
    fun `an index that validate finds missing or invalid should refuse the start, naming the store's ddl`() {
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        fun backend(report: QueryIndexReport) = SnapshotQueryBackend(
            SnapshotQueryBackend(InMemoryQueryStorage()).factory,
            indexes = { _, _, _ -> report },
            store = { "reads" },
        )
        val validated = QueryIndexReport(missing = listOf("ix_a"), invalid = listOf("ix_b"))
        val refused = assertThrows<WowConfigurationException> {
            QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(validated), catalog) { "wow.stores.$it.ddl" }
                .start()
        }
        refused.key.assert().isEqualTo("wow.stores.reads.ddl")
        refused.message.assert().contains("ix_a", "ix_b", "$ACCOUNT")
        assertThrows<WowConfigurationException> {
            QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(validated), catalog).start()
        }.key.assert().isEqualTo("wow.ddl")
        // What auto created is not missing any more.
        val created = QueryIndexReport(created = listOf("ix_a"), missing = listOf("ix_a"))
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend(created), catalog).apply {
            start()
            isRunning.assert().isTrue()
            reports.getValue(ACCOUNT).assert().isEqualTo(created)
        }
    }

    @Test
    fun `an aggregate's storage should be prepared before its indexes are managed`() {
        // Finding F16: a node's index step on a table runs after its own schema step of the table.
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        val steps = mutableListOf<String>()
        val backend = SnapshotQueryBackend(
            SnapshotQueryBackend(InMemoryQueryStorage()).factory,
            prepare = { steps += "prepare $it" },
            indexes = { type, _, _ -> QueryIndexReport().also { steps += "indexes $type" } },
        )
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend, catalog).start()
        steps.assert().containsExactly("prepare $ACCOUNT", "indexes $ACCOUNT")
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
