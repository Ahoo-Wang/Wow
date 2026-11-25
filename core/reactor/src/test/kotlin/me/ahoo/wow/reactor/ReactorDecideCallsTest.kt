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

package me.ahoo.wow.reactor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A decide function may return a Reactor `Mono` (`guide/aggregates.md`), and the code the compiler generates for it
 * awaits it with [awaitResult]. A Wow client is suspending, so such a function calls one inside `mono { }`. A call that
 * stands takes the request id derived from the deciding request (D314 ④, D479 ①), so that a decision decided again
 * sends the same request, which its target answers as a repeat: [awaitResult] subscribes the publisher with the command
 * being decided in its Reactor context, where the coroutine of `mono { }` or `flux { }` finds it (#7046).
 */
class ReactorDecideCallsTest {
    data class Open(val by: String)
    data class Charge(val amount: Int)
    data class Opened(val by: String)
    data class Charged(val amount: Int)
    data class Fee(val amount: Int)
    data class Refund(val amount: Int)
    data class Refunded(val amount: Int)

    class Account(val id: String)

    /** The request id a Wow client sends each call with, in each attempt: its derived one, else a new one. */
    private val sent = mutableListOf<String>()

    private val model = aggregate<Account>("test", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        // As the compiler generates a decide function that returns a Mono: the Mono awaited with awaitResult.
        decideSuspending<Charge> { _, body ->
            awaitResult(
                mono {
                    // What InProcessCommandClient and HttpCommandClient send the call with.
                    sent += currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(body.amount), "peer-1"))
                        ?: UUID.randomUUID().toString()
                    Decision.events(Charged(body.amount))
                },
            ) as Decision
        }
        // As the compiler generates a decide function that returns a Flux: its events awaited with awaitResult.
        decideSuspending<Refund> { _, body ->
            val events = awaitResult(
                flux {
                    sent += currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(-body.amount), "peer-1"))
                        ?: UUID.randomUUID().toString()
                    send(Refunded(body.amount))
                },
            ) as List<*>
            Decision.events(*events.filterNotNull().toTypedArray())
        }
        evolve<Opened> { }
        evolve<Charged> { }
        evolve<Refunded> { }
    }
    private val ref = model.type.ref("t", "a-1")

    @Test
    fun `a Mono decide function decided again after a conflict sends its call that stands as the same request`() =
        runTest {
            val log = InMemoryEventLog()
            Kernel(log).execute(model, Command("open", "open", ref, Open("alice")))
            val racing = object : EventLog by log {
                var raced = false

                override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                    if (!raced) {
                        raced = true
                        Kernel(log).execute(model, Command("other", "other", ref, Charge(2)))
                    }
                    return log.append(stream, transport)
                }
            }
            sent.clear()
            Kernel(racing).execute(model, Command("c-1", "r-1", ref, Charge(1)))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            // The first attempt, the racing command's own call, then the attempt decided again on the newer state.
            sent.assert().hasSize(3)
            val derived = DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 0)
            sent[0].assert().isEqualTo(derived)
            sent[2].assert().isEqualTo(derived)
        }

    @Test
    fun `a Flux decide function sends its call that stands as the request derived from the deciding request`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(model, Command("open", "open", ref, Open("alice")))
        sent.clear()
        Kernel(log).execute(model, Command("c-1", "r-1", ref, Refund(1)))
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
        sent.assert().containsExactly(DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 0))
    }

    @Test
    fun `a publisher awaited outside a decision carries no command`() = runTest {
        awaitResult(mono { currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(1), "peer-1")) })
            .assert().isNull()
    }

    @Test
    fun `a call outside any publisher, with no Reactor context, carries no command`() = runTest {
        currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(1), "peer-1")).assert().isNull()
    }
}
