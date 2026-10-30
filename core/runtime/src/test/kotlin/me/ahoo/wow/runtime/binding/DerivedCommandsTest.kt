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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommittedRequests
import me.ahoo.wow.runtime.admission.CreationIds
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
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )
    private val derived = DerivedCommands(listOf(cart), coordinator)

    /** A command a saga execution of the order `o-1` derived. */
    private fun command(id: String, body: Any) = Command(id, id, CART.ref(TENANT, "alice"), body, causation = root)

    @Test
    fun `committed, unchanged and already committed commands should complete the saga, in order`(): Unit =
        runBlocking {
            val add = command("c-1", AddItems(listOf("apple")))
            derived.execute(listOf(add, command("c-2", RemoveItems(listOf("pear")))), null)
                .executed.assert().containsExactly("c-1", "c-2")
            derived.execute(listOf(add), null).executed.assert().containsExactly("c-1")
            decided.assert().containsExactly("c-2")
        }

    @Test
    fun `a creation that names no cart should take its id from its request, the same when derived again (D191)`(): Unit =
        runBlocking {
            var drawn = 0
            val ids = IdGenerators(aggregates = AggregateIdGenerator { "cart-n${++drawn}" })
            val creating =
                DerivedCommands(listOf(cart), coordinator, creations = CreationIds(ids, CommittedRequests.of(log)))
            val add = Command("c-new", "c-new", CART.unassigned(TENANT), AddItems(listOf("apple")))
            creating.execute(listOf(add), null).executed.assert().containsExactly("c-new")
            log.findByRequestId(CART, TENANT, "c-new")!!.ref.id.assert().isEqualTo("cart-n1")
            // Handling the commit again derives the same request: it finds the cart it created and draws no id.
            creating.execute(listOf(add), null).executed.assert().containsExactly("c-new")
            drawn.assert().isEqualTo(1)
            // A command that does not create its aggregate cannot leave its id to admission.
            val remove = Command("c-remove", "c-remove", CART.unassigned(TENANT), RemoveItems(listOf("apple")))
            runCatching { creating.execute(listOf(remove), null) }.exceptionOrNull()!!.message.assert()
                .contains("only a command that creates its aggregate may leave its id to admission")
        }

    @Test
    fun `a command whose outcome is unknown or contended should fail the saga`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        answer = AppendOutcome.Unknown(IOException("The log is gone."))
        val unknown = runCatching { derived.execute(listOf(command("c-2", AddItems(listOf("pear")))), null) }
            .exceptionOrNull()!!
        unknown.message.assert().isEqualTo(
            "The derived command c-2 on shop.cart/t/alice failed: wow.command.unknown Whether the command committed " +
                "is unknown; retrying it with the same request id is safe.",
        )
        unknown.cause!!.message.assert().isEqualTo("The log is gone.")
        // With its code, so the subscription tells backpressure from a failure (decision D263).
        (unknown as WowProblemException).code.assert().isEqualTo(FrameworkErrors.COMMAND_UNKNOWN)
        unknown.retryable.assert().isTrue()
        answer = AppendOutcome.Conflict(5)
        runCatching { derived.execute(listOf(command("c-3", AddItems(listOf("pear")))), null) }.exceptionOrNull()!!
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
            val executed = derived.execute(listOf(first, rejected, after), null)
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
        derived.execute(commands, null).executed.assert().isEqualTo(commands.map { it.id })
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
            derived.execute(carts.map { add("open-$it", it) }, null)
            derived.execute(listOf(Command("delete", "delete", CART.ref(TENANT, "cart-7"), DeleteAggregate)), null)
                .rejected.assert().isEmpty()
            val recompiled = carts.map { add("again-$it", it) }
            val executed = derived.execute(recompiled, null)
            executed.executed.assert().isEqualTo(recompiled.map { it.id })
            executed.rejected.single().let {
                it.command.ref.id.assert().isEqualTo("cart-7")
                it.rejection.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
            }
            executed.committed.map { it.command.ref.id }.assert().isEqualTo(carts - "cart-7")
        }

    @Test
    fun `a rejected command should say which it was, not fail the execution`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        val rejected = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM)))
        val after = command("c-3", AddItems(listOf("pear")))
        val executed = derived.execute(listOf(rejected, after), null)
        executed.executed.assert().containsExactly("c-2", "c-3")
        val rejection = executed.rejected.single()
        rejection.command.assert().isEqualTo(rejected)
        rejection.rejection.code.assert().isEqualTo(FORBIDDEN)
        rejection.error.code.assert().isEqualTo(FORBIDDEN)
        rejection.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        log.findByRequestId(CART, TENANT, "c-3").assert().isNotNull()
        val execution = ExecutionId("CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2)
        val handed = rejection.toReceipt(execution)
        handed.commandId.assert().isEqualTo("c-2")
        handed.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        handed.ref.assert().isEqualTo(rejected.ref)
        handed.executionId.assert().isEqualTo(execution)
        handed.causation.assert().isEqualTo(root)
        val failure = DerivedCommandRejectedException(rejected, rejection.rejection)
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
            derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
            // Decision D311: the require of the target's decide function is a rejection (D153), not a failure.
            val executed = derived.execute(listOf(command("c-2", RemoveItems(listOf(BLANK_ITEM)))), null)
            executed.executed.assert().containsExactly("c-2")
            executed.rejected.single().rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
            executed.rejected.single().rejection.message.assert().isEqualTo("An item has a name.")
        }

    @Test
    fun `a receipt should carry only the request facts that propagate of its command`(): Unit = runBlocking {
        val facts = mapOf(RequestFacts.APP_ID to "console", RequestFacts.REMOTE_IP to "10.0.0.7", "X-Trace" to "t")
        val added = command("c-1", AddItems(listOf("apple"))).copyWith(headers = facts)
        val removed = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM))).copyWith(headers = facts)
        val executed = derived.execute(listOf(added, removed), null)
        val execution = ExecutionId("CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2)
        val propagated = mapOf(RequestFacts.APP_ID to "console")
        executed.committed.single().toReceipt(execution).headers.assert().isEqualTo(propagated)
        executed.rejected.single().toReceipt(execution).headers.assert().isEqualTo(propagated)
    }
}
