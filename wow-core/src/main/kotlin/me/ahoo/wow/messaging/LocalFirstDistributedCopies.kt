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

package me.ahoo.wow.messaging

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import reactor.core.Disposables
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The distributed copies of a [LocalFirstMessageBus], sent in send order per aggregate without the local-first sender
 * waiting for a local receiver's demand (decision D3).
 *
 * Every local-first send [enqueues][enqueue] its copy in the queue of its aggregate (by aggregate ID). A copy is sent
 * only after every earlier copy of that aggregate was sent, so the distributed bus sees an aggregate's messages in the
 * order they were sent, as before 9.3.0. Each copy waits, in its turn, for the local decision ([Copy.decide]): a
 * handed-off message waits for its admission (`local_first=true` when admitted, `false` when rejected), a refused one
 * goes out at once with `local_first=false`. A failed copy is logged and counted by the distributed bus's send
 * metrics; only a refused sender, which waits for its own copy, sees the failure.
 *
 * The backlog — copies not yet sent — is a gauge per aggregate type (`wow.local_first.backlog`); reaching
 * [backlogHighWaterMark] logs a warning. A slow local consumer makes it, and the in-process local sink, grow.
 *
 * [name] must be unique per meter registry (the starter names one per bus): the gauge of a second instance with the
 * same name would read the first one's backlog.
 *
 * As a [RuntimeComponent] stopped after the dispatchers and before the transports' runtime resources, it lets every
 * queued copy be sent within the runtime's shutdown deadline ([stopGracefully]); a force stop cancels the rest, which
 * other services then never receive. Only an instance registered with the runtime (the starter registers the ones it
 * creates) is awaited at shutdown; one created by hand is not, unless it is added to the runtime's components.
 */
