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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.AggregateIdScanner.Companion.FIRST_ID
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.openapi.aggregate.snapshot.BatchRegenerateSnapshotRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
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
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class BatchRegenerateSnapshotHandlerFunctionTest {

    @Test
    fun `factory should create batch regenerate snapshot handler`() {
        val factory = BatchRegenerateSnapshotHandlerFunctionFactory(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = InMemoryEventStore(),
            snapshotRepository = NoOpSnapshotRepository,
            exceptionHandler = WebFluxRequestExceptionHandler(),
            batchExecutionPolicy = BatchExecutionPolicy(),
        )

        factory.supportedSpec.assert().isEqualTo(BatchRegenerateSnapshotRouteSpec::class.java)
        factory.create(
            BatchRegenerateSnapshotRouteSpec(
                currentContext = MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA,
                componentContext = OpenAPIComponentContext.default(),
            )
        ).assert().isInstanceOf(BatchRegenerateSnapshotHandlerFunction::class.java)
    }

    @Test
    fun `should handle batch regenerate snapshot request`() {
        val handlerFunction = BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = InMemoryEventStore(),
            snapshotRepository = NoOpSnapshotRepository,
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
        val snapshotRepository = CapturingSnapshotRepository(
            aggregateIds = listOf(MOCK_AGGREGATE_METADATA.aggregateId(aggregateId))
        )
        val handlerFunction = BatchRegenerateSnapshotHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
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

        snapshotRepository.savedSnapshots.assert().hasSize(1)
        val savedSnapshot = snapshotRepository.savedSnapshots.single()
        savedSnapshot.aggregateId.id.assert().isEqualTo(aggregateId)
        savedSnapshot.version.assert().isOne()
    }

    private class CapturingSnapshotRepository(
        private val aggregateIds: List<AggregateId>
    ) : SnapshotRepository {
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

        override fun scanAggregateId(
            namedAggregate: NamedAggregate,
            afterId: String,
            limit: Int
        ): Flux<AggregateId> {
            return Flux.fromIterable(
                aggregateIds.asSequence()
                    .filter { it.isSameAggregateName(namedAggregate) }
                    .filter { it.id > afterId }
                    .take(limit)
                    .toList()
            )
        }
    }
}
