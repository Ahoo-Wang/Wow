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
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.AggregateIdScanner.Companion.FIRST_ID
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.BatchComponent
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.event.state.ResendStateEventFunction
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.test.test

class ResendStateEventHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = ResendStateEventFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            snapshotRepository = NoOpSnapshotRepository,
            stateEventCompensator = StateEventCompensator(
                stateAggregateFactory = ConstructorStateAggregateFactory,
                eventStore = eventStore,
                stateEventBus = InMemoryStateEventBus(),
            ),
            exceptionHandler = DefaultRequestExceptionHandler,
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
}
