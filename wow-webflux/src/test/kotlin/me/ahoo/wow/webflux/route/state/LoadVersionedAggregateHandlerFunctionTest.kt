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
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.rest.WowHeaders
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.whenCommand
import me.ahoo.wow.test.aggregateVerifier
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test
import java.net.URI

class LoadVersionedAggregateHandlerFunctionTest {

    @Test
    fun `should handle load versioned aggregate request`() {
        val handlerFunction = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotStore = NoOpSnapshotStore,
                eventStore = InMemoryEventStore(),
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
            route = StateLoadRoute.VERSIONED,
        ).create(
            testAggregateRouteContract(
                handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE,
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()
            )
        )

        val request = MockServerRequest.builder()
            .method(HttpMethod.GET)
            .uri(URI.create("http://localhost"))
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(MessageRecords.VERSION, "1")
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .build()
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.NOT_FOUND)
            }.verifyComplete()
    }

    @Test
    fun `should load the requested version and reject a version the aggregate never reached`() {
        val eventStore = InMemoryEventStore()
        val aggregateId = generateGlobalId()
        aggregateVerifier<MockCommandAggregate, MockStateAggregate>(eventStore = eventStore)
            .whenCommand(MockCreateAggregate(id = aggregateId, data = "test-data"))
            .expectNoError()
            .verify()
        // Built directly, with the default admission, as a host that does not use the factory would.
        val handlerFunction = LoadAggregateHandlerFunction(
            aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotStore = NoOpSnapshotStore,
                eventStore = eventStore,
            ),
            exceptionHandler = WebFluxRequestExceptionHandler(),
            route = StateLoadRoute.VERSIONED,
        )
        fun load(version: Int) = handlerFunction.handle(
            MockServerRequest.builder()
                .pathVariable(MessageRecords.ID, aggregateId)
                .pathVariable(MessageRecords.TENANT_ID, TenantId.DEFAULT_TENANT_ID)
                .pathVariable(MessageRecords.VERSION, version.toString())
                .build()
        )

        load(1).test().consumeNextWith {
            it.statusCode().assert().isEqualTo(HttpStatus.OK)
        }.verifyComplete()
        load(2).test().consumeNextWith {
            it.statusCode().assert().isEqualTo(HttpStatus.BAD_REQUEST)
            it.headers().getFirst(WowHeaders.ERROR_CODE).assert().isEqualTo(ErrorCodes.ILLEGAL_STATE)
        }.verifyComplete()
    }
}
