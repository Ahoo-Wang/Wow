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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.command.peer
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Round two of the deep review, security: the request id of a timer's command is predictable
 * (`Timers.idOf(owner, key, version)` and `#slot`), and a public caller may name any request id (`Wow-Request-Id`).
 * A command a caller commits under that id first, on the scheduled aggregate itself or on another aggregate of its type
 * and tenant, settles the timer's command when it fires: a derived command's duplicate is answered whatever its scope,
 * and another command type under the same request id is the rejection `wow.command.request-id-reused`, which settles the
 * timer for good (`guide/failures.md`: a rejection settles the timer). So an unpaid order is never cancelled.
 */
class R2TimerRequestIdTest {
    private val channel = LoopbackClusterChannel()
    private lateinit var node: ServiceNode

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun start(): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel()),
        shopManifest(),
        TimedBackends(),
        channel,
        options = fastNodeOptions().copyWith(
            timers = TimerOptions(
                pollInterval = 20.milliseconds,
                lookahead = 1.seconds,
                claimTimeout = 300.milliseconds,
            ),
        ),
        contracts = ContractDirectory(shopManifest(), listOf(peer(stockManifest()))),
    ).also {
        node = it
        channel.bind(it.address, it.forwardHandler)
        runBlocking { it.start() }
    }

    private fun owner(id: String) = Caller.of(tenantId = TENANT, scope = Scope(ownerId = id), subject = id)

    private suspend fun send(caller: Caller, id: String, command: String, body: String, requestId: String? = null) =
        node.gateway.execute(
            ServerCommandRequest(
                SHOP,
                "order",
                command,
                CommandBody.of(body),
                caller = caller,
                aggregateId = id,
                requestId = requestId,
            ),
        )

    /** The request id the first slot of [id]'s pay timeout is sent with, as anyone can write it. */
    private fun payTimeoutRequestId(id: String): String = Timers.idOf(order(id), PAY_TIMEOUT, 1) + "#1"

    private suspend fun statusOf(id: String): List<String> =
        node.context(SHOP).backends.log.read(order(id)).toList().flatMap { stream -> stream.events.map { it.name } }

    @Test
    fun `a buyer who commits under its order's timer request id first keeps an unpaid order from being cancelled`(): Unit =
        runBlocking {
            start()
            val alice = owner("alice")
            send(alice, "o-1", "PlaceOrder", """{"payWithinMillis":400}""").status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            // A command that commits on the order and pays nothing, under the request id the timer will use.
            send(alice, "o-1", "Remind", """{"at":${Long.MAX_VALUE / 2}}""", payTimeoutRequestId("o-1")).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { node.context(SHOP).backends.timers!!.find(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
            val timer = checkNotNull(node.context(SHOP).backends.timers!!.find(order("o-1"), PAY_TIMEOUT))
            timer.outcome.assert().describedAs("the timer's own command decided the unpaid order")
                .isEqualTo(TimerOutcomes.COMMITTED)
            statusOf("o-1").assert().contains("shop.order.OrderCancelled")
        }

    @Test
    fun `another owner of the tenant who commits under the timer's request id first keeps another's order from being cancelled`(): Unit =
        runBlocking {
            start()
            send(owner("alice"), "o-1", "PlaceOrder", """{"payWithinMillis":400}""").status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            // Bob places an order of his own under the request id of Alice's order's timer.
            send(owner("bob"), "o-2", "PlaceOrder", """{"payWithinMillis":600000}""", payTimeoutRequestId("o-1"))
                .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { node.context(SHOP).backends.timers!!.find(order("o-1"), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
            val timer = checkNotNull(node.context(SHOP).backends.timers!!.find(order("o-1"), PAY_TIMEOUT))
            timer.outcome.assert().describedAs("the timer's own command decided Alice's unpaid order")
                .isEqualTo(TimerOutcomes.COMMITTED)
            statusOf("o-1").assert().contains("shop.order.OrderCancelled")
        }
}
