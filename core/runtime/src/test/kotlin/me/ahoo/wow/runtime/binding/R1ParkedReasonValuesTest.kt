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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.FailureReasons
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.admissionOf
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test

/**
 * Deep review r1, second part (kernel, and the runtime's side): a parked aggregate's reason is "what failed, never a
 * value it failed on" (#6588, [FailureReasons]). It keeps the message of a failure written for a caller, a
 * [me.ahoo.wow.api.problem.WowProblemException] among them. `DerivedCommands.failureOf` throws such a problem for a
 * saga's derived command that failed, and writes into its message the raw failure the command's handling threw
 * (`settlement.cause`), with that failure's own message: a decide function's failure that names a value (a
 * connection string, a key) leaves the node in the parked reason, through the ops routes and the park webhook.
 */
class R1ParkedReasonValuesTest {
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

    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog())),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "node-1:9090", SHOP_SERVICE),
        WaitOptions(),
    )

    @Test
    fun `a saga's derived command that failed parks with a reason that names no value it failed on`(): Unit =
        runBlocking {
            val derived = DerivedCommands(listOf(payment), coordinator, admission = admissionOf())
            val root = Causation("root", "shop.order/$TENANT/o-1@2")
            val command = Command("p-1", "p-1", payment.type.ref(TENANT, "p"), Charge(), causation = root)
            val failure = checkNotNull(runCatching { derived.execute(listOf(command), TENANT, null) }.exceptionOrNull())

            val reason = FailureReasons.DEFAULT.reasonOf(failure)

            reason.assert().describedAs("the parked reason").doesNotContain("secret", "hunter2", "db-internal")
        }
}
