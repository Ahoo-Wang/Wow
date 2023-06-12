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

package me.ahoo.wow.spring.boot.starter.event

import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.event.LocalFirstDomainEventBus
import me.ahoo.wow.event.NoOpDomainEventBus
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
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
@EnableConfigurationProperties(EventProperties::class)
class EventAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
        EventProperties.BUS_TYPE,
        havingValue = BusProperties.Type.IN_MEMORY_NAME,
    )
    fun inMemoryDomainEventBus(): LocalDomainEventBus {
        return InMemoryDomainEventBus()
    }

    @Bean
    @ConditionalOnProperty(
        EventProperties.BUS_TYPE,
        havingValue = BusProperties.Type.NO_OP_NAME,
    )
    fun ooOpDomainEventBus(): DomainEventBus {
        return NoOpDomainEventBus
    }

    @Bean
    fun domainEventCompensator(
        eventStore: EventStore,
        eventBus: DomainEventBus
    ): DomainEventCompensator {
        return DomainEventCompensator(eventStore, eventBus)
    }

    @Bean
    @ConditionalOnMissingBean(LocalDomainEventBus::class)
    @ConditionalOnBean(value = [DistributedDomainEventBus::class])
    @ConditionalOnEventLocalFirstEnabled
    fun localDomainEventBus(): LocalDomainEventBus {
        return InMemoryDomainEventBus()
    }

    @Bean
    @Primary
    @ConditionalOnBean(value = [LocalDomainEventBus::class, DistributedDomainEventBus::class])
    @ConditionalOnEventLocalFirstEnabled
    fun localFirstDomainEventBus(
        localBus: LocalDomainEventBus,
        distributedBus: DistributedDomainEventBus
    ): LocalFirstDomainEventBus {
        return LocalFirstDomainEventBus(distributedBus, localBus)
    }
}
