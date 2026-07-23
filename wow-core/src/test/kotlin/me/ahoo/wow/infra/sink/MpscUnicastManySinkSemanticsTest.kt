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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Scannable
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import reactor.util.concurrent.Queues
import reactor.util.context.Context
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Observable compatibility checks against Reactor's native
 * `Sinks.unsafe().many().unicast().onBackpressureBuffer(MPSC queue)`.
 *
 * Fusion is intentionally excluded: [MpscUnicastManySink] hides the factory-owned
 * queue, while preserving the public [Sinks.Many] and Reactive Streams behavior.
 */
class MpscUnicastManySinkSemanticsTest {

    @TestFactory
    fun `should match native warmup backpressure and terminal ordering`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()

                sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
                sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.OK)
                sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
                sink.scan(Scannable.Attr.BUFFERED).assert().isEqualTo(2)

                StepVerifier.create(sink.asFlux(), 0)
                    .then { sink.currentSubscriberCount().assert().isEqualTo(1) }
                    .expectNoEvent(Duration.ofMillis(10))
                    .thenRequest(1)
                    .expectNext(1)
                    .expectNoEvent(Duration.ofMillis(10))
                    .thenRequest(1)
                    .expectNext(2)
                    .expectComplete()
                    .verify(TEST_TIMEOUT)

                sink.currentSubscriberCount().assert().isEqualTo(0)
                StepVerifier.create(sink.asFlux())
                    .expectErrorMatches {
                        it is IllegalStateException &&
                            it.message.orEmpty().contains("only allow a single Subscriber")
                    }
                    .verify(TEST_TIMEOUT)
            }
        }

    @TestFactory
    fun `should match native cancellation results and discard behavior`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()

                StepVerifier.create(sink.asFlux().take(1))
                    .then {
                        sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)
                        sink.emitNext(2, Sinks.EmitFailureHandler.FAIL_FAST)
                    }
                    .expectNext(1)
                    .expectComplete()
                    .verifyThenAssertThat(TEST_TIMEOUT)
                    .hasDiscardedExactly(2)

                sink.scan(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
                sink.tryEmitNext(3).assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
                sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
                sink.tryEmitError(IllegalStateException("cancelled"))
                    .assert()
                    .isEqualTo(Sinks.EmitResult.FAIL_CANCELLED)
            }
        }

    @TestFactory
    fun `should match native late cancellation after a buffered terminal`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()

                sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
                sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)

                StepVerifier.create(sink.asFlux(), 0)
                    .thenCancel()
                    .verifyThenAssertThat(TEST_TIMEOUT)
                    .hasDiscardedExactly(1)

                sink.currentSubscriberCount().assert().isEqualTo(0)
                sink.scan(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
            }
        }

    @TestFactory
    fun `should match native error replay and terminal results`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()
                val error = IllegalStateException("terminal")

                sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
                sink.tryEmitError(error).assert().isEqualTo(Sinks.EmitResult.OK)
                sink.tryEmitNext(2).assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
                sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
                sink.tryEmitError(IllegalStateException("late"))
                    .assert()
                    .isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
                sink.scan(Scannable.Attr.TERMINATED).assert().isEqualTo(true)
                sink.scan(Scannable.Attr.ERROR).assert().isSameAs(error)

                StepVerifier.create(sink.asFlux(), 0)
                    .thenRequest(1)
                    .expectNext(1)
                    .expectErrorMatches { it === error }
                    .verify(TEST_TIMEOUT)
            }
        }

    @TestFactory
    fun `should match native emit API drop behavior after termination`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()
                val lateError = IllegalStateException("late")

                StepVerifier.create(sink.asFlux())
                    .then {
                        sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST)
                        sink.emitNext(1, Sinks.EmitFailureHandler.FAIL_FAST)
                        sink.emitError(lateError, Sinks.EmitFailureHandler.FAIL_FAST)
                    }
                    .expectComplete()
                    .verifyThenAssertThat(TEST_TIMEOUT)
                    .hasDroppedExactly(1)
                    .hasDroppedErrorMatching { it === lateError }
            }
        }

    @TestFactory
    fun `should match native actual subscriber scans`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()
                val flux = sink.asFlux()
                val fluxView = Scannable.from(flux)
                val subscription = AtomicReference<Subscription>()
                val subscriber = object : CoreSubscriber<Int>, Scannable {
                    override fun currentContext(): Context = Context.empty()

                    override fun onSubscribe(delegate: Subscription) {
                        subscription.set(delegate)
                    }

                    override fun onNext(value: Int) = Unit

                    override fun onError(throwable: Throwable) = Unit

                    override fun onComplete() = Unit

                    override fun scanUnsafe(key: Scannable.Attr<*>): Any? = null
                }

                flux.subscribe(subscriber)

                sink.scan(Scannable.Attr.ACTUAL).assert().isSameAs(subscriber)
                sink.inners().findFirst().orElseThrow().assert().isSameAs(subscriber)
                fluxView.scan(Scannable.Attr.ACTUAL).assert().isSameAs(subscriber)
                fluxView.inners().findFirst().orElseThrow().assert().isSameAs(subscriber)
                subscription.get().cancel()
                sink.inners().count().assert().isEqualTo(0)
                fluxView.inners().count().assert().isEqualTo(0)
            }
        }

    @TestFactory
    fun `should match native flux view scans`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()
                val fluxView = Scannable.from(sink.asFlux())

                sink.tryEmitNext(1).assert().isEqualTo(Sinks.EmitResult.OK)
                fluxView.scan(Scannable.Attr.BUFFERED)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.BUFFERED))
                fluxView.scan(Scannable.Attr.CAPACITY)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.CAPACITY))
                fluxView.scan(Scannable.Attr.PREFETCH)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.PREFETCH))
                fluxView.scan(Scannable.Attr.TERMINATED)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.TERMINATED))
                fluxView.scan(Scannable.Attr.CANCELLED)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.CANCELLED))
                fluxView.scan(Scannable.Attr.ERROR)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.ERROR))
                fluxView.scan(Scannable.Attr.ACTUAL)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.ACTUAL))

                sink.tryEmitComplete().assert().isEqualTo(Sinks.EmitResult.OK)
                fluxView.scan(Scannable.Attr.TERMINATED)
                    .assert()
                    .isEqualTo(sink.scan(Scannable.Attr.TERMINATED))
            }
        }

    @TestFactory
    fun `should match native cancellation after an on next callback failure`(): List<DynamicTest> =
        sinkCases.map { sinkCase ->
            DynamicTest.dynamicTest(sinkCase.name) {
                val sink = sinkCase.create()
                val callbackFailure = IllegalStateException("callback")
                val firstEntered = CountDownLatch(1)
                val secondOffered = CountDownLatch(1)
                val received = ConcurrentLinkedQueue<Int>()
                val errors = ConcurrentLinkedQueue<Throwable>()
                val subscription = sink.asFlux()
                    .subscribe(
                        { value ->
                            received.add(value)
                            if (value == 1) {
                                firstEntered.countDown()
                                check(secondOffered.await(5, TimeUnit.SECONDS)) {
                                    "Timed out waiting for the second value to be offered."
                                }
                                throw callbackFailure
                            }
                        },
                        errors::add,
                    )
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val first = executor.submit<Sinks.EmitResult> { sink.tryEmitNext(1) }
                    firstEntered.await(5, TimeUnit.SECONDS).assert().isTrue()
                    val second = executor.submit<Sinks.EmitResult> {
                        sink.tryEmitNext(2).also {
                            secondOffered.countDown()
                        }
                    }

                    second.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
                    first.get(5, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)

                    received.assert().containsExactly(1)
                    errors.assert().containsExactly(callbackFailure)
                    sink.scan(Scannable.Attr.CANCELLED).assert().isEqualTo(true)
                } finally {
                    secondOffered.countDown()
                    subscription.dispose()
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
                }
            }
        }

    private data class SinkCase(
        val name: String,
        val create: () -> Sinks.Many<Int>,
    )

    private companion object {
        val TEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        val sinkCases = listOf(
            SinkCase("reactor-native-unicast") {
                Sinks.unsafe()
                    .many()
                    .unicast()
                    .onBackpressureBuffer(Queues.unboundedMultiproducer<Int>().get())
            },
            SinkCase("wow-mpsc-unicast") {
                mpscUnicastManySink()
            },
        )
    }
}
