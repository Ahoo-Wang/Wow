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

package me.ahoo.wow.kernel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Deep review r1 (kernel): a command a decide function sends through a Wow client takes a request id derived from the
 * deciding command's request id and the call's index ([me.ahoo.wow.spi.call.DecidingCommand]), the same in every
 * attempt. After a version conflict the kernel compensates the attempt ([me.ahoo.wow.api.decide.DecideScope.onAppendFailure],
 * as the user guide's "Undo an outside act" asks for an act outside the aggregate) and decides again, and a caller may
 * retry a rejected request with the same request id. The next attempt's call then reuses the id of a call that was
 * undone, or that asked something else, and the target answers it as a duplicate of the first: the second call is
 * never executed, and the caller's commit records an act the target does not hold.
 *
 * The target here is a second [Kernel], called as `InProcessCommandClient` calls the gateway: the request id from
 * `currentCoroutineContext().requestIdOf(null)`, and a duplicate answered as success (the client's SUCCEEDED).
 */
class R1DerivedRequestReuseTest {
    // The target context: a payment that is authorized for an amount, and voided.
    data class Authorize(val amount: Int)
    data class Authorized(val amount: Int)
    class Void
    class Voided

    class Payment(val id: String) {
        var authorized: Int = 0
        var voided: Boolean = false
    }

    private val payments = aggregate<Payment>("pay", "payment") {
        initial { Payment(it.id) }
        decide<Authorize>(Creation.ALLOWED) { _, body -> Decision.events(Authorized(body.amount)) }
        decide<Void> { _, _ -> Decision.events(Voided()) }
        evolve<Authorized> {
            authorized += it.amount
            voided = false
        }
        evolve<Voided> { voided = true }
    }
    private val paymentLog = InMemoryEventLog()
    private val paymentKernel = Kernel(paymentLog)
    private val paymentRef = payments.type.ref("t", "p-1")

    /** What a Wow client does from a decide function: sends with the derived request id, a duplicate is success. */
    private suspend fun send(body: Any, requestId: String?) {
        val command = Command(UUID.randomUUID().toString(), requestId!!, paymentRef, body)
        val outcome = paymentKernel.execute(payments, command)
        check(outcome is CommandOutcome.Committed || outcome is CommandOutcome.AlreadyCommitted) { "$outcome" }
    }

    // The calling context: an order that is paid by authorizing its total, and to which items are added.
    data class AddItem(val price: Int)
    data class ItemAdded(val price: Int)
    class Pay
    data class Paid(val amount: Int)
    class Block
    class Blocked
    class Unblock
    class Unblocked

    class Order(val id: String) {
        var total: Int = 0
        var paid: Int = 0
        var blocked: Boolean = false
    }

    private val orders = aggregate<Order>("shop", "order") {
        initial { Order(it.id) }
        decide<AddItem>(Creation.ALLOWED) { _, body -> Decision.events(ItemAdded(body.price)) }
        decide<Block> { _, _ -> Decision.events(Blocked()) }
        decide<Unblock> { _, _ -> Decision.events(Unblocked()) }
        decideSuspending<Pay> { state, _ ->
            // The outside act, then its undo should the events not be appended, as the user guide shows.
            send(Authorize(state.total), currentCoroutineContext().requestIdOf(null))
            onAppendFailure { send(Void(), UUID.randomUUID().toString()) }
            if (state.blocked) reject("order.blocked", "The order is blocked.")
            Decision.events(Paid(state.total))
        }
        evolve<ItemAdded> { total += it.price }
        evolve<Paid> { paid = it.amount }
        evolve<Blocked> { blocked = true }
        evolve<Unblocked> { blocked = false }
    }
    private val orderRef = orders.type.ref("t", "o-1")

    private fun command(body: Any, requestId: String = UUID.randomUUID().toString()) =
        Command(UUID.randomUUID().toString(), requestId, orderRef, body)

    /** The payment as its log holds it now. */
    private suspend fun payment(): Payment {
        val version = paymentLog.read(paymentRef).toList().last().version
        return checkNotNull(Kernel(paymentLog).stateAt(payments, paymentRef, version)).state as Payment
    }

    /**
     * Another node commits [between] to the order while the first attempt of a command appends: the attempt meets a
     * version conflict and is decided again on the newer state.
     */
    private fun orderLogRacing(log: InMemoryEventLog, between: Any): EventLog {
        val other = Kernel(log)
        var raced = false
        return object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                if (!raced && stream.commandType.endsWith("Pay")) {
                    raced = true
                    other.execute(orders, command(between))
                }
                return log.append(stream, transport)
            }
        }
    }

    @Test
    fun `a call decided again after a version conflict is not answered by the call the conflict undid`() = runTest {
        val orderLog = InMemoryEventLog()
        val kernel = Kernel(orderLogRacing(orderLog, AddItem(5)))
        kernel.execute(orders, command(AddItem(10))).assert().isInstanceOf(CommandOutcome.Committed::class.java)

        val outcome = kernel.execute(orders, command(Pay()))

        // The order is paid for 15 (the second attempt saw the item added meanwhile).
        outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        ((outcome as CommandOutcome.Committed).state as Order).paid.assert().isEqualTo(15)
        // The first attempt's authorization (10) was voided by its compensation; the second attempt's (15) must hold.
        val payment = payment()
        payment.voided.assert().describedAs("the payment the committed order relies on is voided").isFalse()
        payment.authorized.assert().isEqualTo(25)
    }

    @Test
    fun `a retry of a rejected request is not answered by the call its rejection undid`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(orders, command(AddItem(10)))
        kernel.execute(orders, command(Block()))

        // Rejected: its authorization is voided by its compensation. A rejection is not recorded, so the caller may
        // send the same request again once the order is unblocked (the rejections guide).
        kernel.execute(orders, command(Pay(), "pay-1")).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        kernel.execute(orders, command(Unblock()))
        val retried = kernel.execute(orders, command(Pay(), "pay-1"))

        retried.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        payment().voided.assert().describedAs("the payment the committed order relies on is voided").isFalse()
    }
}
