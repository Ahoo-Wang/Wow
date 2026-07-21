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

package me.ahoo.wow.opentelemetry

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.opentelemetry.messaging.TracingLocalCommandBus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test

class TracePublisherTest {

    @Test
    fun `should end span when subscription is cancelled`() {
        val traceContext = Context.root()
        val instrumenter = mockInstrumenter(traceContext)

        TraceFlux(
            parentContext = Context.root(),
            instrumenter = instrumenter,
            request = REQUEST,
            source = Flux.never<Int>(),
        ).test()
            .thenCancel()
            .verify()

        verify(exactly = 1) {
            instrumenter.end(traceContext, REQUEST, null, null)
        }
    }

    @Test
    fun `should end span exactly once when downstream cancels after first element`() {
        val traceContext = Context.root()
        val instrumenter = mockInstrumenter(traceContext)

        TraceFlux(
            parentContext = Context.root(),
            instrumenter = instrumenter,
            request = REQUEST,
            source = Flux.just(1, 2),
        ).take(1)
            .test()
            .expectNext(1)
            .verifyComplete()

        verify(exactly = 1) {
            instrumenter.end(traceContext, REQUEST, null, null)
        }
    }

    @Test
    fun `should restore trace context for asynchronous signals`() {
        val contextKey = ContextKey.named<String>("trace-publisher-test")
        val traceContext = Context.root().with(contextKey, "active")
        val instrumenter = mockInstrumenter(traceContext)

        TraceMono(
            parentContext = Context.root(),
            instrumenter = instrumenter,
            request = REQUEST,
            source = Mono.just(1).publishOn(Schedulers.parallel()),
        ).doOnNext {
            Context.current()[contextKey].assert().isEqualTo("active")
        }.test()
            .expectNext(1)
            .verifyComplete()

        verify(exactly = 1) {
            instrumenter.end(traceContext, REQUEST, null, null)
        }
    }

    @Test
    fun `should capture parent context when publisher is subscribed`() {
        val contextKey = ContextKey.named<String>("subscription-context")
        val subscriptionContext = Context.root().with(contextKey, "active")
        val message = mockk<CommandMessage<*>>()
        val delegate = mockk<LocalCommandBus> {
            every { send(message) } returns Mono.empty()
        }
        val instrumenter = mockk<Instrumenter<CommandMessage<*>, Unit>> {
            every { shouldStart(subscriptionContext, message) } returns true
            every { start(subscriptionContext, message) } returns subscriptionContext
            every { end(subscriptionContext, message, null, null) } just runs
        }
        val publisher = TracingLocalCommandBus(delegate, instrumenter).send(message)

        subscriptionContext.makeCurrent().use {
            publisher.block()
        }

        verify(exactly = 1) {
            instrumenter.shouldStart(subscriptionContext, message)
            instrumenter.end(subscriptionContext, message, null, null)
        }
    }

    @Test
    fun `should propagate trace context to nested publisher across thread boundary`() {
        val contextKey = ContextKey.named<String>("nested-publisher-context")
        val parentTraceContext = Context.root().with(contextKey, "parent")
        val parentInstrumenter = mockInstrumenter(parentTraceContext)
        val message = mockk<CommandMessage<*>>()
        val delegate = mockk<LocalCommandBus> {
            every { send(message) } returns Mono.empty()
        }
        val childInstrumenter = mockk<Instrumenter<CommandMessage<*>, Unit>> {
            every { shouldStart(parentTraceContext, message) } returns true
            every { start(parentTraceContext, message) } returns parentTraceContext
            every { end(parentTraceContext, message, null, null) } just runs
        }
        val tracedBus = TracingLocalCommandBus(delegate, childInstrumenter)
        val source = Mono.just(1)
            .publishOn(Schedulers.parallel())
            .flatMap {
                tracedBus.send(message).thenReturn(it)
            }

        TraceMono(
            parentContext = Context.root(),
            instrumenter = parentInstrumenter,
            request = REQUEST,
            source = source,
        ).test()
            .expectNext(1)
            .verifyComplete()

        verify(exactly = 1) {
            childInstrumenter.shouldStart(parentTraceContext, message)
            childInstrumenter.end(parentTraceContext, message, null, null)
        }
    }

    @Test
    fun `should end span with source error`() {
        val traceContext = Context.root()
        val instrumenter = mockInstrumenter(traceContext)
        val failure = IllegalStateException("source failed")

        TraceMono(
            parentContext = Context.root(),
            instrumenter = instrumenter,
            request = REQUEST,
            source = Mono.error<Int>(failure),
        ).test()
            .expectErrorMatches { it === failure }
            .verify()

        verify(exactly = 1) {
            instrumenter.end(traceContext, REQUEST, null, failure)
        }
    }

