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
    fun `stopGracefully cancels active exchange handling before completing`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<TestExchange>()
        val invoked = Sinks.empty<Void>()
        val cancelled = Sinks.empty<Void>()
        val dispatcher = RecordingAggregateDispatcher(
            messageFlux = source.asFlux(),
            handle = {
                invoked.tryEmitEmpty().orThrow()
                Mono.never<Void>()
                    .doOnCancel { cancelled.tryEmitEmpty().orThrow() }
            },
        )
        dispatcher.start()

        StepVerifier.create(invoked.asMono())
            .then { source.tryEmitNext(TestExchange(group = 2)).orThrow() }
            .verifyComplete()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()

        StepVerifier.create(cancelled.asMono())
            .expectComplete()
            .verify(Duration.ofSeconds(1))
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
