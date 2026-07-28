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
import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.RuntimeOwnership
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainDispatcherTest {

    @Test
    fun `empty dispatcher still runs managed start hook`() {
        val startCount = AtomicInteger()
        val dispatcher = object : MainDispatcher<String>() {
            override val name: String = "empty-main"
            override val namedAggregates: Set<NamedAggregate> = emptySet()

            override fun receiveMessage(subscription: MessageSubscription): Flux<String> =
                Flux.empty()

            override fun newAggregateDispatcher(
                namedAggregate: NamedAggregate,
                messageFlux: Flux<String>,
            ): MessageDispatcher = error("No aggregate dispatcher is expected.")

            override fun startManaged() {
                startCount.incrementAndGet()
            }
        }

        dispatcher.start()

        startCount.get().assert().isOne()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `stopGracefully before start does not initialize aggregate dispatchers`() {
        val dispatcher = RecordingMainDispatcher()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
        tryStartAfterTerminalStop(dispatcher)

        dispatcher.receiveCount.get().assert().isEqualTo(0)
        dispatcher.createCount.get().assert().isEqualTo(0)
        dispatcher.childStopCount.get().assert().isEqualTo(0)
    }

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
            .then { dispatcher.start() }
            .expectNext("recording-main", "recording-main")
            .verifyComplete()

        dispatcher.receiveCount.get().assert().isEqualTo(2)
        dispatcher.createCount.get().assert().isEqualTo(2)

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
        dispatcher.childStopCount.get().assert().isEqualTo(2)
    }

    @Test
    fun `direct stop closes every child intake before managed cleanup`() {
        val dispatcher = RecordingMainDispatcher()
        dispatcher.start()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()

        dispatcher.childIntakeCloseCalls.assert().hasSize(2)
        dispatcher.closedIntakeCountsObservedByStop.assert().containsExactly(2, 2)
    }

    @Test
    fun `direct runtime failure stops every child and remains terminal`() {
        val failure = IllegalStateException("runtime")
        val dispatcher = RecordingMainDispatcher()
        dispatcher.start()

        dispatcher.reportFailure(failure)

        StepVerifier.create(dispatcher.stopGracefully())
            .expectErrorSatisfies { error ->
                error.assert().isSameAs(failure)
            }.verify()
        dispatcher.childForceCalls.assert().hasSize(2)
    }

    @Test
    fun `direct runtime remains one shot after shutdown`() {
        val dispatcher = RecordingMainDispatcher()
        dispatcher.start()
        dispatcher.stopGracefully().block()

        assertThrows<IllegalStateException> {
            dispatcher.start()
        }
    }

    @Test
    fun `forceStop continues after a child failure`() {
        val failure = IllegalStateException("force")
        val dispatcher = RecordingMainDispatcher(forceFailure = failure)
        dispatcher.start()

        dispatcher.forceStop()

        StepVerifier.create(dispatcher.stopGracefully())
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
        dispatcher.prepare(DefaultRuntimeContext())
        dispatcher.start()
        val gracefulStop = dispatcher.stopGracefully().toFuture()

        stopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        dispatcher.forceStop()
        stopGate.tryEmitEmpty().orThrow()
        gracefulStop.get(1, TimeUnit.SECONDS)

        dispatcher.childStopCount.get().assert().isOne()
        dispatcher.managedStopCount.get().assert().isZero()
    }

    @Test
    fun `legacy child dispatcher remains compatible with direct graceful lifecycle`() {
        val child = LegacyChildDispatcher()
        val dispatcher = LegacyChildMainDispatcher(child)

        dispatcher.start()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()

        child.calls.assert().containsExactly("start", "stop")
    }

    @Test
    fun `legacy child dispatcher uses its explicit hard stop`() {
        val child = LegacyChildDispatcher()
        val dispatcher = LegacyChildMainDispatcher(child)
        dispatcher.start()

        dispatcher.forceStop()

        child.calls.assert().containsExactly("start", "force")
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `legacy child without hard stop fails before lifecycle preparation`() {
        val child = object : MessageDispatcher {
            override val name: String = "graceful-only"

            override fun start() = Unit

            override fun stopGracefully(): Mono<Void> = Mono.empty()
        }
        val dispatcher = LegacyChildMainDispatcher(child)

        val thrown = assertThrows<IllegalArgumentException>(dispatcher::start)

        thrown.message.assert().contains("ForceStoppable")
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

    private class RecordingMainDispatcher(
        private val forceFailure: RuntimeException? = null,
        private val childStartAction: (() -> Unit)? = null,
        private val childForceAction: (() -> Unit)? = null,
        private val childStopAction: (() -> Mono<Void>)? = null,
    ) : MainDispatcher<String>() {
        val receiveCount = AtomicInteger()
        val createCount = AtomicInteger()
        val childStopCount = AtomicInteger()
        val managedStopCount = AtomicInteger()
        val childForceCalls = mutableListOf<String>()
        val childIntakeCloseCalls = mutableListOf<String>()
        val closedIntakeCountsObservedByStop = mutableListOf<Int>()
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

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>
        ): MessageDispatcher {
            createCount.incrementAndGet()
            return object :
                MessageDispatcher,
                RuntimeComponent {
                override val name: String = "child-${namedAggregate.aggregateName}"

                override val runtimeOwnership: RuntimeOwnership = RuntimeOwnership()

                override fun prepare(runtimeContext: RuntimeContext) {
                    childRuntimeContext = runtimeContext
                    runtimeContext.onAdmissionClose {
                        childIntakeCloseCalls += name
                    }
                }

                override fun start() {
                    childStartAction?.invoke()
                    messageFlux.subscribe {
                        receiverGroups.tryEmitNext(it).orThrow()
                    }
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
            Mono.fromRunnable(managedStopCount::incrementAndGet)

        fun reportFailure(error: Throwable) {
            checkNotNull(childRuntimeContext).reportFailure(error)
        }
    }

    private class LegacyChildMainDispatcher(
        private val child: MessageDispatcher,
    ) : MainDispatcher<String>() {
        override val name: String = "legacy-child-main"
        override val namedAggregates: Set<NamedAggregate> =
            setOf("wow-core-test.legacy_child".toNamedAggregate().materialize())

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> = Flux.empty()

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>,
        ): MessageDispatcher = child
    }

    private class LegacyChildDispatcher :
        MessageDispatcher,
        ForceStoppable {
        override val name: String = "legacy-child"
        val calls = CopyOnWriteArrayList<String>()

        override fun start() {
            calls += "start"
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                calls += "stop"
            }

        override fun forceStop() {
            calls += "force"
        }
    }
}
