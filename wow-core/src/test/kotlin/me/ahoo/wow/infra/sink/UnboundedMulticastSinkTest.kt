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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscription
import reactor.core.Fuseable
import reactor.core.Scannable
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UnboundedMulticastSinkTest {

    @Test
    fun `the queue is FIFO across chunk boundaries and reports empty`() {
        val queue = ChunkedSpscQueue<Int>(chunkSize = 4)
        queue.poll().assert().isNull()
        queue.isEmpty().assert().isTrue()

        (0 until 37).forEach { queue.offer(it).assert().isTrue() }
        queue.size.assert().isEqualTo(37)
        queue.isEmpty().assert().isFalse()

        val polled = generateSequence { queue.poll() }.toList()
        polled.assert().isEqualTo((0 until 37).toList())
        queue.isEmpty().assert().isTrue()
        queue.size.assert().isZero()

        // Interleaved offers and polls keep working past many chunks.
        repeat(100) {
            queue.offer(it)
            queue.poll().assert().isEqualTo(it)
        }
        queue.offer(1)
        queue.offer(2)
        queue.clear()
        queue.isEmpty().assert().isTrue()
        queue.poll().assert().isNull()
    }

    @Test
    fun `the queue behaves as a FIFO under any mix of offers and polls, growing and reusing rings`() {
        listOf(2, 4, 8).forEach { chunkSize ->
            val queue = ChunkedSpscQueue<Int>(chunkSize)
            val model = ArrayDeque<Int>()
            val random = kotlin.random.Random(chunkSize)
            var next = 0
            repeat(200_000) {
                // Bursts of offers outrun the consumer (new rings); bursts of polls drain it (rings reused).
                if (random.nextInt(100) < if (it % 5_000 < 2_500) 70 else 30) {
                    queue.offer(next)
                    model.addLast(next)
                    next++
                } else {
                    queue.poll().assert().describedAs("chunkSize=$chunkSize").isEqualTo(model.removeFirstOrNull())
                }
                queue.isEmpty().assert().isEqualTo(model.isEmpty())
                queue.size.assert().isEqualTo(model.size)
            }
            while (model.isNotEmpty()) {
                queue.poll().assert().isEqualTo(model.removeFirst())
            }
            queue.poll().assert().isNull()
        }
    }

    @Test
    fun `the chunk size must be a power of two`() {
        assertThrows<IllegalArgumentException> { ChunkedSpscQueue<Int>(chunkSize = 3) }
        assertThrows<IllegalArgumentException> { ChunkedSpscQueue<Int>(chunkSize = 1) }
    }

    @Test
    @Suppress("ExplicitGarbageCollectionCall")
    fun `the queue retains nothing it handed out`() {
        val queue = ChunkedSpscQueue<Any>(chunkSize = 8)
        val references = (0 until 1_000).map {
            val value = Any()
            queue.offer(value)
            WeakReference(value)
        }
        var taken = 0
        while (queue.poll() != null) {
            taken++
        }
        taken.assert().isEqualTo(1_000)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (references.any { it.get() != null } && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        references.count { it.get() != null }.assert().isZero()
    }

    @Test
    fun `it buffers without bound before the first subscriber and multicasts after`() {
        val sink = UnboundedMulticastSink.create<Int>(autoCancel = false)
        // Far more than Reactor's small buffer: never refused.
        (0 until 10_000).forEach { sink.tryEmitNext(it).assert().isEqualTo(Sinks.EmitResult.OK) }
        sink.scanUnsafe(Scannable.Attr.BUFFERED).assert().isEqualTo(10_000)

        val first = sink.asFlux().take(10_000).collectList().toFuture()
        first.get(10, TimeUnit.SECONDS).assert().isEqualTo((0 until 10_000).toList())

        val a = CopyOnWriteArrayList<Int>()
        val b = CopyOnWriteArrayList<Int>()
        sink.asFlux().subscribe { a += it }
        sink.asFlux().subscribe { b += it }
        sink.currentSubscriberCount().assert().isEqualTo(2)
        (0 until 3).forEach { sink.tryEmitNext(it) }
        a.assert().containsExactly(0, 1, 2)
        b.assert().containsExactly(0, 1, 2)
    }

    @Test
    fun `the slowest subscriber's demand paces every subscriber`() {
        val sink = UnboundedMulticastSink.create<Int>()
        val fast = CopyOnWriteArrayList<Int>()
        val slow = object : BaseSubscriber<Int>() {
            val received = CopyOnWriteArrayList<Int>()
            override fun hookOnSubscribe(subscription: Subscription) = request(1)
            override fun hookOnNext(value: Int) {
                received += value
            }
        }
        sink.asFlux().subscribe { fast += it }
        sink.asFlux().subscribe(slow)
        (0 until 5).forEach { sink.tryEmitNext(it) }

        slow.received.assert().containsExactly(0)
        fast.assert().containsExactly(0)
        slow.request(10)
        slow.received.assert().containsExactly(0, 1, 2, 3, 4)
        fast.assert().containsExactly(0, 1, 2, 3, 4)
    }

    @Test
    fun `terminal signals reach subscribers and later emissions fail`() {
        val completed = UnboundedMulticastSink.create<Int>()
        val completedValues = completed.asFlux().test()
        completed.tryEmitNext(1)
        completed.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
        completedValues.expectNext(1).verifyComplete()
        completed.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        completed.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        completed.tryEmitError(IllegalStateException()).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        completed.emitNext(3, Sinks.EmitFailureHandler.FAIL_FAST)
        completed.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
        completed.emitError(IllegalStateException("late"), Sinks.EmitFailureHandler.FAIL_FAST)
        completed.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)

        val failed = UnboundedMulticastSink.create<Int>()
        val values = CopyOnWriteArrayList<Int>()
        val errors = CopyOnWriteArrayList<Throwable>()
        failed.asFlux().subscribe({ values += it }, { errors += it })
        failed.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)
        failed.emitError(IllegalStateException("boom"), Sinks.EmitFailureHandler.FAIL_FAST)
        values.assert().containsExactly(1)
        errors.single().message.assert().isEqualTo("boom")

        val viaHandler = UnboundedMulticastSink.create<Int>()
        viaHandler.asFlux().test().also {
            viaHandler.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
        }.verifyComplete()
    }

    @Test
    fun `it auto-cancels when its last subscriber leaves, as Reactor's multicast buffer does`() {
        val sink = UnboundedMulticastSink.create<Int>()
        val reactor = Sinks.unsafe().many().multicast().onBackpressureBuffer<Int>(Int.MAX_VALUE)
        listOf(sink, reactor).forEach {
            it.asFlux().subscribe().dispose()
            it.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
            it.currentSubscriberCount().assert().isZero()
        }
        sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
        reactor.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)

        val kept = UnboundedMulticastSink.create<Int>(autoCancel = false)
        kept.asFlux().subscribe().dispose()
        kept.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(false)
        kept.tryEmitNext(1)
        kept.asFlux().take(1).test().expectNext(1).verifyComplete()
    }

    @Test
    fun `the source fuses only in ASYNC mode and with one subscriber`() {
        val source = FusedQueueSource<Int>()
        source.requestFusion(Fuseable.SYNC).assert().isEqualTo(Fuseable.NONE)
        source.requestFusion(Fuseable.ANY).assert().isEqualTo(Fuseable.ASYNC)
        source.request(1)
        source.cancelled.assert().isFalse()
        source.cancel()
        source.cancelled.assert().isTrue()

        val unfused = FusedQueueSource<Int>()
        assertThrows<IllegalStateException> {
            unfused.subscribe(object : BaseSubscriber<Int>() {})
        }
        assertThrows<IllegalStateException> {
            unfused.subscribe(object : BaseSubscriber<Int>() {})
        }
    }

    @Test
    fun `concurrent producers lose nothing and keep each producer's order under backpressure`() {
        val producers = 8
        val perProducer = 20_000
        val sink = UnboundedMulticastSink.create<Long>().concurrent()
        val received = ConcurrentHashMap<Int, MutableList<Int>>()
        val total = AtomicInteger()
        val consumed = CountDownLatch(1)
        val consumer = Schedulers.newSingle("multicast-consumer")
        // A consumer on its own thread, requesting in small batches, so drains run on producer and consumer threads.
        sink.asFlux()
            .publishOn(consumer, 32)
            .subscribe { value ->
                val producer = (value shr 32).toInt()
                received.computeIfAbsent(producer) { mutableListOf() } += value.toInt()
                if (total.incrementAndGet() == producers * perProducer) {
                    consumed.countDown()
                }
            }
        val pool = Executors.newFixedThreadPool(producers)
        try {
            val start = CountDownLatch(1)
            repeat(producers) { producer ->
                pool.execute {
                    start.await()
                    repeat(perProducer) { sequence ->
                        sink.tryEmitNext((producer.toLong() shl 32) or sequence.toLong()).orThrow()
                    }
                }
            }
            start.countDown()
            consumed.await(60, TimeUnit.SECONDS).assert().isTrue()

            total.get().assert().isEqualTo(producers * perProducer)
            repeat(producers) { producer ->
                received[producer].assert().isEqualTo((0 until perProducer).toList())
            }
            sink.scanUnsafe(Scannable.Attr.BUFFERED).assert().isEqualTo(0)
        } finally {
            pool.shutdownNow()
            consumer.dispose()
        }
    }

    @Test
    fun `a subscriber arriving late gets what is still buffered`() {
        val sink = UnboundedMulticastSink.create<Int>()
        sink.tryEmitNext(1)
        sink.tryEmitNext(2)
        sink.asFlux().take(2).collectList().block(Duration.ofSeconds(5)).assert().containsExactly(1, 2)
    }
}
