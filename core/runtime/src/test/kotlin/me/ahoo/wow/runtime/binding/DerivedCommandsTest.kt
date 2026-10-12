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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
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
                .assert().containsExactly("c-1", "c-2")
            derived.execute(listOf(add), null).assert().containsExactly("c-1")
            decided.assert().containsExactly("c-2")
        }

    @Test
    fun `a command whose outcome is unknown or contended should fail the saga`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        answer = AppendOutcome.Unknown(IOException("The log is gone."))
        runCatching { derived.execute(listOf(command("c-2", AddItems(listOf("pear")))), null) }.exceptionOrNull()!!
            .message.assert().isEqualTo("The outcome of the derived command c-2 on shop.cart/t/alice is unknown.")
        answer = AppendOutcome.Conflict(5)
        runCatching { derived.execute(listOf(command("c-3", AddItems(listOf("pear")))), null) }.exceptionOrNull()!!
            .message.assert().isEqualTo("The derived command c-3 on shop.cart/t/alice was contended 2 times.")
    }

    @Test
    fun `a rejected command should fail the saga with its rejection`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        val failure = runCatching {
            derived.execute(listOf(command("c-2", RemoveItems(listOf(FORBIDDEN_ITEM)))), null)
        }.exceptionOrNull() as DerivedCommandRejectedException
        failure.rejection.code.assert().isEqualTo(FORBIDDEN)
        failure.command.id.assert().isEqualTo("c-2")
    }
}