class LocalFirstDistributedCopies(
    private val name: String = "LocalFirstDistributedCopies",
    private val metrics: WowMetrics = WowMetrics.NONE,
    private val backlogHighWaterMark: Int = DEFAULT_BACKLOG_HIGH_WATER_MARK,
    private val handOffTimeout: Duration = DEFAULT_HAND_OFF_TIMEOUT,
) : RuntimeComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private val DRAIN_POLL_INTERVAL: Duration = Duration.ofMillis(10)
        const val DEFAULT_BACKLOG_HIGH_WATER_MARK: Int = 10_000

        /**
         * How long a copy waits for the local hand-off result (which a local bus answers at once) before it is sent
         * unmarked. A hand-off accepted later still processes the message locally: a duplicate, never a stuck queue.
         * The admission that follows an accepted hand-off has no timeout: closing a route rejects it.
         */
        val DEFAULT_HAND_OFF_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val BACKLOG_METRIC = "wow.local_first.backlog"
    }

    init {
        require(backlogHighWaterMark > 0) { "backlogHighWaterMark must be greater than 0." }
        require(!handOffTimeout.isNegative && !handOffTimeout.isZero) { "handOffTimeout must be positive." }
    }

    private val lanes = ConcurrentHashMap<Any, Lane>()
    private val backlogs = ConcurrentHashMap<NamedAggregate, AtomicInteger>()
    private val total = AtomicInteger()

    /** The copies not yet sent or failed. */
    val pending: Int
        get() = total.get()

    /** The aggregates with a queue; a queue is removed once it is empty. */
    internal val queues: Int
        get() = lanes.size

    /** The copies of [namedAggregate]'s aggregates not yet sent. */
    fun backlog(namedAggregate: NamedAggregate): Int = backlogs[namedAggregate.materialize()]?.get() ?: 0

    /**
     * Queues the copy of a message of the aggregate [key] (its aggregate ID), sent by [send] with the local decision
     * once every earlier copy of that aggregate was sent, in the sender's [context].
     */
    fun enqueue(
        key: Any,
        namedAggregate: NamedAggregate,
        context: ContextView = Context.empty(),
        description: () -> String,
        send: (admitted: Boolean) -> Mono<Void>,
    ): Copy {
        val copy = Copy(namedAggregate.materialize(), LocalFirstContextCaptures.capture(context), description, send)
        while (true) {
            val lane = lanes.computeIfAbsent(key) { Lane(it) }
            val added = synchronized(lane) {
                if (lane.removed) {
                    false
                } else {
                    lane.queue.addLast(copy)
                    true
                }
            }
            if (added) {
                copy.lane = lane
                break
            }
        }
        total.incrementAndGet()
        val backlog = backlogs.computeIfAbsent(copy.namedAggregate) { aggregate ->
            AtomicInteger().also { registerGauge(aggregate, it) }
        }
        if (backlog.incrementAndGet() == backlogHighWaterMark) {
            log.warn {
                "[$name] $backlogHighWaterMark distributed copies of [${copy.namedAggregate.aggregateName}] wait to be " +
                    "sent: a local consumer falls behind and the in-process backlog grows."
            }
        }
        return copy
    }

    private fun registerGauge(namedAggregate: NamedAggregate, backlog: AtomicInteger) {
        metrics.gauge(
            BACKLOG_METRIC,
            MetricDescriptor(
                component = name,
                operation = "copy",
                context = namedAggregate.contextName,
                aggregate = namedAggregate.aggregateName,
            ),
        ) { backlog.get() }
    }

    /** One queued copy; [decide] tells it the local hand-off result. */
    inner class Copy internal constructor(
        internal val namedAggregate: NamedAggregate,
        private val context: Context,
        private val description: () -> String,
        private val send: (Boolean) -> Mono<Void>,
    ) {
        internal lateinit var lane: Lane
        private val decision = AtomicReference<Mono<Boolean>?>()
        private val done = Sinks.empty<Void>()

        // A swap disposed by a force stop disposes the send subscribed after it, so a force stop racing the start
        // still cancels the send.
        private val subscription = Disposables.swap()

        /** Completes when this copy was sent, or fails with the send error. */
        val sent: Mono<Void> = done.asMono()

        internal val decided: Boolean
            get() = decision.get() != null

        /**
         * The sender's thread while [decideFrom] runs the hand-off synchronously, else `null`: a copy decided and
         * started there, admitted as it was handed off, is sent right away on that thread (see [start]).
         */
        internal var deciding: Thread? = null

        /**
         * Subscribes [handoff] on behalf of this copy, independently of the sender, in the captured sender context
         * (trace parent, metrics source): the copy is decided by the true local result even when the sender stops
         * waiting (cancels) before it arrives. An error or an empty result counts as refused, and so does a result
         * that does not arrive within the hand-off timeout (the timer is only armed when the result is not
         * immediate). The returned [Mono] replays the result to the sender.
         */
        fun decideFrom(handoff: Mono<LocalHandoff>): Mono<LocalHandoff> {
            val result = Sinks.one<LocalHandoff>()
            val settled = AtomicBoolean()
            val timer = Disposables.swap()
            fun settle(decided: LocalHandoff) {
                if (!settled.compareAndSet(false, true)) {
                    return
                }
                timer.dispose()
                try {
                    decide(decided)
                } finally {
                    result.tryEmitValue(decided)
                }
            }
            deciding = Thread.currentThread()
            val pending = try {
                Mono.defer { handoff }
                    .onErrorResume { error ->
                        log.warn(error) { "[$name] Local hand-off of ${description()} failed; send it unmarked." }
                        Mono.just(LocalHandoff.REFUSED)
                    }
                    .defaultIfEmpty(LocalHandoff.REFUSED)
                    .contextWrite(context)
                    .subscribe(::settle)
            } finally {
                deciding = null
            }
            if (!settled.get()) {
                timer.update(
                    Mono.delay(handOffTimeout).subscribe {
                        if (!settled.get()) {
                            log.warn {
                                "[$name] No local hand-off result for ${description()} within $handOffTimeout; " +
                                    "send it unmarked."
                            }
                            pending.dispose()
                            settle(LocalHandoff.REFUSED)
                        }
                    },
                )
            }
            return result.asMono()
        }

        /** The local hand-off result; only the first call counts. */
        fun decide(handoff: LocalHandoff) {
            val admission = if (handoff.accepted) handoff.admission else NOT_ADMITTED
            if (decision.compareAndSet(null, admission)) {
                drain(lane, this)
            }
        }

        /**
         * Sends this copy once its admission completes. Only a copy started by its own decision, within the sender's
         * [decideFrom] on the sender's thread, and already decided (admitted as it was handed off, or not handed off)
         * is sent on that thread: the sender is sending anyway. Every other start (a copy started when the one before
         * it finished, on the previous send's completion thread such as a transport's network thread; an admission
         * completed by a receiver, by demand being replenished, or rejected by a route closing) sends on
         * [Schedulers.parallel], so encoding and sending never run on a transport, store or lifecycle thread.
         */
        internal fun start(inline: Boolean) {
            val admission = checkNotNull(decision.get())
            val immediate = inline && (admission === ADMITTED_ON_HAND_OFF || admission === NOT_ADMITTED)
            val sending = (if (immediate) admission else admission.publishOn(Schedulers.parallel()))
                .onErrorReturn(false)
                .defaultIfEmpty(false)
                .flatMap(send)
                .contextWrite(context)
                .doFinally { finish(this) }
                .subscribe(
                    null,
                    { error ->
                        log.warn(error) {
                            "[$name] Failed to send the distributed copy of ${description()}; consumers outside " +
                                "this process miss it."
                        }
                        done.tryEmitError(error)
                    },
                    { done.tryEmitEmpty() },
                )
            subscription.update(sending)
        }

        internal fun cancel() {
            subscription.dispose()
            done.tryEmitError(CancellationException("The distributed copy of ${description()} was cancelled."))
        }
    }

    internal inner class Lane(val key: Any) {
        val queue = ArrayDeque<Copy>()
        var running = false
        var draining = false
        var missed = false
        var removed = false
    }

    /**
     * Starts the head copy of [lane] when it is decided and nothing runs; loops instead of recursing. [decided] is the
     * copy whose decision triggered this drain, if any.
     */
    private fun drain(lane: Lane, decided: Copy? = null) {
        synchronized(lane) {
            if (lane.draining) {
                lane.missed = true
                return
            }
            lane.draining = true
        }
        while (true) {
            val next = synchronized(lane) {
                lane.missed = false
                val head = lane.queue.firstOrNull()
                if (!lane.running && head != null && head.decided) {
                    lane.running = true
                    head
                } else {
                    null
                }
            }
            if (next != null) {
                next.start(inline = next === decided && next.deciding === Thread.currentThread())
                continue
            }
            val idle = synchronized(lane) {
                if (lane.missed) {
                    return@synchronized false
                }
                lane.draining = false
                if (lane.queue.isEmpty() && !lane.running) {
                    lane.removed = true
                    lanes.remove(lane.key, lane)
                }
                true
            }
            if (idle) {
                return
            }
        }
    }

    private fun finish(copy: Copy) {
        val lane = copy.lane
        synchronized(lane) {
            if (lane.queue.firstOrNull() === copy) {
                lane.queue.removeFirst()
                lane.running = false
            } else {
                // Cancelled by a force stop, which cleared the queue.
                return
            }
        }
        total.decrementAndGet()
        backlogs[copy.namedAggregate]?.decrementAndGet()
        drain(lane)
    }

    override fun prepare(runtimeContext: RuntimeContext): Mono<Void> = Mono.empty()

    override fun start() = Unit

    override fun stopGracefully(): Mono<Void> =
        Mono.defer {
            if (total.get() == 0) {
                Mono.empty()
            } else {
                log.info { "[$name] Wait for ${total.get()} distributed copies to be sent." }
                Flux.interval(DRAIN_POLL_INTERVAL)
                    .filter { total.get() == 0 }
                    .next()
                    .then()
            }
        }

    override fun forceStop() {
        val cancelled = lanes.values.flatMap { lane ->
            synchronized(lane) {
                lane.removed = true
                lane.queue.toList().also { lane.queue.clear() }
            }
        }
        lanes.clear()
        if (cancelled.isNotEmpty()) {
            log.warn { "[$name] Cancel ${cancelled.size} distributed copies not sent yet; other services miss them." }
        }
        cancelled.forEach { copy ->
            copy.cancel()
            backlogs[copy.namedAggregate]?.decrementAndGet()
        }
        total.addAndGet(-cancelled.size)
    }

    override fun toString(): String = name
}
