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

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.call.requestIdOf
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A decide function that blocks its thread (`@Blocking`) calls a suspending Wow client inside `runBlocking { }`, a
 * coroutine of its own whose context does not carry the command being decided. Its call that stands still takes the
 * request id derived from the deciding request (D314 ④, D479 ①), as a suspending decide function's does, so that a
 * decision decided again sends the same request (#7083). The kernel keeps the command on the blocking function's
 * thread for that call alone: restored when the call returns or fails, and never on a suspending decide function's.
 */
class BlockingDecideCallsTest {
    data class Open(val by: String)
    data class Charge(val amount: Int)
    data class Opened(val by: String)
    data class Charged(val amount: Int)
    data class Fee(val amount: Int)
    data class Fail(val why: String)
    data class Probe(val n: Int)

    class Account(val id: String)

    /** The request id a Wow client sends the call with in each attempt: the derived one, else none. */
    private val sent = mutableListOf<String?>()

    /** What a suspending decide function finds: the command as its context's element, and a copy on its thread. */
    private val probed = mutableListOf<Pair<Boolean, Boolean>>()

    private val model = aggregate<Account>("test", "account") {
        initial { Account(it.id) }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.by)) }
        decideBlocking<Charge> { _, body ->
            // What a blocking function does to call a suspending client; the client reads its own coroutine's context.
            runBlocking { sent += currentCoroutineContext().requestIdOf(CommandRequest.of(Fee(body.amount), "peer-1")) }
            Decision.events(Charged(body.amount))
        }
        decideBlocking<Fail> { _, body -> throw IllegalStateException(body.why) }
        decideSuspending<Probe> { _, body ->
            probed += (currentCoroutineContext()[DecidingCommand] != null) to
                (DecidingCommand.decidingIn(EmptyCoroutineContext) != null)
            Decision.events(Charged(body.n))
        }
        evolve<Opened> { }
        evolve<Charged> { }
    }
    private val ref = model.type.ref("t", "a-1")

    @Test
    fun `a blocking decide function decided again after a conflict sends its call that stands as the same request`() =
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
            sent[1].assert().isEqualTo(DecidingCommand.requestIdOf(ref.type, ref.tenantId, "other", 0))
            sent[2].assert().isEqualTo(derived)
        }

    @Test
    fun `a blocking decide function on a platform thread leaves nothing on it, once returned or failed`() = runTest {
        // A custom blocking dispatcher whose one platform thread runs every blocking call, unlike a virtual thread.
        val pool = Executors.newSingleThreadExecutor()
        try {
            val kernel = Kernel(InMemoryEventLog(), blocking = pool.asCoroutineDispatcher())
            kernel.execute(model, Command("open", "open", ref, Open("alice")))
            kernel.execute(model, Command("c-1", "r-1", ref, Charge(1)))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            pool.submit<DecidingCommand?> { DecidingCommand.decidingIn(EmptyCoroutineContext) }.get()
                .assert().describedAs("the thread's copy after the call returned").isNull()
            runCatching { kernel.execute(model, Command("c-2", "r-2", ref, Fail("down"))) }
            pool.submit<DecidingCommand?> { DecidingCommand.decidingIn(EmptyCoroutineContext) }.get()
                .assert().describedAs("the thread's copy after the call failed").isNull()
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a suspending decide function's thread carries no copy of the command`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(model, Command("open", "open", ref, Open("alice")))
        Kernel(log).execute(model, Command("p-1", "r-3", ref, Probe(1)))
        probed.assert().containsExactly(true to false)
    }
}
