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
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A command a client sends while deciding takes a request id derived from the deciding request (decision D314 ④): its
 * aggregate and its request id, which a caller's retry keeps, not its command id, which every arrival gets anew. That
 * is a call that stands, the same call in every attempt; a call the decision undoes in `onAppendFailure` takes an id of
 * its attempt's own (`attemptKey()`), so that the next attempt, or a caller's retry, is not answered with the call it undid
 * (#6734).
 */
class KernelDerivedRequestsTest {
    data class Open(val by: String)
    data class Charge(val amount: Int)
    data class Opened(val by: String)
    data class Charged(val amount: Int)
    data class Fee(val amount: Int)

    class Account(val id: String)

    /** The request ids a client would send in each attempt: two calls, the second with the caller's own id. */
    private val sent = mutableListOf<List<String?>>()

    private val model = aggregate<Account>("test", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decideSuspending<Charge> { _, body ->
            val context = currentCoroutineContext()
            sent += listOf(
                context.requestIdOf(CommandRequest.of(Fee(body.amount), "peer-1")),
                context.requestIdOf(CommandRequest.of(Fee(body.amount), "peer-1", requestId = "own")),
                context.requestIdOf(CommandRequest.of(Fee(body.amount), "peer-2")),
            )
            Decision.events(Charged(body.amount))
        }
        evolve<Opened> { }
        evolve<Charged> { }
    }
    private val ref = model.type.ref("t", "a-1")

    private suspend fun opened(): EventLog = InMemoryEventLog().also {
        Kernel(it).execute(model, Command("open", "open", ref, Open("alice")))
    }

    @Test
    fun `a decision sends its n-th command as the n-th request derived from the deciding request`() = runTest {
        Kernel(opened()).execute(model, Command("c-1", "r-1", ref, Charge(1)))
        sent.single().assert().containsExactly(
            DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 0),
            "own",
            DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 1),
        )
    }

    @Test
    fun `a caller's retry of an attempt that called a peer and did not commit sends the same requests again`() =
        runTest {
            val log = opened()
            val refusing = object : EventLog by log {
                var refused = false
                override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                    if (!refused) {
                        refused = true
                        return AppendOutcome.Rejected.of(IllegalStateException("The store refused the append."))
                    }
                    return log.append(stream, transport)
                }
            }
            sent.clear()
            val kernel = Kernel(refusing)
            // A refused append committed nothing, and fails the attempt.
            runCatching { kernel.execute(model, Command("c-1", "r-1", ref, Charge(1))) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
            // The retry arrives anew: the gateway gives it another command id, and it keeps the request id.
            kernel.execute(model, Command("c-2", "r-1", ref, Charge(1)))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            sent.assert().hasSize(2)
            sent[1].assert().isEqualTo(sent[0])
        }

    @Test
    fun `a decision decided again after a conflict sends the same requests again`() = runTest {
        val log = opened()
        val racing = object : EventLog by log {
            var raced = false
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                if (!raced) {
                    raced = true
                    Kernel(log).execute(model, Command("other", "other", ref, Charge(2)))
                }
                return log.append(stream, transport)
            }
        }
        sent.clear()
        Kernel(racing).execute(model, Command("c-1", "r-1", ref, Charge(1)))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
        sent.assert().hasSize(3)
        sent[0].assert().isEqualTo(sent[2])
        sent[0].assert().doesNotContainAnyElementsOf(sent[1].filterNot { it == "own" })
    }

    // A peer context: a payment that is authorized for an amount, and voided.
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
    private val paymentRef = payments.type.ref("t", "p-1")

    /**
     * What a Wow client does from a decide function: sends [body] to the payment under the request's own id, else the
     * one derived from the command being decided; a duplicate is answered as success, as the client's SUCCEEDED.
     */
    private suspend fun call(body: Any, requestId: String? = null) {
        val request = CommandRequest.of(body, paymentRef.id, payments.type, requestId = requestId)
        val id = checkNotNull(currentCoroutineContext().requestIdOf(request) ?: requestId)
        val outcome = Kernel(paymentLog).execute(payments, Command(UUID.randomUUID().toString(), id, paymentRef, body))
        check(outcome is CommandOutcome.Committed || outcome is CommandOutcome.AlreadyCommitted) { "$outcome" }
    }

    // An order paid by authorizing its total, its items added meanwhile.
    data class AddItem(val price: Int)
    data class ItemAdded(val price: Int)
    class Pay
    class PayStanding
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
            // A call this attempt undoes, under an id of the attempt's own, as the guide's "Undo an outside act" does.
            call(Authorize(state.total), requestId = attemptKey())
            onAppendFailure { call(Void(), requestId = attemptKey()) }
            if (state.blocked) reject("order.blocked", "The order is blocked.")
            Decision.events(Paid(state.total))
        }
        decideSuspending<PayStanding> { state, _ ->
            // A call that stands, keyed on the command, whose body depends on the state.
            call(Authorize(state.total))
            Decision.events(Paid(state.total))
        }
        evolve<ItemAdded> { total += it.price }
        evolve<Paid> { paid = it.amount }
        evolve<Blocked> { blocked = true }
        evolve<Unblocked> { blocked = false }
    }
    private val orderRef = orders.type.ref("t", "o-1")

    private fun order(body: Any, requestId: String = UUID.randomUUID().toString()) =
        Command(UUID.randomUUID().toString(), requestId, orderRef, body)

    /** The payment as its log holds it now. */
    private suspend fun payment(): Payment {
        val version = paymentLog.read(paymentRef).toList().last().version
        return checkNotNull(Kernel(paymentLog).stateAt(payments, paymentRef, version)).state as Payment
    }

    /** The order's log, where another node adds an item while the first attempt of a payment appends. */
    private fun racedOrders(log: InMemoryEventLog): EventLog {
        var raced = false
        return object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                if (!raced && stream.commandType.contains(".Pay")) {
                    raced = true
                    Kernel(log).execute(orders, order(AddItem(5)))
                }
                return log.append(stream, transport)
            }
        }
    }

    @Test
    fun `a call undone after a version conflict is made again under an id of the attempt's own`() = runTest {
        val kernel = Kernel(racedOrders(InMemoryEventLog()))
        kernel.execute(orders, order(AddItem(10)))

        val paid = kernel.execute(orders, order(Pay())) as CommandOutcome.Committed

        (paid.state as Order).paid.assert().isEqualTo(15)
        // The first attempt's authorization of 10 was voided; the second attempt's of 15 holds.
        val payment = payment()
        payment.voided.assert().isFalse()
        payment.authorized.assert().isEqualTo(25)
    }

    @Test
    fun `a caller's retry of a rejected request makes its undone call again`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(orders, order(AddItem(10)))
        kernel.execute(orders, order(Block()))
        // Rejected, its authorization voided; a rejection is not recorded, so the same request may be sent again.
        kernel.execute(orders, order(Pay(), "pay-1")).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        kernel.execute(orders, order(Unblock()))

        kernel.execute(orders, order(Pay(), "pay-1")).assert().isInstanceOf(CommandOutcome.Committed::class.java)

        payment().voided.assert().isFalse()
        payment().authorized.assert().isEqualTo(20)
    }

    @Test
    fun `a call that stands and changes when decided again fails the command, naming the call`() = runTest {
        val kernel = Kernel(racedOrders(InMemoryEventLog()))
        kernel.execute(orders, order(AddItem(10)))

        // The second attempt, on the item added meanwhile, would authorize 15 under the id of the authorization of 10.
        val failure = runCatching { kernel.execute(orders, order(PayStanding())) }.exceptionOrNull()

        failure.assert().isInstanceOf(Misuse.State::class.java)
        failure!!.message.assert().contains(
            "call #1",
            "Authorize {\"amount\":15}",
            "Authorize {\"amount\":10}",
            "attemptKey()",
        )
        payment().authorized.assert().isEqualTo(10)
    }

    @Test
    fun `outside a decision a client chooses the request id itself`() = runTest {
        currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(1), "peer-1")).assert().isNull()
        currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(1), "peer-1", requestId = "own"))
            .assert().isEqualTo("own")
    }
}
