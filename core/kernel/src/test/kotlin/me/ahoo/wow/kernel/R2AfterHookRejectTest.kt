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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test

/**
 * `DecideScope`'s KDoc offers it to "a decide function (or an after hook)", and says of `reject` that "it throws a
 * [me.ahoo.wow.api.decide.RejectedException], which the kernel answers as that rejection". An after hook is given the
 * scope (`AfterFunction.Sync.after` is `DecideScope.(S, Any) -> List<Any>`), so it can call `reject`; the kernel's
 * `Decisions.after` does not answer it as a rejection.
 */
class R2AfterHookRejectTest {
    data class Open(val by: String)
    data class Touch(val reason: String)
    data class Opened(val by: String)
    data class Touched(val reason: String)

    class Counter(val id: String)

    private val model = aggregate<Counter>("test", "counter") {
        initial { Counter(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decide<Touch> { _, body -> Decision.events(Touched(body.reason)) }
        after(exclude = setOf(Open::class.java)) { _, _ -> reject("audit.refused", "the audit refuses it") }
        evolve<Opened> { }
        evolve<Touched> { }
    }
    private val ref = model.type.ref("t", "c-1")

    @Test
    fun `an after hook's reject is answered as that rejection, as DecideScope promises`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(model, Command("c-0", "r-0", ref, Open("me")))

        val outcome = runCatching { Kernel(log).execute(model, Command("c-1", "r-1", ref, Touch("x"))) }

        outcome.exceptionOrNull().assert().isNull()
        outcome.getOrThrow().assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        (outcome.getOrThrow() as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("audit.refused")
    }
}
