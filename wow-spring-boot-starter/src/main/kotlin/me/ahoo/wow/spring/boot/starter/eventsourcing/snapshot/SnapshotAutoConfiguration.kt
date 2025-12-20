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

package me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.DefaultSnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.SnapshotFunctionFilter
import me.ahoo.wow.eventsourcing.snapshot.SnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration
import me.ahoo.wow.spring.boot.starter.WowProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.command.SnapshotDispatcherLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@Suppress("TooManyFunctions")
@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnSnapshotEnabled
@EnableConfigurationProperties(SnapshotProperties::class)
class SnapshotAutoConfiguration(
    private val wowProperties: WowProperties,
    private val snapshotProperties: SnapshotProperties
) {

    @Bean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STORAGE],
        havingValue = StorageType.IN_MEMORY_NAME,
    )
    fun inMemorySnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository()
    }

    @Bean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STRATEGY],
        matchIfMissing = true,
        havingValue = Strategy.ALL_NAME,
    )
    fun simpleSnapshotStrategy(
        snapshotRepository: SnapshotRepository
    ): SnapshotStrategy {
        return SimpleSnapshotStrategy(
            snapshotRepository = snapshotRepository,
        )
    }

    @Bean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STRATEGY],
        havingValue = Strategy.VERSION_OFFSET_NAME,
    )
    fun versionOffsetSnapshotStrategy(
        snapshotRepository: SnapshotRepository
    ): SnapshotStrategy {
        return VersionOffsetSnapshotStrategy(
            versionOffset = snapshotProperties.versionOffset,
            snapshotRepository = snapshotRepository
        )
    }

    @Bean
    fun snapshotFunctionFilter(
        snapshotStrategy: SnapshotStrategy
    ): SnapshotFunctionFilter {
        return SnapshotFunctionFilter(
            snapshotStrategy = snapshotStrategy,
        )
    }

    @Bean
    fun snapshotFilterChain(filters: List<ExchangeFilter<StateEventExchange<*>>>): FilterChain<StateEventExchange<*>> {
        return FilterChainBuilder<StateEventExchange<*>>()
            .addFilters(filters)
            .filterCondition(SnapshotDispatcher::class)
            .build()
    }

    @Bean
    fun snapshotHandler(
        @Qualifier("snapshotFilterChain") chain: FilterChain<StateEventExchange<*>>
    ): SnapshotHandler {
        return DefaultSnapshotHandler(chain)
    }

    @Bean
    fun snapshotDispatcher(
        @Qualifier(WowAutoConfiguration.WOW_CURRENT_BOUNDED_CONTEXT)
        namedBoundedContext: NamedBoundedContext,
        snapshotHandler: SnapshotHandler,
        stateEventBus: StateEventBus
    ): SnapshotDispatcher {
        return SnapshotDispatcher(
            name = "${namedBoundedContext.contextName}.${SnapshotDispatcher::class.simpleName}",
            snapshotHandler = snapshotHandler,
            stateEventBus = stateEventBus,
        )
    }

    @Bean
    fun snapshotDispatcherLauncher(snapshotDispatcher: SnapshotDispatcher): SnapshotDispatcherLauncher {
        return SnapshotDispatcherLauncher(snapshotDispatcher, wowProperties.shutdownTimeout)
    }
}
