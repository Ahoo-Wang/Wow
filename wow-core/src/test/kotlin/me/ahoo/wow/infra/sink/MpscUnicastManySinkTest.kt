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
import me.ahoo.wow.infra.Decorator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.Fuseable
import reactor.core.Scannable
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import reactor.util.concurrent.Queues
import reactor.util.context.Context
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

class MpscUnicastManySinkTest {

    companion object {
        private val TEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }

    @Test
    fun `should keep the factory owned mpsc sink unchanged when preparing concurrent emission`() {
        val sink = mpscUnicastManySink<Int>()
        val many: Sinks.Many<Int> = sink

        sink.prepareConcurrentSink().assert().isSameAs(sink)
        (many is ConcurrentManySink<*>).assert().isFalse()
        (many is Decorator<*>).assert().isFalse()
    }

    @Test
    fun `should keep an existing concurrent sink unchanged when preparing concurrent emission`() {
        val sink = Sinks.unsafe().many().unicast().onBackpressureBuffer<Int>().concurrent()

        sink.prepareConcurrentSink().assert().isSameAs(sink)
    }

    @Test
    fun `should wrap an arbitrary raw sink when preparing concurrent emission`() {
        val sink = Sinks.unsafe().many().multicast().onBackpressureBuffer<Int>()

        val prepared = sink.prepareConcurrentSink()

        (prepared is ConcurrentManySink<*>).assert().isTrue()
        (prepared as ConcurrentManySink<Int>).delegate.assert().isSameAs(sink)
    }

    @Test
    fun `should defer physical terminal until an admitted next returns`() {
        val delegate = BlockingNextManySink()
        val sink = sinkWithDelegate(delegate)
        val executor = Executors.newFixedThreadPool(2)
        val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
        try {
            delegate.nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            val terminal = executor.submit<Sinks.EmitResult> { sink.tryEmitComplete() }
            terminal.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.terminalCalled.count.assert().isEqualTo(1)
        } finally {
            delegate.releaseNext.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }

        next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
        delegate.calls.assert().containsExactly("next", "complete")
    }

