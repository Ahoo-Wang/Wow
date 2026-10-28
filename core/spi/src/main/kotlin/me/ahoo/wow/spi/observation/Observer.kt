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

import kotlinx.coroutines.withContext
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The observation seams of the core: where the kernel and the runtime tell an observer, such as
 * wow-observability's tracing and metrics, that a stage of their work starts. The core knows no tracing or metrics
 * library; an observer only watches and never changes what the work does (06-modules §3).
 *
 * Each method but [observed] starts an [Observation] of one stage, which the core ends when the stage does; [observed]
 * tells it something without a duration ([ObservedEvent]). Every method has a default that observes nothing, so an
 * observer overrides only what it watches; the stages' defaults, and [NONE], return [Observation.NONE], so the core
 * allocates nothing for a stage nobody observes.
 *
 * The trace context goes with the work: in a coroutine through the [TraceContext] element an observation's
 * [Observation.context] holds, and between nodes through [me.ahoo.wow.spi.log.Transport.trace] (a log entry, a
 * message of a topic) or the cluster channel. A stage that starts from what arrived that way is handed its
 * propagation fields.
 */
@WowSpi
interface Observer {
    /**
     * Admission of a request for [command] (the name the request gave) of the aggregate type [aggregate] of
     * [context]; [trace] holds the propagation fields the request arrived with, such as an HTTP request's
     * `traceparent`, empty when none.
     */
    fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>): Observation =
        Observation.NONE

    /**
     * The kernel's execution of [command], whose resolved type name is [commandType] (`context.aggregate.Name`, as the
     * Manifest and the log name it, `@Name` included), from taking it to its outcome ([Observation.outcome]).
     */
    fun executing(command: Command<*>, commandType: String): Observation = Observation.NONE

    /**
     * Something the core noticed that has no duration of its own ([ObservedEvent]): a refusal, a dropped signal, a
     * paused partition. Nothing by default.
     */
    fun observed(event: ObservedEvent) = Unit

    /** One decision of [command]: its decide function and its after hooks. */
    fun deciding(command: Command<*>): Observation = Observation.NONE

    /** The append of [stream]; what is appended with it carries the observation's [Observation.trace]. */
    fun appending(stream: EventStreamRecord): Observation = Observation.NONE

    /**
     * [subscription] of [context], run in the log or lease [group], handling [stream], retries included; [trace] holds
     * the propagation fields the entry carries. [topic] is the topic the entry was read from (a consumption), null for
     * the context's log. A subscription is one by its group and its name: each context has its own `snapshot`.
     */
    fun handling(
        group: String,
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ): Observation = Observation.NONE

    /** The firing of [timer]: its command sent to its target. */
    fun firing(timer: Timer): Observation = Observation.NONE

    /**
     * The coroutine context that continues the trace whose propagation fields are [trace], arrived without a stage of
     * its own (a command forwarded over the cluster channel); empty when there is none to continue.
     */
    fun resuming(trace: Map<String, String>): CoroutineContext = EmptyCoroutineContext

    companion object {
        /** Observes nothing. */
        @JvmField
        val NONE: Observer = object : Observer {}

        /**
         * [observers] as one observer: each told of every stage and event in their order, one that fails isolated
         * from the work and from the others ([CompositeObserver]); [NONE] for none, the observer itself for one.
         */
        @JvmStatic
        fun of(observers: List<Observer>): Observer = when (observers.size) {
            0 -> NONE
            1 -> observers.single()
            else -> CompositeObserver(observers)
        }
    }
}

/**
 * One stage of work being observed, from its start to its [end]: a span and its timings, say.
 *
 * - [context] is what the stage runs in: a [TraceContext] the work started on the way inherits, empty when the
 *   observer traces nothing.
 * - [trace] is the propagation fields of the stage, which what it sends to another node or appends carries.
 * - [outcome] names how the stage ended ([ObservedOutcome]), before it ends; [failed] tells it the failure it ended
 *   with.
 *
 * [NONE] observes nothing, and [observe] runs a block with no overhead for it.
 */
@WowSpi
interface Observation {
    val context: CoroutineContext get() = EmptyCoroutineContext

    val trace: Map<String, String> get() = context.trace

    fun outcome(outcome: ObservedOutcome) = Unit

    fun failed(error: Throwable) = Unit

    fun end() = Unit

    companion object {
        /** Observes nothing. */
        @JvmField
        val NONE: Observation = object : Observation {}
    }
}

/**
 * Runs [block] as this observed stage: in its [Observation.context], telling it what [block] fails with, and ending
 * it once [block] returns or fails. For [Observation.NONE] it only runs [block].
 */
@Suppress("TooGenericExceptionCaught") // The failure is the block's: told to the observation, then rethrown unchanged.
suspend inline fun <T> Observation.observe(crossinline block: suspend () -> T): T {
    if (this === Observation.NONE) {
        return block()
    }
    try {
        val context = context
        return if (context === EmptyCoroutineContext) block() else withContext(context) { block() }
    } catch (failure: Throwable) {
        failed(failure)
        throw failure
    } finally {
        end()
    }
}

/**
 * The trace a coroutine's work belongs to: the element an [Observer]'s observation puts in the
 * coroutine context, opaque to the core. The runtime carries it to the coroutines it starts for the same work
 * ([traceContext]) and writes its [fields] where the work goes on elsewhere ([trace]).
 */
@WowSpi
abstract class TraceContext : AbstractCoroutineContextElement(TraceContext) {
    /** The propagation fields of the trace, such as the W3C `traceparent`. */
    abstract val fields: Map<String, String>

    companion object Key : CoroutineContext.Key<TraceContext>
}

/** The propagation fields of the trace this context's work belongs to; empty when it belongs to none. */
val CoroutineContext.trace: Map<String, String>
    get() = this[TraceContext]?.fields ?: emptyMap()

/** The [TraceContext] of this context alone, to start another coroutine of the same work in; empty when none. */
val CoroutineContext.traceContext: CoroutineContext
    get() = this[TraceContext] ?: EmptyCoroutineContext
