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

class DefaultAggregateDsl<C : Any, S : Any>(private val commandAggregateType: Class<C>) :
    AggregateDsl<S>, AbstractDynamicTestBuilder() {
    override val publicServiceProvider: ServiceProvider = SimpleServiceProvider()
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
            serviceProvider = serviceProvider
        )
        val givenDsl = DefaultGivenDsl(givenStage)
        block(givenDsl)
        dynamicNodes.addAll(givenDsl.dynamicNodes)
    }
}
