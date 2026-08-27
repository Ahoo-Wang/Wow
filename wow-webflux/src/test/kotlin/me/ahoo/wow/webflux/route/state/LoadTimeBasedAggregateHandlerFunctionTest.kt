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

package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.net.URI

class LoadTimeBasedAggregateHandlerFunctionTest {
    @Test
    fun `should handle load time-based aggregate request`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(aggregateId, eventStore = eventStore)
            .givenOwnerId(aggregateId)
            .whenCommand(MockCreateAggregate(id = aggregateId, data = "test-data"))
            .expectNoError()
            .expectEventType(MockAggregateCreated::class.java)
            .expectState {
                data.assert().isEqualTo("test-data")
            }
            .verify()

        val handlerFunction = LoadTimeBasedAggregateHandlerFunctionFactory(
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotStore = NoOpSnapshotStore,
                eventStore = eventStore
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE,
                aggregateRouteMetadata = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA
            )
        )

        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .pathVariable(MessageRecords.ID, aggregateId)
            .pathVariable(MessageRecords.OWNER_ID, aggregateId)
            .pathVariable(MessageRecords.CREATE_TIME, System.currentTimeMillis().toString())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
