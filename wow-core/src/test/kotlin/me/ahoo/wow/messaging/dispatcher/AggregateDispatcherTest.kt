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
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class AggregateDispatcherTest {

    @Test
    fun `start subscribes and routes exchanges through handleExchange`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val dispatcher = RecordingAggregateDispatcher(messageFlux = source.asFlux())
        val exchange = TestExchange(group = 1)
        val runtime = start(dispatcher)

        StepVerifier.create(dispatcher.handled.asFlux().take(1))
            .then { source.tryEmitNext(exchange).orThrow() }
            .expectNext(exchange)
            .verifyComplete()

        dispatcher.groups.assert().isEqualTo(listOf(1))
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `stopGracefully completes immediately when no task is active`() {
        val dispatcher = RecordingAggregateDispatcher(messageFlux = Flux.never())
        val runtime = start(dispatcher)

        StepVerifier.create(runtime.stopGracefully())
            .verifyComplete()
    }

    @Test
    fun `stopGracefully drains active exchange handling before completing`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val invoked = Sinks.empty<Void>()
        val cancelled = Sinks.empty<Void>()
        val release = Sinks.empty<Void>()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = {
                invoked.tryEmitEmpty().orThrow()
                release.asMono()
                    .doOnCancel { cancelled.tryEmitEmpty().orThrow() }
            },
        )
        val runtime = start(dispatcher)

        StepVerifier.create(invoked.asMono())
            .then { source.tryEmitNext(TestExchange(group = 2)).orThrow() }
            .verifyComplete()

        val stopFuture = runtime.stopGracefully().toFuture()
        stopFuture.isDone.assert().isFalse()

        StepVerifier.create(cancelled.asMono())
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .thenCancel()
            .verify()

        release.tryEmitEmpty().orThrow()
        stopFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `handleExchange errors are propagated to subscriber error hook`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val error = IllegalStateException("handler failed")
        val dispatcher = ErrorRecordingAggregateDispatcher(source.asFlux(), error)
        start(dispatcher)

        StepVerifier.create(dispatcher.errors.asMono())
            .then { source.tryEmitNext(TestExchange(group = 3)).orThrow() }
            .expectNext(error)
            .verifyComplete()
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

            val runtime = start(dispatcher)

            val dispatcherMeterIds = meterRegistry.meters
                .map { it.id }
                .filter { it.name.startsWith("wow.dispatcher") }
                .filter { it.getTag("dispatcher") == dispatcherName }
            dispatcherMeterIds.assert().isNotEmpty()
            dispatcherMeterIds
                .mapNotNull { it.getTag("group.key") }
                .assert().isEmpty()
            StepVerifier.create(runtime.stopGracefully()).verifyComplete()
        } finally {
            MicrometerMetrics.removeRegistry(meterRegistry)
            meterRegistry.close()
        }
    }

    private fun start(dispatcher: MessageDispatcher): WowRuntime =
        WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        ).also {
            StepVerifier.create(it.start()).verifyComplete()
        }

    private open class RecordingAggregateDispatcher(
        override val messageFlux: Flux<TestExchange>,
        private val handle: ((TestExchange) -> Mono<Void>)? = null,
        override val scheduler: Scheduler = Schedulers.immediate(),
        override val name: String = "recording-dispatcher",
    ) : AggregateDispatcher<TestExchange>() {
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
    override val message: TestNamedMessage = TestNamedMessage()
) : MessageExchange<TestExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
