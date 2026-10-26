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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The observation seams of the core (decision D174): where the kernel and the runtime tell an observer, such as
 * wow-observability's tracing and metrics, that a stage of their work starts. The core knows no tracing or metrics
 * library; an observer only watches and never changes what the work does (06-modules §3).
 *
 * Each method starts an [Observation] of one stage, which the core ends when the stage does. The default methods, and
 * [NONE], observe nothing: they return [Observation.NONE], so the core allocates nothing for a stage nobody observes.
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

    /** The kernel's execution of [command], from taking it to its outcome ([Observation.outcome]). */
    fun executing(command: Command<*>): Observation = Observation.NONE

    /**
     * The kernel's execution of [command], whose resolved type name is [commandType] (`context.aggregate.Name`, as the
     * Manifest and the log name it, `@Name` included); the kernel calls this one. By default [executing] without it.
     */
    fun executing(command: Command<*>, commandType: String): Observation = executing(command)

    /**
     * A command of [type] that ended before the kernel took it (review A5-11), with the framework or authorization
     * [code] it was answered with: an admission refusal, a busy lane or node (`wow.command.busy`), a closing node, an
     * unavailable store, a wait that timed out while it queued.
     */
    fun refused(type: AggregateType, code: String) = Unit

    /** A wait on a command of [type] that timed out (`wow.wait.timeout`), at the [stage] it had reached last. */
    fun waitTimedOut(type: AggregateType, stage: Stage) = Unit

    /** A wait signal for the node [origin] that was dropped without being sent, for [reason] (review A5-12). */
    fun signalDropped(origin: String, reason: String) = Unit

    /**
     * A forward of a command of [type] to its partition's owner that did not get the owner's settlement (decision
     * D223): `local` when it surely did not reach the owner and ran here, `busy` when the peer had its most forwards in
     * flight and it ran here, `unknown` when it may have reached the owner and its outcome is unknown.
     */
    fun forwardFailed(type: AggregateType, outcome: String) = Unit

    /**
     * A lookup of an aggregate's state in the state cache of [context] by the kernel, or a state evicted from it
     * (work package B.O-4): [outcome] is `hit`, `miss` or `evicted`. A miss costs a snapshot and a tail read.
     */
    fun stateCache(context: String, outcome: String) = Unit

    /**
     * A committed fact of [context] that lost a key it reserved (decision D50): another owner took the key after the
     * fact's pending reservation expired, so confirming it could not get it back. Logged as an error too; a counter to
     * alert on, since the fact now breaks the uniqueness it reserved for.
     */
    fun reservationLost(context: String) = Unit

    /**
     * A store fell back from an optional capability (finding R5.B-9): [capability] is one of [StoreFallbacks]'s
     * capabilities, [reason] why ([StoreFallbacks]); the work went on the way every store can. Counted, never failing.
     */
    fun storeFellBack(context: String, capability: String, reason: String) = Unit

    /** One decision of [command]: its decide function and its after hooks. */
    fun deciding(command: Command<*>): Observation = Observation.NONE

    /** The append of [stream]; what is appended with it carries the observation's [Observation.trace]. */
    fun appending(stream: EventStreamRecord): Observation = Observation.NONE

    /**
     * [subscription] of [context] handling [stream], retries included; [trace] holds the propagation fields the entry
     * carries. [topic] is the topic the entry was read from (a consumption), null for the context's log.
     */
    fun handling(
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ): Observation = Observation.NONE

    /**
     * [subscription] of the subscription [group] paused [partition] on a systemic failure (decision D243), [reason]
     * saying which: it handles nothing there but one probe at a time, spending no aggregate's retries and parking none,
     * until a handling succeeds ([partitionResumed]).
     */
    fun partitionPaused(group: String, subscription: String, partition: Int, reason: String) = Unit

    /** [subscription] of the subscription [group] resumed [partition], paused for [pausedMillis] ([partitionPaused]). */
    fun partitionResumed(group: String, subscription: String, partition: Int, pausedMillis: Long) = Unit

    /**
     * A tail of [partition] of [context]'s log read the store (decision D249): the node hands its own commits to its
     * tails in memory, so a tail reads the store only when memory cannot continue it, for [reason]: `start` (a tail
     * opened behind the head), `gap` (a sequence another node appended, or whose outcome was unknown), `overflow` (a
     * tail too slow for its hand-overs), `idle` (entries other nodes appended, found by a probe of the head).
     */
    fun tailRead(context: String, partition: Int, reason: String) = Unit

    /**
     * [subscription] of the subscription [group] started throttling [partition] on backpressure ([throttled] true),
     * running fewer handlings at once, or stopped, back at its in-flight bound ([throttled] false); decision D263.
     */
    fun partitionThrottled(group: String, subscription: String, partition: Int, throttled: Boolean) = Unit

    /**
     * [subscription] of the subscription [group] retries an entry in [partition] whose downstream pushed back with
     * [code] (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, or the failure's class), decision D263.
     */
    fun backpressured(group: String, subscription: String, partition: Int, code: String) = Unit

    /** The firing of [timer]: its command sent to its target. */
    fun firing(timer: Timer): Observation = Observation.NONE

    /**
     * A message at [offset] of [partition] of [topic] this version cannot decode (review A3-14): [parked] when its key
     * names an aggregate, whose subscriptions park it there; otherwise passed over, the checkpoint moving past it.
     */
    fun undecodable(topic: String, partition: Int, offset: Long, parked: Boolean) = Unit

    /**
     * The coroutine context that continues the trace whose propagation fields are [trace], arrived without a stage of
     * its own (a command forwarded over the cluster channel); empty when there is none to continue.
     */
    fun resuming(trace: Map<String, String>): CoroutineContext = EmptyCoroutineContext

    companion object {
        /** Observes nothing. */
        @JvmField
        val NONE: Observer = object : Observer {}
    }
}

/**
 * One stage of work being observed, from its start to its [end]: a span and its timings, say.
 *
 * - [context] is what the stage runs in: a [TraceContext] the work started on the way inherits, empty when the
 *   observer traces nothing.
 * - [trace] is the propagation fields of the stage, which what it sends to another node or appends carries.
 * - [outcome] names how the stage ended, before it ends; [failed] tells it the failure it ended with.
 *
 * [NONE] observes nothing, and [observe] runs a block with no overhead for it.
 */
interface Observation {
    val context: CoroutineContext get() = EmptyCoroutineContext

    val trace: Map<String, String> get() = context.trace

    fun outcome(outcome: String) = Unit

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
 * The trace a coroutine's work belongs to (decision D174): the element an [Observer]'s observation puts in the
 * coroutine context, opaque to the core. The runtime carries it to the coroutines it starts for the same work
 * ([traceContext]) and writes its [fields] where the work goes on elsewhere ([trace]).
 */
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

/** The optional store capabilities an [Observer.storeFellBack] names, and why one fell back (finding R5.B-9). */
object StoreFallbacks {
    /** A log's one-round-trip read of a snapshot with its tail ([me.ahoo.wow.spi.log.SnapshotTailReader]). */
    const val SNAPSHOT_TAIL_READ: String = "snapshot-tail-read"

    /** A snapshot store's rebuild control plane ([me.ahoo.wow.spi.snapshot.SnapshotRebuildStore]). */
    const val SNAPSHOT_REBUILD: String = "snapshot-rebuild"

    /** The capability failed; the work was done the other way. */
    const val FAILED: String = "failed"

    /** The capability cannot serve this case, such as a snapshot store in another database than the log. */
    const val UNAVAILABLE: String = "unavailable"

    /** The store does not have the capability. */
    const val UNSUPPORTED: String = "unsupported"
}
