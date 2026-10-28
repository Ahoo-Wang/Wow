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

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Several observers as one ([Observer.of]): each is told of every stage and event, in their order. An observation
 * of a stage is each observer's; its [Observation.context] is the first one's that has a context (a tracer's), so
 * one trace goes with the work. An observer only watches, so one that fails never fails the work nor keeps the
 * others from being told: its failure is logged, at WARNING the first time for that observer and at DEBUG after.
 */
internal class CompositeObserver(private val observers: List<Observer>) : Observer {
    private val warned: MutableSet<Observer> = ConcurrentHashMap.newKeySet()

    override fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>) =
        each { it.admitting(context, aggregate, command, trace) }

    override fun executing(command: Command<*>, commandType: String) = each { it.executing(command, commandType) }

    override fun observed(event: ObservedEvent) {
        observers.forEach { observer -> guarded(observer, Unit) { observer.observed(event) } }
    }

    override fun deciding(command: Command<*>) = each { it.deciding(command) }

    override fun appending(stream: EventStreamRecord) = each { it.appending(stream) }

    override fun handling(
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ) = each { it.handling(subscription, context, stream, trace, topic) }

    override fun firing(timer: Timer) = each { it.firing(timer) }

    override fun resuming(trace: Map<String, String>): CoroutineContext = observers
        .map { observer -> guarded(observer, EmptyCoroutineContext) { observer.resuming(trace) } }
        .firstOrNull { it != EmptyCoroutineContext } ?: EmptyCoroutineContext

    override fun toString(): String = "CompositeObserver($observers)"

    /** The observation of each observer for one stage, as one; [Observation.NONE] when none observes it. */
    private inline fun each(start: (Observer) -> Observation): Observation {
        val started = observers.map { observer -> observer to guarded(observer, Observation.NONE) { start(observer) } }
            .filter { (_, observation) -> observation !== Observation.NONE }
        return when (started.size) {
            0 -> Observation.NONE
            1 -> started.single().second
            else -> Composite(started)
        }
    }

    private inline fun <T> guarded(observer: Observer, otherwise: T, block: () -> T): T = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // An observer never fails the work.
        val level = if (warned.add(observer)) System.Logger.Level.WARNING else System.Logger.Level.DEBUG
        logger.log(level, "The observer $observer failed; the work goes on without it.", failure)
        otherwise
    }

    /** One stage's observations of several observers. */
    private inner class Composite(private val started: List<Pair<Observer, Observation>>) : Observation {
        override val context: CoroutineContext =
            started.map { it.second.context }.firstOrNull { it != EmptyCoroutineContext } ?: EmptyCoroutineContext

        override fun outcome(outcome: String) =
            started.forEach { (observer, observation) -> guarded(observer, Unit) { observation.outcome(outcome) } }

        override fun failed(error: Throwable) =
            started.forEach { (observer, observation) -> guarded(observer, Unit) { observation.failed(error) } }

        override fun end() = started.forEach { (observer, observation) ->
            guarded(
                observer,
                Unit
            ) { observation.end() }
        }
    }

    private companion object {
        val logger: System.Logger = System.getLogger(CompositeObserver::class.java.name)
    }
}
