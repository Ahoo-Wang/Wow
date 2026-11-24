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
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Deep review round 1 of the host, configuration and observability: what the node's metrics say, against the page. */
class ObservabilityReviewR1Test {
    private val telemetry = Telemetry()

    @Test
    fun `wow-timer-delay should be how late a timer fires, as the firing loop claims and sends it`(): Unit =
        runBlocking {
            val ref = ORDER.ref("t", "o-1")
            val due = 1_000L
            telemetry.now = 5_000
            // The firing loop claims a timer before it sends it (TimerRunner.attempt): FIRING, looked at again after
            // the claim timeout (30 s), the slot still due when it was; the firer is given that claimed row.
            val claimed = Timer(
                id = "$ref#expire@2",
                owner = ref,
                key = "expire",
                version = 2,
                partition = 0,
                rule = ScheduleRule.At(due),
                dueAt = telemetry.now + CLAIM_TIMEOUT_MILLIS,
                target = ref,
                commandType = "$ORDER.CancelOrder",
                body = "{}",
                status = TimerStatus.FIRING,
                slotDueAt = due,
            )
            telemetry.observer.firing(claimed).observe { }
            val delay = telemetry.meters.find(WowMetrics.TIMER_DELAY.name).tags(WowMetrics.CONTEXT, SALES).timer()!!
            delay.totalTime(TimeUnit.MILLISECONDS).assert().isEqualTo(4_000.0)
        }

    /** While set, the sales context's ledger fails to count its entries, as one briefly unreachable. */
    @Volatile
    private var broken = false

    private val memory = InMemoryBackends(2)
    private val backends = BackendFactory { context ->
        val opened = memory.backends(context)
        val ledger = opened.ledger
        opened.copyWith(
            ledger = object : FailureLedger by ledger {
                override suspend fun count(subscription: SubscriptionId, max: Int): Int {
                    check(!broken) { "The ledger is unreachable." }
                    return ledger.count(subscription, max)
                }
            },
        )
    }

    /** A projection that parks the orders whose id starts with `bad` at once. */
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

    @Test
    fun `a parked gauge should keep its subject's series when one sample cannot count it`(): Unit = runBlocking {
        node.start()
        listOf("bad-1", "bad-2").forEach { id ->
            node.gateway.execute(
                ServerCommandRequest(
                    SALES,
                    "order",
                    "PlaceOrder",
                    CommandBody.of("""{"quantity":1}"""),
                    aggregateId = id
                ),
            ).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        }
        eventually { node.parked.list(SubscriptionId(SALES, BOARD)).entries.size == 2 }
        val metrics = NodeMetrics(node, telemetry.meters, NodeSampler(node))
        metrics.sample()
        parked().assert().containsExactly(2.0)

        // The ledger cannot be read for one sample: the two entries are still parked, and an alert on the gauge holds.
        broken = true
        metrics.sample()

        parked().assert().isNotEmpty()
    }

    private fun parked(): List<Double> = telemetry.meters.find(WowMetrics.PARKED_AGGREGATES.name)
        .tags(WowMetrics.SUBSCRIPTION, BOARD).gauges().map { it.value() }

    private companion object {
        const val BOARD = "board"
        const val CLAIM_TIMEOUT_MILLIS = 30_000L
    }
}
