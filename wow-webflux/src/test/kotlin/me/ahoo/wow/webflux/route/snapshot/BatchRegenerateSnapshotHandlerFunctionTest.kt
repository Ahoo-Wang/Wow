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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.AggregateIdScanner.Companion.FIRST_ID
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.policy.BatchExecutionPolicy
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class BatchRegenerateSnapshotHandlerFunctionTest {

    @Test
    fun `factory should create batch regenerate snapshot handler`() {
        val factory = BatchRegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = InMemoryEventStore(),
            snapshotStore = NoOpSnapshotStore,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            batchExecutionPolicy = BatchExecutionPolicy(),
        )

        factory.handlerKey.assert().isEqualTo(BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE)
        factory.create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        ).assert().isInstanceOf(BatchRegenerateSnapshotHandlerFunction::class.java)
    }

    @Test
    fun `should handle batch regenerate snapshot request`() {
        val handlerFunction = BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = InMemoryEventStore(),
            snapshotStore = NoOpSnapshotStore,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            batchExecutionPolicy = BatchExecutionPolicy(),
        )

        val request = MockServerRequest.builder()
            .pathVariable(BatchComponent.PathVariable.BATCH_AFTER_ID, FIRST_ID)
            .pathVariable(BatchComponent.PathVariable.BATCH_LIMIT, Int.MAX_VALUE.toString())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }

    @Test
    fun `should regenerate scanned aggregate snapshots`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
            .whenCommand(MockCreateAggregate(id = aggregateId, data = "snapshot-data"))
            .expectNoError()
            .expectEventType(MockAggregateCreated::class.java)
            .verify()
        val snapshotStore = CapturingSnapshotStore()
        val handlerFunction = BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            snapshotStore = snapshotStore,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            batchExecutionPolicy = BatchExecutionPolicy(),
        )

        val request = MockServerRequest.builder()
            .pathVariable(BatchComponent.PathVariable.BATCH_AFTER_ID, FIRST_ID)
            .pathVariable(BatchComponent.PathVariable.BATCH_LIMIT, "1")
            .build()

        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }
            .verifyComplete()

        snapshotStore.savedSnapshots.assert().hasSize(1)
        val savedSnapshot = snapshotStore.savedSnapshots.single()
        savedSnapshot.aggregateId.id.assert().isEqualTo(aggregateId)
        savedSnapshot.version.assert().isOne()
    }

    private class CapturingSnapshotStore : SnapshotStore {
        override val name: String
            get() = "capturing"

        val savedSnapshots = mutableListOf<Snapshot<*>>()

        override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
            return Mono.empty()
        }

        override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
            return Mono.fromRunnable {
                savedSnapshots += snapshot
            }
        }
    }
}
