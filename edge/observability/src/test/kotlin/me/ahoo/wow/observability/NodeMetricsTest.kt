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

package me.ahoo.wow.observability

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.pauses
import me.ahoo.wow.runtime.node.throttled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The gauges [NodeMetrics] samples: parked entries, and where each subscription of the log stands. */
class NodeMetricsTest {
    private val telemetry = Telemetry()
    private val memory = InMemoryBackends(2)

    /** While set, the sales context's ledger fails to list its entries, as one that is unreachable. */
    @Volatile
    private var broken = false

    private val backends = BackendFactory { context ->
        val opened = memory.backends(context)
        val ledger = opened.ledger
        opened.copyWith(
            ledger = object : FailureLedger by ledger {
                override suspend fun list(
                    subscription: String,
                    after: AggregateRef?,
                    limit: Int
                ): List<ParkedAggregate> {
                    check(!broken) { "The ledger is unreachable." }
                    return ledger.list(subscription, after, limit)
                }
            },
        )
    }

    /** While set, the board's view store is unavailable: the board pauses its partitions (decision D243). */
    @Volatile
    private var down = false

    /** While set, the board's downstream pushes back: the board throttles its partitions (decision D263). */
    @Volatile
    private var pushingBack = false

    /** A projection of the orders that fails on the orders whose id starts with `bad`, which it parks at once. */
    private val board = SubscriptionFactory {
        listOf(
            Subscription(BOARD, SubscriptionFilter(SALES)) { entry ->
                if (down) {
                    throw StorageUnavailableException("The board's view store is unavailable; retry later.")
                }
                if (pushingBack) {
                    throw WowProblemException(
                        "wow.wait.timeout",
                        504,
                        RetryHint(retryable = true, sameRequestId = true),
                        "The board's downstream timed out.",
                    )
                }
                check(!entry.ref.id.startsWith("bad")) { "The board cannot show ${entry.ref}." }
            },
        )
    }

