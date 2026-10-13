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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.StockItems
import me.ahoo.wow.runtime.command.peer
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Scheduled commands on service nodes (decision D166), with in-memory backends and the example order of
 * [TimerFixtures]: an unpaid order cancels itself, a payment cancels the timer, a cancellation racing a payment is
 * refused by the order's state, a timer sent again is deduplicated, a handover moves the timers to the next owner, a
 * saga's timers, a decision that only schedules, a deleted order's timers, and a timer to another context.
 */
class ScheduledCommandsTest {
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun node(
        name: String,
        backends: TimedBackends,
        subscriptions: SubscriptionFactory = SubscriptionFactory.NONE,
        topics: SourceTopics? = null,
    ): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel()),
        shopManifest(),
        backends,
        channel,
        options = fastNodeOptions().copy(timers = TimerOptions(pollInterval = 20.milliseconds, lookahead = 1.seconds)),
        subscriptions = subscriptions,
        contracts = ContractDirectory(shopManifest(), listOf(peer(stockManifest()))),
        topics = topics,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.send(id: String, body: Any, wait: WaitPlan = WaitPlan.COMMITTED): CommandResult {
        val context = context(SHOP)
        val requestId = "$id-${body.javaClass.simpleName}-${System.nanoTime()}"
        return context.coordinator.execute(context.models.single(), Command(requestId, requestId, order(id), body, wait = wait))
    }

    private suspend fun ServiceNode.streams(id: String): List<EventStreamRecord> =
        context(SHOP).backends.log.read(order(id)).toList()

    private suspend fun ServiceNode.timer(owner: AggregateRef, key: String): Timer? =
        context(SHOP).backends.timers!!.find(Timers.ownerOf(owner), key)

    private fun List<EventStreamRecord>.eventNames(): List<String> = flatMap { stream -> stream.events.map { it.name } }

    @Test
    fun `an unpaid order cancels itself when its timer is due`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 200)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        val timer = checkNotNull(node.timer(order("o-1"), PAY_TIMEOUT))
        timer.outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        timer.id.assert().isEqualTo("${order("o-1")}#$PAY_TIMEOUT@1")
        val streams = node.streams("o-1")
        streams.eventNames().assert().containsExactly("shop.order.OrderPlaced", "shop.order.OrderCancelled")
        val cancelled = streams.last()
        cancelled.requestId.assert().isEqualTo(timer.id)
        cancelled.commandType.assert().isEqualTo("shop.order.CancelOrder")
        cancelled.causation?.causationId.assert().isEqualTo("${order("o-1")}@1")
        cancelled.causation?.correlationId.assert().isEqualTo(streams.first().commandId)
        cancelled.commitTime.assert().isGreaterThanOrEqualTo(streams.first().commitTime + 200)
    }

    @Test
    fun `paying before the timeout cancels the timer`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.PENDING }
        node.send("o-1", PayOrder())
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.CANCELLED }
        node.streams("o-1").eventNames().assert().containsExactly("shop.order.OrderPlaced", "shop.order.OrderPaid")
    }

    @Test
    fun `a timer racing a payment is refused by the order's state and is done`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 300))
        // Paid without the cancel, as if it reached the store only after the timer was sent.
        node.send("o-1", PayOrder(cancelTimeout = false))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        node.timer(order("o-1"), PAY_TIMEOUT)?.outcome.assert().isEqualTo(TimerOutcomes.REJECTED)
        node.streams("o-1").eventNames().assert().containsExactly("shop.order.OrderPlaced", "shop.order.OrderPaid")
        node.context(SHOP).backends.ledger.list(TimerOptions.PARKED_SUBSCRIPTION).assert().isEmpty()
    }

    @Test
    fun `a timer sent again after its outcome was lost is decided once`(): Unit = runBlocking {
        val backends = TimedBackends { LosingFirstOutcome(InMemoryTimerStore()) }
        val node = node("node-1", backends).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 100))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        node.timer(order("o-1"), PAY_TIMEOUT)?.outcome.assert().isEqualTo(TimerOutcomes.ALREADY_COMMITTED)
        node.streams("o-1").eventNames().assert().containsExactly("shop.order.OrderPlaced", "shop.order.OrderCancelled")
    }

    @Test
    fun `a node's timers are sent by the next owner of their partitions`(): Unit = runBlocking {
        val backends = TimedBackends()
        val first = node("node-1", backends).also { it.start() }
        first.send("o-1", PlaceOrder(payWithinMillis = 1_500))
        eventually { first.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.PENDING }
        first.stop()
        first.context(SHOP).timers!!.runner.partitions.assert().isEmpty()
        val second = node("node-2", backends).also { it.start() }
        eventually { second.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        second.timer(order("o-1"), PAY_TIMEOUT)?.outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        second.streams("o-1").eventNames().assert().containsExactly("shop.order.OrderPlaced", "shop.order.OrderCancelled")
    }

    @Test
    fun `a saga's timer is written with a deterministic id and sent`(): Unit = runBlocking {
        val bindings = SubscriptionBindings(mapOf(SHOP to listOf(reminderSaga(afterMillis = 100))))
        val node = node("node-1", TimedBackends(), bindings).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        val owner = Timers.sagaOwner("ReminderSaga.onOrderPlaced", order("o-1"))
        val store = node.context(SHOP).backends.timers!!
        eventually { store.find(owner, "remind")?.status == TimerStatus.FIRED }
        val timer = checkNotNull(store.find(owner, "remind"))
        timer.id.assert().isEqualTo(Timers.sagaTimerId(ExecutionId("ReminderSaga.onOrderPlaced", order("o-1"), 1), 0))
        timer.outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        node.streams("o-1").last().events.single().payload.assert().isEqualTo("""{"reason":"reminder"}""")
    }

    @Test
    fun `a decision that only schedules commits, is waited for, and its timer is sent`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        val reminded = node.send("o-1", Remind(at = System.currentTimeMillis() + 100), wait = WaitPlan.SNAPSHOT)
        reminded.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        node.streams("o-1")[1].events.assert().isEmpty()
        eventually { node.timer(order("o-1"), "remind")?.status == TimerStatus.FIRED }
        node.streams("o-1").last().events.single().name.assert().isEqualTo("shop.order.OrderCancelled")
    }

    @Test
    fun `deleting an order cancels its timers`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        node.send("o-1", DeleteAggregate)
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.CANCELLED }
    }

    @Test
    fun `a timer to another context goes through its command topic`(): Unit = runBlocking {
        val topics = InMemoryTopics(2).also { it.create(STOCK_COMMANDS, STOCK_PARTITIONS) }
        val stock = StockItems()
        ServiceNode(
            "stock-service",
            "stock-1",
            "stock-1.internal:9090",
            listOf(stock.model),
            stockManifest(),
            InMemoryBackends(STOCK_PARTITIONS),
            channel,
            options = fastNodeOptions(),
            contracts = ContractDirectory(stockManifest()),
            topics = SourceTopics(topics, PREFIX, topics, intake = true),
        ).also {
            nodes += it
            channel.bind(it.address, it.forwardHandler)
            it.start()
        }
        val shop = node("shop-1", TimedBackends(), topics = SourceTopics(topics, PREFIX, topics)).also { it.start() }
        shop.send("o-1", PlaceOrder(payWithinMillis = 60_000, reserve = 2))
        eventually { shop.timer(order("o-1"), "reserve")?.status == TimerStatus.FIRED }
        shop.timer(order("o-1"), "reserve")?.outcome.assert().isEqualTo(TimerOutcomes.SENT)
        eventually { stock.decided.size == 1 }
        stock.decided.single().assert().isEqualTo("${order("o-1")}#reserve@1")
        topics.messages(STOCK_COMMANDS).single().key.assert().isEqualTo(ITEM.ref(TENANT, "o-1").toString())
    }

    /** A timer store that loses the first outcome recorded as fired, as a crash right after the send would. */
    private class LosingFirstOutcome(private val store: TimerStore) : TimerStore by store {
        @Volatile
        private var lost = false

        override suspend fun update(timer: Timer, expected: TimerStatus): Boolean {
            if (!lost && timer.status == TimerStatus.FIRED) {
                lost = true
                throw IllegalStateException("The node crashed before recording the outcome.")
            }
            return store.update(timer, expected)
        }
    }

    private companion object {
        const val PREFIX = "test."
        const val STOCK_PARTITIONS = 4
        const val STOCK_COMMANDS = PREFIX + "wow.stock.commands"
    }
}
