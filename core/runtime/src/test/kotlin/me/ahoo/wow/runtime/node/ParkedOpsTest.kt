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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.runtime.subscription.CART
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.cart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ParkedOpsTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val failing = AtomicBoolean(true)
    private val handled = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        if (entry.ref.id.startsWith("x") && failing.get()) error("${entry.ref.id} is broken")
        handled += "${entry.ref.id}@${entry.version}"
    }
    private val spec = ParkedRetrySpec(maxRetries = 2, initialBackoff = 1.hours, executionTimeout = 1.seconds)
    private val options = testOptions(
        maxAttempts = 1
    ).copyWith(parked = ParkedRetryOptions(spec, scanInterval = 1.hours))
    private val runtime = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(subscription),
        options
    )
    private val ops = ParkedOps { listOf(ParkedOps.Scope.of(runtime)) }

    @Test
    fun `an operator resumes and skips the entries of the partitions this node runs at once`(): Unit = runBlocking {
        appender.append("x1")
        appender.append("x2")
        runtime.start(setOf(0, 1))
        eventually { ledger.list(NAME).size == 2 }

        val view = ops.get(NAME, cart("x1"))!!
        view.spec.assert().isEqualTo(spec)
        view.status.assert().isEqualTo(ParkedView.Status.SCHEDULED)
        view.nextRetryAt.assert().isEqualTo(view.entry.failedAt + 1.hours.inWholeMilliseconds)
        ops.resume(NAME, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.PARKED)
        failing.set(false)
        ops.resume(NAME, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        ops.resume(NAME, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        ops.skip(NAME, cart("x2")).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        ops.skip(NAME, cart("x2")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        handled.assert().containsExactly("x1@1")
        ops.get(NAME, cart("x1")).assert().isNull()
        ops.get("unknown", cart("x1")).assert().isNull()
        runtime.stop()
    }

    @Test
    fun `an entry of a partition another node runs is left as a request for it`(): Unit = runBlocking {
        park("x1")
        ops.resume(NAME, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.RESUME)
        ops.skip(NAME, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
        ops.get(NAME, cart("x1"))!!.entry.request.assert().isEqualTo(LedgerRequest.SKIP)
        ops.resume(NAME, cart("absent")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        ops.skip(NAME, cart("absent")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
    }

    @Test
    fun `an operator marks an entry and applies a spec to it`(): Unit = runBlocking {
        park("x1")
        ops.markRecoverable(NAME, cart("x1"), Recoverable.UNRECOVERABLE)!!.status
            .assert().isEqualTo(ParkedView.Status.UNRECOVERABLE)
        ops.markRecoverable(NAME, cart("x1"), Recoverable.RECOVERABLE)!!.status
            .assert().isEqualTo(ParkedView.Status.SCHEDULED)
        val none = ParkedRetrySpec(maxRetries = 0)
        val applied = ops.applyRetrySpec(NAME, cart("x1"), none)!!
        applied.spec.assert().isEqualTo(none)
        applied.status.assert().isEqualTo(ParkedView.Status.EXHAUSTED)
        applied.nextRetryAt.assert().isNull()
        ops.applyRetrySpec(NAME, cart("x1"), null)!!.spec.assert().isEqualTo(spec)
        ops.markRecoverable(NAME, cart("absent"), Recoverable.RECOVERABLE).assert().isNull()
        ops.applyRetrySpec(NAME, cart("absent"), none).assert().isNull()
        ledger.park(ParkedAggregate(NAME, cart("gone"), 1, "${ParkedAggregate.UNRECOVERABLE}: gone", 0))
        ops.get(NAME, cart("gone"))!!.status.assert().isEqualTo(ParkedView.Status.UNRECOVERABLE)
    }

    @Test
    fun `entries are listed in pages by subscription and aggregate`(): Unit = runBlocking {
        val other = Subscription("$NAME.other", SubscriptionFilter("shop")) { }
        val runtimes = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), ledger, listOf(subscription, other))
        val listing = ParkedOps { listOf(ParkedOps.Scope.of(runtimes)) }
        listing.subscriptions.assert().containsExactly(NAME, "$NAME.other")
        listOf("c", "a", "b").forEach { park(it) }
        listOf("z", "y").forEach { park(it, subscription = "$NAME.other") }

        val first = listing.list(limit = 2)
        first.entries.map { it.entry.ref.id }.assert().containsExactly("a", "b")
        first.next.assert().isEqualTo(ParkedKey(NAME, cart("b")))
        val second = listing.list(after = first.next, limit = 2)
        second.entries.map { it.entry.subscription to it.entry.ref.id }
            .assert().containsExactly(NAME to "c", "$NAME.other" to "y")
        val third = listing.list(after = second.next, limit = 2)
        third.entries.map { it.entry.ref.id }.assert().containsExactly("z")
        third.next.assert().isNull()
        listing.list(subscription = "$NAME.other").entries.map { it.entry.ref.id }.assert().containsExactly("y", "z")
        listing.list(subscription = NAME, after = ParkedKey(NAME, cart("a"))).entries.map { it.entry.ref.id }
            .assert().containsExactly("b", "c")
        listing.list(subscription = "none").entries.assert().isEmpty()
        assertThrownBy<IllegalArgumentException> { runBlocking { listing.list(limit = 0) } }
    }

    @Test
    fun `a built-in subscription's entry is found in the scope of its aggregate's context`(): Unit = runBlocking {
        val shop = InMemoryFailureLedger()
        val stock = InMemoryFailureLedger()
        val stockType = AggregateType("stock", "item")
        val shopRuntime = SubscriptionRuntime(
            "shop",
            log,
            InMemoryCheckpointStore(),
            shop,
            listOf(Subscription("snapshot", SubscriptionFilter("shop")) { }),
        )
        val stockRuntime = SubscriptionRuntime(
            "stock",
            log,
            InMemoryCheckpointStore(),
            stock,
            listOf(Subscription("snapshot", SubscriptionFilter("stock")) { }),
        )
        // Both ledgers hold an entry of the stock item, as when two contexts share one table.
        val item = stockType.ref("t", "i")
        shop.park(ParkedAggregate("snapshot", item, 1, "in the shop's", 0))
        stock.park(ParkedAggregate("snapshot", item, 1, "in the stock's", 0))
        val both = ParkedOps { listOf(ParkedOps.Scope.of(shopRuntime), ParkedOps.Scope.of(stockRuntime)) }
        both.get("snapshot", item)!!.entry.reason.assert().isEqualTo("in the stock's")
        both.list("snapshot").entries.size.assert().isEqualTo(1)
        both.skip("snapshot", item).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
        stock.parked("snapshot", item)!!.request.assert().isEqualTo(LedgerRequest.SKIP)
        shop.parked("snapshot", item)!!.request.assert().isNull()
        both.subscriptions.assert().containsExactly("snapshot")
    }

    private suspend fun park(id: String, subscription: String = NAME) {
        ledger.park(ParkedAggregate(subscription, CART.ref("t", id), 1, "$id failed", 1_700_000_000_000L))
    }

    private companion object {
        const val NAME = "ops"
    }
}
