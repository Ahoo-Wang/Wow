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

import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.MaskingResultPolicy
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.query.EventStreamQueryServiceRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryServiceRegistrar
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
@Import(SnapshotQueryServiceRegistrar::class, EventStreamQueryServiceRegistrar::class)
@ConditionalOnWowEnabled
class QueryAutoConfiguration {

    @Bean
    @Deprecated("Use ResultPolicy for query result masking.")
    fun stateDataMaskerRegistry(
        maskers: List<StateDynamicDocumentMasker>
    ): StateDataMaskerRegistry {
        val maskerRegistry = StateDataMaskerRegistry()
        maskers.forEach {
            maskerRegistry.register(it)
        }
        return maskerRegistry
    }

    @Bean
    @Deprecated("Use ResultPolicy for query result masking.")
    fun eventStreamMaskerRegistry(
        maskers: List<EventStreamDynamicDocumentMasker>
    ): EventStreamMaskerRegistry {
        val maskerRegistry = EventStreamMaskerRegistry()
        maskers.forEach {
            maskerRegistry.register(it)
        }
        return maskerRegistry
    }

    @Bean
    fun maskingResultPolicy(
        stateDataMaskerRegistry: StateDataMaskerRegistry,
        eventStreamMaskerRegistry: EventStreamMaskerRegistry
    ): MaskingResultPolicy {
        return MaskingResultPolicy(stateDataMaskerRegistry, eventStreamMaskerRegistry)
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotQueryServiceFactory::class)
    fun noOpSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return NoOpSnapshotQueryServiceFactory
    }

    @Bean
    @ConditionalOnMissingBean(EventStreamQueryServiceFactory::class)
    fun noOpEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        return NoOpEventStreamQueryServiceFactory
    }
}
