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
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.wire.DevelopmentHeaders
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
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.policy.ReadAuthorizer
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
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
 * The memory backend with its snapshots queried: the application's [me.ahoo.wow.query.backend.QueryBindings], the
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
                storages.computeIfAbsent(type.context) { InMemoryEventQueryStorage.of(context.log) }.binding(type)
            }
        }
    }
}

/** A beta application's row filter left as a `QueryPolicy` bean, which the node no longer applies. */
@Configuration(proxyBeanMethods = false)
class LeftoverQueryPolicy {
    @Bean
    fun hideFromMallory(): QueryPolicy =
        QueryPolicy { caller, _ -> if (caller.subject == "mallory") MatchNoneFilter else MatchAllFilter }
}

/** A read authorizer that lets only the callers with the `reader` role read, wherever they read from. */
@Configuration(proxyBeanMethods = false)
class ReadersOnly {
    @Bean
    fun readerRole(): ReadAuthorizer = ReadAuthorizer { caller, read ->
        if ("reader" in caller.attributes["roles"].orEmpty()) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of("${caller.subject} may not read ${read.type}: it needs the reader role.")
        }
    }
}

/** The access policy of the accounts: the tellers read and change them, the auditors only read them. */
@Configuration(proxyBeanMethods = false)
class TellersReadAccounts {
    @Bean
    fun accountAccess(): AccessPolicy<Account> = accessPolicy {
        read { caller -> allowIf(caller.hasRole("teller") || caller.hasRole("auditor")) }
        write { caller -> allowIf(caller.hasRole("teller")) }
    }
}