    @Test
    fun `should subscribe without tracing when instrumenter declines`() {
        val monoInstrumenter = mockk<Instrumenter<String, Unit>> {
            every { shouldStart(Context.root(), REQUEST) } returns false
        }
        val fluxInstrumenter = mockk<Instrumenter<String, Unit>> {
            every { shouldStart(Context.root(), REQUEST) } returns false
        }
        val exchange = mockk<MessageExchange<*, *>>()
        val exchangeInstrumenter = mockk<Instrumenter<MessageExchange<*, *>, Unit>> {
            every { shouldStart(Context.root(), exchange) } returns false
        }

        TraceMono(
            parentContext = Context.root(),
            instrumenter = monoInstrumenter,
            request = REQUEST,
            source = Mono.just(1),
        ).test()
            .expectNext(1)
            .verifyComplete()
        TraceFlux(
            parentContext = Context.root(),
            instrumenter = fluxInstrumenter,
            request = REQUEST,
            source = Flux.just(1),
        ).test()
            .expectNext(1)
            .verifyComplete()
        ExchangeTraceMono(
            parentContext = Context.root(),
            instrumenter = exchangeInstrumenter,
            request = exchange,
            source = Mono.empty(),
        ).test()
            .verifyComplete()

        verify(exactly = 0) {
            monoInstrumenter.start(any(), any())
            fluxInstrumenter.start(any(), any())
            exchangeInstrumenter.start(any(), any())
        }
    }

    @Test
    fun `should end span when source throws during subscription`() {
        val failure = IllegalStateException("subscribe failed")
        val traceContext = Context.root()
        val monoInstrumenter = mockInstrumenter(traceContext)
        val fluxInstrumenter = mockInstrumenter(traceContext)
        val actual = mockk<CoreSubscriber<Int>>(relaxed = true)

        assertThrows<IllegalStateException> {
            TraceMono(
                parentContext = Context.root(),
                instrumenter = monoInstrumenter,
                request = REQUEST,
                source = throwingMono<Int>(failure),
            ).subscribe(actual)
        }.assert().isSameAs(failure)
        assertThrows<IllegalStateException> {
            TraceFlux(
                parentContext = Context.root(),
                instrumenter = fluxInstrumenter,
                request = REQUEST,
                source = throwingFlux(failure),
            ).subscribe(actual)
        }.assert().isSameAs(failure)

        verify(exactly = 1) {
            monoInstrumenter.end(traceContext, REQUEST, null, failure)
            fluxInstrumenter.end(traceContext, REQUEST, null, failure)
        }
    }

    @Test
    fun `should end exchange span when source throws during subscription`() {
        val failure = IllegalStateException("subscribe failed")
        val traceContext = Context.root()
        val exchange = mockk<MessageExchange<*, *>>()
        val instrumenter = mockk<Instrumenter<MessageExchange<*, *>, Unit>> {
            every { shouldStart(Context.root(), exchange) } returns true
            every { start(Context.root(), exchange) } returns traceContext
            every { end(traceContext, exchange, null, failure) } just runs
        }

        assertThrows<IllegalStateException> {
            ExchangeTraceMono(
                parentContext = Context.root(),
                instrumenter = instrumenter,
                request = exchange,
                source = throwingMono(failure),
            ).subscribe(mockk<CoreSubscriber<Void>>(relaxed = true))
        }.assert().isSameAs(failure)

        verify(exactly = 1) {
            instrumenter.end(traceContext, exchange, null, failure)
        }
    }

    @Test
    fun `should ignore repeated terminal signal`() {
        val traceContext = Context.root()
        val instrumenter = mockInstrumenter(traceContext)
        val actual = mockk<CoreSubscriber<Int>>(relaxed = true)
        val subscriber = TraceSubscriber(instrumenter, traceContext, REQUEST, actual)

        subscriber.onComplete()
        subscriber.onComplete()

        verify(exactly = 1) {
            instrumenter.end(traceContext, REQUEST, null, null)
            actual.onComplete()
        }
    }

    private fun <T : Any> throwingMono(failure: Throwable): Mono<T> {
        return object : Mono<T>() {
            override fun subscribe(actual: CoreSubscriber<in T>) {
                throw failure
            }
        }
    }

    private fun throwingFlux(failure: Throwable): Flux<Int> {
        return object : Flux<Int>() {
            override fun subscribe(actual: CoreSubscriber<in Int>) {
                throw failure
            }
        }
    }

    private fun mockInstrumenter(traceContext: Context): Instrumenter<String, Unit> {
        return mockk {
            every { shouldStart(any(), REQUEST) } returns true
            every { start(any(), REQUEST) } returns traceContext
            every { end(traceContext, REQUEST, null, null) } just runs
            every { end(traceContext, REQUEST, null, any()) } just runs
        }
    }

    companion object {
        private const val REQUEST = "request"
    }
}
