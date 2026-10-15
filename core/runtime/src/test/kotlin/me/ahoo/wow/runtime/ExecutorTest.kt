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

package me.ahoo.wow.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ExecutorTest {
    data class Add(val amount: Int)
    data class Hold(val gate: String)
    data class Added(val amount: Int)

    class Counter(val id: String) {
        var total = 0
    }

    private val gates = HashMap<String, CompletableDeferred<Unit>>()

    /** Completed once a [Hold] command is deciding, suspended on its gate inside its aggregate's lane. */
    private val holding = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val inside = AtomicInteger()
    private val overlaps = AtomicInteger()

    private val model = aggregate<Counter>("test", "counter") {
        initial { Counter(it.id) }
        decideSuspending<Add>(Creation.ALLOWED) { _, body ->
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet()
            }
            yield()
            inside.decrementAndGet()
            Decision.events(Added(body.amount))
        }
        decideSuspending<Hold>(Creation.ALLOWED) { _, body ->
            holding.computeIfAbsent(body.gate) { CompletableDeferred() }.complete(Unit)
            gates.getValue(body.gate).await()
            Decision.events(Added(0))
        }
        evolve<Added> { total += it.amount }
    }
    private val cache = LruStateCache(capacity = 16)
    private val executor = Executor(Kernel(InMemoryEventLog(), states = cache))
    private val sequence = AtomicInteger()

    private fun command(id: String, body: Any): Command<Any> {
        val n = sequence.incrementAndGet()
        return Command("c-$n", "r-$n", model.type.ref("t", id), body)
    }

    @Test
    fun `commands of one aggregate run one at a time, in order, without conflicts`(): Unit = runBlocking {
        val outcomes = (1..64).map { amount ->
            async(Dispatchers.Default) { executor.execute(model, command("a", Add(amount))) }
        }.awaitAll()
        overlaps.get().assert().isEqualTo(0)
        outcomes.map { (it as CommandOutcome.Committed).stream.version }.sorted().assert().isEqualTo((1..64).toList())
        (cache.take(model.type.ref("t", "a"))!!.state as Counter).total.assert().isEqualTo((1..64).sum())
        executor.activeLanes.assert().isEqualTo(0)
    }

    @Test
    fun `an aggregate waiting on I-O does not hold up another`(): Unit = runBlocking {
        // The first command of the JVM pays its warm-up (codec, reflection): not inside the window measured below.
        executor.execute(model, command("warm", Add(1))).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        val gate = CompletableDeferred<Unit>().also { gates["g"] = it }
        val held = async(Dispatchers.Default) { executor.execute(model, command("slow", Hold("g"))) }
        // "slow" holds its lane, suspended on the gate, before "fast" starts: the overlap is certain, not raced.
        withTimeout(TIMEOUT_MS) { holding.computeIfAbsent("g") { CompletableDeferred() }.await() }
        // Never completes while "fast" waits on "slow": the timeout only turns that hang into a failure.
        withTimeout(TIMEOUT_MS) { executor.execute(model, command("fast", Add(1))) }
            .assert().isInstanceOf(CommandOutcome.Committed::class.java)
        gate.complete(Unit)
        held.await().assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `a full lane refuses the next command`(): Unit = runBlocking {
        val small = Executor(Kernel(InMemoryEventLog()), laneCapacity = 1)
        val gate = CompletableDeferred<Unit>().also { gates["full"] = it }
        val held = async(Dispatchers.Default) { small.execute(model, command("x", Hold("full"))) }
        while (small.activeLanes == 0) yield()
        runCatching { small.execute(model, command("x", Add(1))) }.exceptionOrNull()
            .assert().isInstanceOf(ExecutorBusyException::class.java)
        gate.complete(Unit)
        held.await()
    }

    @Test
    fun `close refuses new commands and waits for those in flight`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>().also { gates["close"] = it }
        val held = async(Dispatchers.Default) { executor.execute(model, command("y", Hold("close"))) }
        while (executor.activeLanes == 0) yield()
        val closing = async(Dispatchers.Default) { executor.close() }
        while (runCatching { executor.execute(model, command("z", Add(1))) }.isSuccess) yield()
        runCatching { executor.execute(model, command("z", Add(1))) }.exceptionOrNull()
            .assert().isInstanceOf(ExecutorClosedException::class.java)
        closing.isCompleted.assert().isFalse()
        gate.complete(Unit)
        held.await().assert().isInstanceOf(CommandOutcome.Committed::class.java)
        withTimeout(TIMEOUT_MS) { closing.await() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
