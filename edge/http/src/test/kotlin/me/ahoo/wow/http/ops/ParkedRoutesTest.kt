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

package me.ahoo.wow.http.ops

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdLimits
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.http.Answer
import me.ahoo.wow.http.HttpJson
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.announce
import me.ahoo.wow.http.answer
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.headerCallers
import me.ahoo.wow.http.servingOnNetty
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.node.ParkedKey
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerLoop
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ParkedRoutesTest {
    private val type = AggregateType("shop", "cart")
    private val log = InMemoryEventLog(partitions = 2)
    private val ledger = InMemoryFailureLedger()
    private val options = SubscriptionOptions(
        retry = RetryPolicy(maxAttempts = 1, initialBackoff = 1.milliseconds, maxBackoff = 1.milliseconds),
        parked = ParkedRetryOptions(ParkedRetrySpec(3, 1.hours, 1.minutes), scanInterval = 1.hours),
    )
    private val failing = Subscription(NAME, SubscriptionFilter("shop")) { error("${it.ref.id} is broken") }

    /** The node that runs every partition. */
    private val owner = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), ledger, listOf(failing), options)

    /** A node that runs none: its actions are requests to the owner. */
    private val other = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), ledger, listOf(failing), options)
    private val actions = CopyOnWriteArrayList<Pair<String, CommandAction>>()
    private val authorizer = CommandAuthorizer.operational { caller, action ->
        actions += caller.subject.orEmpty() to action
        if (caller.subject == "mallory") Authorization.Denied("Mallory may not.") else Authorization.Allowed
    }

    private fun client(runtime: SubscriptionRuntime): WebTestClient = WebTestClient
        .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(runtime)), cartManifest(), authorizer, headerCallers))
        .build()

    @AfterEach
    fun stop(): Unit = runBlocking { owner.stop() }

    /** The members of a route's body that name [id]'s entry, of [tenant], parked by [subscription]. */
    private fun named(id: String, tenant: String = TENANT, subscription: String = KEY): String =
        """"subscription":"$subscription","context":"shop","aggregate":"cart","tenantId":"$tenant","id":"$id""""

    private fun WebTestClient.post(path: String, body: String? = null, user: String? = "alice", json: Boolean = true) =
        post().uri(path).headers {
            if (user != null) it.set("X-User", user)
            it.set("Content-Type", if (json) "application/json" else "text/plain")
        }.let { if (body == null) it else it.bodyValue(body) }.answer()

    /** [action] on [id]'s entry, [members] beside the ones that name it. */
    private fun WebTestClient.act(
        action: String,
        id: String,
        members: String = "",
        user: String? = "alice",
        tenant: String = TENANT,
        subscription: String = KEY,
    ): Answer = post(
        "${OpsPaths.PARKED}/$action",
        "{${named(id, tenant, subscription)}${if (members.isEmpty()) "" else ",$members"}}",
        user,
    )

    /** One entry. */
    private fun WebTestClient.read(
        id: String,
        user: String? = "alice",
        tenant: String = TENANT,
        subscription: String = KEY,
    ): Answer = act(OpsPaths.ENTRY, id, user = user, tenant = tenant, subscription = subscription)

    /** A page of the entries, [members] its body's. */
    private fun WebTestClient.list(members: String = "", user: String? = "alice"): Answer =
        post("${OpsPaths.PARKED}/${OpsPaths.LIST}", "{$members}", user)

    private suspend fun park(id: String, version: Int = 1) {
        ledger.park(
            ParkedAggregate(SubscriptionId("shop", NAME), type.ref(TENANT, id), 0, version, "$id failed", FAILED_AT)
        )
    }

    private suspend fun append(id: String) {
        val stream = EventStreamRecord(
            type.ref(TENANT, id),
            1,
            "c-$id",
            "r-$id",
            "shop.cart.Act",
            0L,
            listOf(EventRecord("shop.cart.Added", 1, "{}")),
            Scope.NONE,
        )
        (log.append(stream) as AppendOutcome.Committed).position.assert().isNotNull()
    }

    @Test
    fun `the operator's actions and read are the operational commands, and nothing else is one of them`() {
        listOf(OpsCommands.RETRY, OpsCommands.SKIP, OpsCommands.MARK_RECOVERABLE, OpsCommands.APPLY_RETRY_SPEC)
            .forEach { OpsCommands.typeOf(it).name.assert().isEqualTo(it) }
        OpsCommands.typeOf(OpsPaths.READ).name.assert().isEqualTo(Ledger.READ)
        assertThrows<IllegalArgumentException> { OpsCommands.typeOf("wow.lifecycle.DeleteAggregate") }
            .message.assert().contains("no operational command")
    }

    @Test
    fun `entries are listed in pages and read one at a time`(): Unit = runBlocking {
        listOf("b", "a", "c").forEach { park(it) }
        val client = client(other)
        val first = client.list(""""limit":2""")
        first.status.assert().isEqualTo(200)
        first.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("a", "b")
        val next = first.body!!["next"].asString()
        next.assert().isEqualTo(encodeCursor(ParkedKey(ID, type.ref(TENANT, "b"))))
        val second = client.list(""""subscription":"$KEY","after":"$next"""")
        second.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("c")
        second.body!!["next"].isNull.assert().isTrue()

        val one = client.read("a")
        one.status.assert().isEqualTo(200)
        val body = one.body!!
        body["subscription"].asString().assert().isEqualTo(KEY)
        body["aggregate"]["context"].asString().assert().isEqualTo("shop")
        body["aggregate"]["name"].asString().assert().isEqualTo("cart")
        body["aggregate"]["tenantId"].asString().assert().isEqualTo(TENANT)
        body["fromVersion"].asInt().assert().isEqualTo(1)
        body["reason"].asString().assert().isEqualTo("a failed")
        body["failedAt"].asLong().assert().isEqualTo(FAILED_AT)
        body["retries"].asInt().assert().isEqualTo(0)
        body["lastRetryAt"].isNull.assert().isTrue()
        body["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(3)
        body["retrySpec"]["initialBackoff"].asString().assert().isEqualTo("PT1H")
        body["retrySpec"]["claimTtl"].asString().assert().isEqualTo("PT1M")
        body["retrySpec"]["applied"].asBoolean().assert().isFalse()
        body["recoverable"].asString().assert().isEqualTo("unknown")
        body["status"].asString().assert().isEqualTo("scheduled")
        body["nextRetryAt"].asLong().assert().isEqualTo(FAILED_AT + 1.hours.inWholeMilliseconds)

        client.read("absent").status.assert().isEqualTo(404)
        client.list(user = null).status.assert().isEqualTo(401)
        // Reads are authorized as wow.ledger.Read on each entry's aggregate (review R2.3-9).
        actions.map { it.second.type.name }.toSet().assert().containsExactly(OpsPaths.READ)
        actions.first().second.ref.assert().isEqualTo(type.ref(TENANT, "a"))
        val hidden = client.list(""""limit":2""", user = "mallory")
        hidden.status.assert().isEqualTo(200)
        hidden.body!!["entries"].toList().assert().isEmpty()
        val refused = client.read("a", user = "mallory")
        refused.status.assert().isEqualTo(403)
        refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `a body announced past the codecs' limit is refused from its Content-Length, the same 413 problem (#7001)`() {
        val path = "${OpsPaths.PARKED}/${OpsPaths.LIST}"
        val read = client(other).post(path, """{"subscription":"${"a".repeat(300 * 1024)}"}""")
        read.status.assert().isEqualTo(413)
        val routes = parkedRoutes(ParkedOps.of(listOf(other)), cartManifest(), authorizer, headerCallers)
        val announced = servingOnNetty(routes) { announce(it, path) }
        announced.statusLine.assert().startsWith("HTTP/1.1 413")
        HttpJson.mapper.readTree(announced.body).assert().isEqualTo(read.body)
    }

    @Test
    fun `a listing or an entry its body does not name rightly is refused, and a blank listing is the first page`(): Unit =
        runBlocking {
            listOf("b", "a", "c").forEach { park(it) }
            val client = client(other)
            client.list(""""after":"bm90LWEtY3Vyc29y"""").status.assert().isEqualTo(400)
            client.list(""""limit":0""").status.assert().isEqualTo(400)
            client.list(""""limit":"x"""").status.assert().isEqualTo(400)
            client.list(""""after":1""").status.assert().isEqualTo(400)
            // A listing's body may be left out, or its members blank or null: the first page of every subscription.
            client.post("${OpsPaths.PARKED}/${OpsPaths.LIST}").status.assert().isEqualTo(200)
            client.list(""""subscription":"","after":"","limit":null""").body!!["entries"].toList()
                .map { it["aggregate"]["id"].asString() }.assert().containsExactly("a", "b", "c")
            // A subscription is named <context>.<name>, as the configuration keys it.
            client.list(""""subscription":"nodot"""").status.assert().isEqualTo(400)
            client.read("a", subscription = "nodot").status.assert().isEqualTo(400)
            // The body names the entry, each member a string.
            client.post("${OpsPaths.PARKED}/${OpsPaths.ENTRY}", """{"subscription":"$KEY","context":"shop"}""")
                .let {
                    it.assertMalformed()
                    it.body!!["detail"].asString().assert().contains("aggregate is missing")
                }
            val numbered = """{"subscription":"$KEY","context":"shop","aggregate":"cart","tenantId":"$TENANT","id":1}"""
            client.post("${OpsPaths.PARKED}/${OpsPaths.ENTRY}", numbered).assertMalformed()
            // A null member is a missing one; a blank tenant names no aggregate.
            client.post("${OpsPaths.PARKED}/${OpsPaths.ENTRY}", numbered.replace("\"id\":1", "\"id\":null"))
                .body!!["detail"].asString().assert().contains("id is missing")
            client.read("a", tenant = " ").assertMalformed()
        }

    @Test
    fun `an entry of the longest tenant and id is read, retried and skipped by routes whose paths hold no data`(): Unit =
        runBlocking {
            // A tenant of 256 characters that percent-encode to nine each, and the longest id the rules allow, 256
            // characters of two bytes each (#6626, #6628): no path holds them.
            val tenant = "租".repeat(256)
            val id = "é".repeat(AggregateIdLimits.MAX_LENGTH)
            ledger.park(ParkedAggregate(SubscriptionId("shop", NAME), type.ref(tenant, id), 0, 1, "failed", FAILED_AT))
            val client = client(other)
            val admin = "root"
            fun act(action: String, members: String = "") = client.post().uri("${OpsPaths.PARKED}/$action")
                .headers {
                    it.set("X-User", admin)
                    it.set("X-Admin", "true")
                    it.set("Content-Type", "application/json")
                }.bodyValue("{${named(id, tenant)}${if (members.isEmpty()) "" else ",$members"}}").answer()
            val read = act(OpsPaths.ENTRY)
            read.status.assert().isEqualTo(200)
            read.body!!["aggregate"]["tenantId"].asString().assert().isEqualTo(tenant)
            read.body!!["aggregate"]["id"].asString().assert().isEqualTo(id)
            act(OpsPaths.RETRY).status.assert().isEqualTo(202)
            act(OpsPaths.SKIP, """"failedAt":$FAILED_AT""").status.assert().isEqualTo(202)
            actions.map { it.second.ref }.toSet().assert().containsExactly(type.ref(tenant, id))
        }

    @Test
    fun `retry and skip run on the owner and are requests elsewhere`(): Unit = runBlocking {
        park("gone")
        append("x")
        owner.start(setOf(0, 1))
        eventually { ledger.isParked(SubscriptionId("shop", NAME), type.ref(TENANT, "x")) }
        val local = client(owner)

        // Nothing of "gone" is in the log: retrying it handles nothing and resolves it.
        val resolved = local.act(OpsPaths.RETRY, "gone")
        resolved.status.assert().isEqualTo(200)
        resolved.body!!["outcome"].asString().assert().isEqualTo("resolved")
        resolved.body!!.has("entry").assert().isFalse()
        val parked = local.act(OpsPaths.RETRY, "x")
        parked.status.assert().isEqualTo(200)
        parked.body!!["outcome"].asString().assert().isEqualTo("parked")
        parked.body!!["entry"]["reason"].asString().assert().isEqualTo("java.lang.IllegalStateException")

        val remote = client(other)
        val requested = remote.act(OpsPaths.RETRY, "x")
        requested.status.assert().isEqualTo(202)
        requested.body!!["outcome"].asString().assert().isEqualTo("requested")
        requested.body!!["entry"]["request"].asString().assert().isEqualTo("retry")
        // A skip names the parking it discards; without it the skip is refused.
        val x = """"failedAt":${ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "x"))!!.failedAt}"""
        remote.act(OpsPaths.SKIP, "x").assertMalformed()
        remote.act(OpsPaths.SKIP, "x", x).body!!["entry"]["request"].asString().assert().isEqualTo("skip")
        ledger.parked(
            SubscriptionId("shop", NAME),
            type.ref(TENANT, "x")
        )!!.request.assert().isEqualTo(LedgerRequest.SKIP)

        val skipped = local.act(OpsPaths.SKIP, "x", x)
        skipped.status.assert().isEqualTo(200)
        skipped.body!!["outcome"].asString().assert().isEqualTo("resolved")
        local.act(OpsPaths.SKIP, "x", x).status.assert().isEqualTo(404)
        local.act(OpsPaths.RETRY, "x").status.assert().isEqualTo(404)

        actions.map { it.second.type.name }.assert().containsExactly(
            OpsCommands.RETRY,
            OpsCommands.RETRY,
            OpsCommands.RETRY,
            // Authorized, then refused for naming no parking.
            OpsCommands.SKIP,
            OpsCommands.SKIP,
            OpsCommands.SKIP,
            OpsCommands.SKIP,
            OpsCommands.RETRY,
        )
        actions.first().second.ref.assert().isEqualTo(type.ref(TENANT, "gone"))
        actions.first().second.scope.assert().isEqualTo(Scope("alice"))
    }

    @Test
    fun `a retry or a skip naming another parking than the entry's is refused as stale`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val failedAt = ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "a"))!!.failedAt
        listOf(OpsPaths.RETRY, OpsPaths.SKIP).forEach { action ->
            val stale = client.act(action, "a", """"failedAt":${failedAt - 1}""")
            stale.status.assert().isEqualTo(409)
            stale.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.LEDGER_STALE)
            client.act(action, "a", """"failedAt":"yesterday"""").assertMalformed()
        }
        ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "a"))!!.request.assert().isNull()
        // The parking the operator saw: acted on, here a request to the owner.
        val requested = client.act(OpsPaths.SKIP, "a", """"failedAt":$failedAt""")
        requested.status.assert().isEqualTo(202)
        ledger.parked(
            SubscriptionId("shop", NAME),
            type.ref(TENANT, "a")
        )!!.request.assert().isEqualTo(LedgerRequest.SKIP)
    }

    @Test
    fun `a mark or a spec naming another parking than the entry's is refused as stale, and changes nothing`(): Unit =
        runBlocking {
            park("a")
            val client = client(other)
            val ref = type.ref(TENANT, "a")
            val before = ledger.parked(SubscriptionId("shop", NAME), ref)!!
            val bodies = mapOf(
                OpsPaths.MARK_RECOVERABLE to "\"recoverable\":\"unrecoverable\"",
                OpsPaths.APPLY_RETRY_SPEC to "\"maxRetries\":0",
            )
            bodies.forEach { (action, body) ->
                val stale = client.act(action, "a", "$body,\"failedAt\":${before.failedAt - 1}")
                stale.status.assert().isEqualTo(409)
                stale.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.LEDGER_STALE)
            }
            ledger.parked(SubscriptionId("shop", NAME), ref).assert().isEqualTo(before)
            bodies.forEach { (action, body) ->
                client.act(action, "a", "$body,\"failedAt\":${before.failedAt}").status.assert().isEqualTo(200)
            }
            ledger.parked(SubscriptionId("shop", NAME), ref)!!.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
        }

    @Test
    fun `parked timers are listed, retried and skipped by an operator`(): Unit = runBlocking {
        val timers = InMemoryTimerStore()
        val loop = TimerLoop(TimerOptions(), Clock.systemUTC())
        val runner = TimerRunner("shop", timers, ledger, { TimerOutcomes.COMMITTED }, loop)
        val client = WebTestClient
            .bindToRouterFunction(
                parkedRoutes(
                    ParkedOps.of(listOf(owner), mapOf(runner to (owner to 2))),
                    cartManifest(),
                    authorizer,
                    headerCallers,
                ),
            )
            .build()
        try {
            parkTimer(timers, "pay", "t-1")
            parkTimer(timers, "remind", "t-2")
            val subscription = "shop.${TimerOptions.PARKED_SUBSCRIPTION}"
            val listed = client.list(""""subscription":"$subscription"""")
            listed.status.assert().isEqualTo(200)
            listed.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert()
                .containsExactly("t-1", "t-2")
            val one = client.read("t-1", subscription = subscription)
            one.status.assert().isEqualTo(200)
            one.body!!["reason"].asString().assert().isEqualTo("pay: down")
            // Nothing retries a parked timer: it waits for an operator.
            one.body!!["status"].asString().assert().isEqualTo("exhausted")

            val retried = client.act(OpsPaths.RETRY, "t-1", subscription = subscription)
            retried.status.assert().isEqualTo(200)
            retried.body!!["outcome"].asString().assert().isEqualTo("resolved")
            timers.find(type.ref(TENANT, "o-1"), "pay")!!.status.assert().isEqualTo(TimerStatus.PENDING)
            val parkedAt = client.read("t-2", subscription = subscription).body!!["failedAt"].asLong()
            val skipped = client.act(OpsPaths.SKIP, "t-2", """"failedAt":$parkedAt""", subscription = subscription)
            skipped.status.assert().isEqualTo(200)
            skipped.body!!["outcome"].asString().assert().isEqualTo("resolved")
            val remind = timers.find(type.ref(TENANT, "o-1"), "remind")!!
            remind.status.assert().isEqualTo(TimerStatus.CANCELLED)
            remind.outcome.assert().isEqualTo(TimerOutcomes.SKIPPED)
            client.list(""""subscription":"$subscription"""").body!!["entries"].toList().assert().isEmpty()
            client.act(OpsPaths.RETRY, "t-1", subscription = subscription).status.assert().isEqualTo(404)
        } finally {
            loop.close()
        }
    }

    /** A timer of an aggregate's, [key], parked after its attempts, its [target]'s entry in the ledger. */
    private suspend fun parkTimer(timers: InMemoryTimerStore, key: String, target: String) {
        val owner = type.ref(TENANT, "o-1")
        val timer = Timer(
            id = "$owner#$key@1",
            owner = owner,
            key = key,
            version = 1,
            partition = 0,
            rule = ScheduleRule.At(FAILED_AT),
            dueAt = FAILED_AT,
            target = type.ref(TENANT, target),
            commandType = "shop.cart.Remind",
            body = "{}",
        )
        timers.put(timer).assert().isTrue()
        val parked = timer.copyWith(status = TimerStatus.PARKED, attempts = 10, outcome = "down")
        timers.settle(listOf(TimerChange(timer, parked))).assert().containsExactly(true)
        ledger.park(
            ParkedAggregate(
                SubscriptionId("shop", TimerOptions.PARKED_SUBSCRIPTION),
                timer.target,
                0,
                1,
                "$key: down",
                FAILED_AT
            )
        )
    }

    @Test
    fun `an operator marks an entry recoverable or not`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val marked = client.act(OpsPaths.MARK_RECOVERABLE, "a", """"recoverable":"unrecoverable"""")
        marked.status.assert().isEqualTo(200)
        marked.body!!["recoverable"].asString().assert().isEqualTo("unrecoverable")
        marked.body!!["status"].asString().assert().isEqualTo("unrecoverable")
        marked.body!!["nextRetryAt"].isNull.assert().isTrue()
        ledger.parked(
            SubscriptionId("shop", NAME),
            type.ref(TENANT, "a")
        )!!.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)

        val mark = "${OpsPaths.PARKED}/${OpsPaths.MARK_RECOVERABLE}"
        client.act(OpsPaths.MARK_RECOVERABLE, "a", """"recoverable":"maybe"""").assertMalformed()
        client.act(OpsPaths.MARK_RECOVERABLE, "a").assertMalformed()
        client.post(mark, """{}""").assertMalformed()
        client.post(mark, null).assertMalformed()
        client.act(OpsPaths.MARK_RECOVERABLE, "a", """"recoverable":1""").assertMalformed()
        client.post(mark, "[]").assertMalformed()
        client.post(mark, "{").assertMalformed()
        val recoverable = """"recoverable":"recoverable""""
        client.post(mark, "{${named("a")},$recoverable}", json = false).status.assert().isEqualTo(415)
        client.act(OpsPaths.MARK_RECOVERABLE, "absent", recoverable).status.assert().isEqualTo(404)
        actions.map { it.second.type.name }.toSet().assert().containsExactly(OpsCommands.MARK_RECOVERABLE)
    }

    @Test
    fun `an operator applies a retry spec, the members left out kept`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        fun apply(members: String = "") = client.act(OpsPaths.APPLY_RETRY_SPEC, "a", members)
        val applied = apply(""""maxRetries":5,"initialBackoff":"10s"""")
        applied.status.assert().isEqualTo(200)
        applied.body!!["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(5)
        applied.body!!["retrySpec"]["initialBackoff"].asString().assert().isEqualTo("PT10S")
        applied.body!!["retrySpec"]["claimTtl"].asString().assert().isEqualTo("PT1M")
        applied.body!!["retrySpec"]["applied"].asBoolean().assert().isTrue()
        applied.body!!["nextRetryAt"].asLong().assert().isEqualTo(FAILED_AT + 10_000)
        ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "a"))!!.retrySpec.assert()
            .isEqualTo(ParkedRetrySpec(5, 10.seconds, 1.minutes))

        // The durations the ops context's ApplyRetrySpec and the configuration take: the short forms, ISO-8601, zero.
        apply(""""initialBackoff":"3m","claimTtl":"PT2M"""").body!!["retrySpec"].let {
            it["initialBackoff"].asString().assert().isEqualTo("PT3M")
            it["claimTtl"].asString().assert().isEqualTo("PT2M")
            it["maxRetries"].asInt().assert().isEqualTo(5)
        }
        apply(""""initialBackoff":"0s","claimTtl":"500ms"""").body!!["retrySpec"].let {
            it["initialBackoff"].asString().assert().isEqualTo("PT0S")
            it["claimTtl"].asString().assert().isEqualTo("PT0.500S")
        }
        val none = apply(""""maxRetries":0""").body!!
        none["status"].asString().assert().isEqualTo("exhausted")
        apply().status.assert().isEqualTo(200)

        apply(""""maxRetries":-1""").assertMalformed()
        apply(""""maxRetries":"5"""").assertMalformed()
        apply(""""initialBackoff":"soon"""").assertMalformed()
        apply(""""initialBackoff":-1""").assertMalformed()
        // One body shape: a bare number of seconds, as 9.x took it, is not a duration.
        apply(""""initialBackoff":180""").assertMalformed()
        apply(""""initialBackoff":"-1m"""").assertMalformed()
        apply(""""claimTtl":true""").assertMalformed()
        client.act(OpsPaths.APPLY_RETRY_SPEC, "absent").status.assert().isEqualTo(404)
    }

    @Test
    fun `the actions are authenticated and authorized`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val denied = client.act(OpsPaths.SKIP, "a", user = "mallory")
        denied.status.assert().isEqualTo(403)
        denied.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        denied.body!!["detail"].asString().assert().isEqualTo("Mallory may not.")
        ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "a"))!!.request.assert().isNull()
        actions.single().first.assert().isEqualTo("mallory")

        val anonymous = client.act(OpsPaths.RETRY, "a", user = null)
        anonymous.status.assert().isEqualTo(401)
        anonymous.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_UNAUTHENTICATED)
        client.post().uri("${OpsPaths.PARKED}/${OpsPaths.RETRY}")
            .header("X-User", "alice").header("Accept", "text/html").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{${named("a")}}").answer().status.assert().isEqualTo(406)
        // Without an authorizer every action is refused, never allowed by default (review A3-3).
        val unauthorized = WebTestClient
            .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(other)), cartManifest(), null, headerCallers))
            .build()
        val refused = unauthorized.act(OpsPaths.RETRY, "a")
        refused.status.assert().isEqualTo(403)
        refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        refused.body!!["detail"].asString().assert().contains("no CommandAuthorizer")
        // Nor is any read (review R2.3-9).
        unauthorized.read("a").status.assert().isEqualTo(403)
        val list = unauthorized.list()
        list.status.assert().isEqualTo(403)
        list.body!!["detail"].asString().assert().contains("no CommandAuthorizer")
        ledger.parked(SubscriptionId("shop", NAME), type.ref(TENANT, "a"))!!.request.assert().isNull()
    }

    @Test
    fun `a caller sees and acts on its own tenant's entries alone, a cross-scope caller on every tenant's`(): Unit =
        runBlocking {
            park("a")
            ledger.park(
                ParkedAggregate(SubscriptionId("shop", NAME), type.ref("other", "b"), 0, 1, "b failed", FAILED_AT)
            )
            ledger.park(
                ParkedAggregate(SubscriptionId("shop", NAME), type.ref("other", "c"), 0, 1, "c failed", FAILED_AT)
            )
            val client = client(other)
            val listed = client.list(""""limit":1""")
            listed.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("a")
            listed.body!!["next"].asString().assert().isEqualTo(encodeCursor(ParkedKey(ID, type.ref(TENANT, "a"))))
            client.list(""""after":"${listed.body!!["next"].asString()}"""").body!!["entries"].toList()
                .assert().isEmpty()
            client.read("b", tenant = "other").status.assert().isEqualTo(404)
            listOf(OpsPaths.RETRY, OpsPaths.SKIP).forEach { action ->
                client.act(action, "b", tenant = "other").status.assert().isEqualTo(404)
            }
            client.act(OpsPaths.MARK_RECOVERABLE, "b", """"recoverable":"unrecoverable"""", tenant = "other")
                .status.assert().isEqualTo(404)
            client.act(OpsPaths.APPLY_RETRY_SPEC, "b", """"maxRetries":0""", tenant = "other").status.assert()
                .isEqualTo(404)
            ledger.parked(SubscriptionId("shop", NAME), type.ref("other", "b"))!!.let {
                it.request.assert().isNull()
                it.recoverable.assert().isEqualTo(Recoverable.UNKNOWN)
                it.retrySpec.assert().isNull()
            }
            // Refused before it is authorized: the authorizer never hears of another tenant's entry.
            actions.map { it.second.ref.tenantId }.toSet().assert().containsExactly(TENANT)

            val admin = client.post().uri("${OpsPaths.PARKED}/${OpsPaths.LIST}")
                .headers {
                    it.set("X-User", "root")
                    it.set("X-Admin", "true")
                    it.set("Content-Type", "application/json")
                }.bodyValue("""{"limit":10}""").answer()
            admin.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert()
                .containsExactly("a", "b", "c")
            client.post().uri("${OpsPaths.PARKED}/${OpsPaths.SKIP}")
                .headers {
                    it.set("X-User", "root")
                    it.set("X-Admin", "true")
                    it.set("Content-Type", "application/json")
                }.bodyValue("""{${named("b", "other")},"failedAt":$FAILED_AT}""").answer()
                .status.assert().isEqualTo(202)
        }

    @Test
    fun `a ledger that is unavailable is a problem the client retries after a while (R2_3-8)`() {
        val down = object : FailureLedger by ledger {
            override suspend fun parked(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? =
                throw StorageUnavailableException("The ledger is unavailable.")
        }
        val broken = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), down, listOf(failing), options)
        val answer = WebTestClient
            .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(broken)), cartManifest(), authorizer, headerCallers))
            .build()
            .read("a")
        answer.status.assert().isEqualTo(503)
        answer.contentType.assert().contains("problem+json")
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
        answer.headers.getFirst("Retry-After").assert().isEqualTo("1")
    }

    private fun Answer.assertMalformed() {
        status.assert().isEqualTo(400)
        body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
    }

    private suspend fun eventually(condition: suspend () -> Boolean) {
        withTimeout(10.seconds) {
            while (!condition()) {
                delay(5)
            }
        }
    }

    private companion object {
        const val NAME = "CartProjector.onAdded"
        const val KEY = "shop.$NAME"
        val ID = SubscriptionId("shop", NAME)
        const val FAILED_AT = 1_700_000_000_000L
    }
}
