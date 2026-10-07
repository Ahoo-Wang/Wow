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

package me.ahoo.wow.infra.sink

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import reactor.core.CoreSubscriber
import reactor.core.Fuseable
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Operators
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.util.context.Context
import java.util.concurrent.atomic.AtomicLongFieldUpdater

/**
 * A multicast [Sinks.Many] with an unbounded buffer: exactly what
 * `Sinks.unsafe().many().multicast().onBackpressureBuffer(Int.MAX_VALUE)` is (the same Reactor multicast processor, the
 * same auto-cancel, subscriber, demand and terminal behaviour, and an emission is never refused for want of room), but
 * the processor buffers in a [ChunkedSpscQueue] instead of Reactor's `SpscLinkedArrayQueue`.
 *
 * The processor is subscribed, with queue fusion, to a source that owns the queue: an emission offers to the queue and
 * signals the processor, which drains the queue as it does its own.
 *
 * Emissions must be serialized, as on any Reactor sink: wrap it with [concurrent] for concurrent producers.
 */
internal class UnboundedMulticastSink<T : Any> private constructor(
    private val processor: Sinks.ManyWithUpstream<T>,
) : Sinks.Many<T> {
    private val source = FusedQueueSource<T>()

    /** Set once a terminal signal was emitted; a later emission fails as on the processor itself. */
    @Volatile
    private var terminated = false

    init {
        processor.subscribeTo(source)
        checkNotNull(source.downstream) { "The multicast processor did not subscribe to its queue source." }
    }

    companion object {
        fun <T : Any> create(autoCancel: Boolean = true): UnboundedMulticastSink<T> =
            UnboundedMulticastSink(
                Sinks.unsafe().manyWithUpstream().multicastOnBackpressureBuffer(Int.MAX_VALUE, autoCancel),
            )
    }

    override fun tryEmitNext(t: T): Sinks.EmitResult {
        if (terminated) {
            return Sinks.EmitResult.FAIL_TERMINATED
        }
        if (source.cancelled) {
            return Sinks.EmitResult.FAIL_CANCELLED
        }
        source.emit(t)
        return Sinks.EmitResult.OK
    }

    override fun tryEmitComplete(): Sinks.EmitResult {
        if (terminated) {
            return Sinks.EmitResult.FAIL_TERMINATED
        }
        terminated = true
        source.complete()
        return Sinks.EmitResult.OK
    }

    override fun tryEmitError(error: Throwable): Sinks.EmitResult {
        if (terminated) {
            return Sinks.EmitResult.FAIL_TERMINATED
        }
        terminated = true
        source.error(error)
        return Sinks.EmitResult.OK
    }

    override fun emitNext(t: T, failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val result = tryEmitNext(t)
            if (result.isSuccess) {
                return
            }
            if (!failureHandler.onEmitFailure(SignalType.ON_NEXT, result)) {
                // Only FAIL_TERMINATED is possible: the value is dropped, as Reactor's sinks drop it.
                Operators.onNextDropped(t, currentContext())
                return
            }
        }
    }

    override fun emitComplete(failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val result = tryEmitComplete()
            if (result.isSuccess || !failureHandler.onEmitFailure(SignalType.ON_COMPLETE, result)) {
                return
            }
        }
    }

    override fun emitError(error: Throwable, failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val result = tryEmitError(error)
            if (result.isSuccess) {
                return
            }
            if (!failureHandler.onEmitFailure(SignalType.ON_ERROR, result)) {
                Operators.onErrorDropped(error, currentContext())
                return
            }
        }
    }

    private fun currentContext(): Context =
        (processor as? CoreSubscriber<*>)?.currentContext() ?: Context.empty()

    override fun currentSubscriberCount(): Int = processor.currentSubscriberCount()

    override fun asFlux(): Flux<T> = processor.asFlux()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? = processor.scanUnsafe(key)
}

/**
 * The upstream of an [UnboundedMulticastSink]'s processor: a [Fuseable.QueueSubscription] over a [ChunkedSpscQueue],
 * fused in `ASYNC` mode, so the processor polls the queue and an `onNext` only tells it to drain.
 */
