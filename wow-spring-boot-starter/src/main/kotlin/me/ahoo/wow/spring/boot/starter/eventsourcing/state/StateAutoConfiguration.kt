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

package me.ahoo.wow.spring.boot.starter.eventsourcing.state

import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalFirstStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.messaging.LocalFirstDistributedCopies
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowRuntimeComponentOrder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.Order

@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(StateProperties::class)
class StateAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        StateProperties.BUS_TYPE,
        havingValue = BusType.IN_MEMORY_NAME,
    )
    fun inMemoryStateEventBus(): StateEventBus {
        return InMemoryStateEventBus()
    }

    @Bean
    fun stateEventCompensator(
        stateAggregateFactory: StateAggregateFactory,
        eventStore: EventStore,
        stateEventBus: StateEventBus
    ): StateEventCompensator {
        return StateEventCompensator(stateAggregateFactory, eventStore, stateEventBus)
    }

    @Bean
    @ConditionalOnMissingBean(LocalStateEventBus::class)
    @ConditionalOnBean(value = [DistributedStateEventBus::class])
    @ConditionalOnStateEventLocalFirstEnabled
    fun localStateEventBus(): LocalStateEventBus {
        return InMemoryStateEventBus()
    }

    @Bean
    @Primary
    @ConditionalOnBean(value = [LocalStateEventBus::class, DistributedStateEventBus::class])
    @ConditionalOnStateEventLocalFirstEnabled
    fun localFirstStateEventBus(
        localBus: LocalStateEventBus,
        distributedBus: DistributedStateEventBus,
        @Qualifier(LOCAL_FIRST_COPIES) distributedCopies: LocalFirstDistributedCopies,
    ): LocalFirstStateEventBus {
        return LocalFirstStateEventBus(distributedBus, localBus, distributedCopies)
    }

    /**
     * The distributed copies of [LocalFirstStateEventBus], its own bean so a decorated bus never hides it: a runtime component
     * stopped after the dispatchers and before the transports.
     */
    @Bean(LOCAL_FIRST_COPIES)
    @ConditionalOnBean(value = [DistributedStateEventBus::class])
    @ConditionalOnStateEventLocalFirstEnabled
    @Order(WowRuntimeComponentOrder.LOCAL_FIRST_COPIES)
    fun localFirstStateEventBusDistributedCopies(
        stateProperties: StateProperties,
        metrics: ObjectProvider<WowMetrics>,
    ): LocalFirstDistributedCopies =
        LocalFirstDistributedCopies(
            name = "LocalFirstStateEventBus",
            metrics = metrics.getIfAvailable { WowMetrics.NONE },
            backlogHighWaterMark = stateProperties.bus.localFirst.backlogHighWaterMark,
        )

    companion object {
        const val LOCAL_FIRST_COPIES = "localFirstStateEventBusDistributedCopies"
    }
}
