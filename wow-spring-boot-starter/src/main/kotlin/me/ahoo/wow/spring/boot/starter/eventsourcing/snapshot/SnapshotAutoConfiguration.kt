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
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.SnapshotFunctionFilter
import me.ahoo.wow.eventsourcing.snapshot.SnapshotHandler
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.TimeOffsetSnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.VersionOffsetSnapshotStrategy
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.command.SnapshotDispatcherLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnSnapshotEnabled
@EnableConfigurationProperties(SnapshotProperties::class)
class SnapshotAutoConfiguration(
    private val snapshotProperties: SnapshotProperties,
) {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STORAGE],
        havingValue = SnapshotStorage.IN_MEMORY_NAME,
    )
    fun inMemorySnapshotRepository(): SnapshotRepository {
        return InMemorySnapshotRepository()
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STRATEGY],
        matchIfMissing = true,
        havingValue = Strategy.ALL_NAME,
    )
    fun snapshotStrategy(
        snapshotRepository: SnapshotRepository,
        eventStore: EventStore,
        stateAggregateFactory: StateAggregateFactory,
    ): SnapshotStrategy {
        return SimpleSnapshotStrategy(
            snapshotRepository = snapshotRepository,
            eventStore = eventStore,
            stateAggregateFactory = stateAggregateFactory,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STRATEGY],
        havingValue = Strategy.VERSION_NAME,
    )
    fun versionOffsetSnapshotStrategy(
        snapshotRepository: SnapshotRepository,
        eventStore: EventStore,
        stateAggregateFactory: StateAggregateFactory,
    ): VersionOffsetSnapshotStrategy {
        return VersionOffsetSnapshotStrategy(
            versionOffset = snapshotProperties.versionOffset,
            snapshotRepository = snapshotRepository,
            eventStore = eventStore,
            stateAggregateFactory = stateAggregateFactory,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        value = [SnapshotProperties.STRATEGY],
        havingValue = Strategy.TIME_NAME,
    )
    fun timeOffsetSnapshotStrategy(
        snapshotRepository: SnapshotRepository,
        eventStore: EventStore,
        stateAggregateFactory: StateAggregateFactory,
    ): TimeOffsetSnapshotStrategy {
        return TimeOffsetSnapshotStrategy(
            timeOffset = snapshotProperties.timeOffset.toMillis(),
            snapshotRepository = snapshotRepository,
            eventStore = eventStore,
            stateAggregateFactory = stateAggregateFactory,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun snapshotFunctionFilter(
        snapshotStrategy: SnapshotStrategy,
    ): SnapshotFunctionFilter {
        return SnapshotFunctionFilter(
            snapshotStrategy = snapshotStrategy,
        )
    }

    @Bean
    fun snapshotFilterChain(filters: List<Filter<EventStreamExchange>>): FilterChain<EventStreamExchange> {
        return FilterChainBuilder<EventStreamExchange>()
            .addFilters(filters)
            .filterCondition(SnapshotDispatcher::class)
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun snapshotHandler(
        @Qualifier("snapshotFilterChain") chain: FilterChain<EventStreamExchange>,
    ): SnapshotHandler {
        return SnapshotHandler(chain)
    }

    @Bean
    @ConditionalOnMissingBean
    fun snapshotDispatcher(
        namedBoundedContext: NamedBoundedContext,
        snapshotHandler: SnapshotHandler,
        domainEventBus: DomainEventBus,
    ): SnapshotDispatcher {
        return SnapshotDispatcher(
            name = "${namedBoundedContext.contextName}.${SnapshotDispatcher::class.simpleName}",
            snapshotHandler = snapshotHandler,
            domainEventBus = domainEventBus,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun snapshotDispatcherLauncher(snapshotDispatcher: SnapshotDispatcher): SnapshotDispatcherLauncher {
        return SnapshotDispatcherLauncher(snapshotDispatcher)
    }
}
