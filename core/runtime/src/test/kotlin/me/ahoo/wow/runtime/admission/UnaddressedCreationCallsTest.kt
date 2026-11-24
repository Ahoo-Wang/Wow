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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.client.InProcessCommandClient
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Review r1 (cluster, routing and node): a command a decide function sends takes a request id derived from the deciding
 * command's aggregate and request id ([me.ahoo.wow.spi.call.DecidingCommand]), so that "a caller's retry of an attempt
 * that called a peer and did not commit sends the same requests again, which their targets answer as repeats rather
 * than run twice". A creation that names no aggregate is given a new id by admission on every attempt that finds
 * nothing committed ([CreationIds]: the default generator aims a fresh id at the request's partition), so the retry's
 * derived request ids differ from the first attempt's, and its target runs the command a second time.
 */
class UnaddressedCreationCallsTest {
    data class OpenOrder(val amount: Int)
    data class OrderOpened(val amount: Int)
    data class Charge(val amount: Int)
    data class Charged(val amount: Int)

    class Order
    class Payment

    /** The node's own client, which the order's creation charges the payment through while it decides. */
    private lateinit var client: CommandClient

    private val orders = aggregate<Order>(CONTEXT, "order") {
        initial { Order() }
        decideSuspending<OpenOrder>(Creation.REQUIRED) { _, body ->
            client.wait(CommandRequest.of(Charge(body.amount), PAYMENT, wait = WaitPlan.COMMITTED))
            Decision.events(OrderOpened(body.amount))
        }
        evolve<OrderOpened> { }
    }

    private val payments = aggregate<Payment>(CONTEXT, "payment") {
        initial { Payment() }
        decide<Charge>(Creation.ALLOWED) { _, body -> Decision.events(Charged(body.amount)) }
        evolve<Charged> { }
    }

    private val stored = InMemoryEventLog(partitions = 4)

    /** The log, refusing the first append of an order: the store refused it, so the attempt committed nothing. */
    private val log = object : EventLog by stored {
        @Volatile
        var refused = false

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
            if (stream.ref.type.name == "order" && !refused) {
                refused = true
                return AppendOutcome.Rejected.of(IllegalStateException("The store refused the append."))
            }
            return stored.append(stream, transport)
        }
    }

    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val gateway = CommandGateway(
        ModelRegistry(listOf(orders, payments)),
        mapOf(
            CONTEXT to WaitCoordinator(
                Executor(Kernel(log)),
                registry,
                SubscriptionDirectory.NONE,
                LocalSignalSender(origin.node, registry),
                origin,
                WaitOptions(maxTimeout = 30.seconds),
            ),
        ),
        // The node's own wiring: the creation reads what its request committed before it is given an id.
        committed = CommittedRequests.of(log),
    )

    private val alice = Caller(TENANT, Scope("alice"), subject = "alice")

    private fun openOrder(id: String? = null) = ServerCommandRequest.builder(
        CONTEXT,
        "order",
        OpenOrder::class.java.name,
        CommandBody.Value(OpenOrder(10)),
    ).caller(alice).aggregateId(id).requestId("open-order-1").wait(WaitPlan.COMMITTED).build()

    /** The payment's commits after the order was opened twice, the first attempt refused, with [id] named or not. */
    private suspend fun chargesAfterARetry(id: String?): List<EventStreamRecord> {
        client = InProcessCommandClient(gateway).of(alice)
        // The first attempt charges the payment, then its own append is refused: the order is not created.
        gateway.execute(openOrder(id)).status.assert().isEqualTo(CommandResult.Status.FAILED)
        // The caller retries with the same request id, as the failure tells it to.
        gateway.execute(openOrder(id)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        return stored.read(payments.type.ref(TENANT, PAYMENT)).toList()
    }

    @Test
    fun `a retried creation that names its aggregate charges its payment once`(): Unit = runBlocking {
        // The control: the same flow with the order's id named holds the promise.
        chargesAfterARetry("order-1").assert().hasSize(1)
    }

    @Test
    fun `a retried creation that names no aggregate charges its payment once`(): Unit = runBlocking {
        val charges = chargesAfterARetry(null)
        // The retry's charge is the first attempt's request again, which the payment answers as a repeat.
        charges.map { it.requestId }.distinct().assert().hasSize(1)
        charges.assert().hasSize(1)
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "acme"
        const val PAYMENT = "payment-1"
    }
}
