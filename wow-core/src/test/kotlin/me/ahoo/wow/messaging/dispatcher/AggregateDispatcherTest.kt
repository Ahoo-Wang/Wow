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

package me.ahoo.wow.messaging.dispatcher

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.runtime.internal.RuntimeCleanupExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AggregateDispatcherTest {

    @Test
    fun `start subscribes and routes exchanges through handleExchange`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val exchange = TestExchange(group = 1)
        prepareAndStart(dispatcher)

        StepVerifier.create(dispatcher.handled.asFlux().take(1))
            .then { source.tryEmitNext(exchange).orThrow() }
            .expectNext(exchange)
            .verifyComplete()

        dispatcher.groups.assert().isEqualTo(listOf(1))
        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `top-level runtime waits for aggregate dispatcher message readiness`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val readiness = Sinks.empty<Void>()
        val processingAdmissions = AtomicInteger()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            messageReadiness = readiness.asMono(),
            processingAdmission = processingAdmissions::incrementAndGet,
        )
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val startup = runtime.start().toFuture()
        source.currentSubscriberCount().assert().isOne()
        startup.isDone.assert().isFalse()
        runtime.isRunning.assert().isFalse()
        processingAdmissions.get().assert().isZero()

        readiness.tryEmitEmpty().orThrow()
        startup.get(1, TimeUnit.SECONDS)
        runtime.isRunning.assert().isTrue()
        processingAdmissions.get().assert().isOne()
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `stopGracefully completes immediately when no task is active`() {
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.never())
        prepareAndStart(dispatcher)

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
    }

    @Test
    fun `graceful stop before preparation remains terminal`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
        tryStartAfterTerminalStop(dispatcher)

        source.currentSubscriberCount().assert().isZero()
    }

    @Test
    fun `force stop before preparation remains terminal`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())

        dispatcher.forceStop()
        tryStartAfterTerminalStop(dispatcher)

        source.currentSubscriberCount().assert().isZero()
    }

    @Test
    fun `force stop remains bounded when a source cancellation hook blocks`() {
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val source = Flux.never<TestExchange>()
            .doOnCancel {
                cancellationEntered.countDown()
                while (releaseCancellation.count > 0) {
                    try {
                        releaseCancellation.await()
                    } catch (_: InterruptedException) {
                        // Deliberately model uncooperative user cleanup.
                    }
                }
            }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofMillis(200),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            runtime.start().block()

            val forceStop = executor.submit(runtime::forceStop)

            forceStop.get(1, TimeUnit.SECONDS)
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(runtime.terminationSignal).verifyComplete()
        } finally {
            releaseCancellation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `quiesce remains bounded when a source cancellation hook blocks`() {
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val source = Flux.never<TestExchange>()
            .doOnCancel {
                cancellationEntered.countDown()
                while (releaseCancellation.count > 0) {
                    try {
                        releaseCancellation.await()
                    } catch (_: InterruptedException) {
                        // Deliberately model uncooperative user cleanup.
                    }
                }
            }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)
        val executor = Executors.newSingleThreadExecutor()

        try {
            prepareAndStart(dispatcher)

            val quiescence = executor.submit(dispatcher::quiesce)

            quiescence.get(1, TimeUnit.SECONDS)
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
        } finally {
            releaseCancellation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected detached cleanup reports every failure without inline fallback`() {
        val cancellationThread = AtomicReference<String?>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val failuresReported = CountDownLatch(2)
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = Flux.never<TestExchange>()
                .doOnCancel {
                    cancellationThread.set(Thread.currentThread().name)
                },
            cleanupDispatcher = { false },
        )
        val runtimeContext = DefaultRuntimeContext(
            failureHandler = { error ->
                failures += error
                failuresReported.countDown()
            },
        )
        dispatcher.prepare(runtimeContext).block()
        dispatcher.start()

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            dispatcher.forceStop()
        }

        failuresReported.await(1, TimeUnit.SECONDS).assert().isTrue()
        cancellationThread.get().assert().isNull()
        failures.assert().hasSize(2)
        failures.forEach { failure ->
            failure.assert()
                .isInstanceOf(RejectedExecutionException::class.java)
                .hasMessageContaining("bounded runtime cleanup executor is saturated")
        }
    }

    @Test
    fun `blocking terminated observer cannot block dispatcher force stop`() {
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val secondObserver = CountDownLatch(1)
        val replayObserver = CountDownLatch(1)
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.never())
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()
        dispatcher.terminatedSignal.subscribe(
            {},
            {},
            {
                observerEntered.countDown()
                releaseObserver.await()
            },
        )
        dispatcher.terminatedSignal.subscribe({}, {}, secondObserver::countDown)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val forceStop = executor.submit(runtime::forceStop)

            forceStop.get(1, TimeUnit.SECONDS)
            observerEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            secondObserver.await(1, TimeUnit.SECONDS).assert().isTrue()

            dispatcher.terminatedSignal.subscribe({}, {}, replayObserver::countDown)
            replayObserver.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseObserver.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop remains bounded when opening source demand blocks`() {
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val source = object : Flux<TestExchange>() {
            override fun subscribe(actual: CoreSubscriber<in TestExchange>) {
                actual.onSubscribe(
                    object : Subscription {
                        override fun request(n: Long) {
                            requestEntered.countDown()
                            while (releaseRequest.count > 0) {
                                try {
                                    releaseRequest.await()
                                } catch (_: InterruptedException) {
                                    // Deliberately model an uncooperative upstream request hook.
                                }
                            }
                        }

                        override fun cancel() = Unit
                    }
                )
            }
        }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)
        dispatcher.prepare(DefaultRuntimeContext()).block()
        val executor = Executors.newFixedThreadPool(2)
        val startup = executor.submit(dispatcher::start)
        var forceStop: java.util.concurrent.Future<*>? = null

        try {
            requestEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            val forceStopFuture = executor.submit(dispatcher::forceStop)
            forceStop = forceStopFuture

            forceStopFuture.get(1, TimeUnit.SECONDS)
            StepVerifier.create(dispatcher.terminatedSignal).verifyComplete()
        } finally {
            releaseRequest.countDown()
            startup.get(1, TimeUnit.SECONDS)
            runCatching { forceStop?.get(1, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop prevents subscription created after overlapping prepare`() {
        val messageFluxRequested = CountDownLatch(1)
        val releaseMessageFlux = CountDownLatch(1)
        val subscribed = AtomicInteger()
        val dispatcher = object : RecordingAggregateDispatcher(messageFlux = Flux.never()) {
            override val messageFlux: Flux<TestExchange>
                get() {
                    messageFluxRequested.countDown()
                    releaseMessageFlux.await()
                    return Flux.never<TestExchange>()
                        .doOnSubscribe { subscribed.incrementAndGet() }
                }
        }
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newFixedThreadPool(2)
        val startup = executor.submit<Throwable?> {
            runCatching { runtime.start().block() }.exceptionOrNull()
        }

        try {
            messageFluxRequested.await(1, TimeUnit.SECONDS).assert().isTrue()
            val forceStop = executor.submit(runtime::forceStop)

            forceStop.get(1, TimeUnit.SECONDS)
            releaseMessageFlux.countDown()
            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()
            subscribed.get().assert().isZero()
        } finally {
            releaseMessageFlux.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `late upstream cancellation after force stop runs on cleanup worker`() {
        val sourceSubscriptionEntered = CountDownLatch(1)
        val allowOnSubscribe = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val cancellationThread = AtomicReference<String>()
        val source = object : Flux<TestExchange>() {
            override fun subscribe(actual: CoreSubscriber<in TestExchange>) {
                sourceSubscriptionEntered.countDown()
                awaitIgnoringInterrupt(allowOnSubscribe)
                actual.onSubscribe(
                    object : Subscription {
                        override fun request(n: Long) = Unit

                        override fun cancel() {
                            cancellationThread.set(Thread.currentThread().name)
                            cancellationEntered.countDown()
                            awaitIgnoringInterrupt(releaseCancellation)
                        }
                    },
                )
            }
        }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = executor.submit<Throwable?> {
                runCatching { runtime.start().block() }.exceptionOrNull()
            }
            sourceSubscriptionEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            runtime.forceStop()
            allowOnSubscribe.countDown()

            startup.get(1, TimeUnit.SECONDS).assert().isNotNull()
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            cancellationThread.get()
                .assert()
                .startsWith("wow-runtime-cleanup-")
        } finally {
            allowOnSubscribe.countDown()
            releaseCancellation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `fatal pipeline failure terminates its owning runtime`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val failure = IllegalStateException("fatal")
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = { Mono.error(failure) },
        )
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        source.tryEmitNext(TestExchange(group = 1)).orThrow()

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }
            .verify()
    }

    @Test
    fun `runtime closes dispatcher intake before detached source cancellation`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val detachedCancellation = AtomicReference<Runnable?>()
        val handled = AtomicInteger()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = {
                handled.incrementAndGet()
                Mono.empty()
            },
            cleanupDispatcher = { action ->
                detachedCancellation.compareAndSet(null, action)
            },
        )
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        val cancellation = checkNotNull(detachedCancellation.get())
        try {
            source.tryEmitNext(TestExchange(group = 1)).orThrow()
            handled.get().assert().isZero()
        } finally {
            cancellation.run()
        }
    }

    @Test
    fun `prepare subscribes without processing until start`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val exchange = TestExchange(group = 1)

        dispatcher.prepare(DefaultRuntimeContext()).block()
        source.currentSubscriberCount().assert().isEqualTo(1)
        source.tryEmitNext(exchange).orThrow()
        dispatcher.handled.currentSubscriberCount().assert().isZero()

        StepVerifier.create(dispatcher.handled.asFlux().take(1))
            .then(dispatcher::start)
            .expectNext(exchange)
            .verifyComplete()

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `runtime prepares downstream subscription before upstream start publishes`() {
        val downstreamSource = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val downstreamHandled = Sinks.empty<Void>()
        val upstream = RecordingAggregateDispatcher(
            name = "upstream",
            messageFlux = Flux.just(TestExchange(group = 1)),
            handle = {
                Mono.fromRunnable {
                    downstreamSource.tryEmitNext(TestExchange(group = 2)).orThrow()
                }
            },
        )
        val downstream = RecordingAggregateDispatcher(
            name = "downstream",
            messageFlux = downstreamSource.asFlux(),
            handle = {
                downstreamHandled.tryEmitEmpty().orThrow()
                Mono.empty()
            },
        )
        val runtime = WowRuntime(
            components = listOf(upstream, downstream),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        runtime.start().block()

        StepVerifier.create(downstreamHandled.asMono()).verifyComplete()
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `source may complete during prepare before start opens demand`() {
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.empty())

        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `synchronous source failure fails dispatcher preparation`() {
        val prepareFailure = IllegalStateException("prepare")
        val source = object : Flux<TestExchange>() {
            override fun subscribe(actual: CoreSubscriber<in TestExchange>) {
                throw prepareFailure
            }
        }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)

        val thrown = assertThrows<IllegalStateException> {
            dispatcher.prepare(DefaultRuntimeContext()).block()
        }

        thrown.assert().isSameAs(prepareFailure)
        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(prepareFailure)
            }
            .verify()
    }

    @Test
    fun `source failure caused by opening demand fails runtime startup`() {
        val startFailure = IllegalStateException("start")
        val source = Flux.create<TestExchange> { sink ->
            sink.onRequest {
                sink.error(startFailure)
            }
        }
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source)
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val thrown = assertThrows<IllegalStateException> { runtime.start().block() }

        thrown.assert().isSameAs(startFailure)
        runtime.isRunning.assert().isFalse()
    }

    @Test
    fun `stopGracefully waits for active exchange handling to complete`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val invoked = Sinks.empty<Void>()
        val release = Sinks.empty<Void>()
        val cancelled = Sinks.empty<Void>()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = {
                invoked.tryEmitEmpty().orThrow()
                release.asMono()
                    .doOnCancel { cancelled.tryEmitEmpty().orThrow() }
            },
        )
        prepareAndStart(dispatcher)

        StepVerifier.create(invoked.asMono())
            .then { source.tryEmitNext(TestExchange(group = 2)).orThrow() }
            .verifyComplete()

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .then { release.tryEmitEmpty().orThrow() }
            .verifyComplete()

        StepVerifier.create(cancelled.asMono())
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun `stopGracefully drains active and queued exchanges without cancellation`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val firstInvoked = Sinks.empty<Void>()
        val releaseFirst = Sinks.empty<Void>()
        val cancelled = Sinks.empty<Void>()
        val handledIds = mutableListOf<Int>()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = { exchange ->
                handledIds += exchange.id
                if (exchange.id == 1) {
                    firstInvoked.tryEmitEmpty().orThrow()
                    releaseFirst.asMono()
                        .doOnCancel { cancelled.tryEmitEmpty().orThrow() }
                } else {
                    Mono.empty()
                }
            },
        )
        prepareAndStart(dispatcher)

        StepVerifier.create(firstInvoked.asMono())
            .then {
                source.tryEmitNext(TestExchange(group = 1, id = 1)).orThrow()
                source.tryEmitNext(TestExchange(group = 1, id = 2)).orThrow()
            }
            .verifyComplete()

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .then { releaseFirst.tryEmitEmpty().orThrow() }
            .verifyComplete()

        handledIds.assert().containsExactly(1, 2)
        StepVerifier.create(cancelled.asMono())
            .expectTimeout(Duration.ofMillis(100))
            .verify()
    }

    @Test
    fun `runtime shutdown admits delayed tail work during the quiet period`() {
        val firstSource = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val secondSource = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val firstInvoked = Sinks.empty<Void>()
        val releaseFirst = Sinks.empty<Void>()
        val secondHandled = Sinks.empty<Void>()
        val secondDispatcher = RecordingAggregateDispatcher(
            name = "second-dispatcher",
            messageFlux = secondSource.asFlux(),
            handle = {
                secondHandled.tryEmitEmpty().orThrow()
                Mono.empty()
            },
        )
        val firstDispatcher = RecordingAggregateDispatcher(
            name = "first-dispatcher",
            messageFlux = firstSource.asFlux(),
            handle = {
                firstInvoked.tryEmitEmpty().orThrow()
                releaseFirst.asMono()
            },
        )
        val runtime = WowRuntime(
            components = listOf(firstDispatcher, secondDispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ofMillis(100),
        )
        runtime.start().block()

        StepVerifier.create(firstInvoked.asMono())
            .then { firstSource.tryEmitNext(TestExchange(group = 1)).orThrow() }
            .verifyComplete()

        StepVerifier.create(runtime.stopGracefully())
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .then { releaseFirst.tryEmitEmpty().orThrow() }
            .thenAwait(Duration.ofMillis(20))
            .then { secondSource.tryEmitNext(TestExchange(group = 2)).orThrow() }
            .verifyComplete()

        StepVerifier.create(secondHandled.asMono())
            .verifyComplete()
    }

    @Test
    fun `handleExchange errors are propagated to subscriber error hook`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val error = IllegalStateException("handler failed")
        val dispatcher = ErrorRecordingAggregateDispatcher(source.asFlux(), error)
        prepareAndStart(dispatcher)

        StepVerifier.create(dispatcher.errors.asMono())
            .then { source.tryEmitNext(TestExchange(group = 3)).orThrow() }
            .expectNext(error)
            .verifyComplete()

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .expectErrorSatisfies { terminalError ->
                terminalError.assert().isSameAs(error)
            }
            .verify()
    }

    @Test
    fun `group key failure releases runtime activity before failing the runtime`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val groupKeyFailure = IllegalStateException("group-key")
        val dispatcher = object : RecordingAggregateDispatcher(source.asFlux()) {
            override fun TestExchange.toGroupKey(): Int {
                throw groupKeyFailure
            }
        }
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        runtime.start().block()

        source.tryEmitNext(TestExchange(group = 1)).orThrow()

        StepVerifier.create(runtime.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(groupKeyFailure)
                error.suppressedExceptions.assert().isEmpty()
            }
            .verify()
    }

    @Test
    fun `dispatcher metrics should not expose routing group keys`() {
        val meterRegistry = SimpleMeterRegistry()
        try {
            val dispatcherName = "metrics-cardinality-dispatcher"
            val dispatcher = RecordingAggregateDispatcher(
                messageFlux = Flux.just(TestExchange(group = 1), TestExchange(group = 2)),
                name = dispatcherName,
                metrics = WowMetrics(meterRegistry),
            )

            prepareAndStart(dispatcher)

            val dispatcherMeterIds = meterRegistry.meters
                .map { it.id }
                .filter { it.name == "wow.operation" }
                .filter { it.getTag("component") == "dispatcher" }
                .filter { it.getTag("processor") == dispatcherName }
            dispatcherMeterIds.assert().isNotEmpty()
            dispatcherMeterIds
                .mapNotNull { it.getTag("group.key") }
                .assert().isEmpty()
        } finally {
            meterRegistry.close()
        }
    }

    private fun tryStartAfterTerminalStop(dispatcher: AggregateDispatcher<TestExchange>) {
        try {
            dispatcher.start()
        } catch (_: IllegalStateException) {
            // A terminal lifecycle may reject restart instead of treating it as a no-op.
        }
    }

    private fun prepareAndStart(dispatcher: AggregateDispatcher<TestExchange>) {
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Model an uncooperative external publisher hook.
            }
        }
    }

    private open class RecordingAggregateDispatcher(
        override val messageFlux: Flux<TestExchange>,
        private val handle: ((TestExchange) -> Mono<Void>)? = null,
        override val scheduler: Scheduler = Schedulers.immediate(),
        override val name: String = "recording-dispatcher",
        cleanupDispatcher: (Runnable) -> Boolean = { action ->
            RuntimeCleanupExecutor.execute(action)
        },
        messageReadiness: Mono<Void> = Mono.empty(),
        processingAdmission: () -> Unit = {},
        metrics: WowMetrics = WowMetrics.NONE,
    ) : AggregateDispatcher<TestExchange>(
        cleanupDispatcher = cleanupDispatcher,
        messageReadiness = messageReadiness,
        processingAdmission = processingAdmission,
        metrics = metrics,
    ) {
        override val parallelism: Int = 2
        override val namedAggregate: NamedAggregate = "wow-core-test.messaging_aggregate".toNamedAggregate().materialize()
        val handled: Sinks.Many<TestExchange> = Sinks.many().replay().all()
        val groups = mutableListOf<Int>()

        override fun TestExchange.toGroupKey(): Int {
            groups.add(group)
            return group
        }

        override fun handleExchange(exchange: TestExchange): Mono<Void> {
            handle?.let {
                return it(exchange)
            }
            handled.tryEmitNext(exchange).orThrow()
            return Mono.empty()
        }
    }

    private class ErrorRecordingAggregateDispatcher(
        messageFlux: Flux<TestExchange>,
        private val error: Throwable
    ) : RecordingAggregateDispatcher(
        messageFlux = messageFlux,
        handle = { Mono.error(error) },
    ) {
        val errors: Sinks.One<Throwable> = Sinks.one()

        override fun hookOnError(throwable: Throwable) {
            errors.tryEmitValue(throwable).orThrow()
        }
    }
}

private data class TestExchange(
    val group: Int,
    val id: Int = group,
    override val message: TestNamedMessage = TestNamedMessage()
) : MessageExchange<TestExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
