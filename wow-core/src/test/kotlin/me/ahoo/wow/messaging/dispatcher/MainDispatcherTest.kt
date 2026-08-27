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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainDispatcherTest {

    @Test
    fun `forceStop before start remains terminal`() {
        val dispatcher = RecordingMainDispatcher()

        dispatcher.forceStop()
        tryStartAfterTerminalStop(dispatcher)

        dispatcher.receiveCount.get().assert().isEqualTo(0)
        dispatcher.createCount.get().assert().isEqualTo(0)
        dispatcher.childForceCalls.assert().isEmpty()
    }

    @Test
    fun `start creates one child per aggregate with receiver group subscription`() {
        val dispatcher = RecordingMainDispatcher()

        StepVerifier.create(dispatcher.receiverGroups.asFlux().take(2))
            .then { prepareAndStart(dispatcher) }
            .expectNext("recording-main", "recording-main")
            .verifyComplete()

        dispatcher.receiveCount.get().assert().isEqualTo(2)
        dispatcher.createCount.get().assert().isEqualTo(2)

        dispatcher.quiesce()
        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
        dispatcher.childStopCount.get().assert().isEqualTo(2)
    }

    @Test
    fun `reported child start failure prevents later child start and receiver admission`() {
        val failure = IllegalStateException("child-start")
        val dispatcher = RecordingMainDispatcher(
            firstChildReportedFailure = failure,
        )
        val runtime = runtime(dispatcher)

        val thrown = assertThrows<IllegalStateException> {
            runtime.start().block()
        }

        thrown.assert().isSameAs(failure)
        dispatcher.childStartCount.get().assert().isOne()
        dispatcher.processingOpenCount.get().assert().isZero()
        dispatcher.childStopCount.get().assert().isEqualTo(2)
        dispatcher.managedStopCount.get().assert().isOne()
    }

    @Test
    fun `runtime stop closes every child intake before managed cleanup`() {
        val dispatcher = RecordingMainDispatcher()
        val runtime = runtime(dispatcher)
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .verifyComplete()

        dispatcher.processingCloseCount.get().assert().isEqualTo(2)
        dispatcher.childIntakeCloseCalls.assert().hasSize(2)
        dispatcher.closedProcessingCountsObservedByQuiesce.assert().containsExactly(2, 2)
        dispatcher.closedIntakeCountsObservedByStop.assert().containsExactly(2, 2)
        dispatcher.managedStopCount.get().assert().isOne()
        dispatcher.closedIntakeCountObservedByManagedStop.get().assert().isEqualTo(2)
    }

    @Test
    fun `runtime failure stops every child and remains terminal`() {
        val failure = IllegalStateException("runtime")
        val dispatcher = RecordingMainDispatcher()
        val runtime = runtime(dispatcher)
        runtime.start().block()

        dispatcher.reportFailure(failure)

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }.verify()
        dispatcher.childForceCalls.assert().hasSize(2)
    }

    @Test
    fun `forceStop continues after a child failure`() {
        val failure = IllegalStateException("force")
        val dispatcher = RecordingMainDispatcher(forceFailure = failure)
        val runtime = runtime(dispatcher)
        runtime.start().block()

        runtime.forceStop()

        StepVerifier.create(runtime.terminationSignal)
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }.verify()
        dispatcher.childForceCalls.assert().hasSize(2)
    }

    @Test
    fun `forceStop is not blocked by a child start`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val dispatcher = RecordingMainDispatcher(
            childStartAction = {
                startEntered.countDown()
                releaseStart.await()
            },
            childForceAction = releaseStart::countDown,
        )
        val executor = Executors.newFixedThreadPool(2)
        dispatcher.prepare(DefaultRuntimeContext()).block()
        val start = CompletableFuture.runAsync(dispatcher::start, executor)
        var force: CompletableFuture<Void>? = null

        try {
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            val forceFuture = CompletableFuture.runAsync(dispatcher::forceStop, executor)
            force = forceFuture

            forceFuture.get(1, TimeUnit.SECONDS)
            dispatcher.childForceCalls.assert().isNotEmpty()
        } finally {
            releaseStart.countDown()
            runCatching { start.get(1, TimeUnit.SECONDS) }
            runCatching { force?.get(1, TimeUnit.SECONDS) }
            executor.shutdownNow()
        }
    }

    @Test
    fun `force stop prevents a nested graceful chain from entering later cleanup`() {
        val stopEntered = CountDownLatch(1)
        val stopGate = Sinks.empty<Void>()
        val dispatcher = RecordingMainDispatcher(
            childStopAction = {
                stopEntered.countDown()
                stopGate.asMono()
            },
        )
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        dispatcher.quiesce()
        val gracefulStop = dispatcher.stopGracefully().toFuture()

        stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        dispatcher.forceStop()
        stopGate.tryEmitEmpty().orThrow()
        gracefulStop.get(1, TimeUnit.SECONDS)

        dispatcher.childStopCount.get().assert().isOne()
        dispatcher.managedStopCount.get().assert().isZero()
    }

    private fun tryStartAfterTerminalStop(dispatcher: RecordingMainDispatcher) {
        try {
            dispatcher.start()
        } catch (_: IllegalStateException) {
            // A terminal lifecycle may reject restart instead of treating it as a no-op.
        } finally {
            dispatcher.forceStop()
        }
    }

    private fun prepareAndStart(dispatcher: MainDispatcher<String>) {
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
    }

    private fun runtime(dispatcher: MainDispatcher<String>): WowRuntime =
        WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

    private class RecordingMainDispatcher(
        private val forceFailure: RuntimeException? = null,
        private val childStartAction: (() -> Unit)? = null,
        private val childForceAction: (() -> Unit)? = null,
        private val childStopAction: (() -> Mono<Void>)? = null,
        private val firstChildReportedFailure: Throwable? = null,
    ) : MainDispatcher<String>() {
        val receiveCount = AtomicInteger()
        val createCount = AtomicInteger()
        val childStartCount = AtomicInteger()
        val processingOpenCount = AtomicInteger()
        val processingCloseCount = AtomicInteger()
        val childStopCount = AtomicInteger()
        val managedStopCount = AtomicInteger()
        val childForceCalls = mutableListOf<String>()
        val childIntakeCloseCalls = mutableListOf<String>()
        val closedProcessingCountsObservedByQuiesce = mutableListOf<Int>()
        val closedIntakeCountsObservedByStop = mutableListOf<Int>()
        val closedIntakeCountObservedByManagedStop = AtomicInteger(-1)
        val receiverGroups: Sinks.Many<String> = Sinks.many().replay().all()
        private var childRuntimeContext: RuntimeContext? = null

        override val name: String = "recording-main"
        override val namedAggregates: Set<NamedAggregate> = setOf(
            "wow-core-test.messaging_aggregate".toNamedAggregate().materialize(),
            "wow-core-test.command_aggregate".toNamedAggregate().materialize(),
        )

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> {
            receiveCount.incrementAndGet()
            return Flux.just(subscription.receiverGroup)
        }

        override fun createMessageReceiver(
            subscription: MessageSubscription,
        ): MessageReceiver<String> =
            MessageReceiver(
                messages = receiveMessage(subscription),
                processingAdmission = processingOpenCount::incrementAndGet,
                processingQuiescence = processingCloseCount::incrementAndGet,
            )

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>
        ): MessageDispatcher {
            createCount.incrementAndGet()
            return object : MessageDispatcher {
                private var runtimeContext: RuntimeContext? = null

                override val name: String = "child-${namedAggregate.aggregateName}"
                override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
                    Mono.fromRunnable {
                        this.runtimeContext = runtimeContext
                        childRuntimeContext = runtimeContext
                    }

                override fun start() {
                    if (childStartCount.incrementAndGet() == 1) {
                        firstChildReportedFailure?.let {
                            checkNotNull(runtimeContext).reportFailure(it)
                        }
                    }
                    childStartAction?.invoke()
                    messageFlux.subscribe {
                        receiverGroups.tryEmitNext(it).orThrow()
                    }
                }

                override fun quiesce() {
                    closedProcessingCountsObservedByQuiesce += processingCloseCount.get()
                    childIntakeCloseCalls += name
                }

                override fun stopGracefully(): Mono<Void> =
                    Mono.defer {
                        closedIntakeCountsObservedByStop += childIntakeCloseCalls.size
                        childStopCount.incrementAndGet()
                        childStopAction?.invoke() ?: Mono.empty()
                    }

                override fun forceStop() {
                    childForceCalls += name
                    childForceAction?.invoke()
                    if (childForceCalls.size == 1) {
                        forceFailure?.let { throw it }
                    }
                }
            }
        }

        override fun stopManagedGracefully(): Mono<Void> =
            Mono.fromRunnable {
                closedIntakeCountObservedByManagedStop.set(childIntakeCloseCalls.size)
                managedStopCount.incrementAndGet()
            }

        fun reportFailure(error: Throwable) {
            checkNotNull(childRuntimeContext).reportFailure(error)
        }
    }
}
