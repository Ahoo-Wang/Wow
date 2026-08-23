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
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.filter.DefaultEventStreamQueryHandler
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.event.filter.MaskingEventStreamQueryFilter
import me.ahoo.wow.query.event.filter.TailEventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.AggregationQueryValidationFilter
import me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryContext
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryFilterProvider
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.query.EventStreamQueryServiceRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryServiceRegistrar
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import reactor.core.publisher.Mono

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
    fun maskingSnapshotQueryFilter(stateDataMaskerRegistry: StateDataMaskerRegistry): SnapshotQueryFilter {
        return MaskingSnapshotQueryFilter(stateDataMaskerRegistry)
    }

    @Bean
    fun aggregationQueryValidationFilter(): SnapshotAggregationQueryFilter = AggregationQueryValidationFilter()

    @Bean
    fun maskingEventStreamQueryFilter(eventStreamMaskerRegistry: EventStreamMaskerRegistry): EventStreamQueryFilter {
        return MaskingEventStreamQueryFilter(eventStreamMaskerRegistry)
    }

    @Bean
    fun tailSnapshotQueryFilter(
        snapshotQueryServiceFactory: ObjectProvider<SnapshotQueryServiceFactory>,
    ): TailSnapshotQueryFilter<Any> {
        return TailSnapshotQueryFilter(snapshotQueryServiceFactory.getObject())
    }

    @Bean
    fun tailEventStreamQueryFilter(
        eventStreamQueryServiceFactory: ObjectProvider<EventStreamQueryServiceFactory>,
    ): TailEventStreamQueryFilter {
        return TailEventStreamQueryFilter(eventStreamQueryServiceFactory.getObject())
    }

    @Bean
    fun snapshotQueryFilterChain(
        filters: List<Filter<QueryContext<*, *>>>
    ): FilterChain<QueryContext<*, *>> {
        return FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(filters)
            .filterCondition(SnapshotQueryHandler::class)
            .build()
    }

    @Bean
    fun snapshotAggregationQueryFilterChain(
        aggregationFilters: List<SnapshotAggregationQueryFilter>,
        queryFilters: List<Filter<QueryContext<*, *>>>,
    ): FilterChain<SnapshotAggregationQueryContext> {
        val snapshotQueryFilters = queryFilters.filter {
            me.ahoo.wow.filter.TypedFilterCondition(SnapshotQueryHandler::class).matches(it)
        }
        val unsupportedFilters = snapshotQueryFilters.filterNot {
            it is SnapshotAggregationQueryFilterProvider
        }
        if (unsupportedFilters.isNotEmpty()) {
            val names = unsupportedFilters.joinToString { it.javaClass.name }
            return FilterChain {
                Mono.error(
                    UnsupportedOperationException(
                        "Snapshot aggregation is disabled because query filters do not provide aggregation policy: [$names].",
                    ),
                )
            }
        }
        val inheritedFilters = snapshotQueryFilters.map {
            (it as SnapshotAggregationQueryFilterProvider).createSnapshotAggregationQueryFilter()
        }
        return FilterChainBuilder<SnapshotAggregationQueryContext>()
            .addFilters(aggregationFilters + inheritedFilters)
            .build()
    }

    @Bean
    fun eventStreamQueryFilterChain(
        filters: List<Filter<QueryContext<*, *>>>
    ): FilterChain<QueryContext<*, *>> {
        return FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(filters)
            .filterCondition(EventStreamQueryHandler::class)
            .build()
    }

    @Bean("snapshotQueryErrorHandler")
    @ConditionalOnMissingBean(name = ["snapshotQueryErrorHandler"])
    fun snapshotQueryErrorHandler(): ErrorHandler<QueryContext<*, *>> {
        return LogErrorHandler()
    }

    @Bean("eventStreamQueryErrorHandler")
    @ConditionalOnMissingBean(name = ["eventStreamQueryErrorHandler"])
    fun eventStreamQueryErrorHandler(): ErrorHandler<QueryContext<*, *>> {
        return LogErrorHandler()
    }

    @Bean
    fun snapshotQueryHandler(
        @Qualifier("snapshotQueryFilterChain") chain: FilterChain<QueryContext<*, *>>,
        @Qualifier("snapshotQueryErrorHandler") queryErrorHandler: ErrorHandler<QueryContext<*, *>>,
        @Qualifier("snapshotAggregationQueryFilterChain")
        aggregationChain: FilterChain<SnapshotAggregationQueryContext>,
    ): SnapshotQueryHandler {
        return DefaultSnapshotQueryHandler(chain, queryErrorHandler, aggregationChain)
    }

    @Bean
    fun eventStreamQueryHandler(
        @Qualifier("eventStreamQueryFilterChain") chain: FilterChain<QueryContext<*, *>>,
        @Qualifier("eventStreamQueryErrorHandler") queryErrorHandler: ErrorHandler<QueryContext<*, *>>
    ): EventStreamQueryHandler {
        return DefaultEventStreamQueryHandler(chain, queryErrorHandler)
    }

    @Bean("noOpSnapshotQueryServiceFactory")
    @ConditionalOnMissingBean(SnapshotQueryServiceFactory::class)
    fun unavailableSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return UnavailableSnapshotQueryServiceFactory
    }

    @Bean("noOpEventStreamQueryServiceFactory")
    @ConditionalOnMissingBean(EventStreamQueryServiceFactory::class)
    fun unavailableEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        return UnavailableEventStreamQueryServiceFactory
    }

    @Deprecated("NoOp query services must be configured explicitly.")
    fun noOpSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory = NoOpSnapshotQueryServiceFactory

    @Deprecated("NoOp query services must be configured explicitly.")
    fun noOpEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory = NoOpEventStreamQueryServiceFactory
}
