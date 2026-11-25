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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.FailureReasons
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.CreationIds
import me.ahoo.wow.runtime.admission.admissionOf
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/** The outcomes of a saga's derived commands (design 01 §1): only a settled command completes the saga. */
class DerivedCommandsTest {
    private val root = Causation("root", "shop.order/$TENANT/o-1@2")
    private val log = InMemoryEventLog()

    /** What the log answers an append of a cart, instead of appending it, when not null. */
    @Volatile
    private var answer: AppendOutcome? = null

    private val failing = object : EventLog by log {
        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
            answer ?: log.append(stream, transport)
    }
    private val decided = mutableListOf<String>()
    private val cart = cartModel(decided)
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(failing, options = KernelOptions(maxAttempts = 2))),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin(SHOP_SERVICE, "node-1", "node-1:9090"),
        WaitOptions(),
    )
    private val derived = DerivedCommands(listOf(cart), coordinator, admission = admissionOf())

    /** A command a saga execution of the order `o-1` derived. */
    private fun command(id: String, body: Any) = Command(id, id, CART.ref(TENANT, "alice"), body, causation = root)

    @Test
    fun `committed, unchanged and already committed commands should complete the saga, in order`(): Unit =
        runBlocking {
            val add = command("c-1", AddItems(listOf("apple")))
            derived.execute(listOf(add, command("c-2", RemoveItems(listOf("pear")))), TENANT, null)
                .executed.assert().containsExactly("c-1", "c-2")
            derived.execute(listOf(add), TENANT, null).executed.assert().containsExactly("c-1")
            decided.assert().containsExactly("c-2")
        }

    @Test
    fun `a creation that names no cart should take its id from its request, the same when derived again (D191)`(): Unit =
        runBlocking {
            var drawn = 0
            val ids = IdGenerators(aggregates = AggregateIdGenerator { "cart-n${++drawn}" })
            val creating =
                DerivedCommands(
                    listOf(cart),
                    coordinator,
                    admission = admissionOf(CreationIds(ids, CommittedRequests.of(log)))
                )
            val add = Command("c-new", "c-new", CART.unassigned(TENANT), AddItems(listOf("apple")))
            creating.execute(listOf(add), TENANT, null).executed.assert().containsExactly("c-new")
            log.findByRequestId(CART, TENANT, "c-new")!!.ref.id.assert().isEqualTo("cart-n1")
            // Handling the commit again derives the same request: it finds the cart it created and draws no id.
            creating.execute(listOf(add), TENANT, null).executed.assert().containsExactly("c-new")
            drawn.assert().isEqualTo(1)
            // A command that does not create its aggregate cannot leave its id to admission.
            val remove = Command("c-remove", "c-remove", CART.unassigned(TENANT), RemoveItems(listOf("apple")))
            runCatching { creating.execute(listOf(remove), TENANT, null) }.exceptionOrNull()!!.message.assert()
                .contains("only a command that creates its aggregate may leave its id to admission")
        }

    @Test
    fun `a command to a wallet naming none and with no owner is rejected as malformed, not failed (#6558)`(): Unit =
        runBlocking {
            // A wallet's id is its owner's: a derived or scheduled command with no owner has none to take.
            val wallet = aggregate<Any>(SHOP, "wallet") {
                id(AggregateIdSource.OWNER)
                initial { Any() }
                decide<AddItems>(Creation.ALLOWED) { _, _ -> Decision.events(ItemsAdded(listOf())) }
                evolve<ItemsAdded> { }
            }
            val owning = DerivedCommands(listOf(wallet), coordinator, admission = admissionOf())
            val type = wallet.type
            val unowned = Command("w-1", "w-1", type.unassigned(TENANT), AddItems(listOf("apple")), causation = root)
            val owned = unowned.copyWith(id = "w-2", requestId = "w-2", scope = Scope("alice"))
            val executed = owning.execute(listOf(unowned, owned), TENANT, null)
            executed.executed.assert().containsExactly("w-1", "w-2")
            val rejected = executed.rejected.single()
            rejected.command.assert().isEqualTo(unowned)
            rejected.commandType.assert().isEqualTo("shop.wallet.AddItems")
            rejected.rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_MALFORMED)
            rejected.rejection.message.assert().isEqualTo("This caller has no owner; address the aggregate by its id.")
            executed.committed.single().result.ref.assert().isEqualTo(type.ref(TENANT, "alice"))
        }

    @Test
    fun `a command naming an id admission refuses, or taking such an owner's, is rejected as malformed`(): Unit =
        runBlocking {
            // #6594: a saga's command reaches no aggregate the gateway's routes could not, as admission refuses it.
            val wallet = aggregate<Any>(SHOP, "wallet") {
                id(AggregateIdSource.OWNER)
                initial { Any() }
                decide<AddItems>(Creation.ALLOWED) { _, _ -> Decision.events(ItemsAdded(listOf())) }
                evolve<ItemsAdded> { }
            }
            val deriving = DerivedCommands(listOf(wallet), coordinator, admission = admissionOf())
            val add = AddItems(listOf("apple"))
            val named = Command("w-1", "w-1", wallet.type.ref(TENANT, "a/b"), add, causation = root)
            val owned = Command("w-2", "w-2", wallet.type.unassigned(TENANT), add, causation = root)
                .copyWith(scope = Scope("a;b"))
            val executed = deriving.execute(listOf(named, owned), TENANT, null)
            executed.rejected.map { it.command.id to it.rejection.code }.assert().containsExactly(
                "w-1" to FrameworkCodes.COMMAND_MALFORMED,
                "w-2" to FrameworkCodes.COMMAND_MALFORMED,
            )
            executed.rejected.first().rejection.message.assert().contains("'a/b' holds '/'")
            executed.rejected.last().rejection.message.assert().contains("'a;b' holds ';'")
            executed.committed.assert().isEmpty()
        }

    @Test
    fun `a command whose outcome is unknown or contended should fail the saga`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), TENANT, null)
        answer = AppendOutcome.Unknown(IOException("The log is gone."))
        val unknown = runCatching { derived.execute(listOf(command("c-2", AddItems(listOf("pear")))), TENANT, null) }
            .exceptionOrNull()!!
        unknown.message.assert().isEqualTo(
            "The derived command c-2 on shop.cart/t/alice failed: wow.command.unknown Whether the command committed " +
                "is unknown; retrying it with the same request id is safe.",
        )
        unknown.cause!!.message.assert().isEqualTo("The log is gone.")
        // With its code, so the subscription tells backpressure from a failure (decision D263).
        (unknown as WowProblemException).code.assert().isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
        unknown.retryable.assert().isTrue()
        answer = AppendOutcome.Conflict(5)
        runCatching {
            derived.execute(
                listOf(command("c-3", AddItems(listOf("pear")))),
                TENANT,
                null
            )
        }.exceptionOrNull()!!
            .message.assert().isEqualTo(
                "The derived command c-3 on shop.cart/t/alice failed: wow.command.contended Concurrent writers kept " +
                    "winning the version slot for 2 attempts.",
            )
    }

    @Test
    fun `a rejected command should be settled on its own, the others executed, and say which it was`(): Unit =
        runBlocking {
            val first = command("c-1", AddItems(listOf("apple")))
            val rejected = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM)))
            val after = command("c-3", AddItems(listOf("pear")))
            val executed = derived.execute(listOf(first, rejected, after), TENANT, null)
            // Decision D153: a business outcome, not a failure; it does not stop the commands after it.
            executed.executed.assert().containsExactly("c-1", "c-2", "c-3")
            executed.committed.map { it.command.id }.assert().containsExactly("c-1", "c-3")
            executed.rejected.map { it.command.id }.assert().containsExactly("c-2")
            log.read(CART.ref(TENANT, "alice")).toList().map { it.requestId }.assert().containsExactly("c-1", "c-3")
        }

    @Test
    fun `the commands to one aggregate should keep their order, whatever the others'`(): Unit = runBlocking {
        fun to(cart: String, id: String) = Command(id, id, CART.ref(TENANT, cart), AddItems(listOf(id)))
        val commands = (1..5).flatMap { listOf(to("alice", "a-$it"), to("bob", "b-$it"), to("carol", "c-$it")) }
        derived.execute(commands, TENANT, null).executed.assert().isEqualTo(commands.map { it.id })
        listOf("alice", "bob", "carol").forEach { cart ->
            // Each cart's commits, in its versions' order, are its commands in the order the saga derived them.
            log.read(CART.ref(TENANT, cart)).toList().map { it.requestId }.assert()
                .isEqualTo(commands.filter { it.ref.id == cart }.map { it.id })
        }
    }

    @Test
    fun `a fan-out whose target was deleted midway should recompile every other target, the deleted one rejected`(): Unit =
        runBlocking {
            // A recompilation of every dataset that reads a source (DatasetRecompilation): one was deleted since.
            val carts = (1..20).map { "cart-$it" }
            fun add(id: String, cart: String) = Command(id, id, CART.ref(TENANT, cart), AddItems(listOf(id)))
            derived.execute(carts.map { add("open-$it", it) }, TENANT, null)
            derived.execute(
                listOf(Command("delete", "delete", CART.ref(TENANT, "cart-7"), DeleteAggregate())),
                TENANT,
                null
            )
                .rejected.assert().isEmpty()
            val recompiled = carts.map { add("again-$it", it) }
            val executed = derived.execute(recompiled, TENANT, null)
            executed.executed.assert().isEqualTo(recompiled.map { it.id })
            executed.rejected.single().let {
                it.command.ref.id.assert().isEqualTo("cart-7")
                it.rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
            }
            executed.committed.map { it.command.ref.id }.assert().isEqualTo(carts - "cart-7")
        }

    @Test
    fun `a rejected command should say which it was, not fail the execution`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), TENANT, null)
        val rejected = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM)))
        val after = command("c-3", AddItems(listOf("pear")))
        val executed = derived.execute(listOf(rejected, after), TENANT, null)
        executed.executed.assert().containsExactly("c-2", "c-3")
        val rejection = executed.rejected.single()
        rejection.command.assert().isEqualTo(rejected)
        rejection.rejection.code.assert().isEqualTo(FORBIDDEN)
        rejection.error.code.assert().isEqualTo(FORBIDDEN)
        rejection.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        log.findByRequestId(CART, TENANT, "c-3").assert().isNotNull()
        val execution = ExecutionId(ORDER.context, "CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2)
        val handed = rejection.toReceipt(execution)
        handed.commandId.assert().isEqualTo("c-2")
        handed.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        handed.ref.assert().isEqualTo(rejected.ref)
        handed.executionId.assert().isEqualTo(execution)
        handed.causation.assert().isEqualTo(root)
        val failure = DerivedCommandRejectedException(listOf(rejected to rejection.rejection))
        failure.error.code.assert().isEqualTo(FORBIDDEN)
        failure.message!!.assert().contains("c-2", FORBIDDEN)
        // Several rejections no handler takes park once, listing every one.
        val both =
            DerivedCommandRejectedException(listOf(rejected to rejection.rejection, after to rejection.rejection))
        both.message!!.assert().contains("2 derived commands were rejected", "c-2", "c-3")
        both.command.assert().isEqualTo(rejected)
    }

    @Test
    fun `a derived command a require refuses should be rejected, not failed, so it is neither retried nor parked`(): Unit =
        runBlocking {
            derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), TENANT, null)
            // Decision D311: the require of the target's decide function is a rejection (D153), not a failure.
            val executed = derived.execute(listOf(command("c-2", RemoveItems(listOf(BLANK_ITEM)))), TENANT, null)
            executed.executed.assert().containsExactly("c-2")
            executed.rejected.single().rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
            executed.rejected.single().rejection.message.assert().isEqualTo("An item has a name.")
        }

    class Quote
    class Quoted

    /** An aggregate whose decide function bounds a call with `withTimeout`, and the call does not answer in time. */
    private val quote = aggregate<Any>(SHOP, "quote") {
        initial { Any() }
        decideSuspending<Quote>(Creation.ALLOWED) { _, _ -> withTimeout(10.milliseconds) { awaitCancellation() } }
        evolve<Quoted> { }
    }

    @Test
    fun `a derived command whose decide function's own call timed out should fail the saga, saying so`(): Unit =
        runBlocking {
            // #6737: the timeout is a failure of the command's handling, retried and then parked as any failure; the
            // saga's handling is not cancelled by it, nor does the command vanish from its outcomes.
            val quoting = DerivedCommands(listOf(quote), coordinator, admission = admissionOf())
            val command = Command("q-1", "q-1", quote.type.ref(TENANT, "q"), Quote(), causation = root)
            val failure = runCatching { quoting.execute(listOf(command), TENANT, null) }.exceptionOrNull()

            failure.assert().isInstanceOf(WowProblemException::class.java)
            (failure as WowProblemException).code.assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
            failure.message.assert().contains("q-1", "CancelledDecisionException")
        }

    class Charge
    class Charged

    /** A decide function that fails, as one whose driver names its connection does. */
    private val payment = aggregate<Any>(SHOP, "payment") {
        initial { Any() }
        decide<Charge>(Creation.ALLOWED) { _, _ ->
            throw UnsupportedOperationException("secret jdbc:postgresql://db-internal:5432/pay?password=hunter2")
        }
        evolve<Charged> { }
    }

    @Test
    fun `a derived command that failed should park its saga with a reason that names no value it failed on`(): Unit =
        runBlocking {
            // #6796: the problem's message is kept in the parked reason, so it names its cause by its class alone.
            val paying = DerivedCommands(listOf(payment), coordinator, admission = admissionOf())
            val command = Command("p-1", "p-1", payment.type.ref(TENANT, "p"), Charge(), causation = root)
            val failure = checkNotNull(runCatching { paying.execute(listOf(command), TENANT, null) }.exceptionOrNull())

            val reason = FailureReasons.DEFAULT.reasonOf(failure)

            reason.assert().doesNotContain("secret", "hunter2", "db-internal")
                .contains("p-1", FrameworkCodes.COMMAND_FAILED, UnsupportedOperationException::class.java.name)
            failure.cause.assert().isInstanceOf(UnsupportedOperationException::class.java)
        }

    @Test
    fun `a receipt should carry only the request facts that propagate of its command`(): Unit = runBlocking {
        val facts = mapOf(RequestFacts.APP_ID to "console", RequestFacts.REMOTE_IP to "10.0.0.7", "X-Trace" to "t")
        val added = command("c-1", AddItems(listOf("apple"))).copyWith(headers = facts)
        val removed = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM))).copyWith(headers = facts)
        val executed = derived.execute(listOf(added, removed), TENANT, null)
        val execution = ExecutionId(ORDER.context, "CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2)
        val propagated = mapOf(RequestFacts.APP_ID to "console")
        executed.committed.single().toReceipt(execution).headers.assert().isEqualTo(propagated)
        executed.rejected.single().toReceipt(execution).headers.assert().isEqualTo(propagated)
    }
}
