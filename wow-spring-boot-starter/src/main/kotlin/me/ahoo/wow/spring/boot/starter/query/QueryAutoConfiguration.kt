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
package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.event.filter.MaskingEventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.EventStreamObjectNodeMasker
import me.ahoo.wow.query.mask.EventStreamObjectNodeMaskerRegistry
import me.ahoo.wow.query.mask.StateObjectNodeMasker
import me.ahoo.wow.query.mask.StateObjectNodeMaskerRegistry
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Query AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@Import(SnapshotQueryGatewayRegistrar::class, EventStreamQueryGatewayRegistrar::class)
@ConditionalOnWowEnabled
class QueryAutoConfiguration {

    @Bean
    fun stateObjectNodeMaskerRegistry(maskers: List<StateObjectNodeMasker>): StateObjectNodeMaskerRegistry {
        val maskerRegistry = StateObjectNodeMaskerRegistry()
        maskers.forEach(maskerRegistry::register)
        return maskerRegistry
    }

    @Bean
    fun eventStreamObjectNodeMaskerRegistry(
        maskers: List<EventStreamObjectNodeMasker>
    ): EventStreamObjectNodeMaskerRegistry {
        val maskerRegistry = EventStreamObjectNodeMaskerRegistry()
        maskers.forEach(maskerRegistry::register)
        return maskerRegistry
    }

    @Bean
    fun maskingSnapshotQueryFilter(stateObjectNodeMaskerRegistry: StateObjectNodeMaskerRegistry): SnapshotQueryFilter =
        MaskingSnapshotQueryFilter(stateObjectNodeMaskerRegistry)

    @Bean
    fun maskingEventStreamQueryFilter(
        eventStreamObjectNodeMaskerRegistry: EventStreamObjectNodeMaskerRegistry
    ): EventStreamQueryFilter = MaskingEventStreamQueryFilter(eventStreamObjectNodeMaskerRegistry)

    @Bean("snapshotQueryErrorHandler")
    @ConditionalOnMissingBean(name = ["snapshotQueryErrorHandler"])
    fun snapshotQueryErrorHandler(): ErrorHandler<QueryContext<*, *>> = LogErrorHandler()

    @Bean("eventStreamQueryErrorHandler")
    @ConditionalOnMissingBean(name = ["eventStreamQueryErrorHandler"])
    fun eventStreamQueryErrorHandler(): ErrorHandler<QueryContext<*, *>> = LogErrorHandler()

    @Bean("noOpSnapshotQueryBackendFactory")
    @ConditionalOnMissingBean(SnapshotQueryBackendFactory::class)
    fun unavailableSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory =
        UnavailableSnapshotQueryBackendFactory

    @Bean("noOpEventStreamQueryBackendFactory")
    @ConditionalOnMissingBean(EventStreamQueryBackendFactory::class)
    fun unavailableEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory =
        UnavailableEventStreamQueryBackendFactory
}
