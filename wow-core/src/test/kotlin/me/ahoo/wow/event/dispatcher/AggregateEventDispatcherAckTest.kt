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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.acknowledgementWithheldBy
import me.ahoo.wow.messaging.handler.isAcknowledgementWithheld
import me.ahoo.wow.messaging.handler.withholdAcknowledgement
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AggregateEventDispatcherAckTest {

    @Test
    fun `acknowledges the event stream after its functions are handled`() {
        val exchange = AckCountingExchange()

        dispatcher { Mono.empty() }.handleExchange(exchange)
            .test()
            .verifyComplete()

        exchange.ackCount.get().assert().isOne()
    }

    @Test
    fun `a function exchange that withholds acknowledgement leaves the event stream unacknowledged`() {
        val exchange = AckCountingExchange()
        val handled = CopyOnWriteArrayList<String>()

        dispatcher { functionExchange ->
            Mono.fromRunnable {
                val name = checkNotNull(functionExchange.getFunction()).name
                handled += name
                if (name == WITHHOLDING_FUNCTION) {
                    functionExchange.withholdAcknowledgement()
                }
            }
        }.handleExchange(exchange)
            .test()
            .verifyComplete()

        handled.assert().containsExactlyInAnyOrder(WITHHOLDING_FUNCTION, SIBLING_FUNCTION)
        exchange.isAcknowledgementWithheld().assert().isTrue()
        exchange.acknowledgementWithheldBy().assert().contains(WITHHOLDING_FUNCTION)
            .doesNotContain(SIBLING_FUNCTION)
        exchange.ackCount.get().assert().isZero()
    }

    @Test
    fun `metrics keep the per-aggregate dispatcher name as processor tag`() {
        val meterRegistry = SimpleMeterRegistry()
        val exchange = AckCountingExchange()
        val dispatcher = dispatcher(
            messageFlux = Flux.just(exchange),
            metrics = WowMetrics(meterRegistry),
        ) { Mono.empty() }

        dispatcher.name.assert().isEqualTo("${MOCK_AGGREGATE_METADATA.contextName}-AggregateEventDispatcher")
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
        dispatcher.terminatedSignal.block(Duration.ofSeconds(5))

        meterRegistry.meters
            .filter { it.id.getTag("component") == "dispatcher" }
            .mapNotNull { it.id.getTag("processor") }
            .toSet()
            .assert().containsExactly("${MOCK_AGGREGATE_METADATA.aggregateName}-AggregateEventDispatcher")
        meterRegistry.close()
    }

    @Test
    fun `each function exchange gets its own copy of the stream exchange attributes`() {
        listOf(emptyMap(), mapOf("trace" to "t-1")).forEach { streamAttributes ->
            val exchange = AckCountingExchange().apply { attributes.putAll(streamAttributes) }
            val seen = CopyOnWriteArrayList<Map<String, Any>>()
            val maps = CopyOnWriteArrayList<MutableMap<String, Any>>()

            dispatcher { functionExchange ->
                maps.add(functionExchange.attributes)
                functionExchange.attributes.filterKeys { it == "trace" }.let(seen::add)
                functionExchange.attributes["written-by-function"] = true
                Mono.empty()
            }.handleExchange(exchange).test().verifyComplete()

            seen.assert().hasSize(2)
            seen.forEach { it.assert().isEqualTo(streamAttributes) }
            // Each function has a map of its own: not one shared by both, nor the stream exchange's.
            maps.assert().hasSize(2)
            maps[0].assert().isNotSameAs(maps[1])
            maps.forEach { it.assert().isNotSameAs(exchange.attributes) }
            exchange.attributes.assert().isEqualTo(streamAttributes)
        }
    }

    private fun dispatcher(
        messageFlux: Flux<EventStreamExchange> = Flux.empty(),
        metrics: WowMetrics = WowMetrics.NONE,
        onHandle: (DomainEventExchange<*>) -> Mono<Void>,
    ): AggregateEventDispatcher {
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        return AggregateEventDispatcher(
            namedAggregates = setOf(namedAggregate),
            messageFlux = messageFlux,
            metrics = metrics,
            functionRegistrar = DomainEventFunctionRegistrar().apply {
                register(CreatedFunction(namedAggregate, WITHHOLDING_FUNCTION))
                register(CreatedFunction(namedAggregate, SIBLING_FUNCTION))
            },
            eventHandler = object : EventHandler {
                override fun handle(context: DomainEventExchange<*>): Mono<Void> = onHandle(context)
            },
        )
    }

    private class AckCountingExchange : EventStreamExchange {
        override val message = MockAggregateCreated("created").toDomainEventStream(
            upstream = GivenInitializationCommand(MOCK_AGGREGATE_METADATA.aggregateId("ack-aggregate")),
            aggregateVersion = 0,
        )
        override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
        val ackCount = AtomicInteger()

        override fun acknowledge(): Mono<Void> = Mono.fromRunnable { ackCount.incrementAndGet() }
    }

    private class CreatedFunction(
        namedAggregate: NamedAggregate,
        override val name: String,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val functionKind: FunctionKind = FunctionKind.EVENT
        override val contextName: String = namedAggregate.contextName
        override val supportedType: Class<*> = MockAggregateCreated::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate)
        override val processor: Any = this

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }

    private companion object {
        const val WITHHOLDING_FUNCTION = "onCreatedWithholding"
        const val SIBLING_FUNCTION = "onCreatedSibling"
    }
}
