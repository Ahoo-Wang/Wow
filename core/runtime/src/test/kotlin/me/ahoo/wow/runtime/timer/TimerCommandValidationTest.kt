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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.RemoveItems
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/**
 * A command a context's timer fires at an aggregate of its own context is checked by the node's command validators
 * before it runs, as an admitted, a derived or a topic's command is: one they refuse is the timer's rejected command,
 * never decided.
 */
class TimerCommandValidationTest {
    private val log = InMemoryEventLog()
    private val cart = cartModel()
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(log)),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )

    /** As Bean Validation's constraints would be: no item named [POISON]. */
    private val noPoison = CommandValidator { command ->
        val body = command.body
        if (body is RemoveItems && POISON in body.items) listOf(VIOLATION) else emptyList()
    }
    private val firer = ContextTimerFirer(listOf(cart), coordinator, validators = listOf(noPoison))

    private fun timer(id: String, items: String) = Timer(
        id = id,
        owner = CART.ref(TENANT, "owner"),
        key = "key",
        version = 1,
        partition = 0,
        rule = ScheduleRule.At(0),
        dueAt = 0,
        target = CART.ref(TENANT, "alice"),
        commandType = "shop.cart.AddItems",
        body = """{"items":$items}""",
    )

    private fun removing(id: String, items: String) = timer(id, items).copyWith(commandType = "shop.cart.RemoveItems")

    @Test
    fun `a timer's command the validators refuse should be its rejected command, never decided`(): Unit =
        runBlocking {
            firer.fire(timer("t-add", """["apple"]""")).assert().isEqualTo(TimerOutcomes.COMMITTED)

            firer.fire(removing("t-poison", """["$POISON"]""")).assert().isEqualTo(TimerOutcomes.REJECTED)
            // Nothing was decided: the log holds the first command's commit alone, its id the timer's `timer#slot`.
            log.read(CART.ref(TENANT, "alice")).toList().map { it.commandId }.assert().containsExactly("t-add#1")

            firer.fire(removing("t-remove", """["apple"]""")).assert().isEqualTo(TimerOutcomes.COMMITTED)
        }

    private companion object {
        const val POISON = "poison"
        val VIOLATION = Violation("items", "no-poison", "An item may not be $POISON.")
    }
}
