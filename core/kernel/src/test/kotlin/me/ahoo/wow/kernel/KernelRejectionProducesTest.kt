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
import me.ahoo.wow.api.decide.reject
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A rejection's events are held to the function's `produces`, as a decision's are, whenever it is known. */
class KernelRejectionProducesTest {
    data class OpenSafe(val owner: String)
    data class Unlock(val code: String)
    data class UnlockThrowing(val code: String)

    data class SafeOpened(val owner: String)
    data class Unlocked(val code: String)
    data class AttemptFailed(val code: String)

    class SafeState {
        var failures = 0
    }

    /** The safe whose [Unlock] declares [produces]: the success event, the rejection's event too, or nothing. */
    private fun model(produces: Set<Class<*>>?) = aggregate<SafeState>("vault", "safe") {
        initial { SafeState() }
        decide<OpenSafe>(Creation.REQUIRED) { _, command -> Decision.events(SafeOpened(command.owner)) }
        decide<Unlock>(produces = produces) { _, command ->
            if (command.code == "4242") {
                Decision.events(Unlocked(command.code))
            } else {
                Decision.reject("wrong-code", "Wrong code.", events = listOf(AttemptFailed(command.code)))
            }
        }
        decide<UnlockThrowing>(produces = produces) { _, command ->
            reject("wrong-code", "Wrong code.", events = listOf(AttemptFailed(command.code)))
        }
        evolve<SafeOpened> { }
        evolve<Unlocked> { }
        evolve<AttemptFailed> { failures++ }
    }

    private val undeclared = model(setOf(Unlocked::class.java))
    private val declared = model(setOf(Unlocked::class.java, AttemptFailed::class.java))
    private val unknown = model(null)
    private val ref = undeclared.type.ref("tenant", "safe-1")
    private var sequence = 0

    private fun command(body: Any) =
        Command(id = "command-${++sequence}", requestId = "request-$sequence", ref = ref, body = body)

    private suspend fun opened(log: InMemoryEventLog = InMemoryEventLog()): Pair<Kernel, InMemoryEventLog> {
        val kernel = Kernel(log)
        kernel.execute(undeclared, command(OpenSafe("ahoo")))
        return kernel to log
    }

    @Test
    fun `a rejection's event the function does not declare should fail the command and write nothing`() = runTest {
        val (kernel, log) = opened()
        listOf(Unlock("0000"), UnlockThrowing("0000")).forEach { body ->
            val failed = assertThrows<UndeclaredEventException> { kernel.execute(undeclared, command(body)) }
            failed.event.assert().isEqualTo(AttemptFailed::class.java)
            failed.message.assert().isEqualTo(
                "vault.safe's decide function of ${body.javaClass.simpleName} rejected it with AttemptFailed, which " +
                    "it does not declare: a rejection's events are declared as a decision's are. Add it to the " +
                    "function's @OnCommand(produces = [AttemptFailed::class]).",
            )
        }
        log.read(ref).toList().size.assert().isEqualTo(1)
    }

    @Test
    fun `a rejection's declared event, or one of a function that declares nothing, should be committed`() = runTest {
        listOf(declared, unknown).forEach { model ->
            val (kernel, _) = opened()
            val outcome = kernel.execute(model, command(Unlock("0000"))) as CommandOutcome.Committed
            outcome.rejection!!.code.assert().isEqualTo("vault.safe.wrong-code")
            outcome.stream.events.single().name.assert().isEqualTo("vault.safe.AttemptFailed")
        }
    }

    @Test
    fun `a log holding a rejection's event never declared should replay as before`() = runTest {
        // Written while the function declared nothing, as a beta's model did.
        val (writer, log) = opened()
        writer.execute(unknown, command(Unlock("0000")))
        // Read by a model that does not declare it: its events are sourced, never decided again.
        val outcome = Kernel(log).execute(undeclared, command(Unlock("4242"))) as CommandOutcome.Committed
        (outcome.state as SafeState).failures.assert().isEqualTo(1)
        outcome.stream.version.assert().isEqualTo(3)
    }
}