    private val node = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel()),
        salesManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(maxAttempts = 1),
        subscriptions = board,
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        node.stop()
    }

    private suspend fun place(id: String) {
        val result = node.gateway.execute(
            ServerCommandRequest(SALES, "order", "PlaceOrder", CommandBody.of("""{"quantity":1}"""), aggregateId = id),
        )
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    private fun gauge(name: String, vararg tags: String): List<Double> =
        telemetry.meters.find(name).tags(*tags).gauges().map { it.value() }

    @Test
    fun `a sample should gauge the parked entries and where each subscription stands`(): Unit = runBlocking {
        node.start()
        listOf("good-1", "good-2", "bad-1", "bad-2").forEach { place(it) }
        eventually { node.parked.list(BOARD).entries.size == 2 }
        val runtime = node.context(SALES).logGroups.single().subscriptions
        val heads = (0 until 2).associateWith { node.context(SALES).backends.log.head(it) ?: 0L }
        eventually { runtime.positions().filter { it.subscription == BOARD }.all { it.watermark == heads[it.partition] } }
        val metrics = NodeMetrics(node, telemetry.meters, NodeSampler(node))
        metrics.sample()
        gauge(WowMetrics.PARKED_AGGREGATES.name, WowMetrics.SUBSCRIPTION, BOARD).assert().containsExactly(2.0)
        val board = arrayOf(WowMetrics.GROUP, runtime.group, WowMetrics.SUBSCRIPTION, BOARD)
        gauge(WowMetrics.POSITION.name, *board).sum().assert().isEqualTo(heads.values.sum().toDouble())
        gauge(WowMetrics.BACKLOG.name, *board).assert().containsOnly(0.0)
        gauge(WowMetrics.PROGRESS.name, *board).assert().containsOnly(1.0)
        // Nothing in flight once the commands settled (review A5-11).
        listOf(
            WowMetrics.EXECUTOR_IN_FLIGHT.name,
            WowMetrics.EXECUTOR_LANES.name,
            WowMetrics.APPEND_QUEUE_DEPTH.name
        ).forEach {
            gauge(it, WowMetrics.CONTEXT, SALES).assert().containsExactly(0.0)
        }
        // Counted up to the bound, a page at a time.
        val capped = Telemetry()
        NodeMetrics(node, capped.meters, NodeSampler(node, maxParked = 1)).sample()
        capped.meters.find(WowMetrics.PARKED_AGGREGATES.name).tags(WowMetrics.SUBSCRIPTION, BOARD).gauge()!!.value()
            .assert().isEqualTo(1.0)
    }

    @Test
    fun `a sample should gauge the partitions paused on a systemic failure, and none once resumed`(): Unit =
        runBlocking {
            node.start()
            down = true
            place("good-1")
            eventually { node.pauses.paused().isNotEmpty() }
            val paused = node.pauses.paused().single()
            paused.subscription.assert().isEqualTo(BOARD)
            val metrics = NodeMetrics(node, telemetry.meters, NodeSampler(node))
            metrics.sample()
            val board = arrayOf(WowMetrics.GROUP, paused.group, WowMetrics.SUBSCRIPTION, BOARD)
            // One series per subscription, counting its paused partitions.
            gauge(WowMetrics.PAUSED_PARTITIONS.name, *board).assert().containsExactly(1.0)
            down = false
            eventually { node.pauses.paused().isEmpty() }
            metrics.sample()
            gauge(WowMetrics.PAUSED_PARTITIONS.name, *board).assert().containsOnly(0.0)
            node.parked.list(BOARD).entries.assert().isEmpty()
        }

    @Test
    fun `a sample should gauge the partitions throttled on backpressure, and none once they recover`(): Unit =
        runBlocking {
            node.start()
            pushingBack = true
            place("good-1")
            eventually { node.throttled.isNotEmpty() }
            val throttled = node.throttled.single()
            val metrics = NodeMetrics(node, telemetry.meters, NodeSampler(node))
            metrics.sample()
            val board = arrayOf(WowMetrics.GROUP, throttled.group, WowMetrics.SUBSCRIPTION, BOARD)
            gauge(WowMetrics.THROTTLED_PARTITIONS.name, *board).assert().containsExactly(1.0)
            gauge(WowMetrics.PAUSED_PARTITIONS.name, *board).assert().containsExactly(0.0)
            pushingBack = false
            eventually { node.throttled.isEmpty() }
            metrics.sample()
            gauge(WowMetrics.THROTTLED_PARTITIONS.name, *board).assert().containsOnly(0.0)
            node.parked.list(BOARD).entries.assert().isEmpty()
        }

    @Test
    fun `a node that no longer runs a partition removes its gauges, and the parked count with partition 0`(): Unit =
        runBlocking {
            // Review A3-13: a partition handed off leaves no stale gauge, and one node reports the service's ledger.
            node.start()
            listOf("good-1", "bad-1").forEach { place(it) }
            eventually { node.parked.list(BOARD).entries.size == 1 }
            val metrics = NodeMetrics(node, telemetry.meters, NodeSampler(node))
            metrics.reportsParked().assert().isTrue()
            metrics.sample()
            gauge(WowMetrics.PARKED_AGGREGATES.name, WowMetrics.SUBSCRIPTION, BOARD).assert().containsExactly(1.0)
            gauge(WowMetrics.POSITION.name, WowMetrics.SUBSCRIPTION, BOARD).assert().isNotEmpty()

            node.stop()
            metrics.reportsParked().assert().isFalse()
            metrics.sample()
            telemetry.meters.find(WowMetrics.PARKED_AGGREGATES.name).gauges().assert().isEmpty()
            telemetry.meters.find(WowMetrics.POSITION.name).gauges().assert().isEmpty()
            telemetry.meters.find(WowMetrics.BACKLOG.name).gauges().assert().isEmpty()
            telemetry.meters.find(WowMetrics.PROGRESS.name).gauges().assert().isEmpty()
        }

    @Test
    fun `sampling should go on every interval, past a sample that fails, until it is closed`(): Unit = runBlocking {
        node.start()
        place("good-1")
        val sampler = NodeSampler(node, interval = 20.milliseconds)
        NodeMetrics(node, telemetry.meters, sampler)
        broken = true
        sampler.start()
        sampler.start()
        // While the ledger is down, every sample fails before it gauges anything.
        kotlinx.coroutines.delay(100.milliseconds)
        telemetry.meters.find(WowMetrics.PARKED_AGGREGATES.name).gauges().assert().isEmpty()
        broken = false
        eventually(
            5.seconds
        ) { gauge(WowMetrics.PARKED_AGGREGATES.name, WowMetrics.SUBSCRIPTION, BOARD) == listOf(0.0) }
        sampler.close()
    }

    @Test
    fun `progress should be the share of the log handled, all of an empty log`() {
        NodeMetrics.progress(0, 0).assert().isEqualTo(1.0)
        NodeMetrics.progress(5, 20).assert().isEqualTo(0.25)
        NodeMetrics.progress(30, 20).assert().isEqualTo(1.0)
        assertThrows<IllegalArgumentException> { NodeSampler(node, interval = 0.seconds) }
        assertThrows<IllegalArgumentException> { NodeSampler(node, maxParked = 0) }
    }

    private companion object {
        const val BOARD = "board"
    }
}
