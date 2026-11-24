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

package me.ahoo.wow.runtime.binding

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.RejectionLog
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A saga's `@OnRejected` handler that accepts the codes it names (`CommandReceipt.Rejected.accept`), in the saga's own
 * context: an accepted code derives nothing and is logged under `wow.saga.rejection`; any other parks the order at once,
 * without a retry, its reason naming the handler, the command and the code, and the wait ends REJECTED at the saga's
 * hop. A handler compensates one code and accepts another.
 */
class SagaAcceptedRejectionTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val decided = CopyOnWriteArrayList<String>()
    private val handled = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()
    private val accepted = ListAppender<ILoggingEvent>()
    private val acceptedLogger = LoggerFactory.getLogger(RejectionLog.ACCEPTED_CATEGORY) as Logger

    /** Accepts [codes] alone. */
    private fun accepting(vararg codes: String) =
        RejectionBinding.of("CartSaga", "onRemoveRejected", RemoveItems::class.java) { rejected, _ ->
            handled += rejected
            rejected.accept(codes.first(), *codes.drop(1).toTypedArray())
            emptyList()
        }

    /** Puts a forbidden item back in the cart, and accepts an invalid removal. */
    private val compensatingOrAccepting = RejectionBinding.of(
        "CartSaga",
        "onRemoveRejected",
        RemoveItems::class.java,
        derives = listOf(AddItems::class.java),
    ) { rejected, _ ->
        handled += rejected
        listOfNotNull(
            if (rejected.error.code == FORBIDDEN) {
                CommandRequest.to(rejected.ref, AddItems(listOf("restored")))
            } else {
                rejected.accept(FrameworkCodes.COMMAND_INVALID)
                null
            },
        )
    }

    private fun saga(rejection: RejectionBinding<*>) = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = listOf(rejection),
        derives = listOf(RemoveItems::class.java),
    ) { event, _ ->
        val owner = event.scope.ownerId ?: return@saga emptyList()
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, owner), RemoveItems(it.items))
        }
    }

    private var node: ServiceNode? = null

    private fun start(saga: ProcessorBinding<*>): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(decided)),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
    ).also {
        node = it
        runBlocking {
            it.start()
            it.gateway.execute(request("cart", "AddItems", listOf("apple"), "bob", WaitPlan.COMMITTED))
        }
    }

    @BeforeEach
    fun listen() {
        accepted.start()
        acceptedLogger.addAppender(accepted)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        acceptedLogger.detachAppender(accepted)
        node?.stop() ?: Unit
    }

    private fun request(aggregate: String, command: String, items: List<String>, id: String, wait: WaitPlan) =
        ServerCommandRequest(
            context = SHOP,
            aggregate = aggregate,
            command = command,
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope(ownerId = "bob")),
            aggregateId = id,
            wait = wait,
        )

    private suspend fun ServiceNode.place(id: String, vararg items: String): CommandResult =
        gateway.send(request("order", "Place", items.toList(), id, CHAIN)).toList().last()

    private val cart = CART.ref(TENANT, "bob")
    private val ledger get() = backends.opened.getValue(SHOP).ledger

    private fun removalId(order: String) = ExecutionSagaContext.derivedId(
        ExecutionId(ORDER.context, SAGA, ORDER.ref(TENANT, order), 1),
        cart,
        RemoveItems::class.java,
    )

    private fun lines() = accepted.list.map { "${it.level} ${it.formattedMessage}" }

    @Test
    fun `an accepted code should derive nothing, park nothing and be logged once`(): Unit = runBlocking {
        val node = start(saga(accepting(FORBIDDEN, FrameworkCodes.COMMAND_INVALID)))
        val last = node.place("o-1", FORBIDDEN_ITEM)
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.hop.subscription.assert().isEqualTo(SAGA)
        last.error!!.code.assert().isEqualTo(FORBIDDEN)
        last.derived.assert().containsExactly(removalId("o-1"))
        handled.single().commandId.assert().isEqualTo(removalId("o-1"))
        ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-1")).assert().isFalse()
        lines().assert().containsExactly(
            "INFO CartSaga.onRemoveRejected in shop accepted the rejection of shop.cart.RemoveItems " +
                "${removalId("o-1")} on $cart: $FORBIDDEN.",
        )
    }

    @Test
    fun `a code the handler does not accept should park the order at once, naming it, and reject the wait`(): Unit =
        runBlocking {
            val node = start(saga(accepting(FrameworkCodes.COMMAND_INVALID)))
            val last = node.place("o-2", FORBIDDEN_ITEM)
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            last.hop.subscription.assert().isEqualTo(SAGA)
            last.error!!.code.assert().isEqualTo(FORBIDDEN)
            eventually { ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2")) }
            ledger.parked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, "o-2"))!!.reason.assert().startsWith(
                "me.ahoo.wow.kernel.UnacceptedRejectionHandlingException: CartSaga.onRemoveRejected accepts " +
                    "${FrameworkCodes.COMMAND_INVALID}, not the rejection of shop.cart.RemoveItems " +
                    "${removalId("o-2")} on $cart: $FORBIDDEN $FORBIDDEN_ITEM cannot be removed.",
            )
            // Parked at once: the handling ran once, the removal was decided once, and nothing was logged as accepted.
            handled.assert().hasSize(1)
            decided.assert().hasSize(1)
            lines().assert().isEmpty()
        }

    @Test
    fun `a handler should compensate the code it compensates and accept the one it accepts`(): Unit = runBlocking {
        val node = start(saga(compensatingOrAccepting))
        node.place("o-3", FORBIDDEN_ITEM).derived.assert().hasSize(2)
        node.place("o-4", BLANK_ITEM).error!!.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        handled.map { it.error.code }.assert().containsExactly(FORBIDDEN, FrameworkCodes.COMMAND_INVALID)
        // The forbidden removal was compensated, the invalid one accepted and logged; neither order parked.
        val payloads = backends.opened.getValue(SHOP).log.read(cart).toList()
            .flatMap { stream -> stream.events.map { it.payload.toString() } }
        payloads.joinToString().assert().contains("restored")
        lines().single().assert().contains("accepted the rejection of shop.cart.RemoveItems ${removalId("o-4")}")
        listOf("o-3", "o-4").forEach {
            ledger.isParked(SubscriptionId(SHOP, SAGA), ORDER.ref(TENANT, it)).assert().isFalse()
        }
    }

    private companion object {
        const val SAGA = "CartSaga.onOrderPlaced"
        val CHAIN: WaitPlan = WaitPlan.of(Hop.committed(), Hop.handled(SAGA), Hop.committed())
    }
}
