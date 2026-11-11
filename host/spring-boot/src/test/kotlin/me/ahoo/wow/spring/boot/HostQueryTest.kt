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
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spring.boot.HostQueryFixtures.ACCOUNT
import me.ahoo.wow.spring.boot.HostQueryFixtures.eventually
import me.ahoo.wow.spring.boot.HostQueryFixtures.openAccount
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import kotlin.time.toKotlinDuration

/** The application's own [me.ahoo.wow.runtime.node.BackendFactory]: in memory, opened by no layout of the host's. */
@Configuration(proxyBeanMethods = false)
class ApplicationBackends {
    @Bean
    fun ownBackends(properties: WowProperties): HostBackends =
        HostBackends.of(HostBackends.memory(properties.log.partitions).factory)
}

/** The query plane of a host on the memory backend (work package 6.2c). */
class HostQueryTest {
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

            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"id","value":"$id"}}""")
                .status.assert().isEqualTo(404)
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"match-all"}}""")
                .status.assert().isEqualTo(404)
            host.context.getBean(QueryIndexesLifecycle::class.java).reports.assert().isEmpty()
        }
    }

    @Test
    fun `the events model should leave out a stored TagsApplied, and the aggregate still take commands`() {
        startHost(
            "wow.query.memory.enabled" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val log = host.node.context(BANK).backends.log
            // As a released version wrote it: the tags it applied, beside an event the model reads.
            runBlocking {
                val opened = log.read(ACCOUNT.ref(Caller.DEFAULT_TENANT, id)).toList().single()
                val stored = opened.copyWith(
                    version = 2,
                    commandId = "old-2",
                    requestId = "old-2",
                    events = listOf(
                        EventRecord("wow.lifecycle.TagsApplied", 1, """{"tags":{"tier":["gold"]}}"""),
                        EventRecord("bank.account.Deposited", 1, """{"amount":5}"""),
                    ),
                )
                log.append(stored).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            }
            host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200)

            val events = host.context.getBean(HttpQueries::class.java).events(ACCOUNT)!!
            val streams = runBlocking {
                events.list(ListQuery(AggregateIdFilter(id), limit = 10), QueryCaller(Caller())).toList()
            }
            streams.map { it["version"].asInt() }.assert().containsExactly(1, 2, 3)
            val names = streams[1]["events"].values().map { it["name"].asString() }
            names.assert().containsExactly("bank.account.Deposited")
        }
    }

    @Test
    fun `an application's own backend factory should get no events scan, though memory queries are on`() {
        startHost(sources = listOf(HostTestApp::class.java, ApplicationBackends::class.java)).use { host ->
            host.context.getBean(HostBackends::class.java).layout.assert().isNull()
            host.context.getBean(EventQueryBackend::class.java).binding(ACCOUNT).assert().isNull()
            host.context.getBean(HttpQueries::class.java).events(ACCOUNT).assert().isNull()
            val id = host.openAccount("mona")
            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"id","value":"$id"}}""")
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
    fun `an application's query backend should serve the snapshots under the configured budget`() {
        startHost(
            "wow.query.budgets.http.max-list-size" to 7,
            "wow.query.budgets.http.default-list-size" to 2,
            "wow.query.budgets.http.count-limit" to 50,
            "wow.query.budgets.http.timeout" to "5s",
            // The events of the log in memory are scanned (review R3.4-1).
            sources = listOf(HostTestApp::class.java, MemorySnapshotQueries::class.java),
        ).use { host ->
            listOf("mona", "lisa", "nina").forEach { host.openAccount(it) }
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.snapshots(ACCOUNT).assert().isNotNull()
            queries.defaultListSize.assert().isEqualTo(2)

            val listed = host.post(
                "/bank/account/snapshot/query",
                """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"state.owner","direction":"asc"}]}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("lisa", "mona")
            val counted = host.post("/bank/account/snapshot/query", """{"kind":"count","filter":{"op":"match-all"}}""")
            counted.body!!["count"].asLong().assert().isEqualTo(3)
            counted.body!!["exact"].asBoolean().assert().isTrue()
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"match-all"},"limit":8}""")
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
    fun `the memory backend should serve its snapshots and its events by default`() {
        startHost().use { host ->
            val id = listOf("mona", "lisa").map { host.openAccount(it) }.first()
            host.context.getBean(HttpQueries::class.java).snapshots(ACCOUNT).assert().isNotNull()

            val listed = host.post(
                "/bank/account/snapshot/query",
                """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"state.owner","direction":"asc"}]}""",
            )
            listed.status.assert().isEqualTo(200)
            listed.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("lisa", "mona")

            // Its log is scanned in memory for the events model (review R3.4-1).
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
            val events = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"id","value":"$id"}}"""
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
            val store = MemoryStore(StoreConfig("memory", StoreTypes.MEMORY), queries = queries).snapshots(BANK)
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
            val plain = MemoryStore(StoreConfig("plain", StoreTypes.MEMORY))
            plain.snapshots(BANK).assert().isInstanceOf(InMemorySnapshotStore::class.java)
        }
    }

    @Test
    fun `the node's NodeQueryApi should read the snapshots, events and states of its models as a caller`() {
        startHost("wow.query.budgets.in-process.max-list-size" to 5).use { host ->
            val id = host.openAccount("mona")
            host.post("/bank/account/$id/deposit", """{"amount":3}""").status.assert().isEqualTo(200)
            val api = host.context.getBean(NodeQueryApi::class.java)
            val accounts = api.of(Caller.ANONYMOUS).snapshots<Account>()
            accounts.type.assert().isEqualTo(ACCOUNT)
            eventually {
                runBlocking { accounts.single(SingleQuery(AggregateIdFilter(id)))?.state?.balance == 3L }
            }
            runBlocking {
                accounts.state(id)!!.state.owner.assert().isEqualTo("mona")
                // At a version reached: the snapshot of its record, the deposit its last commit.
                val read = accounts.state(id, 2)!!
                read.version.assert().isGreaterThanOrEqualTo(2)
                read.state.balance.assert().isEqualTo(3L)
                read.lastEventTime.assert().isGreaterThanOrEqualTo(read.firstEventTime)
                api.of(Caller.of(tenantId = "other")).snapshots<Account>().state(id).assert().isNull()
                val events = api.trusted().events(ACCOUNT).list(ListQuery(AggregateIdFilter(id))).toList()
                events.map { it.events.single().body }.assert().containsExactly(AccountOpened("mona"), Deposited(3))
                // wow.query.budgets.in-process.*: the in-process budget, trusted code's too.
                runCatching { api.trusted().snapshots(ACCOUNT).list(ListQuery(MatchAllFilter, limit = 6)).toList() }
                    .exceptionOrNull().assert().isInstanceOf(WowProblemException::class.java)
                    .hasCauseInstanceOf(QueryRequestException::class.java)
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
    fun `an application's own NodeQueryApi should serve the handlers as a caller of the commit's tenant`() {
        val trusted = mockk<TrustedQueries>()
        val api = mockk<NodeQueryApi> { every { trusted() } returns trusted }
        QueryAutoConfiguration.NodeQueries().wowContextQueries(api).of("acme").trusted().assert().isSameAs(trusted)
    }

    @Test
    fun `the query plane should be off when wow-query-enabled is false`() {
        startHost("wow.query.enabled" to false).use { host ->
            host.context.getBeanNamesForType(HttpQueries::class.java).assert().isEmpty()
            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"id","value":"a"}}""")
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
}
