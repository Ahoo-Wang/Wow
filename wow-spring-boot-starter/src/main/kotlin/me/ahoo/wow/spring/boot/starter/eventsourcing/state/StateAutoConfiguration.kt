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
import me.ahoo.wow.eventsourcing.state.SendStateEventFilter
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.BusProperties
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(StateProperties::class)
class StateAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        StateProperties.BUS_TYPE,
        havingValue = BusProperties.Type.IN_MEMORY_NAME,
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
        distributedBus: DistributedStateEventBus
    ): LocalFirstStateEventBus {
        return LocalFirstStateEventBus(distributedBus, localBus)
    }

    @Bean
    fun sendStateEventFilter(stateEventBus: StateEventBus): SendStateEventFilter {
        return SendStateEventFilter(stateEventBus)
    }
}
