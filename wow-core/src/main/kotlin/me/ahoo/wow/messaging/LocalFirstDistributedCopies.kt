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
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
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
 * As a [RuntimeComponent] stopped after the dispatchers and before the transports' runtime resources, it lets every
 * queued copy be sent within the runtime's shutdown deadline ([stopGracefully]); a force stop cancels the rest, which
 * other services then never receive.
 */
class LocalFirstDistributedCopies(
    private val name: String = "LocalFirstDistributedCopies",
    private val metrics: WowMetrics = WowMetrics.NONE,
    private val backlogHighWaterMark: Int = DEFAULT_BACKLOG_HIGH_WATER_MARK,
) : RuntimeComponent {
    companion object {
        private val log = KotlinLogging.logger {}
        private val DRAIN_POLL_INTERVAL: Duration = Duration.ofMillis(10)
        const val DEFAULT_BACKLOG_HIGH_WATER_MARK: Int = 10_000
        const val BACKLOG_METRIC = "wow.local_first.backlog"
    }

    init {
        require(backlogHighWaterMark > 0) { "backlogHighWaterMark must be greater than 0." }
    }

    private val lanes = ConcurrentHashMap<Any, Lane>()
    private val backlogs = ConcurrentHashMap<NamedAggregate, AtomicInteger>()
    private val total = AtomicInteger()

    /** The copies not yet sent or failed. */
    val pending: Int
        get() = total.get()

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
        val copy = Copy(namedAggregate.materialize(), context, description, send)
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
        private val context: ContextView,
        private val description: () -> String,
        private val send: (Boolean) -> Mono<Void>,
    ) {
        internal lateinit var lane: Lane
        private val decision = AtomicReference<Mono<Boolean>?>()
        private val done = Sinks.empty<Void>()
        internal var subscription: Disposable? = null

        /** Completes when this copy was sent, or fails with the send error. */
        val sent: Mono<Void> = done.asMono()

        internal val decided: Boolean
            get() = decision.get() != null

        /** The local hand-off result; only the first call counts. */
        fun decide(handoff: LocalHandoff) {
            val admission = if (handoff.accepted) handoff.admission else Mono.just(false)
            if (decision.compareAndSet(null, admission)) {
                drain(lane)
            }
        }

        internal fun start() {
            val admission = checkNotNull(decision.get())
            subscription = admission
                .onErrorReturn(false)
                .defaultIfEmpty(false)
                .flatMap(send)
                .contextWrite(Context.of(context))
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
        }

        internal fun cancel() {
            subscription?.dispose()
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

    /** Starts the head copy of [lane] when it is decided and nothing runs; loops instead of recursing. */
    private fun drain(lane: Lane) {
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
                next.start()
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
