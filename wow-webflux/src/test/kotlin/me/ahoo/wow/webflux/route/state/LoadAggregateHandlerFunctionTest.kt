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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.state.LoadAggregateRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.kotlin.test.test
import java.net.URI

class LoadAggregateHandlerFunctionTest {

    @Test
    fun handle() {
        val handlerFunction = LoadAggregateHandlerFunctionFactory(
            stateAggregateRepository = EventSourcingStateAggregateRepository(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                snapshotRepository = NoOpSnapshotRepository,
                eventStore = InMemoryEventStore(),
            ),
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(LoadAggregateRouteSpec(MOCK_AGGREGATE_METADATA, MOCK_AGGREGATE_METADATA))
        val request = mockk<ServerRequest> {
            every { method() } returns HttpMethod.GET
            every { uri() } returns URI.create("http://localhost")
            every { pathVariable(RoutePaths.ID_KEY) } returns GlobalIdGenerator.generateAsString()
            every { pathVariables()[MessageRecords.TENANT_ID] } returns GlobalIdGenerator.generateAsString()
        }
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it.statusCode(), Matchers.equalTo(HttpStatus.NOT_FOUND))
            }.verifyComplete()
    }
}
