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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class AggregateDispatcherTest {

    @Test
    fun `start subscribes and routes exchanges through handleExchange`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val exchange = TestExchange(group = 1)
        dispatcher.start()

        StepVerifier.create(dispatcher.handled.asFlux().take(1))
            .then { source.tryEmitNext(exchange).orThrow() }
            .expectNext(exchange)
            .verifyComplete()

        dispatcher.groups.assert().isEqualTo(listOf(1))
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `stopGracefully completes immediately when no task is active`() {
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.never())
        dispatcher.start()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
    }

    @Test
    fun `graceful stop before direct start remains terminal`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())

        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
        tryStartAfterTerminalStop(dispatcher)

        source.currentSubscriberCount().assert().isZero()
    }

    @Test
    fun `force stop before direct start remains terminal`() {
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
            runtime.start()

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
    fun `force stop prevents managed graceful cleanup after processing drain was entered`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val handlingEntered = CountDownLatch(1)
        val managedStopCount = AtomicInteger()
        val dispatcher = object : RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = {
                handlingEntered.countDown()
                Mono.never()
            },
            forceCleanupDispatcher = { action ->
                action.run()
                true
            },
        ) {
            override fun stopManagedGracefully(): Mono<Void> =
                Mono.fromRunnable(managedStopCount::incrementAndGet)
        }
        dispatcher.prepare(DefaultRuntimeContext())
        dispatcher.start()
        source.tryEmitNext(TestExchange(group = 1)).orThrow()
        handlingEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        val gracefulStop = dispatcher.stopGracefully().toFuture()

        dispatcher.forceStop()
        gracefulStop.get(1, TimeUnit.SECONDS)

        managedStopCount.get().assert().isZero()
    }

    @Test
    fun `rejected force cleanup reports failure without inline fallback`() {
        val cancellationThread = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val failureReported = CountDownLatch(1)
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = Flux.never<TestExchange>()
                .doOnCancel {
                    cancellationThread.set(Thread.currentThread().name)
                },
            forceCleanupDispatcher = { false },
        )
        val runtimeContext = DefaultRuntimeContext()
        runtimeContext.failureSignal.subscribe(
            {},
            { error ->
                failure.set(error)
                failureReported.countDown()
            },
        )
        dispatcher.prepare(runtimeContext)
        dispatcher.start()

        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            dispatcher.forceStop()
        }

        failureReported.await(1, TimeUnit.SECONDS).assert().isTrue()
        cancellationThread.get().assert().isNull()
        failure.get().assert()
            .isInstanceOf(RejectedExecutionException::class.java)
            .hasMessageContaining("bounded runtime cleanup executor is saturated")
        checkNotNull(failure.get()).suppressedExceptions.assert().hasSize(1)
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
        runtime.start()
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
        dispatcher.prepare(DefaultRuntimeContext())
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
            runCatching(runtime::start).exceptionOrNull()
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
                runCatching(runtime::start).exceptionOrNull()
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
    fun `direct fatal pipeline failure is owned and triggers force cleanup`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val failure = IllegalStateException("fatal")
        val forceStopCount = AtomicInteger()
        val dispatcher = object : RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = { Mono.error(failure) },
        ) {
            override fun forceStopManaged() {
                forceStopCount.incrementAndGet()
            }
        }
        dispatcher.start()

        source.tryEmitNext(TestExchange(group = 1)).orThrow()

        StepVerifier.create(dispatcher.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }
            .verify()
        forceStopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `runtime closes dispatcher intake at the idle boundary`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ofMillis(100),
        )
        runtime.start()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        source.currentSubscriberCount().assert().isZero()
        source.tryEmitNext(TestExchange(group = 1)).isFailure.assert().isTrue()
    }

    @Test
    fun `prepare subscribes without processing until start`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val exchange = TestExchange(group = 1)

        dispatcher.prepare(DefaultRuntimeContext())
        source.currentSubscriberCount().assert().isEqualTo(1)
        source.tryEmitNext(exchange).orThrow()
        dispatcher.handled.currentSubscriberCount().assert().isZero()

        StepVerifier.create(dispatcher.handled.asFlux().take(1))
            .then(dispatcher::start)
            .expectNext(exchange)
            .verifyComplete()

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

        runtime.start()

        StepVerifier.create(downstreamHandled.asMono()).verifyComplete()
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `source may complete during prepare before start opens demand`() {
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.empty())

        dispatcher.prepare(DefaultRuntimeContext())
        dispatcher.start()

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
            dispatcher.prepare(DefaultRuntimeContext())
        }

        thrown.assert().isSameAs(prepareFailure)
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

        val thrown = assertThrows<IllegalStateException>(runtime::start)

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
        dispatcher.start()

        StepVerifier.create(invoked.asMono())
            .then { source.tryEmitNext(TestExchange(group = 2)).orThrow() }
            .verifyComplete()

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
        dispatcher.start()

        StepVerifier.create(firstInvoked.asMono())
            .then {
                source.tryEmitNext(TestExchange(group = 1, id = 1)).orThrow()
                source.tryEmitNext(TestExchange(group = 1, id = 2)).orThrow()
            }
            .verifyComplete()

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
        runtime.start()

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
        dispatcher.start()

        StepVerifier.create(dispatcher.errors.asMono())
            .then { source.tryEmitNext(TestExchange(group = 3)).orThrow() }
            .expectNext(error)
            .verifyComplete()

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
        runtime.start()

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
        MicrometerMetrics.addRegistry(meterRegistry)
        try {
            val dispatcherName = "metrics-cardinality-dispatcher"
            val dispatcher = RecordingAggregateDispatcher(
                messageFlux = Flux.just(TestExchange(group = 1), TestExchange(group = 2)),
                name = dispatcherName,
            )

            dispatcher.start()

            val dispatcherMeterIds = meterRegistry.meters
                .map { it.id }
                .filter { it.name.startsWith("wow.dispatcher") }
                .filter { it.getTag("dispatcher") == dispatcherName }
            dispatcherMeterIds.assert().isNotEmpty()
            dispatcherMeterIds
                .mapNotNull { it.getTag("group.key") }
                .assert().isEmpty()
        } finally {
            MicrometerMetrics.removeRegistry(meterRegistry)
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
        forceCleanupDispatcher: (Runnable) -> Boolean = { action ->
            RuntimeCleanupExecutor.execute(action)
        },
    ) : AggregateDispatcher<TestExchange>(forceCleanupDispatcher) {
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
