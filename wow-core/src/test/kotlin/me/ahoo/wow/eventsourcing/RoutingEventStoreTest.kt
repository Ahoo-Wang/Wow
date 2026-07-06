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
package me.ahoo.wow.eventsourcing

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RoutingEventStoreTest {
    private val order = MaterializedNamedAggregate("sales", "Order")
    private val invoice = MaterializedNamedAggregate("billing", "Invoice")

    @Test
    fun `append chooses configured store from event stream aggregate`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.append(eventStream(aggregateId)))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("append")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `load by version chooses configured store`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.load(aggregateId, headVersion = 2, tailVersion = 3))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("loadByVersion")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `load by event time chooses configured store`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.load(aggregateId, headEventTime = 100, tailEventTime = 200))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("loadByEventTime")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `single chooses configured store`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.single(aggregateId, version = 2))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("single")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `last chooses configured store`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.last(aggregateId))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("last")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `missing route uses default event store`() {
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore()
        val aggregateId = invoice.aggregateId("invoice-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.load(aggregateId))
            .verifyComplete()

        defaultStore.lastOperation.assert().isEqualTo("loadByVersion")
        defaultStore.lastAggregateId.assert().isEqualTo(aggregateId)
        orderStore.lastOperation.assert().isNull()
    }

    @Test
    fun `selected store exception is propagated without wrapping`() {
        val failure = IllegalStateException("selected store failed")
        val defaultStore = RecordingEventStore()
        val orderStore = RecordingEventStore(failure)
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingEventStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.append(eventStream(aggregateId)))
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()
    }

    private fun routingEventStore(
        defaultStore: EventStore,
        orderStore: EventStore
    ): EventStore {
        val routeKey = NamedAggregateStub(order.contextName, order.aggregateName)
        return RoutingEventStore(
            AggregateEventStoreRegistry(
                defaultEventStore = defaultStore,
                routes = mapOf(routeKey to orderStore),
            ),
        )
    }

    private class RecordingEventStore(
        private val failure: Throwable? = null
    ) : EventStore {
        var lastOperation: String? = null
        var lastAggregateId: AggregateId? = null

        override fun append(eventStream: DomainEventStream): Mono<Void> {
            record("append", eventStream.aggregateId)
            return failure?.let { Mono.error(it) } ?: Mono.empty()
        }

        override fun load(
            aggregateId: AggregateId,
            headVersion: Int,
            tailVersion: Int
        ): Flux<DomainEventStream> {
            record("loadByVersion", aggregateId)
            return failure?.let { Flux.error(it) } ?: Flux.empty()
        }

        override fun load(
            aggregateId: AggregateId,
            headEventTime: Long,
            tailEventTime: Long
        ): Flux<DomainEventStream> {
            record("loadByEventTime", aggregateId)
            return failure?.let { Flux.error(it) } ?: Flux.empty()
        }

        override fun single(aggregateId: AggregateId, version: Int): Mono<DomainEventStream> {
            record("single", aggregateId)
            return failure?.let { Mono.error(it) } ?: Mono.empty()
        }

        override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
            record("last", aggregateId)
            return failure?.let { Mono.error(it) } ?: Mono.empty()
        }

        private fun record(operation: String, aggregateId: AggregateId) {
            lastOperation = operation
            lastAggregateId = aggregateId
        }
    }

    private data class NamedAggregateStub(
        override val contextName: String,
        override val aggregateName: String
    ) : NamedAggregate

    private data class DomainEventStreamStub(
        override val aggregateId: AggregateId
    ) : DomainEventStream {
        override val id: String = "stream-1"
        override val requestId: String = "request-1"
        override val header = DefaultHeader.empty()
        override val body = emptyList<me.ahoo.wow.api.event.DomainEvent<*>>()
        override val contextName: String = aggregateId.contextName
        override val aggregateName: String = aggregateId.aggregateName
        override val ownerId: String = "owner-1"
        override val spaceId: String = "space-1"
        override val commandId: String = "command-1"
        override val version: Int = 1
        override val size: Int = 0
        override val createTime: Long = 1000

        override fun copy(): DomainEventStream = this
        override fun iterator(): Iterator<me.ahoo.wow.api.event.DomainEvent<*>> = body.iterator()
    }

    private fun eventStream(aggregateId: AggregateId): DomainEventStream =
        DomainEventStreamStub(aggregateId)
}