/** An access policy of a state no aggregate of the node has. */
@Configuration(proxyBeanMethods = false)
class StrayAccessPolicy {
    @Bean
    fun strayAccess(): AccessPolicy<DecidedCommands> = accessPolicy { readWrite { allowAll() } }
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
    private fun Host.openAccount(owner: String, headers: Map<String, String> = emptyMap()): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""", headers)
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    /** A teller of the development profile: a request's headers name its caller. */
    private val tellerHeaders = mapOf(DevelopmentHeaders.USER to "tom", DevelopmentHeaders.ROLES to "teller")

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
    fun `a QueryPolicy bean should refuse the start, naming it and where its rule goes`() {
        val refused = assertThrows<Exception> {
            startHost(sources = listOf(HostTestApp::class.java, LeftoverQueryPolicy::class.java)).close()
        }
        val cause = generateSequence<Throwable>(refused) { it.cause }
            .filterIsInstance<WowConfigurationException>().first()
        cause.key.assert().isEqualTo(QueryPolicy::class.java.name)
        cause.reason.assert().contains("hideFromMallory", "no longer applied")
        // Reported through the host's failure analyzer, as any refused configuration.
        val analysis = checkNotNull(WowConfigurationFailureAnalyzer().analyze(refused))
        analysis.description.assert().contains("hideFromMallory")
        analysis.action.assert().contains("ReadAuthorizer", "AccessPolicy")
    }

    @Test
    fun `an access policy bean should govern its aggregate's reads, and one of no aggregate refuse the start`() {
        // The development profile: a request's headers name its caller.
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, TellersReadAccounts::class.java),
        ).use { host ->
            val id = host.openAccount("mona", tellerHeaders)
            val engine = host.context.getBean(QueryEngine::class.java)
            engine.access.governs(ACCOUNT).assert().isTrue()
            fun accounts(caller: Caller) = runBlocking {
                engine.snapshots(ACCOUNT)!!.list(ListQuery(MatchAllFilter, limit = 10), QueryCaller(caller)).toList()
            }
            val tom = Caller.of(subject = "tom", attributes = mapOf("roles" to listOf("teller")))
            eventually { accounts(tom).size == 1 }
            // Not by its owner: the policy alone decides.
            accounts(Caller.of(subject = "mona", scope = Scope("mona"))).assert().isEmpty()
            // A state read alike: a teller who is an owner of none of them reads it, no one else.
            val teller = mapOf(
                DevelopmentHeaders.USER to "tom",
                DevelopmentHeaders.OWNER to "tom",
                DevelopmentHeaders.ROLES to "teller",
            )
            host.get("/bank/account/$id/state", teller).status.assert().isEqualTo(200)
            host.get("/bank/account/$id/state").status.assert().isEqualTo(404)
            // Its events alike, by a query that names no aggregate: each stream records the account's attributes.
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            host.post("/bank/account/events/query", all, teller).body!!.values()
                .map { it["aggregateId"].asString() }.toSet().assert().containsExactly(id)
            host.post("/bank/account/events/query", all).body!!.size().assert().isZero()
        }
        val refused = assertThrows<Exception> {
            startHost(sources = listOf(HostTestApp::class.java, StrayAccessPolicy::class.java)).close()
        }
        generateSequence<Throwable>(refused) { it.cause }.mapNotNull { it.message }
            .any { "is of no aggregate of this node" in it }.assert().isTrue()
        // A node of some of the service's contexts leaves such a policy to the node that hosts its aggregate.
        startHost(
            "wow.contexts.include" to BANK,
            sources = listOf(HostTestApp::class.java, StrayAccessPolicy::class.java),
        ).use { host -> host.context.getBean(QueryEngine::class.java).access.governed.assert().isEmpty() }
    }

    @Test
    fun `an access policy bean should decide who changes its aggregate, over HTTP and in process`() {
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, TellersReadAccounts::class.java),
        ).use { host ->
            val id = host.openAccount("mona", tellerHeaders)
            val deposit = "/bank/account/$id/deposit"
            host.post(deposit, """{"amount":5}""", tellerHeaders).status.assert().isEqualTo(200)
            // An auditor reads the account but may not change it; any other caller does not learn it exists.
            val auditor = mapOf(DevelopmentHeaders.USER to "amy", DevelopmentHeaders.ROLES to "auditor")
            host.post(deposit, """{"amount":5}""", auditor).let {
                it.status.assert().isEqualTo(403)
                it.body!!["code"].asString().assert().isEqualTo("wow.aggregate.forbidden")
            }
            host.post(deposit, """{"amount":5}""", mapOf(DevelopmentHeaders.USER to "eve")).let {
                it.status.assert().isEqualTo(404)
                it.body!!["code"].asString().assert().isEqualTo("wow.aggregate.not-found")
            }
            // Opening one is a change too: the auditor may not create an account it could not change.
            host.post("/bank/account/open-account", """{"owner":"amy"}""", auditor).status.assert().isEqualTo(403)
            val ref = ACCOUNT.ref(Caller.DEFAULT_TENANT, id)
            val commands = host.context.getBean(CommandClient::class.java)
            runBlocking {
                // In process, the default caller is anonymous and holds no claim: refused as any other.
                assertThrows<CommandRejectedException> { commands.wait(CommandRequest.to(ref, Deposit(1))) }
                    .code.assert().isEqualTo("wow.aggregate.not-found")
                // The service's own code sends as the service, which access policies trust.
                commands.of(Caller.ofService("host-test")).wait(CommandRequest.to(ref, Deposit(1))).status.assert()
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
            }
        }
    }

    @Test
    fun `an owner transfer should move the aggregate's whole history to its new owner's events queries`() {
        startHost("spring.profiles.active" to "dev").use { host ->
            val tom = Caller.of(scope = Scope("tom"), subject = "tom")
            val commands = host.context.getBean(CommandClient::class.java).of(tom)
            val ref = runBlocking {
                val opened = commands.wait(CommandRequest.of(OpenAccount("tom")))
                commands.wait(CommandRequest.to(opened.ref, Deposit(5))).status.assert()
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
                opened.ref
            }
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            fun versions(owner: String) = host.post(
                "/bank/account/events/query",
                all,
                mapOf(DevelopmentHeaders.USER to owner, DevelopmentHeaders.OWNER to owner),
            ).body!!.values().map { it["version"].asInt() }
            versions("tom").assert().containsExactly(1, 2)
            versions("ann").assert().isEmpty()
            runBlocking { commands.wait(CommandRequest.to(ref, TransferOwner.of("ann"))) }.status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            // The built-in stamping stamps the earlier streams with the owner of now.
            eventually { versions("ann") == listOf(1, 2, 3) }
            versions("tom").assert().isEmpty()
        }
    }

    @Test
    fun `without a query policy no snapshot policy gateway is built`() {
        startHost(
            "wow.query.memory.enabled" to false,
            sources = listOf(HostTestApp::class.java, ScannedEvents::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val queries = host.context.getBean(HttpQueries::class.java)
            queries.visibility(ACCOUNT).assert().isNull()
            val events = host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"match-all"}}""")
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
    fun `a read authorizer bean should refuse a caller every read before anything is read, over HTTP and in process`() {
        startHost(
            "spring.profiles.active" to "dev",
            sources = listOf(HostTestApp::class.java, ReadersOnly::class.java),
        ).use { host ->
            val id = host.openAccount("mona")
            val reader = mapOf(DevelopmentHeaders.USER to "rita", DevelopmentHeaders.ROLES to "reader")
            val all = """{"kind":"list","filter":{"op":"match-all"}}"""
            eventually { host.post("/bank/account/snapshot/query", all, reader).body!!.size() == 1 }
            host.get("/bank/account/$id/state", reader).status.assert().isEqualTo(200)
            // Refused alike whatever is asked: a query, a state that exists or not, a trace, the events.
            val stranger = mapOf(DevelopmentHeaders.USER to "sam")
            listOf(
                host.post("/bank/account/snapshot/query", all, stranger),
                host.post("/bank/account/events/query", all, stranger),
                host.get("/bank/account/$id/state", stranger),
                host.get("/bank/account/no-such-account/state", stranger),
                host.get("/bank/account/$id/state/trace", stranger),
            ).forEach { answer ->
                answer.status.assert().isEqualTo(403)
                answer.body!!["code"].asString().assert().isEqualTo("wow.auth.forbidden")
            }
            val api = host.context.getBean(NodeQueryApi::class.java)
            runBlocking {
                runCatching { api.of(Caller.of(subject = "sam")).snapshots<Account>().state(id) }
                    .exceptionOrNull().assert().isInstanceOf(WowProblemException::class.java)
                    .hasMessageContaining("needs the reader role")
                // Trusted code reads with no caller, and is not asked.
                api.trusted().snapshots(ACCOUNT).list(ListQuery(MatchAllFilter, limit = 5)).toList().assert().hasSize(1)
            }
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

    @Test
    fun `the properties should default to the query engine's defaults`() {
        val properties = QueryProperties()
        val policy = properties.entryPolicy()
        policy.http.maxListSize.assert().isEqualTo(QueryBudget.HTTP_DEFAULT.maxListSize)
        policy.http.countLimit.assert().isEqualTo(QueryBudget.DEFAULT_COUNT_LIMIT)
        policy.http.allowExactCount.assert().isFalse()
        policy.inProcessTimeout.assert().isEqualTo(QueryBudget.DEFAULT_TIMEOUT)
        policy.requireExplicitEntry.assert().isFalse()
        properties.sensitivity.policy().displayComparable.assert().isTrue()
        // Unset: on wherever the store is in memory.
        properties.memory.enabled.assert().isTrue()
        properties.budgets.http.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        policy.inProcess!!.maxListSize.assert().isEqualTo(QueryBudget.IN_PROCESS_DEFAULT.maxListSize)
        policy.inProcess!!.label.assert().isEqualTo(QueryBudget.IN_PROCESS_LABEL)
        properties.budgets.inProcess.defaultListSize.assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        properties.unboundedTimeout = Duration.ZERO
        properties.entryPolicy().inProcessTimeout.assert().isNull()
    }

    @Test
    fun `an events backend should manage the indexes of the logs on the stores that manage them only`() {
        val plan = QueryIndexPlan.of(runBlocking { catalogSchema() }, emptyList())
        EventQueryBackend.NONE.managesIndexes.assert().isFalse()
        EventQueryBackend.NONE.managesIndexes(ACCOUNT).assert().isFalse()
        EventQueryBackend.NONE.manageIndexes(ACCOUNT, plan).assert().isEqualTo(QueryIndexReport.NONE)
        // Given indexes and no say per type, every type's.
        val every = EventQueryBackend(indexes = { _, _ -> QueryIndexReport.NONE }) { null }
        every.managesIndexes(ACCOUNT).assert().isTrue()
        val prepared = ArrayList<AggregateType>()
        val created = QueryIndexReport(created = listOf("access_owner"))
        val backend = EventQueryBackend(
            prepare = { prepared += it },
            indexes = { _, _ -> created },
            manages = { it == ACCOUNT },
        ) { null }
        backend.managesIndexes(ACCOUNT).assert().isTrue()
        backend.managesIndexes(AggregateType(BANK, "other")).assert().isFalse()
        backend.prepare(ACCOUNT)
        prepared.assert().containsExactly(ACCOUNT)
        backend.manageIndexes(ACCOUNT, plan).assert().isEqualTo(created)
    }

    @Test
    fun `a backend without index management should report nothing`() {
        SnapshotQueryBackend.NONE.managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage()).managesIndexes.assert().isFalse()
        SnapshotQueryBackend(InMemoryQueryStorage())
            .manageIndexes(ACCOUNT, QueryIndexPlan.of(runBlocking { catalogSchema() }, emptyList()))
            .assert().isEqualTo(QueryIndexReport.NONE)
    }

    @Test
    fun `an index that validate finds missing or invalid should refuse the start, naming the store's ddl`() {
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        fun backend(report: QueryIndexReport) = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            indexes = { _, _ -> report },
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
    fun `a state holding a sealed value should start, its discriminator indexed`() {
        // Each subtype as the Manifest writes it: its own properties, then its discriminator's value.
        fun subtype(name: String, extra: Map<String, Any?>) = mapOf(
            "allOf" to listOf(
                mapOf("type" to "object", "properties" to mapOf("type" to mapOf("type" to "string")) + extra),
                mapOf("type" to "object", "properties" to mapOf("type" to mapOf("const" to name))),
            ),
        )
        val holder = mapOf("oneOf" to listOf(mapOf("\$ref" to "#/\$defs/Rack"), mapOf("\$ref" to "#/\$defs/Crate")))
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(
            contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))),
            defs = mapOf(
                "Account" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("holder" to holder),
                    "x-wow-index" to listOf(mapOf("name" to "ix_holder_type", "keys" to listOf("state.holder.type"))),
                ),
                "Rack" to subtype("Rack", mapOf("levels" to mapOf("type" to "integer"))),
                "Crate" to subtype("Crate", mapOf("lidded" to mapOf("type" to "boolean"))),
            ),
        )
        val catalog = QuerySchemaCatalog(
            QueryBackendStorages(InMemoryQueryStorage()),
            QueryModelCompiler.of(listOf(ManifestStateSchemas(manifest))),
        )
        val plans = mutableListOf<QueryIndexPlan>()
        val backend = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            indexes = { _, plan -> QueryIndexReport().also { plans += plan } },
        )
        QueryIndexesLifecycle(manifest, listOf(ACCOUNT), backend, catalog).apply {
            start()
            isRunning.assert().isTrue()
        }
        plans.single().indexes.single().keys.single().field.assert().isEqualTo(QueryField("state.holder.type"))
    }

    @Test
    fun `an aggregate's storage should be prepared before its indexes are managed`() {
        // Finding F16: a node's index step on a table runs after its own schema step of the table.
        val account = AggregateManifest("account", state = StateManifest("#/\$defs/Account"))
        val manifest = Manifest(contexts = listOf(ContextManifest(BANK, aggregates = listOf(account))))
        val catalog = QuerySchemaCatalog(QueryBackendStorages(InMemoryQueryStorage()))
        val steps = mutableListOf<String>()
        val backend = SnapshotQueryBackend(
            InMemoryQueryStorage(),
            prepare = { steps += "prepare $it" },
            indexes = { type, _ -> QueryIndexReport().also { steps += "indexes $type" } },
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
