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

package me.ahoo.wow.runtime.command

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.RejectionLog
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A saga's `@OnRejected` handler that accepts the codes it names, for a command it sent to another context, whose
 * rejection comes back on the receipts topic: an accepted code derives nothing and is logged under `wow.saga.rejection`
 * in the saga's service; any other parks the saga's aggregate at once under the receipts subscription, its reason naming
 * the handler, the command and the code.
 */
class CrossContextAcceptedRejectionTest : CommandIntakeFixtures() {
    private val handled = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()
    private val accepted = ListAppender<ILoggingEvent>()
    private val acceptedLogger = LoggerFactory.getLogger(RejectionLog.ACCEPTED_CATEGORY) as Logger

    @BeforeEach
    fun listen() {
        accepted.start()
        acceptedLogger.addAppender(accepted)
    }

    @AfterEach
    fun unlisten() {
        acceptedLogger.detachAppender(accepted)
    }

    /** The order saga, whose handler of a rejected reservation accepts [code] alone. */
    private fun accepting(code: String) = ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = listOf(
            RejectionBinding.of("OrderSaga", "onReserveRejected", Reserve::class.java) { rejected, _ ->
                handled += rejected
                rejected.accept(code)
                emptyList()
            },
        ),
        derives = listOf(Reserve::class.java),
    ) { event, _ ->
        event.events.filterIsInstance<OrderPlaced>().map {
            CommandRequest.to(ITEM.ref(event.ref.tenantId, event.ref.id), Reserve(it.quantity))
        }
    }

    @Test
    fun `an accepted code should derive nothing, park nothing and be logged in the saga's service`(): Unit = runBlocking {
        stockNode().start()
        salesNode(saga = accepting(INSUFFICIENT)).start()
        nodes.last { it.service == SALES_SERVICE }.place("o-1", AVAILABLE + 1)
        eventually { handled.size == 1 }
        receiptsRead(topics, RECEIPTS, salesBackends.backends(SALES).checkpoints, SALES_SERVICE, SALES)
        val commandId = handled.single().commandId
        accepted.list.map { "${it.level} ${it.formattedMessage}" }.assert().containsExactly(
            "INFO OrderSaga.onReserveRejected in sales accepted the rejection of stock.item.Reserve $commandId on " +
                "${ITEM.ref(TENANT, "o-1")}: $INSUFFICIENT.",
        )
        salesBackends.backends(SALES).ledger.count(RECEIPTS_SUBSCRIPTION, 10).assert().isEqualTo(0)
    }

    @Test
    fun `a code the handler does not accept should park the saga's aggregate at once, naming it`(): Unit = runBlocking {
        stockNode().start()
        salesNode(saga = accepting(FrameworkCodes.AGGREGATE_NOT_FOUND)).start()
        nodes.last { it.service == SALES_SERVICE }.place("o-2", AVAILABLE + 1)
        val ledger = salesBackends.backends(SALES).ledger
        eventually { ledger.isParked(RECEIPTS_SUBSCRIPTION, ORDER.ref(TENANT, "o-2")) }
        ledger.parked(RECEIPTS_SUBSCRIPTION, ORDER.ref(TENANT, "o-2"))!!.reason.assert().startsWith(
            "me.ahoo.wow.kernel.UnacceptedRejectionHandlingException: OrderSaga.onReserveRejected accepts " +
                "${FrameworkCodes.AGGREGATE_NOT_FOUND}, not the rejection of stock.item.Reserve " +
                "${handled.single().commandId} on ${ITEM.ref(TENANT, "o-2")}: $INSUFFICIENT Only $AVAILABLE left.",
        )
        // Parked at once: the receipt was handled once, and nothing was logged as accepted.
        handled.assert().hasSize(1)
        accepted.list.assert().isEmpty()
    }

    private companion object {
        val RECEIPTS_SUBSCRIPTION = SubscriptionId(SALES, Receipts.nameOf(SALES))
    }
}
