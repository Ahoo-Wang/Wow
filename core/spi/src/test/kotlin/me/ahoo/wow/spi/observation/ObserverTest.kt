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

package me.ahoo.wow.spi.observation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class ObserverTest {
    private class Trace(name: String) : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to name)
    }

    /** An observation that records what it is told, running in [context]. */
    private class Recorded(override val context: CoroutineContext) : Observation {
        val told = CopyOnWriteArrayList<String>()

        override fun outcome(outcome: String) {
            told += "outcome $outcome"
        }

        override fun failed(error: Throwable) {
            told += "failed ${error.message}"
        }

        override fun end() {
            told += "end"
        }
    }

    @Test
    fun `nothing should be observed by default`() {
        val ref = AggregateType("shop", "cart").ref("t", "c-1")
        val stream =
            EventStreamRecord(ref, 1, "c", "r", "shop.cart.Open", 0, listOf(EventRecord("e", 1, "{}")), Scope.NONE)
        val timer = Timer("i", ref, "k", 1, 0, ScheduleRule.At(0), 0, ref, "shop.cart.Close", "{}")
        val observer = Observer.NONE
        listOf(
            observer.admitting("shop", "cart", "Open", emptyMap()),
            observer.executing(Command("c", "r", ref, Any())),
            observer.deciding(Command("c", "r", ref, Any())),
            observer.appending(stream),
            observer.handling("s", "shop", stream, emptyMap(), null),
            observer.firing(timer),
        ).forEach { it.assert().isSameAs(Observation.NONE) }
        observer.resuming(mapOf("traceparent" to "t")).assert().isSameAs(EmptyCoroutineContext)
        observer.storeFellBack("shop", StoreFallbacks.SNAPSHOT_TAIL_READ, StoreFallbacks.FAILED)
        Observation.NONE.context.assert().isSameAs(EmptyCoroutineContext)
        Observation.NONE.trace.assert().isEmpty()
        Observation.NONE.outcome("ok")
        Observation.NONE.failed(IllegalStateException())
        Observation.NONE.end()
    }

    @Test
    fun `an observed block should run in the observation's context and end it, telling it a failure`(): Unit =
        runBlocking {
            val traced = Recorded(Trace("t-1"))
            traced.observe { currentCoroutineContext().trace }.assert().isEqualTo(mapOf("traceparent" to "t-1"))
            traced.trace.assert().isEqualTo(mapOf("traceparent" to "t-1"))
            traced.told.assert().containsExactly("end")
            assertThrows<IllegalStateException> { traced.observe { error("boom") } }
            traced.told.assert().containsExactly("end", "failed boom", "end")
            // An observation without a context of its own runs the block where it is.
            val untraced = Recorded(EmptyCoroutineContext)
            untraced.observe { currentCoroutineContext().traceContext }.assert().isSameAs(EmptyCoroutineContext)
            untraced.told.assert().containsExactly("end")
            // Nothing observed: the block only runs.
            Observation.NONE.observe { 42 }.assert().isEqualTo(42)
        }

    @Test
    fun `a context should give its trace and its trace element, none without one`(): Unit = runBlocking {
        val trace = Trace("t-2")
        (trace as CoroutineContext).trace.assert().isEqualTo(mapOf("traceparent" to "t-2"))
        (trace as CoroutineContext).traceContext.assert().isSameAs(trace)
        EmptyCoroutineContext.trace.assert().isEmpty()
        EmptyCoroutineContext.traceContext.assert().isSameAs(EmptyCoroutineContext)
    }
}
