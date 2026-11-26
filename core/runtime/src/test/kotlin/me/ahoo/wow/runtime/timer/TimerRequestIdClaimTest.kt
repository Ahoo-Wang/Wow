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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.command.peer
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.id.DerivedIds
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * #7064: the request id of a timer's command can be computed by anyone (`Timers.idOf(owner, key, version)` and
 * `#slot`), and a command committed first under it settled the timer's command when it fired, so an unpaid order was
 * never cancelled. The framework's request ids now start with `wow:`, which admission refuses from a caller: the
 * claim is refused, and the timer's own command decides the order. A caller's request id of the old shape commits,
 * and is no longer the timer's.
 */
class TimerRequestIdClaimTest {
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
                claimTtl = 300.milliseconds,
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

    /** The first slot of [id]'s pay timeout, as anyone can write it: the timer's request id without `wow:`. */
    private fun payTimeoutSlot(id: String): String = Timers.idOf(order(id), PAY_TIMEOUT, 1) + "#1"

    private suspend fun statusOf(id: String): List<String> =
        node.context(SHOP).backends.log.read(order(id)).toList().flatMap { stream -> stream.events.map { it.name } }

    /** Waits for [id]'s pay timeout to fire, and asserts its own command, under its `wow:` request id, cancelled it. */
    private suspend fun assertCancelledByItsTimer(id: String) {
        val timers = node.context(SHOP).backends.timers!!
        eventually { timers.find(order(id), PAY_TIMEOUT)?.status == TimerStatus.FIRED }
        val timer = checkNotNull(timers.find(order(id), PAY_TIMEOUT))
        timer.requestId.assert().isEqualTo(DerivedIds.PREFIX + payTimeoutSlot(id))
        timer.outcome.assert().describedAs("the timer's own command decided the unpaid order")
            .isEqualTo(TimerOutcomes.COMMITTED)
        statusOf(id).assert().contains("shop.order.OrderCancelled")
    }

    private fun CommandResult.assertRefusedAsTheFrameworks() {
        status.assert().isNotEqualTo(CommandResult.Status.SUCCEEDED)
        error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
        error!!.message.assert().contains("never starts with ${DerivedIds.PREFIX}")
    }

    @Test
    fun `a buyer may not commit under its order's timer request id, so the unpaid order is cancelled`(): Unit =
        runBlocking {
            start()
            val alice = owner("alice")
            send(alice, "o-1", "PlaceOrder", """{"payWithinMillis":400}""").status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            send(alice, "o-1", "Remind", """{"at":${Long.MAX_VALUE / 2}}""", DerivedIds.PREFIX + payTimeoutSlot("o-1"))
                .assertRefusedAsTheFrameworks()
            assertCancelledByItsTimer("o-1")
        }

    @Test
    fun `another owner may not commit under another's timer request id, so that order is cancelled`(): Unit =
        runBlocking {
            start()
            send(owner("alice"), "o-1", "PlaceOrder", """{"payWithinMillis":400}""").status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            val placed = send(
                owner("bob"),
                "o-2",
                "PlaceOrder",
                """{"payWithinMillis":600000}""",
                DerivedIds.PREFIX + payTimeoutSlot("o-1"),
            )
            placed.assertRefusedAsTheFrameworks()
            assertCancelledByItsTimer("o-1")
        }

    @Test
    fun `a caller's request id of the timer's old shape commits, and is not the timer's`(): Unit = runBlocking {
        start()
        val alice = owner("alice")
        send(alice, "o-1", "PlaceOrder", """{"payWithinMillis":400}""").status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        send(alice, "o-1", "Remind", """{"at":${Long.MAX_VALUE / 2}}""", payTimeoutSlot("o-1")).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        assertCancelledByItsTimer("o-1")
    }
}
