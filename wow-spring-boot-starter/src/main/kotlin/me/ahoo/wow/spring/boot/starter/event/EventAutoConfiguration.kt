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

import me.ahoo.wow.event.DefaultEventCompensator
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.EventCompensator
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.MessageBusType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@EnableConfigurationProperties(EventProperties::class)
class EventAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
        EventProperties.Bus.TYPE,
        havingValue = MessageBusType.IN_MEMORY_NAME,
    )
    fun inMemoryDomainEventBus(): DomainEventBus {
        return InMemoryDomainEventBus()
    }

    @Bean
    fun eventCompensator(
        eventStore: EventStore,
        eventBus: DomainEventBus,
    ): EventCompensator {
        return DefaultEventCompensator(eventStore, eventBus)
    }
}