    @Test
    fun `should not complete the real unicast delegate while an admitted offer is paused`() {
        val queue = PausingOfferQueue<Int>()
        val sink = sinkWithQueue(queue)
        val calls = ConcurrentLinkedQueue<String>()
        val terminal = CountDownLatch(1)
        val subscription = sink.asFlux().subscribe(
            { calls.add("next:$it") },
            { throw it },
            {
                calls.add("complete")
                terminal.countDown()
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            queue.offerEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            executor.submit<Sinks.EmitResult> { sink.tryEmitComplete() }
                .get(5, TimeUnit.SECONDS)
                .assert()
                .isEqualTo(Sinks.EmitResult.OK)
            calls.assert().isEmpty()

            queue.releaseOffer.countDown()
            next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
            calls.assert().containsExactly("next:1", "complete")
        } finally {
            queue.releaseOffer.countDown()
            subscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should claim only the first terminal even when the delegate accepts every terminal`() {
        val delegate = AlwaysAcceptingManySink<Int>()
        val sink = sinkWithDelegate(delegate)
        val error = IllegalStateException("loser")

        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
        sink.scanUnsafe(Scannable.Attr.ERROR).assert().isNull()
        sink.tryEmitError(error).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)

        delegate.completeCalls.get().assert().isEqualTo(1)
        delegate.errorCalls.get().assert().isEqualTo(0)
    }

    @Test
    fun `should keep logical terminal success when cancellation wins after claim`() {
        val delegate = CancellingTerminalManySink<Int>()
        val sink = sinkWithDelegate(delegate)

        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)

        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
        sink.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
        sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
    }

    @Test
    fun `should keep logical terminal success when cancellation wins before last releaser delegates`() {
        val delegate = BlockingNextManySink()
        val sink = sinkWithDelegate(delegate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            delegate.nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.completeCalls.get().assert().isEqualTo(0)
            delegate.cancelled = true
            delegate.releaseNext.countDown()

            next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.completeCalls.get().assert().isEqualTo(1)
            sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
        } finally {
            delegate.releaseNext.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should reject an unexpected physical terminal result as an invariant violation`() {
        val sink = sinkWithDelegate<Int>(
            TerminalResultManySink(Sinks.EmitResult.FAIL_TERMINATED),
        )

        assertThrows<Sinks.EmissionException> {
            sink.tryEmitComplete()
        }

        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
        sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
    }

    @Test
    fun `should reject terminal before claim when cancellation is already visible`() {
        val delegate = BlockingNextManySink()
        delegate.cancelled = true
        val sink = sinkWithDelegate(delegate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            delegate.nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
            delegate.completeCalls.get().assert().isEqualTo(0)

            delegate.releaseNext.countDown()
            next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
        } finally {
            delegate.releaseNext.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should expose logical error while its physical terminal is deferred`() {
        val delegate = BlockingNextManySink()
        val sink = sinkWithDelegate(delegate)
        val error = IllegalStateException("deferred")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            delegate.nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitError(error).assert().isEqualTo(Sinks.EmitResult.OK)
            sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
            sink.scanUnsafe(Scannable.Attr.ERROR).assert().isSameAs(error)
            delegate.terminalCalled.count.assert().isEqualTo(1)

            delegate.releaseNext.countDown()
            next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.terminalCalled.await(5, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            delegate.releaseNext.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }

        delegate.calls.assert().containsExactly("next", "error")
    }

    @Test
    fun `should complete after a reentrant terminal returns from on next`() {
        val sink = mpscUnicastManySink<Int>()
        val calls = mutableListOf<String>()
        val terminalResult = AtomicReference<Sinks.EmitResult>()
        val terminal = CountDownLatch(1)
        val subscription = sink.asFlux().subscribe(
            {
                calls.add("next-start")
                terminalResult.set(sink.tryEmitComplete())
                sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
                calls.add("next-end")
            },
            { throw it },
            {
                calls.add("complete")
                terminal.countDown()
            },
        )
        try {
            sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
            terminalResult.get().assert().isEqualTo(Sinks.EmitResult.OK)
            calls.assert().containsExactly("next-start", "next-end", "complete")
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `should error after a reentrant terminal returns from on next`() {
        val sink = mpscUnicastManySink<Int>()
        val calls = mutableListOf<String>()
        val error = IllegalStateException("reentrant")
        val terminalResult = AtomicReference<Sinks.EmitResult>()
        val observedError = AtomicReference<Throwable?>()
        val terminal = CountDownLatch(1)
        val subscription = sink.asFlux().subscribe(
            {
                calls.add("next-start")
                terminalResult.set(sink.tryEmitError(error))
                sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
                calls.add("next-end")
            },
            {
                calls.add("error")
                observedError.set(it)
                terminal.countDown()
            },
            { throw AssertionError("Expected the reentrant error terminal.") },
        )
        try {
            sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
            terminalResult.get().assert().isEqualTo(Sinks.EmitResult.OK)
            observedError.get().assert().isSameAs(error)
            calls.assert().containsExactly("next-start", "next-end", "error")
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun `should reject terminal when downstream cancellation happens before claim during active next`() {
        val sink = mpscUnicastManySink<Int>()
        val cancelled = CountDownLatch(1)
        val releaseOnNext = CountDownLatch(1)
        val completed = AtomicBoolean()
        val observedError = AtomicReference<Throwable?>()
        val subscriber = object : BaseSubscriber<Int>() {
            override fun hookOnNext(value: Int) {
                cancel()
                cancelled.countDown()
                check(releaseOnNext.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the cancelled onNext callback."
                }
            }

            override fun hookOnComplete() {
                completed.set(true)
            }

            override fun hookOnError(throwable: Throwable) {
                observedError.set(throwable)
            }
        }
        sink.asFlux().subscribe(subscriber)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            cancelled.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
            sink.tryEmitError(IllegalStateException("cancelled"))
                .assert()
                .isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
            sink.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
            sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(false)

            releaseOnNext.countDown()
            next.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
            completed.get().assert().isFalse()
            observedError.get().assert().isNull()
        } finally {
            releaseOnNext.countDown()
            subscriber.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should drain buffered values before terminal when subscription is late`() {
        val sink = mpscUnicastManySink<Int>()

        sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
        sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.OK)
        sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)

        StepVerifier.create(sink.asFlux())
            .expectNext(1, 2)
            .expectComplete()
            .verify(TEST_TIMEOUT)
    }

    @Test
    fun `should deliver every concurrent value once and preserve each producer order`() {
        val producerCount = 4
        val valuesPerProducer = 250
        val sink = mpscUnicastManySink<Int>()
        val received = ConcurrentLinkedQueue<Int>()
        val terminal = CountDownLatch(1)
        val subscriberError = AtomicReference<Throwable?>()
        val subscription = sink.asFlux().subscribe(
            received::add,
            {
                subscriberError.set(it)
                terminal.countDown()
            },
            terminal::countDown,
        )
        val executor = Executors.newFixedThreadPool(producerCount)
        val ready = CountDownLatch(producerCount)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until producerCount).map { producer ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(valuesPerProducer) { index ->
                        val value = producer * valuesPerProducer + index
                        sink.tryEmitNext(value).assert().isEqualTo(Sinks.EmitResult.OK)
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS).assert().isTrue()
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
            terminal.await(5, TimeUnit.SECONDS).assert().isTrue()

            subscriberError.get().assert().isNull()
            received.size.assert().isEqualTo(producerCount * valuesPerProducer)
            received.toSet().size.assert().isEqualTo(producerCount * valuesPerProducer)
            repeat(producerCount) { producer ->
                val expected = (0 until valuesPerProducer).map { producer * valuesPerProducer + it }
                received.filter { it / valuesPerProducer == producer }
                    .assert()
                    .containsExactly(*expected.toTypedArray())
            }
        } finally {
            start.countDown()
            subscription.dispose()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should allow only one winner when complete and error race`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(64) { iteration ->
                val sink = mpscUnicastManySink<Int>()
                val error = IllegalStateException("terminal-$iteration")
                val start = CyclicBarrier(3)
                val terminal = CountDownLatch(1)
                val completed = AtomicBoolean()
                val observedError = AtomicReference<Throwable?>()
                val subscription = sink.asFlux().subscribe(
                    {},
                    {
                        observedError.set(it)
                        terminal.countDown()
                    },
                    {
                        completed.set(true)
                        terminal.countDown()
                    },
                )
                val complete = executor.submit<Sinks.EmitResult> {
                    start.await(5, TimeUnit.SECONDS)
                    sink.tryEmitComplete()
                }
                val fail = executor.submit<Sinks.EmitResult> {
                    start.await(5, TimeUnit.SECONDS)
                    sink.tryEmitError(error)
                }

                start.await(5, TimeUnit.SECONDS)
                val results = listOf(
                    complete.get(5, TimeUnit.SECONDS),
                    fail.get(5, TimeUnit.SECONDS),
                )

                results.count(Sinks.EmitResult.OK::equals).assert().isEqualTo(1)
                results.count(Sinks.EmitResult.FAIL_TERMINATED::equals).assert().isEqualTo(1)
                terminal.await(5, TimeUnit.SECONDS).assert().isTrue()
                if (results.first() == Sinks.EmitResult.OK) {
                    completed.get().assert().isTrue()
                    observedError.get().assert().isNull()
                } else {
                    completed.get().assert().isFalse()
                    observedError.get().assert().isSameAs(error)
                }
                subscription.dispose()
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `emit should drop next and error after terminal`() {
        val sink = mpscUnicastManySink<Int>()
        val error = IllegalStateException("late")

        StepVerifier.create(sink.asFlux())
            .then {
                sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
                sink.emitNext(2, Sinks.EmitFailureHandler.FAIL_FAST)
                sink.emitError(error, Sinks.EmitFailureHandler.FAIL_FAST)
            }
            .expectComplete()
            .verifyThenAssertThat(TEST_TIMEOUT)
            .hasDroppedExactly(2)
            .hasDroppedErrorMatching { it === error }
    }

    @Test
    fun `emit next should discard a value after downstream cancellation`() {
        val sink = mpscUnicastManySink<Int>()

        StepVerifier.create(sink.asFlux().take(1))
            .then {
                sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)
                sink.emitNext(2, Sinks.EmitFailureHandler.FAIL_FAST)
            }
            .expectNext(1)
            .expectComplete()
            .verifyThenAssertThat(TEST_TIMEOUT)
            .hasDiscardedExactly(2)
    }

    @Test
    fun `emit next should retry with the exact signal type`() {
        val delegate = ScriptedNextManySink<Int>(
            Sinks.EmitResult.FAIL_NON_SERIALIZED,
            Sinks.EmitResult.OK,
        )
        val sink = sinkWithDelegate(delegate)
        val failures = mutableListOf<Pair<SignalType, Sinks.EmitResult>>()

        sink.emitNext(
            1,
            Sinks.EmitFailureHandler { signalType, emitResult ->
                failures.add(signalType to emitResult)
                true
            },
        )

        delegate.nextCalls.get().assert().isEqualTo(2)
        failures.assert().containsExactly(SignalType.ON_NEXT to Sinks.EmitResult.FAIL_NON_SERIALIZED)
    }

    @Test
    fun `emit next retry should observe a terminal claimed by its failure handler`() {
        val delegate = ScriptedNextManySink<Int>(Sinks.EmitResult.FAIL_NON_SERIALIZED)
        val sink = sinkWithDelegate(delegate)
        val failures = mutableListOf<Pair<SignalType, Sinks.EmitResult>>()

        StepVerifier.create(sink.asFlux())
            .then {
                sink.emitNext(
                    1,
                    Sinks.EmitFailureHandler { signalType, emitResult ->
                        failures.add(signalType to emitResult)
                        if (emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            .expectComplete()
            .verifyThenAssertThat(TEST_TIMEOUT)
            .hasDroppedExactly(1)

        failures.assert().containsExactly(
            SignalType.ON_NEXT to Sinks.EmitResult.FAIL_NON_SERIALIZED,
            SignalType.ON_NEXT to Sinks.EmitResult.FAIL_TERMINATED,
        )
        delegate.nextCalls.get().assert().isEqualTo(1)
        delegate.completeCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `emit terminal should retry cancellation without creating a logical claim`() {
        val delegate = BlockingNextManySink()
        delegate.cancelled = true
        val sink = sinkWithDelegate(delegate)
        val completeFailures = mutableListOf<Pair<SignalType, Sinks.EmitResult>>()
        val errorFailures = mutableListOf<Pair<SignalType, Sinks.EmitResult>>()

        sink.emitComplete(
            Sinks.EmitFailureHandler { signalType, emitResult ->
                completeFailures.add(signalType to emitResult)
                completeFailures.size == 1
            },
        )
        sink.emitError(
            IllegalStateException("cancelled"),
            Sinks.EmitFailureHandler { signalType, emitResult ->
                errorFailures.add(signalType to emitResult)
                errorFailures.size == 1
            },
        )

        completeFailures.assert().containsExactly(
            SignalType.ON_COMPLETE to Sinks.EmitResult.FAIL_CANCELLED,
            SignalType.ON_COMPLETE to Sinks.EmitResult.FAIL_CANCELLED,
        )
        errorFailures.assert().containsExactly(
            SignalType.ON_ERROR to Sinks.EmitResult.FAIL_CANCELLED,
            SignalType.ON_ERROR to Sinks.EmitResult.FAIL_CANCELLED,
        )
        delegate.completeCalls.get().assert().isEqualTo(0)
        delegate.errorCalls.get().assert().isEqualTo(0)
        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(false)
    }

    @Test
    fun `emit next overflow should discard the value and terminate with overflow`() {
        val delegate = OverflowingNextManySink<Int>()
        val sink = sinkWithDelegate(delegate)

        StepVerifier.create(sink.asFlux())
            .then { sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST) }
            .expectErrorMatches { Exceptions.isOverflow(it) }
            .verifyThenAssertThat(TEST_TIMEOUT)
            .hasDiscardedExactly(1)

        Exceptions.isOverflow(delegate.observedError.get()).assert().isTrue()
        delegate.errorCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `should reject a second subscriber with the unicast contract`() {
        val sink = mpscUnicastManySink<Int>()
        val first = sink.asFlux().subscribe()
        try {
            StepVerifier.create(sink.asFlux())
                .expectError(IllegalStateException::class.java)
                .verify(TEST_TIMEOUT)
        } finally {
            first.dispose()
        }
    }

    @Test
    fun `should expose a stable flux and delegate subscriber scans and inners`() {
        val sink = mpscUnicastManySink<Int>()
        val flux = sink.asFlux()
        val error = IllegalStateException("terminal")
        val observedError = AtomicReference<Throwable?>()

        flux.assert().isSameAs(sink.asFlux())
        (flux is Sinks.Many<*>).assert().isFalse()
        (flux is Fuseable).assert().isFalse()
        Scannable.from(flux).scan(Scannable.Attr.PARENT).assert().isNull()
        Scannable.from(flux).parents().noneMatch { it is Sinks.Many<*> }.assert().isTrue()
        sink.currentSubscriberCount().assert().isEqualTo(0)
        sink.inners().count().assert().isEqualTo(0)
        sink.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(false)
        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(false)
        sink.scanUnsafe(Scannable.Attr.ERROR).assert().isNull()
        sink.scanUnsafe(Scannable.Attr.BUFFERED).assert().isEqualTo(0)
        val capacity = sink.scanUnsafe(Scannable.Attr.CAPACITY)
        capacity.assert().isNotNull()

        sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
        sink.scanUnsafe(Scannable.Attr.BUFFERED).assert().isEqualTo(1)

        val subscription = flux.subscribe({}, observedError::set)
        sink.currentSubscriberCount().assert().isEqualTo(1)
        sink.scanUnsafe(Scannable.Attr.BUFFERED).assert().isEqualTo(0)
        sink.scanUnsafe(Scannable.Attr.CAPACITY).assert().isEqualTo(capacity)
        sink.inners().count().assert().isEqualTo(1)
        val inner = sink.inners().findFirst().orElseThrow()
        inner.isScanAvailable.assert().isTrue()
        inner.scan(Scannable.Attr.ACTUAL).assert().isSameAs(subscription)
        inner.scan(Scannable.Attr.PARENT).assert().isNull()
        sink.scanUnsafe(Scannable.Attr.ACTUAL).assert().isSameAs(inner)

        sink.tryEmitError(error).assert().isEqualTo(Sinks.EmitResult.OK)

        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
        sink.scanUnsafe(Scannable.Attr.CANCELLED).assert().isEqualTo(false)
        sink.scanUnsafe(Scannable.Attr.ERROR).assert().isSameAs(error)
        observedError.get().assert().isSameAs(error)
        sink.currentSubscriberCount().assert().isEqualTo(0)
        sink.inners().count().assert().isEqualTo(0)
        subscription.isDisposed.assert().isTrue()
    }

    @Test
    fun `should not expose the raw sink through publisher or subscription`() {
        val sink = mpscUnicastManySink<Int>()
        val flux = sink.asFlux()
        val exposedSubscription = AtomicReference<Subscription>()
        val subscriberContext = Context.of("opaque", "context")
        val subscriber = object : CoreSubscriber<Int>, Scannable {
            override fun currentContext(): Context = subscriberContext

            override fun onSubscribe(subscription: Subscription) {
                exposedSubscription.set(subscription)
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(value: Int) = Unit

            override fun onError(throwable: Throwable) = Unit

            override fun onComplete() = Unit

            override fun scanUnsafe(key: Scannable.Attr<*>): Any? = null
        }

        flux.subscribe(subscriber)

        flux.assert().isSameAs(sink.asFlux())
        (flux is Sinks.Many<*>).assert().isFalse()
        Scannable.from(flux).scan(Scannable.Attr.PARENT).assert().isNull()
        Scannable.from(flux).parents().noneMatch { it is Sinks.Many<*> }.assert().isTrue()
        val subscription = checkNotNull(exposedSubscription.get())
        (subscription is Sinks.Many<*>).assert().isFalse()
        (subscription is Fuseable.QueueSubscription<*>).assert().isFalse()
        Scannable.from(subscription).scan(Scannable.Attr.PARENT).assert().isNull()
        Scannable.from(subscription).parents().noneMatch { it is Sinks.Many<*> }.assert().isTrue()
        val inner = sink.inners().findFirst().orElseThrow()
        (inner as CoreSubscriber<*>).currentContext().assert().isSameAs(subscriberContext)
        inner.scan(Scannable.Attr.ACTUAL).assert().isSameAs(subscriber)
        inner.scan(Scannable.Attr.PARENT).assert().isNull()

        subscription.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> sinkWithQueue(queue: Queue<T>): MpscUnicastManySink<T> {
        val constructor = MpscUnicastManySink::class.java.getDeclaredConstructor(Queue::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(queue) as MpscUnicastManySink<T>
    }
}

class MpscUnicastManySinkInvariantTest {

    @Test
    fun `should reject a corrupted exhausted admission count`() {
        val sink = mpscUnicastManySink<Int>()
        stateOf(sink).set((1L shl 62) - 1)

        val error = assertThrows<IllegalStateException> {
            sink.tryEmitNext(1)
        }

        error.message.assert().isEqualTo("MPSC unicast sink active admission count exhausted.")
    }

    @Test
    fun `should reject a corrupted release underflow`() {
        val sink = mpscUnicastManySink<Int>()

        val invocation = assertThrows<InvocationTargetException> {
            invokeReleaseNext(sink)
        }
        val error = checkNotNull(invocation.cause)

        (error is IllegalStateException).assert().isTrue()
        error.message.assert().isEqualTo("MPSC unicast sink active admission underflow.")
    }

    @Test
    fun `should defer terminal delegation until a claimed signal is published`() {
        val sink = mpscUnicastManySink<Int>()
        stateOf(sink).set(1L shl 62)

        invokeDelegateTerminal(sink).assert().isNull()
    }

    @Test
    fun `should delegate terminal after the last admitted next returns`() {
        val producerCount = 3
        val delegate = BlockingMultipleNextManySink(producerCount)
        val sink = sinkWithDelegate(delegate)
        val executor = Executors.newFixedThreadPool(producerCount)
        val allButLastReleased = CountDownLatch(producerCount - 1)
        try {
            val next = (0 until producerCount).map { value ->
                executor.submit<Sinks.EmitResult> {
                    sink.tryEmitNext(value).also {
                        allButLastReleased.countDown()
                    }
                }
            }
            delegate.allNextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.completeCalls.get().assert().isEqualTo(0)

            delegate.releaseNext.release(producerCount - 1)
            allButLastReleased.await(5, TimeUnit.SECONDS).assert().isTrue()
            delegate.completeCalls.get().assert().isEqualTo(0)

            delegate.releaseNext.release()
            next.forEach { it.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK) }
            delegate.completeCalls.get().assert().isEqualTo(1)
            sink.tryEmitNext(producerCount).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        } finally {
            delegate.releaseNext.release(producerCount)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `should release admission and deliver a claimed terminal when delegate next throws`() {
        val failure = IllegalStateException("next failed")
        val delegate = BlockingThrowingNextManySink(failure)
        val sink = sinkWithDelegate(delegate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val next = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
            delegate.nextEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.completeCalls.get().assert().isEqualTo(0)
            delegate.releaseNext.countDown()

            val execution = assertThrows<ExecutionException> {
                next.get(5, TimeUnit.SECONDS)
            }
            execution.cause.assert().isSameAs(failure)
            delegate.completeCalls.get().assert().isEqualTo(1)
            sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        } finally {
            delegate.releaseNext.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `terminal claim should retry after losing its compare and set race`() {
        val delegate = PausingFirstCancellationScanManySink<Int>()
        val sink = sinkWithDelegate(delegate)
        val error = IllegalStateException("winner")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val loser = executor.submit<Sinks.EmitResult> { sink.tryEmitComplete() }
            delegate.firstScanEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

            sink.tryEmitError(error).assert().isEqualTo(Sinks.EmitResult.OK)
            delegate.releaseFirstScan.countDown()

            loser.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
            delegate.cancellationScans.get().assert().isEqualTo(2)
            delegate.completeCalls.get().assert().isEqualTo(0)
            delegate.errorCalls.get().assert().isEqualTo(1)
            sink.scanUnsafe(Scannable.Attr.ERROR).assert().isSameAs(error)
        } finally {
            delegate.releaseFirstScan.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `emit next should return when the delegate reports zero subscribers`() {
        val delegate = ScriptedNextManySink<Int>(Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER)
        val sink = sinkWithDelegate(delegate)

        sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)

        delegate.nextCalls.get().assert().isEqualTo(1)
        sink.scanUnsafe(Scannable.Attr.TERMINATED).assert().isEqualTo(false)
    }

    @Test
    fun `emit next should reject non serialized emission when the handler declines retry`() {
        val delegate = ScriptedNextManySink<Int>(Sinks.EmitResult.FAIL_NON_SERIALIZED)
        val sink = sinkWithDelegate(delegate)

        val error = assertThrows<Sinks.EmissionException> {
            sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)
        }

        error.message.orEmpty().contains("Spec. Rule 1.3").assert().isTrue()
        delegate.nextCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `emit next overflow should use an empty context without an actual subscriber`() {
        val delegate = OverflowingNextManySink<Int>()
        val sink = sinkWithDelegate(delegate)

        sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)

        Exceptions.isOverflow(delegate.observedError.get()).assert().isTrue()
        delegate.errorCalls.get().assert().isEqualTo(1)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> sinkWithDelegate(delegate: Sinks.Many<T>): MpscUnicastManySink<T> {
    val constructor = MpscUnicastManySink::class.java.getDeclaredConstructor(Sinks.Many::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(delegate) as MpscUnicastManySink<T>
}

private fun stateOf(sink: MpscUnicastManySink<*>): AtomicLong {
    val field = MpscUnicastManySink::class.java.getDeclaredField("state")
    field.isAccessible = true
    return field.get(sink) as AtomicLong
}

private fun invokeReleaseNext(sink: MpscUnicastManySink<*>) {
    val method = MpscUnicastManySink::class.java.getDeclaredMethod("releaseNext")
    method.isAccessible = true
    method.invoke(sink)
}

private fun invokeDelegateTerminal(sink: MpscUnicastManySink<*>): Sinks.EmitResult? {
    val method = MpscUnicastManySink::class.java.getDeclaredMethod("delegateTerminal")
    method.isAccessible = true
    return method.invoke(sink) as Sinks.EmitResult?
}

private open class AlwaysAcceptingManySink<T : Any>(
    protected val backing: Sinks.Many<T> =
        Sinks.unsafe().many().unicast().onBackpressureBuffer(),
) : Sinks.Many<T> by backing {
    val completeCalls = AtomicInteger()
    val errorCalls = AtomicInteger()

    override fun tryEmitComplete(): Sinks.EmitResult {
        completeCalls.incrementAndGet()
        return Sinks.EmitResult.OK
    }

    override fun tryEmitError(error: Throwable): Sinks.EmitResult {
        errorCalls.incrementAndGet()
        return Sinks.EmitResult.OK
    }

    override fun inners(): Stream<out Scannable> = backing.inners()
}

private class CancellingTerminalManySink<T : Any> : AlwaysAcceptingManySink<T>() {
    @Volatile
    private var cancelled: Boolean = false

    override fun tryEmitComplete(): Sinks.EmitResult {
        completeCalls.incrementAndGet()
        cancelled = true
        return Sinks.EmitResult.FAIL_CANCELLED
    }

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        if (key == Scannable.Attr.CANCELLED) {
            cancelled
        } else {
            backing.scanUnsafe(key)
        }
}

private class TerminalResultManySink<T : Any>(
    private val result: Sinks.EmitResult,
) : AlwaysAcceptingManySink<T>() {
    override fun tryEmitComplete(): Sinks.EmitResult {
        completeCalls.incrementAndGet()
        return result
    }
}

private class ScriptedNextManySink<T : Any>(
    vararg results: Sinks.EmitResult,
) : AlwaysAcceptingManySink<T>() {
    private val results = ConcurrentLinkedQueue(results.toList())
    val nextCalls = AtomicInteger()

    override fun tryEmitNext(t: T): Sinks.EmitResult {
        nextCalls.incrementAndGet()
        return checkNotNull(results.poll()) {
            "No scripted next result remains."
        }
    }

    override fun tryEmitComplete(): Sinks.EmitResult {
        completeCalls.incrementAndGet()
        return backing.tryEmitComplete()
    }
}

private class OverflowingNextManySink<T : Any> : AlwaysAcceptingManySink<T>() {
    val observedError = AtomicReference<Throwable?>()

    override fun tryEmitNext(t: T): Sinks.EmitResult = Sinks.EmitResult.FAIL_OVERFLOW

    override fun tryEmitError(error: Throwable): Sinks.EmitResult {
        errorCalls.incrementAndGet()
        observedError.set(error)
        return backing.tryEmitError(error)
    }
}

private class PausingFirstCancellationScanManySink<T : Any> : AlwaysAcceptingManySink<T>() {
    val firstScanEntered = CountDownLatch(1)
    val releaseFirstScan = CountDownLatch(1)
    val cancellationScans = AtomicInteger()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? {
        if (key != Scannable.Attr.CANCELLED) {
            return backing.scanUnsafe(key)
        }
        if (cancellationScans.incrementAndGet() == 1) {
            firstScanEntered.countDown()
            check(releaseFirstScan.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release the first cancellation scan."
            }
        }
        return false
    }
}

private class BlockingNextManySink : AlwaysAcceptingManySink<Int>() {
    val nextEntered = CountDownLatch(1)
    val releaseNext = CountDownLatch(1)
    val terminalCalled = CountDownLatch(1)
    val calls = ConcurrentLinkedQueue<String>()

    @Volatile
    var cancelled: Boolean = false

    override fun tryEmitNext(t: Int): Sinks.EmitResult {
        nextEntered.countDown()
        check(releaseNext.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release the controllable next call."
        }
        calls.add("next")
        return Sinks.EmitResult.OK
    }

    override fun tryEmitComplete(): Sinks.EmitResult {
        completeCalls.incrementAndGet()
        calls.add("complete")
        terminalCalled.countDown()
        return if (cancelled) Sinks.EmitResult.FAIL_CANCELLED else Sinks.EmitResult.OK
    }

    override fun tryEmitError(error: Throwable): Sinks.EmitResult {
        errorCalls.incrementAndGet()
        calls.add("error")
        terminalCalled.countDown()
        return if (cancelled) Sinks.EmitResult.FAIL_CANCELLED else Sinks.EmitResult.OK
    }

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        if (key == Scannable.Attr.CANCELLED) {
            cancelled
        } else {
            backing.scanUnsafe(key)
        }

    override fun asFlux(): Flux<Int> = backing.asFlux()
}

private class BlockingMultipleNextManySink(
    producerCount: Int,
) : AlwaysAcceptingManySink<Int>() {
    val allNextEntered = CountDownLatch(producerCount)
    val releaseNext = Semaphore(0)

    override fun tryEmitNext(t: Int): Sinks.EmitResult {
        allNextEntered.countDown()
        check(releaseNext.tryAcquire(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release a blocked next call."
        }
        return Sinks.EmitResult.OK
    }
}

private class BlockingThrowingNextManySink(
    private val failure: Throwable,
) : AlwaysAcceptingManySink<Int>() {
    val nextEntered = CountDownLatch(1)
    val releaseNext = CountDownLatch(1)

    override fun tryEmitNext(t: Int): Sinks.EmitResult {
        nextEntered.countDown()
        check(releaseNext.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release the throwing next call."
        }
        throw failure
    }
}

private class PausingOfferQueue<T : Any>(
    private val delegate: Queue<T> = Queues.unboundedMultiproducer<T>().get(),
) : Queue<T> by delegate {
    val offerEntered = CountDownLatch(1)
    val releaseOffer = CountDownLatch(1)

    override fun offer(element: T): Boolean {
        offerEntered.countDown()
        check(releaseOffer.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting to release the controllable queue offer."
        }
        return delegate.offer(element)
    }
}
