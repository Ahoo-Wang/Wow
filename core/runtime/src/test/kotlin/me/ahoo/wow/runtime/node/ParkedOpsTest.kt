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
import me.ahoo.wow.runtime.subscription.SubscriptionId
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
    private val spec = ParkedRetrySpec(maxRetries = 2, initialBackoff = 1.hours, claimTtl = 1.seconds)
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
    fun `an operator retries and skips the entries of the partitions this node runs at once`(): Unit = runBlocking {
        appender.append("x1")
        appender.append("x2")
        runtime.start(setOf(0, 1))
        eventually { ledger.list(NAME).size == 2 }

        val view = ops.get(ID, cart("x1"))!!
        view.spec.assert().isEqualTo(spec)
        view.status.assert().isEqualTo(ParkedView.Status.SCHEDULED)
        view.nextRetryAt.assert().isEqualTo(view.entry.failedAt + 1.hours.inWholeMilliseconds)
        ops.retry(ID, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.PARKED)
        failing.set(false)
        ops.retry(ID, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        ops.retry(ID, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        val x2 = ledger.parked(NAME, cart("x2"))!!.failedAt
        ops.skip(ID, cart("x2"), x2 + 1).assert().isEqualTo(ParkedOps.Outcome.STALE)
        ops.skip(ID, cart("x2"), x2).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
        ops.skip(ID, cart("x2"), x2).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        handled.assert().containsExactly("x1@1")
        ops.get(ID, cart("x1")).assert().isNull()
        ops.get(SubscriptionId("shop", "unknown"), cart("x1")).assert().isNull()
        runtime.stop()
    }

    @Test
    fun `an entry of a partition another node runs is left as a request for it`(): Unit = runBlocking {
        park("x1")
        ops.retry(ID, cart("x1")).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
        ledger.parked(NAME, cart("x1"))!!.request.assert().isEqualTo(LedgerRequest.RETRY)
        ops.skip(ID, cart("x1"), ledger.parked(NAME, cart("x1"))!!.failedAt).assert()
            .isEqualTo(ParkedOps.Outcome.REQUESTED)
        ops.get(ID, cart("x1"))!!.entry.request.assert().isEqualTo(LedgerRequest.SKIP)
        ops.retry(ID, cart("absent")).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
        ops.skip(ID, cart("absent"), 0).assert().isEqualTo(ParkedOps.Outcome.NOT_PARKED)
    }

    @Test
    fun `an operator marks an entry and applies a spec to it`(): Unit = runBlocking {
        park("x1")
        ops.markRecoverable(ID, cart("x1"), Recoverable.UNRECOVERABLE)!!.status
            .assert().isEqualTo(ParkedView.Status.UNRECOVERABLE)
        ops.markRecoverable(ID, cart("x1"), Recoverable.RECOVERABLE)!!.status
            .assert().isEqualTo(ParkedView.Status.SCHEDULED)
        val none = ParkedRetrySpec(maxRetries = 0)
        val applied = ops.applyRetrySpec(ID, cart("x1"), none)!!
        applied.spec.assert().isEqualTo(none)
        applied.status.assert().isEqualTo(ParkedView.Status.EXHAUSTED)
        applied.nextRetryAt.assert().isNull()
        ops.applyRetrySpec(ID, cart("x1"), null)!!.spec.assert().isEqualTo(spec)
        ops.markRecoverable(ID, cart("absent"), Recoverable.RECOVERABLE).assert().isNull()
        ops.applyRetrySpec(ID, cart("absent"), none).assert().isNull()
        ledger.park(ParkedAggregate(NAME, cart("gone"), 0, 1, "${ParkedAggregate.UNRECOVERABLE}: gone", 0))
        ops.get(ID, cart("gone"))!!.status.assert().isEqualTo(ParkedView.Status.UNRECOVERABLE)
    }

    @Test
    fun `entries are listed in pages by subscription and aggregate`(): Unit = runBlocking {
        val other = Subscription("$NAME.other", SubscriptionFilter("shop")) { }
        val runtimes = SubscriptionRuntime("group", log, InMemoryCheckpointStore(), ledger, listOf(subscription, other))
        val listing = ParkedOps { listOf(ParkedOps.Scope.of(runtimes)) }
        listing.subscriptions.assert().containsExactly(ID, OTHER)
        listOf("c", "a", "b").forEach { park(it) }
        listOf("z", "y").forEach { park(it, subscription = "$NAME.other") }

        val first = listing.list(limit = 2)
        first.entries.map { it.entry.ref.id }.assert().containsExactly("a", "b")
        first.next.assert().isEqualTo(ParkedKey(ID, cart("b")))
        val second = listing.list(after = first.next, limit = 2)
        second.entries.map { it.id to it.entry.ref.id }.assert().containsExactly(ID to "c", OTHER to "y")
        val third = listing.list(after = second.next, limit = 2)
        third.entries.map { it.entry.ref.id }.assert().containsExactly("z")
        third.next.assert().isNull()
        listing.list(subscription = OTHER).entries.map { it.entry.ref.id }.assert().containsExactly("y", "z")
        listing.list(subscription = ID, after = ParkedKey(ID, cart("a"))).entries.map { it.entry.ref.id }
            .assert().containsExactly("b", "c")
        listing.list(subscription = SubscriptionId("shop", "none")).entries.assert().isEmpty()
        assertThrownBy<IllegalArgumentException> { runBlocking { listing.list(limit = 0) } }
    }

    @Test
    fun `each context's subscription of one name is found by its context`(): Unit = runBlocking {
        val shop = InMemoryFailureLedger()
        val stock = InMemoryFailureLedger()
        val stockType = AggregateType("stock", "item")
        val shopRuntime = SubscriptionRuntime(
            "shop",
            log,
            InMemoryCheckpointStore(),
            shop,
            listOf(Subscription("wow.snapshot", SubscriptionFilter("shop")) { }),
        )
        val stockRuntime = SubscriptionRuntime(
            "stock",
            log,
            InMemoryCheckpointStore(),
            stock,
            listOf(Subscription("wow.snapshot", SubscriptionFilter("stock")) { }),
        )
        // Both ledgers hold an entry of the stock item, as when two contexts share one table.
        val item = stockType.ref("t", "i")
        shop.park(ParkedAggregate("wow.snapshot", item, 0, 1, "in the shop's", 0))
        stock.park(ParkedAggregate("wow.snapshot", item, 0, 1, "in the stock's", 0))
        val both = ParkedOps { listOf(ParkedOps.Scope.of(shopRuntime), ParkedOps.Scope.of(stockRuntime)) }
        val shopSnapshot = SubscriptionId("shop", "wow.snapshot")
        val stockSnapshot = SubscriptionId("stock", "wow.snapshot")
        both.get(stockSnapshot, item)!!.entry.reason.assert().isEqualTo("in the stock's")
        both.get(shopSnapshot, item)!!.entry.reason.assert().isEqualTo("in the shop's")
        both.get(stockSnapshot, item)!!.context.assert().isEqualTo("stock")
        both.list(stockSnapshot).entries.map { it.entry.reason }.assert().containsExactly("in the stock's")
        both.skip(stockSnapshot, item, 0).assert().isEqualTo(ParkedOps.Outcome.REQUESTED)
        stock.parked("wow.snapshot", item)!!.request.assert().isEqualTo(LedgerRequest.SKIP)
        shop.parked("wow.snapshot", item)!!.request.assert().isNull()
        both.get(SubscriptionId("other", "wow.snapshot"), item).assert().isNull()
        both.subscriptions.assert().containsExactly(shopSnapshot, stockSnapshot)
    }

    @Test
    fun `each context's subscription of one name is counted apart, by its group, up to a cap`(): Unit = runBlocking {
        val shop = InMemoryFailureLedger()
        val stock = InMemoryFailureLedger()
        fun runtime(group: String, ledger: InMemoryFailureLedger) = SubscriptionRuntime(
            group,
            log,
            InMemoryCheckpointStore(),
            ledger,
            listOf(Subscription("wow.snapshot", SubscriptionFilter(group)) { }),
        )
        repeat(3) { shop.park(ParkedAggregate("wow.snapshot", CART.ref("t", "c-$it"), 0, 1, "failed", 0)) }
        stock.park(ParkedAggregate("wow.snapshot", AggregateType("stock", "item").ref("t", "i"), 0, 1, "failed", 0))
        val both =
            ParkedOps { listOf(ParkedOps.Scope.of(runtime("shop", shop)), ParkedOps.Scope.of(runtime("stock", stock))) }
        val shopSnapshot = ParkedSubscription("shop", "wow.snapshot")
        val stockSnapshot = ParkedSubscription("stock", "wow.snapshot")
        both.groupedSubscriptions.assert().containsExactly(shopSnapshot, stockSnapshot)
        both.count(shopSnapshot, 10).assert().isEqualTo(3)
        both.count(stockSnapshot, 10).assert().isEqualTo(1)
        both.count(shopSnapshot, 2).assert().isEqualTo(2)
        both.count(ParkedSubscription("other", "wow.snapshot"), 10).assert().isZero()
        assertThrownBy<IllegalArgumentException> { runBlocking { both.count(shopSnapshot, 0) } }
    }

    private suspend fun park(id: String, subscription: String = NAME) {
        ledger.park(ParkedAggregate(subscription, CART.ref("t", id), 0, 1, "$id failed", 1_700_000_000_000L))
    }

    private companion object {
        const val NAME = "ops"
        val ID = SubscriptionId("shop", NAME)
        val OTHER = SubscriptionId("shop", "$NAME.other")
    }
}
