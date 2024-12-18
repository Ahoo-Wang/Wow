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
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryContext
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.query.snapshot.filter.TailSnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.query.EventStreamQueryServiceRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryServiceRegistrar
import me.ahoo.wow.spring.query.getOrNoOp
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
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
    fun stateDataMaskerRegistry(
        maskers: List<StateDynamicDocumentMasker>
    ): StateDataMaskerRegistry {
        val stateDataMaskerRegistry = StateDataMaskerRegistry()
        maskers.forEach {
            stateDataMaskerRegistry.register(it)
        }
        return stateDataMaskerRegistry
    }

    @Bean
    fun maskingSnapshotQueryFilter(stateDataMaskerRegistry: StateDataMaskerRegistry): SnapshotQueryFilter {
        return MaskingSnapshotQueryFilter(stateDataMaskerRegistry)
    }

    @Bean
    fun tailSnapshotQueryFilter(
        snapshotQueryServiceFactory: ObjectProvider<SnapshotQueryServiceFactory>,
    ): TailSnapshotQueryFilter<Any> {
        return TailSnapshotQueryFilter(snapshotQueryServiceFactory.getOrNoOp())
    }

    @Bean
    fun snapshotQueryFilterChain(
        filters: List<Filter<SnapshotQueryContext<*, *, *>>>
    ): FilterChain<SnapshotQueryContext<*, *, *>> {
        return FilterChainBuilder<SnapshotQueryContext<*, *, *>>()
            .addFilters(filters)
            .filterCondition(SnapshotQueryHandler::class)
            .build()
    }

    @Bean("snapshotQueryErrorHandler")
    @ConditionalOnMissingBean(name = ["snapshotQueryErrorHandler"])
    fun snapshotQueryErrorHandler(): ErrorHandler<SnapshotQueryContext<*, *, *>> {
        return LogErrorHandler()
    }

    @Bean
    fun snapshotQueryHandler(
        @Qualifier("snapshotQueryFilterChain") chain: FilterChain<SnapshotQueryContext<*, *, *>>,
        @Qualifier("snapshotQueryErrorHandler") queryErrorHandler: ErrorHandler<SnapshotQueryContext<*, *, *>>
    ): SnapshotQueryHandler {
        return DefaultSnapshotQueryHandler(chain, queryErrorHandler)
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
