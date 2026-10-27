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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.http.Answer
import me.ahoo.wow.http.TENANT
import me.ahoo.wow.http.answer
import me.ahoo.wow.http.cartManifest
import me.ahoo.wow.http.headerCallers
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.manifest.FrameworkErrors
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
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
    private val authorizer = CommandAuthorizer { caller, action ->
        actions += caller.subject.orEmpty() to action
        if (caller.subject == "mallory") Authorization.Denied("Mallory may not.") else Authorization.Allowed
    }

    private fun client(runtime: SubscriptionRuntime): WebTestClient = WebTestClient
        .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(runtime)), cartManifest(), authorizer, headerCallers))
        .build()

    @AfterEach
    fun stop(): Unit = runBlocking { owner.stop() }

    private fun entry(id: String) = "${OpsPaths.PARKED}/$NAME/shop/cart/$TENANT/$id"

    private fun WebTestClient.get(path: String, user: String? = "alice"): Answer =
        get().uri(path).headers { if (user != null) it.set("X-User", user) }.answer()

    private fun WebTestClient.post(path: String, body: String? = null, user: String = "alice", json: Boolean = true) =
        post().uri(path).headers {
            it.set("X-User", user)
            it.set("Content-Type", if (json) "application/json" else "text/plain")
        }.let { if (body == null) it else it.bodyValue(body) }.answer()

    private suspend fun park(id: String, version: Int = 1) {
        ledger.park(ParkedAggregate(NAME, type.ref(TENANT, id), version, "$id failed", FAILED_AT))
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
    fun `entries are listed in pages and read one at a time`(): Unit = runBlocking {
        listOf("b", "a", "c").forEach { park(it) }
        val client = client(other)
        val first = client.get("${OpsPaths.PARKED}?limit=2")
        first.status.assert().isEqualTo(200)
        first.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("a", "b")
        val next = first.body!!["next"].asString()
        next.assert().isEqualTo(encodeCursor(ParkedKey(NAME, type.ref(TENANT, "b"))))
        val second = client.get("${OpsPaths.PARKED}?subscription=$NAME&after=$next")
        second.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("c")
        second.body!!["next"].isNull.assert().isTrue()

        val one = client.get(entry("a"))
        one.status.assert().isEqualTo(200)
        val body = one.body!!
        body["subscription"].asString().assert().isEqualTo(NAME)
        body["aggregate"]["context"].asString().assert().isEqualTo("shop")
        body["aggregate"]["name"].asString().assert().isEqualTo("cart")
        body["aggregate"]["tenantId"].asString().assert().isEqualTo(TENANT)
        body["fromVersion"].asInt().assert().isEqualTo(1)
        body["reason"].asString().assert().isEqualTo("a failed")
        body["failedAt"].asLong().assert().isEqualTo(FAILED_AT)
        body["retries"].asInt().assert().isEqualTo(0)
        body["retryAt"].isNull.assert().isTrue()
        body["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(3)
        body["retrySpec"]["initialBackoff"].asString().assert().isEqualTo("PT1H")
        body["retrySpec"]["timeout"].asString().assert().isEqualTo("PT1M")
        body["retrySpec"]["applied"].asBoolean().assert().isFalse()
        body["recoverable"].asString().assert().isEqualTo("UNKNOWN")
        body["status"].asString().assert().isEqualTo("SCHEDULED")
        body["nextRetryAt"].asLong().assert().isEqualTo(FAILED_AT + 1.hours.inWholeMilliseconds)

        client.get(entry("absent")).status.assert().isEqualTo(404)
        client.get("${OpsPaths.PARKED}?after=bm90LWEtY3Vyc29y").status.assert().isEqualTo(400)
        client.get("${OpsPaths.PARKED}?limit=0").status.assert().isEqualTo(400)
        client.get("${OpsPaths.PARKED}?limit=x").status.assert().isEqualTo(400)
        client.get(OpsPaths.PARKED, user = null).status.assert().isEqualTo(401)
        // Reads are authorized as wow.ledger.Read on each entry's aggregate (review R2.3-9).
        actions.map { it.second.type.name }.toSet().assert().containsExactly(OpsPaths.READ)
        actions.first().second.ref.assert().isEqualTo(type.ref(TENANT, "a"))
        val hidden = client.get("${OpsPaths.PARKED}?limit=2", user = "mallory")
        hidden.status.assert().isEqualTo(200)
        hidden.body!!["entries"].toList().assert().isEmpty()
        val refused = client.get(entry("a"), user = "mallory")
        refused.status.assert().isEqualTo(403)
        refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
    }

    @Test
    fun `resume and skip run on the owner and are requests elsewhere`(): Unit = runBlocking {
        park("gone")
        append("x")
        owner.start(setOf(0, 1))
        eventually { ledger.isParked(NAME, type.ref(TENANT, "x")) }
        val local = client(owner)

        // Nothing of "gone" is in the log: resuming it handles nothing and resolves it.
        val resolved = local.post("${entry("gone")}/${OpsPaths.RESUME}")
        resolved.status.assert().isEqualTo(200)
        resolved.body!!["outcome"].asString().assert().isEqualTo("RESOLVED")
        resolved.body!!.has("entry").assert().isFalse()
        val parked = local.post("${entry("x")}/${OpsPaths.RESUME}")
        parked.status.assert().isEqualTo(200)
        parked.body!!["outcome"].asString().assert().isEqualTo("PARKED")
        parked.body!!["entry"]["reason"].asString().assert().contains("x is broken")

        val remote = client(other)
        val requested = remote.post("${entry("x")}/${OpsPaths.RESUME}")
        requested.status.assert().isEqualTo(202)
        requested.body!!["outcome"].asString().assert().isEqualTo("REQUESTED")
        requested.body!!["entry"]["request"].asString().assert().isEqualTo("RESUME")
        remote.post("${entry("x")}/${OpsPaths.SKIP}").body!!["entry"]["request"].asString().assert().isEqualTo("SKIP")
        ledger.parked(NAME, type.ref(TENANT, "x"))!!.request.assert().isEqualTo(LedgerRequest.SKIP)

        val skipped = local.post("${entry("x")}/${OpsPaths.SKIP}")
        skipped.status.assert().isEqualTo(200)
        skipped.body!!["outcome"].asString().assert().isEqualTo("RESOLVED")
        local.post("${entry("x")}/${OpsPaths.SKIP}").status.assert().isEqualTo(404)
        local.post("${entry("x")}/${OpsPaths.RESUME}").status.assert().isEqualTo(404)

        actions.map { it.second.type.name }.assert().containsExactly(
            OpsCommands.RESUME,
            OpsCommands.RESUME,
            OpsCommands.RESUME,
            OpsCommands.SKIP,
            OpsCommands.SKIP,
            OpsCommands.SKIP,
            OpsCommands.RESUME,
        )
        actions.first().second.ref.assert().isEqualTo(type.ref(TENANT, "gone"))
        actions.first().second.scope.assert().isEqualTo(Scope("alice"))
    }

    @Test
    fun `parked timers are listed, resumed and skipped by an operator`(): Unit = runBlocking {
        val timers = InMemoryTimerStore()
        val loop = TimerLoop(TimerOptions(), Clock.systemUTC())
        val runner = TimerRunner("shop", timers, ledger, { TimerOutcomes.COMMITTED }, loop)
        val client = WebTestClient
            .bindToRouterFunction(
                parkedRoutes(
                    ParkedOps.of(listOf(owner), mapOf(runner to 2)),
                    cartManifest(),
                    authorizer,
                    headerCallers,
                ),
            )
            .build()
        try {
            parkTimer(timers, "pay", "t-1")
            parkTimer(timers, "remind", "t-2")
            val subscription = TimerOptions.PARKED_SUBSCRIPTION
            val listed = client.get("${OpsPaths.PARKED}?subscription=$subscription")
            listed.status.assert().isEqualTo(200)
            listed.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert()
                .containsExactly("t-1", "t-2")
            val one = client.get(timerEntry("t-1"))
            one.status.assert().isEqualTo(200)
            one.body!!["reason"].asString().assert().isEqualTo("pay: down")
            // Nothing retries a parked timer: it waits for an operator.
            one.body!!["status"].asString().assert().isEqualTo("EXHAUSTED")

            val resumed = client.post("${timerEntry("t-1")}/${OpsPaths.RESUME}")
            resumed.status.assert().isEqualTo(200)
            resumed.body!!["outcome"].asString().assert().isEqualTo("RESOLVED")
            timers.find(type.ref(TENANT, "o-1"), "pay")!!.status.assert().isEqualTo(TimerStatus.PENDING)
            val skipped = client.post("${timerEntry("t-2")}/${OpsPaths.SKIP}")
            skipped.status.assert().isEqualTo(200)
            skipped.body!!["outcome"].asString().assert().isEqualTo("RESOLVED")
            val remind = timers.find(type.ref(TENANT, "o-1"), "remind")!!
            remind.status.assert().isEqualTo(TimerStatus.CANCELLED)
            remind.outcome.assert().isEqualTo(TimerOutcomes.SKIPPED)
            client.get("${OpsPaths.PARKED}?subscription=$subscription").body!!["entries"].toList().assert().isEmpty()
            client.post("${timerEntry("t-1")}/${OpsPaths.RESUME}").status.assert().isEqualTo(404)
        } finally {
            loop.close()
        }
    }

    private fun timerEntry(id: String) = "${OpsPaths.PARKED}/${TimerOptions.PARKED_SUBSCRIPTION}/shop/cart/$TENANT/$id"

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
        timers.settle(listOf(TimerChange(timer, parked))).assert().isEqualTo(1)
        ledger.park(ParkedAggregate(TimerOptions.PARKED_SUBSCRIPTION, timer.target, 1, "$key: down", FAILED_AT))
    }

    @Test
    fun `an operator marks an entry recoverable or not`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val marked = client.post("${entry("a")}/${OpsPaths.MARK_RECOVERABLE}", """{"recoverable":"unrecoverable"}""")
        marked.status.assert().isEqualTo(200)
        marked.body!!["recoverable"].asString().assert().isEqualTo("UNRECOVERABLE")
        marked.body!!["status"].asString().assert().isEqualTo("UNRECOVERABLE")
        marked.body!!["nextRetryAt"].isNull.assert().isTrue()
        ledger.parked(NAME, type.ref(TENANT, "a"))!!.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)

        val mark = "${entry("a")}/${OpsPaths.MARK_RECOVERABLE}"
        client.post(mark, """{"recoverable":"maybe"}""").assertMalformed()
        client.post(mark, """{}""").assertMalformed()
        client.post(mark, null).assertMalformed()
        client.post(mark, """{"recoverable":1}""").assertMalformed()
        client.post(mark, "[]").assertMalformed()
        client.post(mark, "{").assertMalformed()
        client.post(mark, """{"recoverable":"RECOVERABLE"}""", json = false).status.assert().isEqualTo(415)
        client.post("${entry("absent")}/${OpsPaths.MARK_RECOVERABLE}", """{"recoverable":"RECOVERABLE"}""")
            .status.assert().isEqualTo(404)
        actions.map { it.second.type.name }.toSet().assert().containsExactly(OpsCommands.MARK_RECOVERABLE)
    }

    @Test
    fun `an operator applies a retry spec, the members left out kept`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val apply = "${entry("a")}/${OpsPaths.APPLY_RETRY_SPEC}"
        val applied = client.post(apply, """{"maxRetries":5,"initialBackoff":"10s"}""")
        applied.status.assert().isEqualTo(200)
        applied.body!!["retrySpec"]["maxRetries"].asInt().assert().isEqualTo(5)
        applied.body!!["retrySpec"]["initialBackoff"].asString().assert().isEqualTo("PT10S")
        applied.body!!["retrySpec"]["timeout"].asString().assert().isEqualTo("PT1M")
        applied.body!!["retrySpec"]["applied"].asBoolean().assert().isTrue()
        applied.body!!["nextRetryAt"].asLong().assert().isEqualTo(FAILED_AT + 10_000)
        ledger.parked(NAME, type.ref(TENANT, "a"))!!.retrySpec.assert()
            .isEqualTo(ParkedRetrySpec(5, 10.seconds, 1.minutes))

        // 9.x's seconds, the short forms, ISO-8601 and zero.
        client.post(apply, """{"initialBackoff":180,"timeout":"PT2M"}""").body!!["retrySpec"].let {
            it["initialBackoff"].asString().assert().isEqualTo("PT3M")
            it["timeout"].asString().assert().isEqualTo("PT2M")
            it["maxRetries"].asInt().assert().isEqualTo(5)
        }
        client.post(apply, """{"initialBackoff":"0s","timeout":"500ms"}""").body!!["retrySpec"].let {
            it["initialBackoff"].asString().assert().isEqualTo("PT0S")
            it["timeout"].asString().assert().isEqualTo("PT0.500S")
        }
        val none = client.post(apply, """{"maxRetries":0}""").body!!
        none["status"].asString().assert().isEqualTo("EXHAUSTED")
        client.post(apply, null).status.assert().isEqualTo(200)

        client.post(apply, """{"maxRetries":-1}""").assertMalformed()
        client.post(apply, """{"maxRetries":"5"}""").assertMalformed()
        client.post(apply, """{"initialBackoff":"soon"}""").assertMalformed()
        client.post(apply, """{"initialBackoff":-1}""").assertMalformed()
        client.post(apply, """{"timeout":true}""").assertMalformed()
        client.post("${entry("absent")}/${OpsPaths.APPLY_RETRY_SPEC}", "{}").status.assert().isEqualTo(404)
    }

    @Test
    fun `the actions are authenticated and authorized`(): Unit = runBlocking {
        park("a")
        val client = client(other)
        val denied = client.post("${entry("a")}/${OpsPaths.SKIP}", user = "mallory")
        denied.status.assert().isEqualTo(403)
        denied.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        denied.body!!["detail"].asString().assert().isEqualTo("Mallory may not.")
        ledger.parked(NAME, type.ref(TENANT, "a"))!!.request.assert().isNull()
        actions.single().first.assert().isEqualTo("mallory")

        val anonymous = client.post().uri("${entry("a")}/${OpsPaths.RESUME}").answer()
        anonymous.status.assert().isEqualTo(401)
        anonymous.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_UNAUTHENTICATED)
        client.post().uri("${OpsPaths.PARKED}/$NAME/shop/cart/$TENANT/a/${OpsPaths.RESUME}")
            .header("X-User", "alice").header("Accept", "text/html").answer().status.assert().isEqualTo(406)
        // Without an authorizer every action is refused, never allowed by default (review A3-3).
        val unauthorized = WebTestClient
            .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(other)), cartManifest(), null, headerCallers))
            .build()
        val refused = unauthorized.post("${entry("a")}/${OpsPaths.RESUME}")
        refused.status.assert().isEqualTo(403)
        refused.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        refused.body!!["detail"].asString().assert().contains("no CommandAuthorizer")
        // Nor is any read (review R2.3-9).
        unauthorized.get(entry("a")).status.assert().isEqualTo(403)
        val list = unauthorized.get(OpsPaths.PARKED)
        list.status.assert().isEqualTo(403)
        list.body!!["detail"].asString().assert().contains("no CommandAuthorizer")
        ledger.parked(NAME, type.ref(TENANT, "a"))!!.request.assert().isNull()
    }

    @Test
    fun `a caller sees and acts on its own tenant's entries alone, a cross-scope caller on every tenant's`(): Unit =
        runBlocking {
            park("a")
            ledger.park(ParkedAggregate(NAME, type.ref("other", "b"), 1, "b failed", FAILED_AT))
            ledger.park(ParkedAggregate(NAME, type.ref("other", "c"), 1, "c failed", FAILED_AT))
            val client = client(other)
            val foreign = "${OpsPaths.PARKED}/$NAME/shop/cart/other/b"

            val listed = client.get("${OpsPaths.PARKED}?limit=1")
            listed.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert().containsExactly("a")
            listed.body!!["next"].asString().assert().isEqualTo(encodeCursor(ParkedKey(NAME, type.ref(TENANT, "a"))))
            client.get("${OpsPaths.PARKED}?after=${listed.body!!["next"].asString()}").body!!["entries"].toList()
                .assert().isEmpty()
            client.get(foreign).status.assert().isEqualTo(404)
            listOf(OpsPaths.RESUME, OpsPaths.SKIP).forEach { action ->
                client.post("$foreign/$action").status.assert().isEqualTo(404)
            }
            client.post("$foreign/${OpsPaths.MARK_RECOVERABLE}", """{"recoverable":"UNRECOVERABLE"}""")
                .status.assert().isEqualTo(404)
            client.post("$foreign/${OpsPaths.APPLY_RETRY_SPEC}", """{"maxRetries":0}""").status.assert().isEqualTo(404)
            ledger.parked(NAME, type.ref("other", "b"))!!.let {
                it.request.assert().isNull()
                it.recoverable.assert().isEqualTo(Recoverable.UNKNOWN)
                it.retrySpec.assert().isNull()
            }
            // Refused before it is authorized: the authorizer never hears of another tenant's entry.
            actions.map { it.second.ref.tenantId }.toSet().assert().containsExactly(TENANT)

            val admin = client.get().uri("${OpsPaths.PARKED}?limit=10")
                .headers {
                    it.set("X-User", "root")
                    it.set("X-Admin", "true")
                }.answer()
            admin.body!!["entries"].toList().map { it["aggregate"]["id"].asString() }.assert()
                .containsExactly("a", "b", "c")
            client.post().uri("$foreign/${OpsPaths.SKIP}")
                .headers {
                    it.set("X-User", "root")
                    it.set("X-Admin", "true")
                }.answer().status.assert().isEqualTo(202)
        }

    @Test
    fun `a ledger that is unavailable is a problem the client retries after a while (R2_3-8)`() {
        val down = object : FailureLedger by ledger {
            override suspend fun parked(subscription: String, ref: AggregateRef): ParkedAggregate? =
                throw StorageUnavailableException("The ledger is unavailable.")
        }
        val broken = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), down, listOf(failing), options)
        val answer = WebTestClient
            .bindToRouterFunction(parkedRoutes(ParkedOps.of(listOf(broken)), cartManifest(), authorizer, headerCallers))
            .build()
            .get(entry("a"))
        answer.status.assert().isEqualTo(503)
        answer.contentType.assert().contains("problem+json")
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.STORAGE_UNAVAILABLE)
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
        const val FAILED_AT = 1_700_000_000_000L
    }
}
