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
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.projection.DefaultProjectionHandler
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.projection.ProjectionFunctionFilter
import me.ahoo.wow.projection.ProjectionFunctionRegistrar
import me.ahoo.wow.tck.modeling.AggregateCreated
import me.ahoo.wow.tck.modeling.MockAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test

abstract class ProjectionDispatcherSpec {
    private val handlerRegistrar = ProjectionFunctionRegistrar()
    protected val aggregateMetadata = aggregateMetadata<MockAggregate, MockAggregate>()
    protected val domainEventBus: DomainEventBus

    init {
        domainEventBus = createEventBus()
        handlerRegistrar.registerProcessor(MockProjector())
    }

    protected fun createEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    @Test
    fun run() {
        val chain = FilterChainBuilder<DomainEventExchange<Any>>()
            .addFilter(ProjectionFunctionFilter(SimpleServiceProvider()))
            .build()
        val projectionDispatcher =
            ProjectionDispatcher(
                name = "test-spec.ProjectionDispatcher",
                domainEventBus = domainEventBus,
                functionRegistrar = handlerRegistrar,
                eventHandler = DefaultProjectionHandler(chain).metrizable(),
            )
        projectionDispatcher.run()

        val eventStream = AggregateCreated(GlobalIdGenerator.generateAsString()).asDomainEventStream(
            command = GivenInitializationCommand(aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())),
            aggregateVersion = 1,
        )
        domainEventBus.send(eventStream).block()

        projectionDispatcher.close()
    }
}
