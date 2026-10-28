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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.TraceContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/** What a cancelled caller still gets done: compensations (A1-5) and a commit's answer (A1-11). */
class KernelCancellationTest {
    data class Hold(val name: String)
    data class Add(val name: String)
    data class Added(val name: String)

    class Bag(val id: String) {
        val names = mutableListOf<String>()
    }

    private val trail = CopyOnWriteArrayList<String>()
    private val deciding = CompletableDeferred<Unit>()

    private val model = aggregate<Bag>("test", "bag") {
        initial { Bag(it.id) }
        decideSuspending<Hold>(Creation.ALLOWED) { _, body ->
            onAppendFailure {
                // A compensation that suspends, as one calling out to undo an effect does.
                yield()
                delay(1)
                trail += "undo ${body.name}"
            }
            deciding.complete(Unit)
            awaitCancellation()
        }
        decideSuspending<Add>(Creation.ALLOWED) { _, body ->
            trail += "decide ${body.name}"
            onAppendFailure {
                yield()
                trail += "undo ${body.name}"
            }
            Decision.events(Added(body.name))
        }
        evolve<Added> { names += it.name }
    }
    private val ref = model.type.ref("t", "b-1")

    @Test
    fun `a suspending compensation runs when the caller is cancelled while deciding`(): Unit = runBlocking {
        val kernel = Kernel(InMemoryEventLog())
        val caller = launch { kernel.execute(model, Command("c-1", "r-1", ref, Hold("x"))) }
        deciding.await()
        caller.cancelAndJoin()
        trail.assert().containsExactly("undo x")
    }

    @Test
    fun `a conflict met by a cancelled caller is compensated and not decided again`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        lateinit var caller: Job
        // Another writer takes the slot, and the caller gives up while the append is under way.
        val racing = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                log.append(stream.copyWith(commandId = "other", requestId = "other"), null)
                caller.cancel()
                return log.append(stream, transport)
            }
        }
        val kernel = Kernel(racing)
        caller = launch { kernel.execute(model, Command("c-1", "r-1", ref, Add("x"))) }
        caller.join()
        trail.assert().containsExactly("decide x", "undo x")
    }

    @Test
    fun `a commit is answered to a caller cancelled meanwhile when the execution is observed`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val observer = object : Observer {
            override fun executing(command: Command<*>, commandType: String): Observation = object : Observation {
                override val context: CoroutineContext = Trace()
            }
        }
        lateinit var caller: Job
        val cancelling = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                caller.cancel()
                return log.append(stream, transport)
            }
        }
        val kernel = Kernel(cancelling, observer = observer)
        var outcome: CommandOutcome? = null
        caller = launch { outcome = kernel.execute(model, Command("c-1", "r-1", ref, Add("x"))) }
        caller.join()
        outcome.assert().isInstanceOf(CommandOutcome.Committed::class.java)
        log.read(ref).toList().single().version.assert().isEqualTo(1)
    }

    @Test
    fun `a caller cancelled before anything commits still sees its cancellation when observed`(): Unit = runBlocking {
        val observer = object : Observer {
            override fun executing(command: Command<*>, commandType: String): Observation = object : Observation {
                override val context: CoroutineContext = Trace()
            }
        }
        val kernel = Kernel(InMemoryEventLog(), observer = observer)
        var outcome: CommandOutcome? = null
        val caller = launch { outcome = kernel.execute(model, Command("c-1", "r-1", ref, Hold("x"))) }
        deciding.await()
        caller.cancelAndJoin()
        caller.isCancelled.assert().isTrue()
        outcome.assert().isNull()
        trail.assert().containsExactly("undo x")
    }

    private class Trace : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to "execute")
    }
}
