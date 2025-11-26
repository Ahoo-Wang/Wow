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

package me.ahoo.wow.spring.boot.starter.compensation

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.compensation.core.CompensationEventProcessor
import me.ahoo.wow.compensation.core.DomainEventCompensationFilter
import me.ahoo.wow.compensation.core.StateEventCompensationFilter
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnCompensationEnabled
@EnableConfigurationProperties(CompensationProperties::class)
class CompensationAutoConfiguration {

    @Bean
    fun eventCompensateSupporter(
        domainEventCompensator: DomainEventCompensator,
        stateEventCompensator: StateEventCompensator
    ): EventCompensateSupporter {
        return EventCompensateSupporter(domainEventCompensator, stateEventCompensator)
    }

    @Bean
    fun domainEventCompensationFilter(commandGateway: CommandGateway): DomainEventCompensationFilter {
        return DomainEventCompensationFilter(commandGateway)
    }

    @Bean
    fun stateEventCompensationFilter(commandGateway: CommandGateway): StateEventCompensationFilter {
        return StateEventCompensationFilter(commandGateway)
    }

    @Bean
    fun compensationEventProcessor(
        eventCompensateSupporter: EventCompensateSupporter,
    ): CompensationEventProcessor {
        return CompensationEventProcessor(eventCompensateSupporter)
    }
}
