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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * A command a client sends while deciding takes a request id derived from the deciding request (decision D314 ④): its
 * aggregate and its request id, which a caller's retry keeps, not its command id, which every arrival gets anew.
 */
class KernelDerivedRequestsTest {
    data class Open(val by: String)
    data class Charge(val amount: Int)
    data class Opened(val by: String)
    data class Charged(val amount: Int)

    class Account(val id: String)

    /** The request ids a client would send in each attempt: two calls, the second with the caller's own id. */
    private val sent = mutableListOf<List<String?>>()

    private val model = aggregate<Account>("test", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decideSuspending<Charge> { _, body ->
            val context = currentCoroutineContext()
            sent += listOf(context.requestIdOf(null), context.requestIdOf("own"), context.requestIdOf(null))
            Decision.events(Charged(body.amount))
        }
        evolve<Opened> { }
        evolve<Charged> { }
    }
    private val ref = model.type.ref("t", "a-1")

    private suspend fun opened(): EventLog = InMemoryEventLog().also {
        Kernel(it).execute(model, Command("open", "open", ref, Open("alice")))
    }

    @Test
    fun `a decision sends its n-th command as the n-th request derived from the deciding request`() = runTest {
        Kernel(opened()).execute(model, Command("c-1", "r-1", ref, Charge(1)))
        sent.single().assert().containsExactly(
            DecidingCommand.requestIdOf(ref, "r-1", 0),
            "own",
            DecidingCommand.requestIdOf(ref, "r-1", 1),
        )
    }

    @Test
    fun `a caller's retry of an attempt that called a peer and did not commit sends the same requests again`() =
        runTest {
            val log = opened()
            val refusing = object : EventLog by log {
                var refused = false
                override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                    if (!refused) {
                        refused = true
                        return AppendOutcome.Rejected.of(IllegalStateException("The store refused the append."))
                    }
                    return log.append(stream, transport)
                }
            }
            sent.clear()
            val kernel = Kernel(refusing)
            // A refused append committed nothing, and fails the attempt.
            runCatching { kernel.execute(model, Command("c-1", "r-1", ref, Charge(1))) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
            // The retry arrives anew: the gateway gives it another command id, and it keeps the request id.
            kernel.execute(model, Command("c-2", "r-1", ref, Charge(1)))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            sent.assert().hasSize(2)
            sent[1].assert().isEqualTo(sent[0])
        }

    @Test
    fun `a decision decided again after a conflict sends the same requests again`() = runTest {
        val log = opened()
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
        sent.assert().hasSize(3)
        sent[0].assert().isEqualTo(sent[2])
        sent[0].assert().doesNotContainAnyElementsOf(sent[1].filterNot { it == "own" })
    }

    @Test
    fun `outside a decision a client chooses the request id itself`() = runTest {
        currentCoroutineContext().requestIdOf(null).assert().isNull()
        currentCoroutineContext().requestIdOf("own").assert().isEqualTo("own")
    }
}
