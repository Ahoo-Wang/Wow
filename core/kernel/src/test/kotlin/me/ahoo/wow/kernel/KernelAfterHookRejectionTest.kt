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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An after hook is handed the decide function's `DecideScope`, whose `reject` the kernel answers as that rejection
 * (#7024): the command is rejected, commits none of the events decided before the hook, and the attempt's
 * compensations run, as for a decide function's refusal.
 */
class KernelAfterHookRejectionTest {
    data class Open(val by: String)
    data class Touch(val reason: String)
    data class Opened(val by: String)
    data class Touched(val reason: String)
    data class Audited(val reason: String)
    data class Undeclared(val reason: String)

    class Counter(val id: String) {
        var touches = 0
    }

    private val trail = mutableListOf<String>()

    private fun model(hook: AggregateModelBuilder<Counter>.() -> Unit) = aggregate<Counter>("test", "counter") {
        initial { Counter(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decide<Touch> { _, body ->
            onAppendFailure { trail += "undo ${body.reason}" }
            Decision.events(Touched(body.reason))
        }
        hook()
        evolve<Opened> { }
        evolve<Touched> { touches++ }
        evolve<Audited> { }
    }

    private val refusing = model {
        after(exclude = setOf(Open::class.java)) { _, body ->
            if ((body as Touch).reason == "late") reject("audit.refused", "the audit refuses ${body.reason}")
            listOf(Audited(body.reason))
        }
    }

    private suspend fun opened(model: AggregateModel<Counter>, log: InMemoryEventLog = InMemoryEventLog()) =
        log.also { Kernel(it).execute(model, Command("c-0", "r-0", model.type.ref("t", "c-1"), Open("me"))) }

    private fun touch(model: AggregateModel<Counter>, reason: String) =
        Command("c-1", "r-1", model.type.ref("t", "c-1"), Touch(reason))

    @Test
    fun `an after hook's reject should be answered as that rejection, committing none of the command's events`() =
        runTest {
            val log = opened(refusing)

            val outcome = Kernel(log).execute(refusing, touch(refusing, "late"))

            outcome.assert().isInstanceOf(CommandOutcome.Rejected::class.java)
            (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("audit.refused")
            outcome.rejection.message.assert().isEqualTo("the audit refuses late")
            log.read(refusing.type.ref("t", "c-1")).toList().map { it.version }.assert().containsExactly(1)
        }

    @Test
    fun `an after hook's rejection should run the attempt's compensations`() = runTest {
        val log = opened(refusing)

        Kernel(log).execute(refusing, touch(refusing, "late"))

        trail.assert().containsExactly("undo late")
    }

    @Test
    fun `an after hook that does not refuse should append its events after the command's own`() = runTest {
        val log = opened(refusing)

        val outcome = Kernel(log).execute(refusing, touch(refusing, "fine")) as CommandOutcome.Committed

        outcome.stream.events.map { it.name }.assert().containsExactly("test.counter.Touched", "test.counter.Audited")
        trail.assert().isEmpty()
    }

    @Test
    fun `an after hook's thrown refusal should be answered as a decide function's is`() = runTest {
        val checking = model {
            after(exclude = setOf(Open::class.java)) { _, _ ->
                require(false) { "not now" }
                emptyList()
            }
        }
        val log = opened(checking)

        val outcome = Kernel(log).execute(checking, touch(checking, "x"))

        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(FrameworkCodes.COMMAND_INVALID)
        trail.assert().containsExactly("undo x")
    }

    @Test
    fun `an after hook's failure that is no refusal should fail the command, as a decide function's does`() =
        runTest {
            val failing = model {
                after(exclude = setOf(Open::class.java)) { _, _ -> throw UnsupportedOperationException("boom") }
            }
            val log = opened(failing)

            assertThrows<UnsupportedOperationException> { Kernel(log).execute(failing, touch(failing, "x")) }

            trail.assert().containsExactly("undo x")
            log.read(failing.type.ref("t", "c-1")).toList().map { it.version }.assert().containsExactly(1)
        }

    @Test
    fun `an after hook's rejection should commit the events it rejects with alone, as a decide function's does`() =
        runTest {
            val declaring = model {
                after(exclude = setOf(Open::class.java), produces = setOf(Audited::class.java)) { _, body ->
                    reject("audit.refused", "refused", events = listOf(Audited((body as Touch).reason)))
                }
            }
            val log = opened(declaring)

            val outcome = Kernel(log).execute(declaring, touch(declaring, "x")) as CommandOutcome.Committed

            outcome.rejection?.code.assert().isEqualTo("audit.refused")
            outcome.stream.events.map { it.name }.assert().containsExactly("test.counter.Audited")
        }

    @Test
    fun `an after hook's rejection with an event it does not declare should fail, naming the hook`() = runTest {
        val undeclared = model {
            after(exclude = setOf(Open::class.java), produces = setOf(Audited::class.java)) { _, _ ->
                reject("audit.refused", "refused", events = listOf(Undeclared("x")))
            }
        }
        val log = opened(undeclared)

        val failure = assertThrows<UndeclaredEventException> {
            Kernel(log).execute(undeclared, touch(undeclared, "x"))
        }

        failure.message.assert().contains("after hook", "Undeclared")
    }

    @Test
    fun `an after hook's rejection with a lifecycle event should fail, as a decide function's does`() = runTest {
        val deleting = model {
            after(exclude = setOf(Open::class.java)) { _, _ ->
                reject("audit.refused", "refused", events = listOf(AggregateDeleted()))
            }
        }
        val log = opened(deleting)

        val failure = assertThrows<UndeclaredEventException> { Kernel(log).execute(deleting, touch(deleting, "x")) }

        failure.message.assert().contains("after hook", "AggregateDeleted")
    }
}
