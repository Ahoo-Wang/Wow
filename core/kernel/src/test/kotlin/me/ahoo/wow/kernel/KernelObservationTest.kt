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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.TraceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/** The kernel's stages as an observer sees them (decision D174). */
class KernelObservationTest {
    private class Trace(name: String) : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to name)
    }

    /** Records each stage started and how it ended; an append runs in the trace `append`. */
    private class Recording : Observer {
        val seen = CopyOnWriteArrayList<String>()

        private fun stage(name: String, context: CoroutineContext? = null) = object : Observation {
            override val context: CoroutineContext = context ?: super.context

            override fun outcome(outcome: String) {
                seen += "$name $outcome"
            }

            override fun failed(error: Throwable) {
                seen += "$name failed ${error.javaClass.simpleName}"
            }
        }

        override fun executing(command: Command<*>): Observation = stage("execute")

        override fun deciding(command: Command<*>): Observation = stage("decide")

        override fun appending(stream: EventStreamRecord): Observation = stage("append", Trace("append"))
    }

    private val observer = Recording()
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log, clock = { 0L }, observer = observer)
    private val ref = cartModel.type.ref("tenant", "cart-1")

    @Test
    fun `each execution and append should be told its outcome, and the append's trace go with its entry`(): Unit =
        runBlocking {
            kernel.execute(cartModel, Command("c-1", "r-1", ref, CreateCart("ahoo")))
            kernel.execute(cartModel, Command("c-1", "r-1", ref, CreateCart("ahoo")))
            kernel.execute(cartModel, Command("c-2", "r-2", ref, CreateCart("ahoo")))
            observer.seen.assert().containsExactly(
                "append committed",
                "execute committed",
                "execute duplicate",
                "execute rejected",
            )
            log.readEntries(ref).toList().single().transport!!.trace.assert()
                .isEqualTo(mapOf("traceparent" to "append"))
        }

    @Test
    fun `a scheduled command not yet due should fail its observed execution, unchanged`(): Unit = runBlocking {
        val due = ScheduleContext("timer-1", "expire", 1, 60_000).headers()
        assertThrows<ScheduleNotDueException> {
            kernel.execute(cartModel, Command("c-1", "r-1", ref, CreateCart("ahoo"), headers = due))
        }
        observer.seen.assert().containsExactly("execute failed ScheduleNotDueException")
        log.readEntries(ref).toList().assert().isEmpty()
    }
}
