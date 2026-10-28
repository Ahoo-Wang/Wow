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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.events.UnpinnedEventsPolicy
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The isolation of the queries from the appends, as the host configures it (review R3.4-2, R3.4-3): each store's query
 * bulkhead, the opt-in refusal of the `events` queries that name no aggregate, and the bound of the node's waits.
 */
class HostQueryIsolationTest {
    private val account = AggregateType(BANK, "account")

    private fun Host.openAccount(owner: String): String {
        val opened = post("/bank/account/open-account", """{"owner":"$owner"}""")
        opened.status.assert().isEqualTo(200)
        return opened.body!!["ref"]["id"].asString()
    }

    @Test
    fun `each store's queries should run under its bulkhead, beyond which a query is wow-query-busy`() {
        startHost().use { host ->
            val id = host.openAccount("bulk")
            val bulkhead = host.context.getBean(QueryStores::class.java).bulkheads
                .getValue(ContextBackends.DEFAULT_STORE)
            bulkhead.maxConcurrent.assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
            hold(bulkhead.maxConcurrent, bulkhead) {
                listOf(
                    host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}"""),
                    host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}"""),
                ).forEach { busy ->
                    busy.status.assert().isEqualTo(429)
                    busy.headers.getFirst(HttpHeaders.RETRY_AFTER).assert().isEqualTo("1")
                    busy.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
                }
                // The commands do not wait for the queries' permits.
                host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200)
            }
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
                .status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a state read replaying the log should run under its log store's bulkhead (D246)`() {
        startHost().use { host ->
            val id = host.openAccount("replay")
            val bulkhead = host.context.getBean(QueryStores::class.java).bulkheads
                .getValue(ContextBackends.DEFAULT_STORE)
            hold(bulkhead.maxConcurrent, bulkhead) {
                // No snapshot of it: its log would be replayed, which waits for a permit.
                val busy = host.get("/bank/account/unknown/state")
                busy.status.assert().isEqualTo(429)
                busy.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
                // A snapshot is read without one.
                host.get("/bank/account/$id/state").status.assert().isEqualTo(200)
                // A state at a version or a time, and a trace, replay the log too (review R4-Q, Q-1).
                listOf("state?version=1", "state?at=2100-01-01T00:00:00Z", "state/trace").forEach { read ->
                    host.get("/bank/account/$id/$read").status.assert().describedAs(read).isEqualTo(429)
                }
            }
            host.get("/bank/account/unknown/state").status.assert().isEqualTo(404)
            host.get("/bank/account/$id/state?version=1").status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `a state read should replay at most wow-query-state-max-replay commits`() {
        startHost("wow.query.state.max-replay" to 1).use { host ->
            host.context.getBean(QueryProperties::class.java).state.maxReplay.assert().isEqualTo(1)
            val id = host.openAccount("bounded")
            repeat(2) { host.post("/bank/account/$id/deposit", """{"amount":1}""").status.assert().isEqualTo(200) }
            host.get("/bank/account/$id/state?version=1").status.assert().isEqualTo(200)
            listOf("state?version=2" to "version", "state/trace?from=3" to "from").forEach { (read, parameter) ->
                val refused = host.get("/bank/account/$id/$read")
                refused.status.assert().describedAs(read).isEqualTo(400)
                refused.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
                refused.body!!["violations"][0]["code"].asString().assert().isEqualTo(QueryErrorCodes.REPLAY_TOO_LONG)
                refused.body!!["violations"][0]["path"].asString().assert().isEqualTo(parameter)
            }
        }
    }

    @Test
    fun `a replay should run at once without a bulkhead of its log store`() {
        startHost("wow.query.max-concurrent" to 0).use { host ->
            host.context.getBean(ReplayGate::class.java).assert().isSameAs(ReplayGate.OPEN)
        }
        startHost().use { host ->
            val gate = host.context.getBean(ReplayGate::class.java)
            gate.assert().isNotSameAs(ReplayGate.OPEN)
            gate.toString().assert().startsWith("ReplayGate(")
            // A read of a type whose log store is not the node's runs under no bulkhead: at once.
            runBlocking { gate.replay(AggregateType("elsewhere", "thing").ref("t", "x")) { "read" } }.assert()
                .isEqualTo("read")
        }
    }

    @Test
    fun `the bound should come from the store, then wow-query-max-concurrent, and 0 should set none`() {
        startHost(
            "wow.query.max-concurrent" to 3,
            "wow.stores.main.type" to "memory",
            "wow.stores.main.query.max-concurrent" to 5,
            "wow.stores.side.type" to "memory",
            "wow.log.store" to "main",
            "wow.query.max-wait" to "1s",
            "wow.stores.main.query.max-wait" to "50ms",
            "wow.stores.main.query.max-queued" to 2,
        ).use { host ->
            val bulkheads = host.context.getBean(QueryStores::class.java).bulkheads
            val main = bulkheads.getValue("main")
            main.maxConcurrent.assert().isEqualTo(5)
            main.maxWait.assert().isEqualTo(50.milliseconds)
            main.maxQueued.assert().isEqualTo(2)
            val side = bulkheads.getValue("side")
            side.maxConcurrent.assert().isEqualTo(3)
            side.maxWait.assert().isEqualTo(1.seconds)
            side.maxQueued.assert().isEqualTo(3 * QueryBulkhead.DEFAULT_QUEUE_FACTOR)
        }
        startHost("wow.query.max-queued" to 1).use { host ->
            val bulkhead = host.context.getBean(QueryStores::class.java).bulkheads.values.single()
            bulkhead.maxQueued.assert().isEqualTo(1)
            bulkhead.maxWait.assert().isEqualTo(QueryBulkhead.DEFAULT_MAX_WAIT)
        }
        startHost("wow.query.max-concurrent" to 0).use { host ->
            host.context.getBean(QueryStores::class.java).bulkheads.assert().isEmpty()
        }
        val negative = runCatching { startHost("wow.query.max-concurrent" to -1).close() }.exceptionOrNull()
        generateSequence(negative) { it.cause }.any { it.message.orEmpty().contains("max-concurrent") }
            .assert().isTrue()
    }

    @Test
    fun `an events query naming no aggregate should run by default and be refused where a store opts in`() {
        startHost().use { host ->
            host.openAccount("free")
            host.post("/bank/account/events/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
                .status.assert().isEqualTo(200)
            host.context.getBean(QueryStores::class.java).unpinned(account).assert().isEqualTo(UnpinnedEvents.ALLOW)
        }
        startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.main.query.events.unpinned" to "refuse",
        ).use { host ->
            val id = host.openAccount("pinned")
            val refused = host.post("/bank/account/events/query", """{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
            refused.status.assert().isEqualTo(400)
            refused.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
            refused.body!!["violations"][0]["code"].asString().assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
            host.post("/bank/account/events/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}""")
                .status.assert().isEqualTo(200)
            val described = host.get("/bank/account/events/query").body!!
            described["constraints"].values().map { it["type"].asString() }
                .assert().contains(UnpinnedEventsPolicy.AGGREGATE_REQUIRED)
        }
        startHost(
            "wow.query.events.unpinned" to "refuse",
            "wow.aggregates.[bank.account].query.events.unpinned" to "allow",
        ).use { host ->
            val stores = host.context.getBean(QueryStores::class.java)
            stores.unpinned(account).assert().isEqualTo(UnpinnedEvents.ALLOW)
            stores.unpinned(AggregateType(BANK, "other")).assert().isEqualTo(UnpinnedEvents.REFUSE)
        }
    }

    @Test
    fun `wow-wait-max-registered should bound the node's waits`() {
        startHost().use { host ->
            host.node.options.wait.maxRegistered.assert().isEqualTo(WaitOptions.DEFAULT_MAX_REGISTERED)
        }
        startHost("wow.wait.max-registered" to 7).use { host ->
            host.node.options.wait.maxRegistered.assert().isEqualTo(7)
        }
    }

    @Test
    fun `a store's read preference and ddl should be its queries', over the global ones`() {
        (null as WowProperties?).storeQueryOptions("main").also {
            it.indexDdl.assert().isEqualTo(Ddl.AUTO)
            it.eventsReadPreference.assert().isNull()
            it.snapshotsReadPreference.assert().isNull()
        }
        val properties = WowProperties().apply {
            ddl = Ddl.VALIDATE
            mongo.queryReadPreference = "secondaryPreferred"
            stores["other"] = WowProperties.Store().apply {
                ddl = Ddl.NONE
                options[WowProperties.QUERY_READ_PREFERENCE] = "nearest"
            }
        }
        properties.storeQueryOptions("main").also {
            it.indexDdl.assert().isEqualTo(Ddl.VALIDATE)
            it.eventsReadPreference.assert().isEqualTo("secondaryPreferred")
            it.snapshotsReadPreference.assert().isEqualTo("secondaryPreferred")
        }
        properties.storeQueryOptions("other").also {
            it.indexDdl.assert().isEqualTo(Ddl.NONE)
            it.eventsReadPreference.assert().isEqualTo("nearest")
            it.snapshotsReadPreference.assert().isEqualTo("nearest")
        }
        WowProperties.Store().apply { queryDataSource = "reads" }.options()["query-data-source"]
            .assert().isEqualTo("reads")
    }

    /** Runs [block] while [count] queries hold the permits of [bulkhead]. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun hold(count: Int, bulkhead: QueryBulkhead, block: () -> Unit) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val held = (1..count).map {
            val entered = CompletableDeferred<Unit>()
            GlobalScope.async {
                bulkhead.run {
                    entered.complete(Unit)
                    gate.await()
                }
            }.also { entered.await() }
        }
        try {
            block()
        } finally {
            gate.complete(Unit)
            held.forEach { it.await() }
        }
    }
}
