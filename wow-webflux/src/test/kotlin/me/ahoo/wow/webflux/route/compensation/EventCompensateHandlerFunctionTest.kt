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

package me.ahoo.wow.webflux.route.compensation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SNAPSHOT_FUNCTION
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.openapi.aggregate.event.EventCompensateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.DefaultRequestExceptionHandler
import me.ahoo.wow.webflux.route.event.EventCompensateHandlerFunctionFactory
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class EventCompensateHandlerFunctionTest {

    @Test
    fun handle() {
        val eventStore = InMemoryEventStore()
        val handlerFunction = EventCompensateHandlerFunctionFactory(
            eventCompensateSupporter = EventCompensateSupporter(
                domainEventCompensator = DomainEventCompensator(
                    eventStore = eventStore,
                    eventBus = InMemoryDomainEventBus(),
                ),
                stateEventCompensator = StateEventCompensator(
                    stateAggregateFactory = ConstructorStateAggregateFactory,
                    eventStore = eventStore,
                    stateEventBus = InMemoryStateEventBus(),
                )
            ),
            exceptionHandler = DefaultRequestExceptionHandler,
        ).create(
            EventCompensateRouteSpec(
                currentContext = MOCK_AGGREGATE_METADATA,
                aggregateRouteMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata(),
                componentContext = OpenAPIComponentContext.default()
            )
        )

        val request = MockServerRequest.builder()
            .pathVariable(MessageRecords.ID, generateGlobalId())
            .pathVariable(MessageRecords.VERSION, "1")
            .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
            .body(
                CompensationTarget(
                    function = SNAPSHOT_FUNCTION
                ).toMono()
            )
        handlerFunction.handle(request)
            .test()
            .consumeNextWith {
                it.statusCode().assert().isEqualTo(HttpStatus.OK)
            }.verifyComplete()
    }
}
