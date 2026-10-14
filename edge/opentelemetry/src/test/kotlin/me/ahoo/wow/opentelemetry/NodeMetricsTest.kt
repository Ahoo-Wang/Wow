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

package me.ahoo.wow.opentelemetry

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
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
        opened.copy(
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

    /** A projection of the orders that fails on the orders whose id starts with `bad`, which it parks at once. */
    private val board = SubscriptionFactory {
        listOf(
            Subscription(BOARD, SubscriptionFilter(SALES)) { entry ->
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
            CommandRequest(SALES, "order", "PlaceOrder", CommandBody.of("""{"quantity":1}"""), aggregateId = id),
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
        val metrics = NodeMetrics(node, telemetry.meters)
        metrics.sample()
        gauge(WowMetrics.PARKED, WowMetrics.SUBSCRIPTION, BOARD).assert().containsExactly(2.0)
        val board = arrayOf(WowMetrics.GROUP, runtime.group, WowMetrics.SUBSCRIPTION, BOARD)
        gauge(WowMetrics.POSITION, *board).sum().assert().isEqualTo(heads.values.sum().toDouble())
        gauge(WowMetrics.BACKLOG, *board).assert().containsOnly(0.0)
        gauge(WowMetrics.PROGRESS, *board).assert().containsOnly(1.0)
        // Counted up to the bound, a page at a time.
        val capped = Telemetry()
        NodeMetrics(node, capped.meters, maxParked = 1).sample()
        capped.meters.find(WowMetrics.PARKED).tags(WowMetrics.SUBSCRIPTION, BOARD).gauge()!!.value()
            .assert().isEqualTo(1.0)
    }

    @Test
    fun `sampling should go on every interval, past a sample that fails, until it is closed`(): Unit = runBlocking {
        node.start()
        place("good-1")
        val metrics = NodeMetrics(node, telemetry.meters, interval = 20.milliseconds)
        broken = true
        metrics.start()
        metrics.start()
        // While the ledger is down, every sample fails before it gauges anything.
        kotlinx.coroutines.delay(100.milliseconds)
        telemetry.meters.find(WowMetrics.PARKED).gauges().assert().isEmpty()
        broken = false
        eventually(5.seconds) { gauge(WowMetrics.PARKED, WowMetrics.SUBSCRIPTION, BOARD) == listOf(0.0) }
        metrics.close()
    }

    @Test
    fun `progress should be the share of the log handled, all of an empty log`() {
        NodeMetrics.progress(0, 0).assert().isEqualTo(1.0)
        NodeMetrics.progress(5, 20).assert().isEqualTo(0.25)
        NodeMetrics.progress(30, 20).assert().isEqualTo(1.0)
        assertThrows<IllegalArgumentException> { NodeMetrics(node, telemetry.meters, interval = 0.seconds) }
        assertThrows<IllegalArgumentException> { NodeMetrics(node, telemetry.meters, maxParked = 0) }
    }

    private companion object {
        const val BOARD = "board"
    }
}
