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

import me.ahoo.wow.api.client.CommandRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.api.schedule.SetSchedule
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
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
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
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
        clock: Clock = Clock.systemUTC(),
        timers: TimerOptions = TimerOptions(
            pollInterval = 20.milliseconds,
            lookahead = 1.seconds,
            claimTimeout = 300.milliseconds
        ),
    ): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel()),
        shopManifest(),
        backends,
        channel,
        options = fastNodeOptions().copyWith(timers = timers),
        subscriptions = subscriptions,
        contracts = ContractDirectory(shopManifest(), listOf(peer(stockManifest()))),
        topics = topics,
        clock = clock,
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.send(
        id: String,
        body: Any,
        wait: WaitPlan = WaitPlan.COMMITTED,
        headers: Map<String, String> = emptyMap(),
    ): CommandResult {
        val context = context(SHOP)
        val requestId = "$id-${body.javaClass.simpleName}-${System.nanoTime()}"
        return context.coordinator.execute(
            context.models.first(),
            Command(requestId, requestId, order(id), body, headers = headers, wait = wait)
        )
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
        streams.eventNames().assert()
            .containsExactly("shop.order.OrderPlaced", Schedules.SCHEDULE_SET, "shop.order.OrderCancelled")
        val cancelled = streams.last()
        cancelled.requestId.assert().isEqualTo("${timer.id}#1")
        // The target reads which slot it was sent for (decision D198).
        ScheduleContext.of(cancelled.headers).assert().isEqualTo(ScheduleContext(timer.id, PAY_TIMEOUT, 1, timer.dueAt))
        cancelled.commandType.assert().isEqualTo("shop.order.CancelOrder")
        cancelled.causation?.causationId.assert().isEqualTo("${order("o-1")}@1")
        cancelled.causation?.correlationId.assert().isEqualTo(streams.first().commandId)
        cancelled.commitTime.assert().isGreaterThanOrEqualTo(streams.first().commitTime + 200)
    }

    @Test
    fun `a timer's command inherits only the scheduling command's request facts that propagate`(): Unit =
        runBlocking {
            val node = node("node-1", TimedBackends()).also { it.start() }
            val facts = mapOf(
                RequestFacts.APP_ID to "console",
                RequestFacts.REMOTE_IP to "10.0.0.7",
                RequestFacts.USER_AGENT to "shop-app/1.0",
                "X-Trace" to "t-1",
            )
            node.send("o-1", PlaceOrder(payWithinMillis = 100), headers = facts).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
            val timer = checkNotNull(node.timer(order("o-1"), PAY_TIMEOUT))
            val cancelled = node.streams("o-1").last()
            cancelled.commandType.assert().isEqualTo("shop.order.CancelOrder")
            // The application it was scheduled for, and the slot (decision D198); not where the request came from.
            cancelled.headers.assert().isEqualTo(
                mapOf(RequestFacts.APP_ID to "console") +
                    ScheduleContext(timer.id, PAY_TIMEOUT, 1, timer.dueAt).headers(),
            )
        }

    @Test
    fun `paying before the timeout cancels the timer`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.PENDING }
        node.send("o-1", PayOrder())
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.CANCELLED }
        node.streams("o-1").eventNames().assert().containsExactly(
            "shop.order.OrderPlaced",
            Schedules.SCHEDULE_SET,
            "shop.order.OrderPaid",
            Schedules.SCHEDULE_CANCELLED,
        )
    }

    @Test
    fun `a timer racing a payment is refused by the order's state and is done`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 300))
        // Paid without the cancel, as if it reached the store only after the timer was sent.
        node.send("o-1", PayOrder(cancelTimeout = false))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        node.timer(order("o-1"), PAY_TIMEOUT)?.outcome.assert().isEqualTo(TimerOutcomes.REJECTED)
        node.streams("o-1").eventNames().assert()
            .containsExactly("shop.order.OrderPlaced", Schedules.SCHEDULE_SET, "shop.order.OrderPaid")
        node.context(SHOP).backends.ledger.list(TimerOptions.PARKED_SUBSCRIPTION).assert().isEmpty()
    }

    @Test
    fun `a timer sent again after its outcome was lost is decided once`(): Unit = runBlocking {
        val backends = TimedBackends { LosingFirstOutcome(InMemoryTimerStore()) }
        val node = node("node-1", backends).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 100))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        node.timer(order("o-1"), PAY_TIMEOUT)?.outcome.assert().isEqualTo(TimerOutcomes.ALREADY_COMMITTED)
        node.streams("o-1").eventNames().assert()
            .containsExactly("shop.order.OrderPlaced", Schedules.SCHEDULE_SET, "shop.order.OrderCancelled")
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
        second.streams("o-1").eventNames().assert()
            .containsExactly("shop.order.OrderPlaced", Schedules.SCHEDULE_SET, "shop.order.OrderCancelled")
    }

    @Test
    fun `a saga's schedule is a system command to its target, with a deterministic id, and sent`(): Unit = runBlocking {
        val bindings = SubscriptionBindings(mapOf(SHOP to listOf(reminderSaga(afterMillis = 100))))
        val node = node("node-1", TimedBackends(), bindings).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        eventually { node.timer(order("o-1"), "remind")?.status == TimerStatus.FIRED }
        val streams = node.streams("o-1")
        val set = streams[1]
        set.commandType.assert().isEqualTo(Schedules.SET)
        set.requestId.assert()
            .isEqualTo(
                ExecutionSagaContext.derivedId(
                    ExecutionId("ReminderSaga.onOrderPlaced", order("o-1"), 1),
                    order("o-1"),
                    SetSchedule::class.java,
                ),
            )
        val timer = checkNotNull(node.timer(order("o-1"), "remind"))
        timer.id.assert().isEqualTo("${order("o-1")}#remind@2")
        timer.outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        streams.last().events.single().payload.assert().isEqualTo("""{"reason":"reminder"}""")
    }

    @Test
    fun `a saga's schedule of an aggregate that does not exist reaches its OnRejected handler`(): Unit = runBlocking {
        val rejected = CopyOnWriteArrayList<CommandRejected<*>>()
        val saga = ProcessorBinding.saga(
            "GhostSaga",
            "onOrderPlaced",
            OrderState::class.java,
            rejections = listOf(
                RejectionBinding.of("GhostSaga", "onScheduleRejected", SetSchedule::class.java) { rejection, _ ->
                    rejected += rejection
                    emptyList()
                },
            ),
        ) { event, context ->
            if (event.events.any { it is OrderPlaced }) {
                context.schedule("ghost", ScheduleRule.after(1.seconds), CommandRequest.to(order("ghost"), CancelOrder("ghost")))
            }
            emptyList()
        }
        val node = node(
            "node-1",
            TimedBackends(),
            SubscriptionBindings(mapOf(SHOP to listOf(saga)))
        ).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        eventually { rejected.isNotEmpty() }
        rejected.single().ref.assert().isEqualTo(order("ghost"))
        rejected.single().commandType.assert().isEqualTo(Schedules.SET)
        rejected.single().error.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
    }

    @Test
    fun `a schedule of a command type its target does not decide is rejected when set, and stores no timer`(): Unit =
        runBlocking {
            // Review X-7: a misspelt cron schedule fails now, not at its first firing.
            val node = node("node-1", TimedBackends()).also { it.start() }
            node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
            val placed = node.streams("o-1")
            val set = SetSchedule("nightly", ScheduleRule.cron("0 0 3 * * *"), "shop.order.CancelOrdr", "{}")
            val result = node.send("o-1", set)
            result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            result.error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
            result.error!!.message.assert().contains("shop.order.CancelOrdr")
            node.streams("o-1").assert().isEqualTo(placed)
            node.timer(order("o-1"), "nightly").assert().isNull()
        }

    @Test
    fun `a node whose clock is ahead fires early, and the target waits for the due time`(): Unit = runBlocking {
        val ahead = Clock.offset(Clock.systemUTC(), java.time.Duration.ofMillis(AHEAD_MILLIS))
        val node = node("node-1", TimedBackends(), clock = ahead).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 1_000))
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        val timer = checkNotNull(node.timer(order("o-1"), PAY_TIMEOUT))
        val cancelled = node.streams("o-1").last()
        cancelled.events.single().name.assert().isEqualTo("shop.order.OrderCancelled")
        cancelled.commitTime.assert().isGreaterThanOrEqualTo(timer.dueAt)
    }

    @Test
    fun `a timer refused under a sustained skew is retried, and fires once, not before its slot's due time`(): Unit =
        runBlocking {
            fireUnderSkew(correctClock = false)
        }

    @Test
    fun `a timer refused until the clock is corrected fires once, not before its slot's due time`(): Unit =
        runBlocking {
            fireUnderSkew(correctClock = true)
        }

    /**
     * A node whose clock runs [FAR_AHEAD_MILLIS] ahead, beyond the target's maximum early wait, fires an order's timer:
     * the order refuses it at once without holding its queue, and the runner retries it. Every retry carries the slot's
     * own due time, so the order decides it once, when it is due on its own clock, whether or not the firing node's
     * clock is corrected first ([correctClock], right after the first refusals).
     */
    private suspend fun fireUnderSkew(correctClock: Boolean) {
        val skew = AtomicLong(FAR_AHEAD_MILLIS)
        val skewed = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.now().plusMillis(skew.get())
        }
        val timers = TimerOptions(
            pollInterval = 20.milliseconds,
            lookahead = 1.seconds,
            retry = RetryPolicy(maxAttempts = 100, initialBackoff = 50.milliseconds, maxBackoff = 100.milliseconds),
            claimTimeout = 1.seconds,
            maxEarlyWait = 300.milliseconds,
        )
        val node = node("node-1", TimedBackends(), clock = skewed, timers = timers).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = FAR_AHEAD_MILLIS + 500))
        val due = node.streams("o-1").single().commitTime + FAR_AHEAD_MILLIS + 500
        // Sent far too early for the order's node: refused at once as a technical failure, and sent again later.
        eventually { (node.timer(order("o-1"), PAY_TIMEOUT)?.attempts ?: 0) >= 2 }
        val retried = checkNotNull(node.timer(order("o-1"), PAY_TIMEOUT))
        retried.outcome.assert().contains(ResultCodes.SCHEDULE_NOT_DUE)
        retried.slotDueAt.assert().isEqualTo(due)
        // The order's queue is not held meanwhile: another command on it commits at once.
        val started = System.nanoTime()
        node.send("o-1", Remind(at = System.currentTimeMillis() + 60_000))
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        ((System.nanoTime() - started) / 1_000_000).assert().isLessThan(FAR_AHEAD_MILLIS / 2)
        node.streams("o-1").eventNames().assert().doesNotContain("shop.order.OrderCancelled")
        if (correctClock) {
            skew.set(0)
        }
        eventually { node.timer(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        val fired = checkNotNull(node.timer(order("o-1"), PAY_TIMEOUT))
        fired.outcome.assert().isEqualTo(TimerOutcomes.COMMITTED)
        fired.dueAt.assert().isEqualTo(due)
        val cancelled = node.streams("o-1").filter { stream ->
            stream.events.any { it.name == "shop.order.OrderCancelled" }
        }
        cancelled.single().requestId.assert().isEqualTo(fired.requestId)
        cancelled.single().commitTime.assert().isGreaterThanOrEqualTo(due)
    }

    @Test
    fun `a decision that only schedules commits, is waited for, and its timer is sent`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends()).also { it.start() }
        node.send("o-1", PlaceOrder(payWithinMillis = 60_000))
        val reminded = node.send("o-1", Remind(at = System.currentTimeMillis() + 100), wait = WaitPlan.SNAPSHOT)
        reminded.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        node.streams("o-1")[1].events.map { it.name }.assert().containsExactly(Schedules.SCHEDULE_SET)
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
        stock.decided.single().assert().isEqualTo("${order("o-1")}#reserve@1#1")
        topics.messages(STOCK_COMMANDS).single().key.assert().isEqualTo(ITEM.ref(TENANT, "o-1").toString())
    }

    /** A timer store that loses the first outcome recorded as fired, as a crash right after the send would. */
    private class LosingFirstOutcome(private val store: TimerStore) : TimerStore by store {
        @Volatile
        private var lost = false

        override suspend fun settle(changes: List<TimerChange>): Int {
            if (!lost && changes.any { it.next.status == TimerStatus.FIRED }) {
                lost = true
                throw IllegalStateException("The node crashed before recording the outcome.")
            }
            return store.settle(changes)
        }
    }

    private companion object {
        const val PREFIX = "test."
        const val AHEAD_MILLIS = 700L
        const val FAR_AHEAD_MILLIS = 2_000L
        const val STOCK_PARTITIONS = 4
        const val STOCK_COMMANDS = PREFIX + "wow.stock.commands"
    }
}