internal class FusedQueueSource<T : Any> :
    Publisher<T>,
    Fuseable.QueueSubscription<T> {
    private val queue = ChunkedSpscQueue<T>()

    @Volatile
    var downstream: Subscriber<in T>? = null
        private set

    override fun subscribe(subscriber: Subscriber<in T>) {
        check(downstream == null) { "FusedQueueSource supports exactly one subscriber." }
        downstream = subscriber
        subscriber.onSubscribe(this)
        check(fused) { "FusedQueueSource requires a subscriber that fuses in ASYNC mode." }
    }

    private var fused = false

    override fun requestFusion(requestedMode: Int): Int {
        if (requestedMode and Fuseable.ASYNC != 0) {
            fused = true
            return Fuseable.ASYNC
        }
        return Fuseable.NONE
    }

    fun emit(value: T) {
        queue.offer(value)
        // ASYNC fusion: the value is in the queue; the signal only triggers a drain.
        @Suppress("UNCHECKED_CAST")
        (downstream as Subscriber<Any?>).onNext(null)
    }

    fun complete() {
        downstream?.onComplete()
    }

    fun error(error: Throwable) {
        downstream?.onError(error)
    }

    /** The processor requests once; with the values pulled from the queue there is no demand to track here. */
    override fun request(n: Long) = Unit

    /** Set once the processor auto-cancelled (its last subscriber left); it clears the queue itself. */
    @Volatile
    var cancelled = false
        private set

    override fun cancel() {
        cancelled = true
    }

    override fun poll(): T? = queue.poll()

    override val size: Int
        get() = queue.size

    override fun isEmpty(): Boolean = queue.isEmpty()

    override fun clear() {
        queue.clear()
    }
}

/**
 * An unbounded single-producer single-consumer queue of linked fixed-size array chunks (a Lamport queue per chunk).
 *
 * The producer writes a slot with a plain store and then publishes its index with an ordered store; the consumer reads
 * the published index and then the slots below it, so it never sees a slot before its value. A new chunk is linked
 * before the first index in it is published. The consumer clears every slot it takes and drops a chunk once it has
 * passed it, so nothing taken is retained.
 *
 * Successive producers (or consumers) on different threads must be ordered by a happens-before edge, as Reactor's own
 * queues require: a lock around the emissions, the processor's work-in-progress counter around the drains.
 */
internal class ChunkedSpscQueue<T : Any>(chunkSize: Int = DEFAULT_CHUNK_SIZE) {
    private val mask: Int

    init {
        require(chunkSize >= 2 && chunkSize and (chunkSize - 1) == 0) { "chunkSize must be a power of two >= 2." }
        mask = chunkSize - 1
    }

    private class Chunk(size: Int) {
        val slots = arrayOfNulls<Any>(size)
        var next: Chunk? = null
    }

    private var producerChunk = Chunk(chunkSize)
    private var consumerChunk = producerChunk

    /** Written by the producer only, published with an ordered store. */
    @Volatile
    @JvmField
    var producerIndex = 0L

    /** Written by the consumer only, published with an ordered store (for [size]). */
    @Volatile
    @JvmField
    var consumerIndex = 0L

    /** The producer's own copy of [producerIndex]. */
    private var producerPosition = 0L

    /** The consumer's own copy of [consumerIndex], and the last [producerIndex] it read. */
    private var consumerPosition = 0L
    private var producerLimit = 0L

    fun offer(value: T): Boolean {
        val index = producerPosition
        val offset = index.toInt() and mask
        if (offset == 0 && index != 0L) {
            val next = Chunk(mask + 1)
            producerChunk.next = next
            producerChunk = next
        }
        producerChunk.slots[offset] = value
        producerPosition = index + 1
        PRODUCER_INDEX.lazySet(this, index + 1)
        return true
    }

    fun poll(): T? {
        val index = consumerPosition
        if (index == producerLimit) {
            producerLimit = producerIndex
            if (index == producerLimit) {
                return null
            }
        }
        val offset = index.toInt() and mask
        if (offset == 0 && index != 0L) {
            consumerChunk = checkNotNull(consumerChunk.next) { "ChunkedSpscQueue chunk was not linked." }
        }
        val slots = consumerChunk.slots

        @Suppress("UNCHECKED_CAST")
        val value = slots[offset] as T
        slots[offset] = null
        consumerPosition = index + 1
        CONSUMER_INDEX.lazySet(this, index + 1)
        return value
    }

    fun isEmpty(): Boolean = consumerPosition == producerLimit && consumerPosition == producerIndex

    /** An estimate from any thread; exact from the consumer. */
    val size: Int
        get() = (producerIndex - consumerIndex).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** Consumer side: takes and drops everything published. */
    fun clear() {
        while (poll() != null) {
            // drop
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256

        private val PRODUCER_INDEX: AtomicLongFieldUpdater<ChunkedSpscQueue<*>> =
            AtomicLongFieldUpdater.newUpdater(ChunkedSpscQueue::class.java, "producerIndex")
        private val CONSUMER_INDEX: AtomicLongFieldUpdater<ChunkedSpscQueue<*>> =
            AtomicLongFieldUpdater.newUpdater(ChunkedSpscQueue::class.java, "consumerIndex")
    }
}
