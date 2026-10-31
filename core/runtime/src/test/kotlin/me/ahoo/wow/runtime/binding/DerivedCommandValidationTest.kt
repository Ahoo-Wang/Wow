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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/** A saga's derived command of its own context is checked by the node's validators, as an admitted command is. */
class DerivedCommandValidationTest {
    private val root = Causation("root", "shop.order/$TENANT/o-1@2")
    private val decided = mutableListOf<String>()
    private val cart = cartModel(decided)
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog())),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )

    /** As Bean Validation's constraints would be: no item named [POISON]. */
    private val noPoison = CommandValidator { _, body ->
        if (body is RemoveItems && POISON in body.items) listOf(VIOLATION) else emptyList()
    }
    private val derived = DerivedCommands(listOf(cart), coordinator, validators = listOf(noPoison))

    private fun command(id: String, body: Any) = Command(id, id, CART.ref(TENANT, "alice"), body, causation = root)

    @Test
    fun `a derived command the validators refuse should be rejected as invalid with its violations, never decided`(): Unit =
        runBlocking {
            derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
            val executed = derived.execute(
                listOf(command("c-2", RemoveItems(listOf(POISON))), command("c-3", RemoveItems(listOf("apple")))),
                null,
            )
            executed.executed.assert().containsExactly("c-2", "c-3")
            val rejected = executed.rejected.single()
            rejected.command.id.assert().isEqualTo("c-2")
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
            rejected.error.violations.assert().containsExactly(VIOLATION)
            // The saga's @OnRejected handler takes it with the violations, as it takes a decided rejection.
            val receipt = rejected.toReceipt(ExecutionId("CartSaga.onEvent", ORDER.ref(TENANT, "o-1"), 2))
            receipt.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
            receipt.error.violations.assert().containsExactly(VIOLATION)
            // No aggregate decided it; the valid command after it ran.
            decided.assert().containsExactly("c-3")
            executed.committed.map { it.command.id }.assert().containsExactly("c-3")
        }

    @Test
    fun `a derived command no validator refuses should run as before`(): Unit = runBlocking {
        derived.execute(listOf(command("c-1", AddItems(listOf("apple")))), null)
        val executed = derived.execute(listOf(command("c-2", RemoveItems(listOf("apple")))), null)
        executed.rejected.assert().isEmpty()
        decided.assert().containsExactly("c-2")
    }

    private companion object {
        const val POISON = "poison"
        val VIOLATION = Violation("items", "no-poison", "An item may not be $POISON.")
    }
}
