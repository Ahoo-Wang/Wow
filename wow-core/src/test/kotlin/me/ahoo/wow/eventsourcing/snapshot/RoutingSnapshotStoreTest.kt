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
package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RoutingSnapshotStoreTest {
    private val order = MaterializedNamedAggregate("sales", "Order")
    private val invoice = MaterializedNamedAggregate("billing", "Invoice")

    @Test
    fun `name should expose stable routing name`() {
        val routingStore = routingSnapshotStore(
            defaultStore = RecordingSnapshotStore(),
            orderStore = RecordingSnapshotStore(),
        )

        routingStore.name.assert().isEqualTo(RoutingSnapshotStore.NAME)
    }

    @Test
    fun `load chooses configured store`() {
        val defaultStore = RecordingSnapshotStore()
        val orderStore = RecordingSnapshotStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingSnapshotStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.load<MockStateAggregate>(aggregateId))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("load")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `get version chooses configured store`() {
        val defaultStore = RecordingSnapshotStore()
        val orderStore = RecordingSnapshotStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingSnapshotStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.getVersion(aggregateId))
            .expectNext(7)
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("getVersion")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `save chooses configured store from snapshot aggregate`() {
        val defaultStore = RecordingSnapshotStore()
        val orderStore = RecordingSnapshotStore()
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingSnapshotStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.save(snapshot(aggregateId)))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("save")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
        defaultStore.lastOperation.assert().isNull()
    }

    @Test
    fun `missing route uses default snapshot store`() {
        val defaultStore = RecordingSnapshotStore()
        val orderStore = RecordingSnapshotStore()
        val aggregateId = invoice.aggregateId("invoice-1")
        val routingStore = routingSnapshotStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.load<MockStateAggregate>(aggregateId))
            .verifyComplete()

        defaultStore.lastOperation.assert().isEqualTo("load")
        defaultStore.lastAggregateId.assert().isEqualTo(aggregateId)
        orderStore.lastOperation.assert().isNull()
    }

    @Test
    fun `selected store exception is propagated without wrapping`() {
        val failure = IllegalStateException("selected store failed")
        val defaultStore = RecordingSnapshotStore()
        val orderStore = RecordingSnapshotStore(failure)
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingSnapshotStore(defaultStore, orderStore)

        StepVerifier.create(routingStore.save(snapshot(aggregateId)))
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()
    }

    @Test
    fun `load at or before falls back to the latest snapshot for a legacy store`() {
        val aggregateId = order.aggregateId("order-1")
        val latest = snapshot(aggregateId)
        val orderStore = RecordingSnapshotStore(loadedSnapshot = latest)
        val routingStore = routingSnapshotStore(RecordingSnapshotStore(), orderStore)

        StepVerifier.create(routingStore.loadAtOrBefore<MockStateAggregate>(aggregateId, latest.version))
            .expectNext(latest)
            .verifyComplete()
        StepVerifier.create(routingStore.loadAtOrBefore<MockStateAggregate>(aggregateId, latest.version - 1))
            .verifyComplete()

        orderStore.lastOperation.assert().isEqualTo("load")
        orderStore.lastAggregateId.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `save checkpoint rejects a legacy selected store`() {
        val aggregateId = order.aggregateId("order-1")
        val routingStore = routingSnapshotStore(RecordingSnapshotStore(), RecordingSnapshotStore())

        StepVerifier.create(routingStore.saveCheckpoint(snapshot(aggregateId)))
            .expectErrorSatisfies { error ->
                error.assert()
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("does not support historical checkpoints")
            }
            .verify()
    }

    private fun routingSnapshotStore(
        defaultStore: SnapshotStore,
        orderStore: SnapshotStore
    ): RoutingSnapshotStore {
        val routeKey = NamedAggregateStub(order.contextName, order.aggregateName)
        return RoutingSnapshotStore(
            AggregateSnapshotStoreRegistry(
                defaultSnapshotStore = defaultStore,
                routes = mapOf(routeKey to orderStore),
            ),
        )
    }

    private class RecordingSnapshotStore(
        private val failure: Throwable? = null,
        private val loadedSnapshot: Snapshot<MockStateAggregate>? = null,
    ) : SnapshotStore {
        override val name: String = "recording"
        var lastOperation: String? = null
        var lastAggregateId: AggregateId? = null

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
            record("load", aggregateId)
            failure?.let { return Mono.error(it) }
            @Suppress("UNCHECKED_CAST")
            return loadedSnapshot?.let { Mono.just(it as Snapshot<S>) } ?: Mono.empty()
        }

        override fun getVersion(aggregateId: AggregateId): Mono<Int> {
            record("getVersion", aggregateId)
            return failure?.let { Mono.error(it) } ?: Mono.just(7)
        }

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
            record("save", snapshot.aggregateId)
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

    private fun snapshot(aggregateId: AggregateId): Snapshot<MockStateAggregate> {
        val stateAggregate = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        return SimpleSnapshot(stateAggregate)
    }
}
