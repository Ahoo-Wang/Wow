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
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A rejection may carry events: ordinary events of the aggregate, sourced and committed with it. */
class KernelRejectionEventsTest {
    data class OpenSafe(val owner: String)
    data class Unlock(val code: String)
    data class UnlockThrowing(val code: String)
    data class Touch(val code: String)
    data class UnlockUnsourced(val code: String)

    data class SafeOpened(val owner: String)
    data class Unlocked(val code: String)
    data class AttemptFailed(val code: String)
    data class Unsourced(val code: String)

    class SafeState {
        var failures = 0
        var unlocked = false
    }

    private val model = aggregate<SafeState>("vault", "safe") {
        initial { SafeState() }
        decide<OpenSafe>(Creation.REQUIRED) { _, command ->
            if (command.owner.isBlank()) {
                Decision.reject("no-owner", "A safe has an owner.", events = listOf(AttemptFailed("")))
            } else {
                Decision.events(SafeOpened(command.owner))
            }
        }
        decide<Unlock> { _, command ->
            if (command.code == SECRET) {
                Decision.events(Unlocked(command.code))
            } else {
                Decision.reject(
                    "wrong-code",
                    "Wrong code.",
                    events = listOf(AttemptFailed(command.code)),
                    extensions = mapOf("attempt" to command.code),
                )
            }
        }
        decide<UnlockThrowing> { _, command ->
            reject("wrong-code", "Wrong code.", events = listOf(AttemptFailed(command.code)))
        }
        decide<Touch> { _, _ -> Decision.reject("locked", "The safe is locked.") }
        decide<UnlockUnsourced> { _, command ->
            Decision.reject("wrong-code", "Wrong code.", events = listOf(Unsourced(command.code)))
        }
        evolve<SafeOpened> { }
        evolve<Unlocked> { unlocked = true }
        evolve<AttemptFailed> { failures++ }
    }
    private val ref = model.type.ref("tenant", "safe-1")
    private var sequence = 0

    private fun command(body: Any, requestId: String = "request-${++sequence}") =
        Command(id = "command-${++sequence}", requestId = requestId, ref = ref, body = body)

    private suspend fun opened(): Pair<Kernel, InMemoryEventLog> {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        kernel.execute(model, command(OpenSafe("ahoo")))
        return kernel to log
    }

    @Test
    fun `a rejection's events should be sourced and committed with it, the rejection recorded on their stream`() =
        runTest {
            val (kernel, log) = opened()
            val outcome = kernel.execute(model, command(Unlock("0000"))) as CommandOutcome.Committed
            outcome.rejection!!.code.assert().isEqualTo("vault.safe.wrong-code")
            outcome.stream.version.assert().isEqualTo(2)
            outcome.results.assert().isEmpty()
            (outcome.state as SafeState).failures.assert().isEqualTo(1)
            val stream = log.read(ref).toList().last()
            stream.events.single().name.assert().isEqualTo("vault.safe.AttemptFailed")
            stream.rejection.assert().isEqualTo(outcome.rejection)
            stream.rejection!!.extensions.assert().isEqualTo(mapOf("attempt" to "0000"))
            stream.headers.keys.assert().contains(EventStreamRecord.REJECTION_CODE, EventStreamRecord.REJECTION_MESSAGE)
            // The next command decides on the state the rejection's events left.
            kernel.execute(model, command(Unlock("0000"))).let { it as CommandOutcome.Committed }
                .let { (it.state as SafeState).failures.assert().isEqualTo(2) }
        }

    @Test
    fun `a thrown rejection should carry its events as a returned one does`() = runTest {
        val (kernel, _) = opened()
        val outcome = kernel.execute(model, command(UnlockThrowing("1"))) as CommandOutcome.Committed
        outcome.rejection!!.code.assert().isEqualTo("vault.safe.wrong-code")
        outcome.stream.events.single().name.assert().isEqualTo("vault.safe.AttemptFailed")
    }

    @Test
    fun `a rejection without events should write nothing and answer the version the decision saw`() = runTest {
        val (kernel, log) = opened()
        val outcome = kernel.execute(model, command(Touch("x"))) as CommandOutcome.Rejected
        outcome.version.assert().isEqualTo(1)
        outcome.duplicate.assert().isFalse()
        log.read(ref).toList().size.assert().isEqualTo(1)
    }

    @Test
    fun `a creation rejected with events should write nothing and answer no version`() = runTest {
        val log = InMemoryEventLog()
        val outcome = Kernel(log).execute(model, command(OpenSafe(" "))) as CommandOutcome.Rejected
        outcome.rejection.code.assert().isEqualTo("vault.safe.no-owner")
        outcome.version.assert().isNull()
        log.read(ref).toList().assert().isEmpty()
    }

    @Test
    fun `a creation rejected with events should be refused, naming the command, when the kernel is strict`() =
        runTest {
            val log = InMemoryEventLog()
            val strict = Kernel(log, options = KernelOptions(refuseCreationRejectedWithEvents = true))
            val opening = command(OpenSafe(" "))
            assertThrows<IllegalStateException> { strict.execute(model, opening) }
                .message.assert().startsWith(
                    "The creation ${opening.id} (vault.safe.OpenSafe) was rejected with events"
                )
            log.read(ref).toList().assert().isEmpty()
        }

    @Test
    fun `a retry of a command rejected with events should be answered with the same rejection`() = runTest {
        val (kernel, log) = opened()
        val first = kernel.execute(model, command(Unlock("0000"), requestId = "retried")) as CommandOutcome.Committed
        val retry = kernel.execute(model, command(Unlock("0000"), requestId = "retried")) as CommandOutcome.Rejected
        retry.rejection.assert().isEqualTo(first.rejection)
        retry.version.assert().isEqualTo(first.stream.version)
        retry.duplicate.assert().isTrue()
        log.read(ref).toList().size.assert().isEqualTo(2)
    }

    @Test
    fun `a rejection's event the aggregate cannot source should fail as an unsourced event does`() = runTest {
        val (kernel, log) = opened()
        assertThrows<IllegalArgumentException> { kernel.execute(model, command(UnlockUnsourced("1"))) }
            .message.assert().contains("cannot evolve")
        log.read(ref).toList().size.assert().isEqualTo(1)
    }

    @Test
    fun `each rejection should be observed once with its code and whether it carried events, a retry not again`() =
        runTest {
            val observed = mutableListOf<ObservedEvent>()
            val observer = object : Observer {
                override fun observed(event: ObservedEvent) {
                    observed += event
                }
            }
            val kernel = Kernel(InMemoryEventLog(), observer = observer)
            kernel.execute(model, command(OpenSafe(" ")))
            kernel.execute(model, command(OpenSafe("ahoo")))
            kernel.execute(model, command(Unlock("0000"), requestId = "retried"))
            kernel.execute(model, command(Unlock("0000"), requestId = "retried"))
            kernel.execute(model, command(Touch("x")))
            observed.filterIsInstance<ObservedEvent.Rejected>().assert().containsExactly(
                ObservedEvent.Rejected(model.type, "vault.safe.OpenSafe", "vault.safe.no-owner", withEvents = false),
                ObservedEvent.Rejected(model.type, "vault.safe.Unlock", "vault.safe.wrong-code", withEvents = true),
                ObservedEvent.Rejected(model.type, "vault.safe.Touch", "vault.safe.locked", withEvents = false),
            )
        }

    private companion object {
        const val SECRET = "4242"
    }
}
