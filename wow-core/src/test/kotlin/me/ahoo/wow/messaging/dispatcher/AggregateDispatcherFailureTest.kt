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
import me.ahoo.wow.execution.KeyedExecutor
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Failure and lifecycle edges of [AggregateDispatcher]: fatal handler errors, start order, transport quiescence. */
class AggregateDispatcherFailureTest {

    @Test
    fun `a JVM-fatal handler error fails its runtime and leaves the shared worker running`() {
        val keyedExecutor = KeyedExecutor(workers = 1, name = "aggregate-dispatcher-fatal")
        try {
            val source = Sinks.many().unicast().onBackpressureBuffer<FailureExchange>()
            val failure = NoClassDefFoundError("fatal")
            val runtime = WowRuntime(
                components = listOf(FailureDispatcher(source.asFlux(), handle = { throw failure })),
                shutdownTimeout = Duration.ofSeconds(5),
                shutdownQuietPeriod = Duration.ZERO,
                keyedExecutor = KeyedExecutor(workers = 1, name = "aggregate-dispatcher-fatal-runtime"),
            )
            runtime.start().block()

            source.tryEmitNext(FailureExchange(1)).orThrow()

            // Reported as an ordinary failure (the fatal error is the cause), so the runtime stops in time.
            StepVerifier.create(runtime.terminationSignal)
                .expectErrorSatisfies { error -> error.cause.assert().isSameAs(failure) }
                .verify(Duration.ofSeconds(5))

            // The same failure on an executor another dispatcher shares: that dispatcher keeps handling.
            val failing = FailureDispatcher(Flux.just(FailureExchange(1)), handle = { throw failure })
            failing.prepare(DefaultRuntimeContext(keyedExecutor = keyedExecutor)).block()
            failing.start()
            StepVerifier.create(failing.terminatedSignal)
                .expectErrorSatisfies { error -> error.cause.assert().isSameAs(failure) }
                .verify(Duration.ofSeconds(5))
            val handled = AtomicInteger()
            val survivor = FailureDispatcher(
                Flux.range(0, 20).map { FailureExchange(it) },
                handle = { Mono.fromRunnable { handled.incrementAndGet() } },
            )
            survivor.prepare(DefaultRuntimeContext(keyedExecutor = keyedExecutor)).block()
            survivor.start()
            StepVerifier.create(survivor.terminatedSignal).verifyComplete()
            handled.get().assert().isEqualTo(20)
        } finally {
            keyedExecutor.close()
        }
    }

    @Test
    fun `start requires preparation and is idempotent once running`() {
        val dispatcher = FailureDispatcher(Flux.never())
        assertThrows<IllegalStateException> { dispatcher.start() }

        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        dispatcher.start()

        dispatcher.forceStop()
        StepVerifier.create(dispatcher.terminatedSignal).verifyComplete()
    }

    @Test
    fun `a transport quiescence failure when the source completes fails the runtime`() {
        val failure = IllegalStateException("quiescence")
        val dispatcher = FailureDispatcher(Flux.empty(), processingQuiescence = { throw failure })
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(5),
            shutdownQuietPeriod = Duration.ZERO,
        )

        StepVerifier.create(runtime.start())
            .expectErrorSatisfies { error -> error.assert().isSameAs(failure) }
            .verify(Duration.ofSeconds(5))
        StepVerifier.create(dispatcher.terminatedSignal)
            .expectErrorSatisfies { error -> error.assert().isSameAs(failure) }
            .verify(Duration.ofSeconds(5))
    }

    private class FailureDispatcher(
        override val messageFlux: Flux<FailureExchange>,
        private val handle: (FailureExchange) -> Mono<Void> = { Mono.empty() },
        processingQuiescence: () -> Unit = {},
    ) : AggregateDispatcher<FailureExchange>(processingQuiescence = processingQuiescence) {
        override val name: String = "failure-dispatcher"
        override val namedAggregates: Set<NamedAggregate> =
            setOf("wow-core-test.messaging_aggregate".toNamedAggregate().materialize())

        override fun FailureExchange.mailboxKey(): Any = key

        override fun handleExchange(exchange: FailureExchange): Mono<Void> = handle(exchange)
    }

    private data class FailureExchange(
        val key: Int,
        override val message: TestNamedMessage = TestNamedMessage(),
    ) : MessageExchange<FailureExchange, TestNamedMessage> {
        override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    }
}
