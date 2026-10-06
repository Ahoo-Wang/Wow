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

package me.ahoo.wow.event

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The local sinks of the event buses never refuse a message because a consumer is slow: a local-first hand-off is
 * never turned into a distributed send by a full buffer (the backlog shows on `wow.local_first.backlog`).
 */
class InMemoryLocalSinkCapacityTest {
    private val aggregate = "context.aggregate".toNamedAggregate()

    private fun <T : Any> assertUnbounded(sink: Sinks.Many<T>, message: () -> T) {
        val stalled = object : BaseSubscriber<T>() {
            override fun hookOnSubscribe(subscription: Subscription) = Unit
        }
        sink.asFlux().subscribe(stalled)
        try {
            repeat(10_000) {
                sink.tryEmitNext(message()).assert().isEqualTo(Sinks.EmitResult.OK)
            }
        } finally {
            stalled.dispose()
        }
    }

    @Test
    fun `the domain event sink buffers whatever a stalled consumer has not pulled`() {
        assertUnbounded(InMemoryDomainEventBus().sinkSupplier(aggregate)) { mockk<DomainEventStream>() }
    }

    @Test
    fun `the state event sink buffers whatever a stalled consumer has not pulled`() {
        assertUnbounded(InMemoryStateEventBus().sinkSupplier(aggregate)) { mockk<StateEvent<*>>() }
    }

    @Test
    fun `events sent while nobody in this process subscribes are not retained`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("no-subscriber")
        val eventStream = MockAggregateCreated("created").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        )
        val stateEvent = eventStream.toStateEvent(MockStateAggregate(aggregateId.id))
        val domainSink = AtomicReference<Sinks.Many<DomainEventStream>>()
        val stateSink = AtomicReference<Sinks.Many<StateEvent<*>>>()
        val domainBus = InMemoryDomainEventBus { aggregate ->
            InMemoryDomainEventBus().sinkSupplier(aggregate).also(domainSink::set)
        }
        val stateBus = InMemoryStateEventBus { aggregate ->
            InMemoryStateEventBus().sinkSupplier(aggregate).also(stateSink::set)
        }

        repeat(1_000) {
            domainBus.send(eventStream.copy()).test().verifyComplete()
            stateBus.send(stateEvent.copy()).test().verifyComplete()
        }

        // An unbounded multicast buffer keeps what was emitted before its first subscriber: nothing may be waiting.
        domainSink.get().asFlux().take(Duration.ofMillis(50)).count().test().expectNext(0).verifyComplete()
        stateSink.get().asFlux().take(Duration.ofMillis(50)).count().test().expectNext(0).verifyComplete()
    }
}
