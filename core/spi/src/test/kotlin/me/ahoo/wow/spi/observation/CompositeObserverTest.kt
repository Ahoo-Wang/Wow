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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Several observers as one: each told of everything in order, one that fails isolated from the work and the others. */
class CompositeObserverTest {
    private val ref = AggregateType("shop", "cart").ref("t", "c-1")
    private val command = Command("c", "r", ref, Any())
    private val stream =
        EventStreamRecord(ref, 1, "c", "r", "shop.cart.Open", 0, listOf(EventRecord("e", 1, "{}")), Scope.NONE)
    private val timer = Timer("i", ref, "k", 1, 0, ScheduleRule.At(0), 0, ref, "shop.cart.Close", "{}")
    private val told = CopyOnWriteArrayList<String>()

    private class Trace(name: String) : TraceContext() {
        override val fields: Map<String, String> = mapOf("traceparent" to name)
    }

    /** An observer that records what it is told under [name], its stages in [traced] when it traces. */
    private inner class Recording(private val name: String, private val traced: Trace? = null) : Observer {
        private fun stage(stage: String): Observation = object : Observation {
            override val context: CoroutineContext = traced as CoroutineContext? ?: EmptyCoroutineContext

            override fun outcome(outcome: String) {
                told += "$name $stage $outcome"
            }

            override fun failed(error: Throwable) {
                told += "$name $stage failed"
            }

            override fun end() {
                told += "$name $stage end"
            }
        }

        override fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>) =
            stage("admit")

        override fun executing(command: Command<*>, commandType: String) = stage("execute")

        override fun deciding(command: Command<*>) = stage("decide")

        override fun appending(stream: EventStreamRecord) = stage("append")

        override fun handling(
            subscription: String,
            context: String,
            stream: EventStreamRecord,
            trace: Map<String, String>,
            topic: String?,
        ) = stage("handle")

        override fun firing(timer: Timer) = stage("fire")

        override fun observed(event: ObservedEvent) {
            told += "$name ${event.javaClass.simpleName}"
        }

        override fun resuming(trace: Map<String, String>): CoroutineContext =
            traced as CoroutineContext? ?: EmptyCoroutineContext
    }

    /** An observer that fails at whatever it is told. */
    private object Failing : Observer {
        override fun executing(command: Command<*>, commandType: String): Observation = error("broken")

        override fun observed(event: ObservedEvent) = error("broken")

        override fun resuming(trace: Map<String, String>): CoroutineContext = error("broken")
    }

    @Test
    fun `none should be no observer, and one itself`() {
        Observer.of(emptyList()).assert().isSameAs(Observer.NONE)
        val one = Recording("a")
        Observer.of(listOf(one)).assert().isSameAs(one)
    }

    @Test
    fun `every observer should be told of every stage and event, in order`() {
        val observer = Observer.of(listOf(Recording("a"), Recording("b")))
        listOf(
            observer.admitting("shop", "cart", "Open", emptyMap()),
            observer.executing(command, "shop.cart.Open"),
            observer.deciding(command),
            observer.appending(stream),
            observer.handling("s", "shop", stream, emptyMap(), null),
            observer.firing(timer),
        ).forEach { observation ->
            observation.outcome("ok")
            observation.failed(IllegalStateException())
            observation.end()
        }
        observer.observed(ObservedEvent.ReservationLost("shop"))
        told.filter { it.startsWith("a ") }.map { it.removePrefix("a ") }.assert()
            .isEqualTo(told.filter { it.startsWith("b ") }.map { it.removePrefix("b ") })
        told.take(2).assert().containsExactly("a admit ok", "b admit ok")
        told.takeLast(2).assert().containsExactly("a ReservationLost", "b ReservationLost")
        observer.toString().assert().startsWith("CompositeObserver(")
    }

    @Test
    fun `the work should go on in the first observer's trace, or none`() {
        val first = Trace("first")
        val observer = Observer.of(
            listOf(Recording("plain"), Recording("traced", first), Recording("late", Trace("late")))
        )
        observer.executing(command, "shop.cart.Open").context.assert().isSameAs(first)
        observer.resuming(mapOf("traceparent" to "t")).assert().isSameAs(first)
        Observer.of(
            listOf(Recording("a"), Recording("b"))
        ).resuming(emptyMap()).assert().isSameAs(EmptyCoroutineContext)
        // A stage no observer observes is no observation.
        Observer.of(listOf(Observer.NONE, Observer.NONE)).deciding(command).assert().isSameAs(Observation.NONE)
    }

    @Test
    fun `a failing observer should neither fail the work nor keep the others from being told`() {
        val observer = Observer.of(listOf(Failing, Recording("a"), Failing))
        observer.executing(command, "shop.cart.Open").end()
        observer.observed(ObservedEvent.ReservationLost("shop"))
        observer.observed(ObservedEvent.ReservationLost("shop"))
        observer.resuming(emptyMap()).assert().isSameAs(EmptyCoroutineContext)
        told.assert().containsExactly("a execute end", "a ReservationLost", "a ReservationLost")
        // A failing stage of one observer among several is that observer's alone.
        val failingEnd = object : Observer {
            override fun deciding(command: Command<*>): Observation = object : Observation {
                override fun end() = error("broken")
            }
        }
        Observer.of(listOf(failingEnd, Recording("b"))).deciding(command).end()
        told.last().assert().isEqualTo("b decide end")
    }
}
