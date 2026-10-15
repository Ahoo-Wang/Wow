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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
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
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import java.io.IOException

/** The outcomes of a saga's derived commands (design 01 §1): only a settled command completes the saga. */
class DerivedCommandsTest {
    private val log = InMemoryEventLog()

    /** What the log answers an append of a cart, instead of appending it, when not null. */
    @Volatile
    private var answer: AppendOutcome? = null

    private val failing = object : EventLog by log {
        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
            answer ?: log.append(stream, transport)
    }
    private val decided = mutableListOf<String>()
    private val cart = cartModel(decided)
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(failing, maxAttempts = 2)),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        WaitOptions(Origin("node-1", "node-1:9090", SHOP_SERVICE)),
    )
    private val derived = DerivedCommands(listOf(cart), coordinator)

    private fun command(id: String, body: Any) = Command(id, id, CART.ref(TENANT, "alice"), body)

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
                "is unknown: The log is gone.",
        )
        unknown.cause!!.message.assert().isEqualTo("The log is gone.")
        answer = AppendOutcome.Conflict(5)
        runCatching { derived.execute(listOf(command("c-3", AddItems(listOf("pear")))), null) }.exceptionOrNull()!!
            .message.assert().isEqualTo(
                "The derived command c-3 on shop.cart/t/alice failed: wow.command.contended Concurrent writers kept " +
                    "winning the version slot for 2 attempts.",
            )
    }

    @Test
    fun `a rejected command should stop the execution and say which it was, not fail it`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        val rejected = command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM)))
        val after = command("c-3", AddItems(listOf("pear")))
        val executed = derived.execute(listOf(rejected, after), null)
        // Decision D153: a business outcome, not a failure; the commands after it are not executed.
        executed.executed.assert().containsExactly("c-2")
        val rejection = executed.rejected!!
        // A command built without a causation is derived as its own root (decision D199).
        rejection.command.assert().isEqualTo(rejected.copyWith(causation = Causation("c-2", "c-2")))
        rejection.rejection.code.assert().isEqualTo(FORBIDDEN)
        rejection.error.code.assert().isEqualTo(FORBIDDEN)
        rejection.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        decided.assert().doesNotContain("c-3")
        val execution = ExecutionId("CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2)
        val handed = rejection.toCommandRejected(execution)
        handed.commandId.assert().isEqualTo("c-2")
        handed.commandType.assert().isEqualTo("shop.cart.RemoveItems")
        handed.ref.assert().isEqualTo(rejected.ref)
        handed.executionId.assert().isEqualTo(execution)
        // A command without a causation is its own root.
        handed.causation.assert().isEqualTo(Causation("c-2", "c-2"))
        val failure = DerivedCommandRejectedException(rejected, rejection.rejection)
        failure.error.code.assert().isEqualTo(FORBIDDEN)
        failure.message!!.assert().contains("c-2", FORBIDDEN)
    }
}
