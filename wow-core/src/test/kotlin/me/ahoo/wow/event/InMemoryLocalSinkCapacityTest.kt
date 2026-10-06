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
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Sinks

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
}
