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

package me.ahoo.wow.tck.projection

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.projection.DefaultProjectionHandler
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.projection.ProjectionFunctionFilter
import me.ahoo.wow.projection.ProjectionFunctionRegistrar
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

abstract class ProjectionDispatcherSpec {
    private val handlerRegistrar = ProjectionFunctionRegistrar()
    protected val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
    protected val domainEventBus: DomainEventBus
    protected val stateEventBus: StateEventBus

    init {
        domainEventBus = createEventBus()
        stateEventBus = createStateEventBus()
        handlerRegistrar.registerProcessor(MockProjector())
    }

    protected fun createStateEventBus(): StateEventBus {
        return InMemoryStateEventBus()
    }

    protected fun createEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    @Test
    fun run() {
        val chain = FilterChainBuilder<DomainEventExchange<*>>()
            .addFilter(ProjectionFunctionFilter(SimpleServiceProvider()))
            .build()
        val projectionDispatcher =
            ProjectionDispatcher(
                name = "wow-tck.ProjectionDispatcher",
                domainEventBus = domainEventBus,
                stateEventBus = stateEventBus,
                functionRegistrar = handlerRegistrar,
                eventHandler = DefaultProjectionHandler(chain).metrizable(),
            )
        projectionDispatcher.start()

        val eventStream = MockAggregateCreated(generateGlobalId()).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateMetadata.aggregateId(generateGlobalId())),
            aggregateVersion = 1,
        )
        domainEventBus.send(eventStream).block()

        projectionDispatcher.close()
    }
}
