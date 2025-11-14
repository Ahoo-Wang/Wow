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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.AggregateVerifier.aggregateVerifier
import me.ahoo.wow.test.dsl.AbstractDynamicTestBuilder

/**
 * Default implementation of the AggregateDsl interface for testing aggregate behavior.
 *
 * This class provides the core functionality for setting up aggregate tests by configuring
 * the testing environment and delegating to the appropriate DSL stages. It manages the
 * service provider and coordinates the creation of GivenDsl instances for test scenarios.
 *
 * @param C the command type that triggers aggregate operations
 * @param S the state type maintained by the aggregate
 * @property commandAggregateType the class of the command aggregate being tested, used to create verifiers
 */
class DefaultAggregateDsl<C : Any, S : Any>(
    private val commandAggregateType: Class<C>
) : AbstractDynamicTestBuilder(),
    AggregateDsl<S> {
    /**
     * Public service provider instance that can be configured with test-specific services.
     * This provider is copied to the test service provider during test execution.
     */
    override val publicServiceProvider: ServiceProvider = SimpleServiceProvider()

    /**
     * Sets up a test scenario for the specified aggregate.
     *
     * This method initializes the aggregate verifier with the provided configuration and
     * executes the test block using a GivenDsl instance. The resulting dynamic test nodes
     * are collected for JUnit 5 dynamic test execution.
     *
     * @param aggregateId the unique identifier for the aggregate instance
     * @param tenantId the tenant identifier for multi-tenant scenarios
     * @param stateAggregateFactory factory for creating state aggregate instances
     * @param eventStore the event store for retrieving and storing domain events
     * @param serviceProvider the service provider containing dependencies for the test
     * @param block the test scenario definition using GivenDsl
     */
    override fun on(
        aggregateId: String,
        tenantId: String,
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        serviceProvider: ServiceProvider,
        block: GivenDsl<S>.() -> Unit
    ) {
        publicServiceProvider.copyTo(serviceProvider)
        val givenStage = commandAggregateType.aggregateVerifier<C, S>(
            aggregateId = aggregateId,
            tenantId = tenantId,
            stateAggregateFactory = stateAggregateFactory,
            eventStore = eventStore,
            serviceProvider = serviceProvider,
        )
        val givenDsl = DefaultGivenDsl(givenStage)
        block(givenDsl)
        dynamicNodes.addAll(givenDsl.dynamicNodes)
    }
}
