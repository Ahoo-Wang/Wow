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

package me.ahoo.wow.event.dispatcher

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CompositeEventDispatcherLifecycleTest {

    @Test
    fun `runtime prepares both child subscriptions before opening demand`() {
        val calls = mutableListOf<String>()
        val dispatcher = RecordingCompositeEventDispatcher(
            domainEventBus = RecordingDomainEventBus(calls),
            stateEventBus = RecordingStateEventBus(calls),
            functionRegistrar = registrar(
                RecordingFunction(FunctionKind.EVENT),
                RecordingFunction(FunctionKind.STATE_EVENT),
            ),
            schedulerSupplier = RecordingSchedulerSupplier(calls),
            prepareCalls = calls,
        )
        val runtime = runtime(dispatcher)

        runtime.start().block()

        dispatcher.prepareCount.get().assert().isEqualTo(1)
        dispatcher.preparedRuntimeContext.assert().isNotNull()
        calls.assert().contains(
            "subscribe:domain",
            "subscribe:state",
            "request:domain",
            "request:state",
        )
        calls.indexOf("subscribe:domain").assert()
            .isLessThan(calls.indexOf("request:domain"))
        calls.indexOf("subscribe:state").assert()
            .isLessThan(calls.indexOf("request:domain"))
        calls.indexOf("subscribe:domain").assert()
            .isLessThan(calls.indexOf("request:state"))
        calls.indexOf("subscribe:state").assert()
            .isLessThan(calls.indexOf("request:state"))

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `graceful path constructs every child before preparing any child`() {
        val fixture = PartialConstructionFailureFixture()

        val thrown = assertThrows<IllegalStateException> {
            fixture.dispatcher.prepare(DefaultRuntimeContext())
        }

        thrown.assert().isSameAs(fixture.constructionFailure)
        fixture.domainEventBus.subscriptionCount.get().assert().isZero()
        StepVerifier.create(fixture.dispatcher.stopGracefully()).verifyComplete()
        fixture.functionRegistrar.filterCount.get().assert().isEqualTo(2)
        fixture.domainEventBus.cancellationCount.get().assert().isZero()
        fixture.schedulerSupplier.gracefulStopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `force path constructs every child before preparing any child`() {
        val fixture = PartialConstructionFailureFixture()

        val thrown = assertThrows<IllegalStateException> {
            fixture.dispatcher.prepare(DefaultRuntimeContext())
        }

        thrown.assert().isSameAs(fixture.constructionFailure)
        fixture.domainEventBus.subscriptionCount.get().assert().isZero()
        fixture.dispatcher.forceStop()
        fixture.functionRegistrar.filterCount.get().assert().isEqualTo(2)
        fixture.domainEventBus.cancellationCount.get().assert().isZero()
        fixture.schedulerSupplier.forceStopCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `runtime graceful stop closes every intake before scheduler cleanup`() {
        val gracefulCalls = mutableListOf<String>()
        val gracefulDispatcher = newRecordingDispatcher(gracefulCalls)
        val runtime = runtime(gracefulDispatcher)
        runtime.start().block()
        gracefulCalls.clear()

        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        gracefulCalls.assert().containsExactly(
            "stop:domain",
            "stop:state",
            "stop:scheduler",
        )
    }

    @Test
    fun `runtime force stop does not wait for physical intake cleanup`() {
        val forceCalls = CopyOnWriteArrayList<String>()
        val releaseCancellation = CountDownLatch(1)
        val domainEventBus = RecordingDomainEventBus(forceCalls) {
            awaitIgnoringInterrupt(releaseCancellation)
        }
        val stateEventBus = RecordingStateEventBus(forceCalls) {
            awaitIgnoringInterrupt(releaseCancellation)
        }
        val schedulerSupplier = RecordingSchedulerSupplier(forceCalls)
        val forceDispatcher = RecordingCompositeEventDispatcher(
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = registrar(
                RecordingFunction(FunctionKind.EVENT),
                RecordingFunction(FunctionKind.STATE_EVENT),
            ),
            schedulerSupplier = schedulerSupplier,
            prepareCalls = forceCalls,
        )
        val runtime = runtime(forceDispatcher)
        runtime.start().block()
        forceCalls.clear()

        try {
            runtime.forceStop()

            schedulerSupplier.forceStopCount.get().assert().isEqualTo(1)
            domainEventBus.awaitCancellation()
            stateEventBus.awaitCancellation()
            forceCalls.assert().contains(
                "stop:domain",
                "stop:state",
                "force:scheduler",
            )
        } finally {
            releaseCancellation.countDown()
        }
    }

    @Test
    fun `force stop prevents graceful scheduler cleanup after a managed stop already entered`() {
        val calls = CopyOnWriteArrayList<String>()
        val managedStopEntered = CountDownLatch(1)
        val managedStopGate = Sinks.empty<Void>()
        val schedulerSupplier = RecordingSchedulerSupplier(calls)
        val dispatcher = RecordingCompositeEventDispatcher(
            domainEventBus = RecordingDomainEventBus(calls),
            stateEventBus = RecordingStateEventBus(calls),
            functionRegistrar = registrar(
                RecordingFunction(FunctionKind.EVENT),
                RecordingFunction(FunctionKind.STATE_EVENT),
            ),
            schedulerSupplier = schedulerSupplier,
            prepareCalls = calls,
            managedStopAction = {
                managedStopEntered.countDown()
                managedStopGate.asMono()
            },
        )
        dispatcher.prepare(DefaultRuntimeContext())
        dispatcher.start()
        val gracefulStop = dispatcher.stopGracefully().toFuture()

        managedStopEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        dispatcher.forceStop()
        managedStopGate.tryEmitEmpty().orThrow()
        gracefulStop.get(1, TimeUnit.SECONDS)

        dispatcher.managedStopCount.get().assert().isOne()
        schedulerSupplier.gracefulStopCount.get().assert().isZero()
        schedulerSupplier.forceStopCount.get().assert().isOne()
    }

    private fun newRecordingDispatcher(calls: MutableList<String>): RecordingCompositeEventDispatcher =
        RecordingCompositeEventDispatcher(
            domainEventBus = RecordingDomainEventBus(calls),
            stateEventBus = RecordingStateEventBus(calls),
            functionRegistrar = registrar(
                RecordingFunction(FunctionKind.EVENT),
                RecordingFunction(FunctionKind.STATE_EVENT),
            ),
            schedulerSupplier = RecordingSchedulerSupplier(calls),
            prepareCalls = calls,
        )

    private fun runtime(dispatcher: RecordingCompositeEventDispatcher): WowRuntime =
        WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

    private fun registrar(
        vararg functions: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
    ): MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> =
        SimpleMessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>()
            .apply {
                functions.forEach(this::register)
            }

    private class PartialConstructionFailureFixture {
        val constructionFailure = IllegalStateException("state-dispatcher-construction")
        val functionRegistrar = FailingSecondFilterRegistrar(
            constructionFailure,
            RecordingFunction(FunctionKind.EVENT),
        )
        val domainEventBus = RecordingDomainEventBus(mutableListOf())
        val schedulerSupplier = RecordingSchedulerSupplier(mutableListOf())
        val dispatcher = RecordingCompositeEventDispatcher(
            domainEventBus = domainEventBus,
            stateEventBus = RecordingStateEventBus(mutableListOf()),
            functionRegistrar = functionRegistrar,
            schedulerSupplier = schedulerSupplier,
            prepareCalls = mutableListOf(),
        )
    }

    private class RecordingCompositeEventDispatcher(
        domainEventBus: DomainEventBus,
        stateEventBus: StateEventBus,
        functionRegistrar: MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>,
        schedulerSupplier: AggregateSchedulerSupplier,
        private val prepareCalls: MutableList<String>,
        private val managedStopAction: () -> Mono<Void> = { Mono.empty() },
    ) : CompositeEventDispatcher(
        name = "recording-composite",
        parallelism = 1,
        domainEventBus = domainEventBus,
        stateEventBus = stateEventBus,
        functionRegistrar = functionRegistrar,
        eventHandler = object : EventHandler {
            override fun handle(context: DomainEventExchange<*>): Mono<Void> = Mono.empty()
        },
        schedulerSupplier = schedulerSupplier,
    ) {
        val prepareCount = AtomicInteger()
        val managedStopCount = AtomicInteger()
        var preparedRuntimeContext: RuntimeContext? = null

        override fun prepareManaged(runtimeContext: RuntimeContext) {
            prepareCalls += "prepare:composite"
            prepareCount.incrementAndGet()
            preparedRuntimeContext = runtimeContext
        }

        override fun stopManagedGracefully(): Mono<Void> =
            Mono.defer {
                managedStopCount.incrementAndGet()
                managedStopAction()
            }
    }

    private class RecordingDomainEventBus(
        private val calls: MutableList<String>,
        private val onCancel: () -> Unit = {},
    ) : DomainEventBus {
        val subscriptionCount = AtomicInteger()
        val cancellationCount = AtomicInteger()
        private val cancellationEntered = CountDownLatch(1)

        override fun send(message: DomainEventStream): Mono<Void> = Mono.empty()

        override fun receive(subscription: MessageSubscription): Flux<EventStreamExchange> =
            Flux.never<EventStreamExchange>()
                .doOnSubscribe {
                    subscriptionCount.incrementAndGet()
                    calls += "subscribe:domain"
                }.doOnRequest {
                    calls += "request:domain"
                }.doOnCancel {
                    cancellationCount.incrementAndGet()
                    calls += "stop:domain"
                    cancellationEntered.countDown()
                    onCancel()
                }

        fun awaitCancellation() {
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    private class RecordingStateEventBus(
        private val calls: MutableList<String>,
        private val onCancel: () -> Unit = {},
    ) : StateEventBus {
        private val cancellationEntered = CountDownLatch(1)

        override fun send(message: StateEvent<*>): Mono<Void> = Mono.empty()

        override fun receive(subscription: MessageSubscription): Flux<StateEventExchange<*>> =
            Flux.never<StateEventExchange<*>>()
                .doOnSubscribe {
                    calls += "subscribe:state"
                }.doOnRequest {
                    calls += "request:state"
                }.doOnCancel {
                    calls += "stop:state"
                    cancellationEntered.countDown()
                    onCancel()
                }

        fun awaitCancellation() {
            cancellationEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    private class RecordingSchedulerSupplier(
        private val calls: MutableList<String>,
    ) : AggregateSchedulerSupplier {
        val gracefulStopCount = AtomicInteger()
        val forceStopCount = AtomicInteger()

        override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
            Schedulers.immediate()

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                gracefulStopCount.incrementAndGet()
                calls += "stop:scheduler"
            }

        override fun forceStop() {
            forceStopCount.incrementAndGet()
            calls += "force:scheduler"
        }
    }

    private class FailingSecondFilterRegistrar(
        private val failure: RuntimeException,
        vararg functions: MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
        private val delegate: SimpleMessageFunctionRegistrar<
            MessageFunction<Any, DomainEventExchange<*>, Mono<*>>,
            > = SimpleMessageFunctionRegistrar(),
    ) : MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> by delegate {
        val filterCount = AtomicInteger()

        init {
            functions.forEach(delegate::register)
        }

        override fun filter(
            predicate: (MessageFunction<Any, DomainEventExchange<*>, Mono<*>>) -> Boolean,
        ): MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>> {
            if (filterCount.incrementAndGet() == 2) {
                throw failure
            }
            return delegate.filter(predicate)
        }
    }

    private class RecordingFunction(
        override val functionKind: FunctionKind,
        private val namedAggregate: NamedAggregate =
            "wow-core-test.composite_aggregate".toNamedAggregate().materialize(),
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val contextName: String = namedAggregate.contextName
        override val name: String = functionKind.name
        override val supportedType: Class<*> = Any::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate)
        override val processor: Any = this

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Model a third-party cancellation hook that ignores interruption.
            }
        }
    }
}
