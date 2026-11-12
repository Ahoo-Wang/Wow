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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The background loops of a node ([launchLoop], [launchWorker]): a round's own wait is the loop's timing, whatever
 * escapes a round ends the loop and is recorded by the node's [NodeFatals], and a stop or a worker that is done ends it
 * quietly.
 */
class NodeLoopsTest {
    private val fatals = NodeFatals("node-1")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + fatals.contextOf(Component.NODE, "test"))

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    @Test
    fun `an error that escapes a round should end the loop, recorded as its end, the other loops going on`(): Unit =
        runBlocking {
            val other = AtomicInteger()
            scope.launchLoop("other") {
                other.incrementAndGet()
                delay(1)
            }
            val rounds = AtomicInteger()
            val ended = scope.launchLoop("failing") {
                if (rounds.incrementAndGet() == 3) {
                    throw NotImplementedError("not yet")
                }
            }

            withTimeout(5.seconds) { ended.join() }

            rounds.get().assert().isEqualTo(3)
            fatals.endedLoop!!.let {
                it.component.assert().isEqualTo(Component.NODE)
                it.name.assert().isEqualTo("test")
                it.loop.assert().isEqualTo("failing")
                it.error.assert().isEqualTo(NotImplementedError::class.java.name)
            }
            fatals.endedCount.assert().isEqualTo(1)
            // The memory is sound: the node is not fatal, and steps out of nothing.
            fatals.isFatal.assert().isFalse()
            val seen = other.get()
            withTimeout(5.seconds) { while (other.get() == seen) delay(1) }
        }

    @Test
    fun `an exception a round did not catch should end the loop as an error does`(): Unit = runBlocking {
        withTimeout(5.seconds) { scope.launchLoop("unhandled") { error("not caught") }.join() }

        fatals.endedLoop!!.error.assert().isEqualTo(IllegalStateException::class.java.name)
    }

    @Test
    fun `a cancellation that escapes a round while the loop runs should end it, as of a timeout`(): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                scope.launchLoop("timed out") { throw CancellationException("a timeout that escaped") }.join()
            }

            fatals.endedLoop!!.loop.assert().isEqualTo("timed out")
        }

    @Test
    fun `a fatal error should be recorded as the node's fatal error, not as a loop's end`(): Unit = runBlocking {
        withTimeout(5.seconds) { scope.launchLoop("out of memory") { throw OutOfMemoryError("heap") }.join() }

        fatals.first!!.let {
            it.component.assert().isEqualTo(Component.NODE)
            it.name.assert().isEqualTo("test")
        }
        fatals.endedLoop.assert().isNull()
    }

    @Test
    fun `a stopped scope and a worker that is done should end their loops quietly`(): Unit = runBlocking {
        val rounds = AtomicInteger()
        val worker = scope.launchWorker("done") { rounds.incrementAndGet() < 3 }
        val loop = scope.launchLoop("stopped") { delay(10.milliseconds) }

        withTimeout(5.seconds) { worker.join() }
        loop.cancelAndJoin()

        rounds.get().assert().isEqualTo(3)
        fatals.endedLoop.assert().isNull()
        fatals.isFatal.assert().isFalse()
    }

    @Test
    fun `a loop of a scope without a node's fatals should end with its error logged, nothing recorded`(): Unit =
        runBlocking {
            val none = NodeFatals.NONE.contextOf(Component.NODE, "alone")
            val alone = CoroutineScope(Dispatchers.Default + SupervisorJob() + none)
            try {
                withTimeout(5.seconds) { alone.launchLoop("alone") { throw NotImplementedError() }.join() }
            } finally {
                alone.cancel()
            }

            NodeFatals.NONE.endedLoop.assert().isNull()
            fatals.endedLoop.assert().isNull()
        }
}
