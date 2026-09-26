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

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.query.CompositeQueryObserver
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryMetricsObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.filter.AbacQueryOptions
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.metrics.isMetricsEnabled
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar.Companion.EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar.Companion.SNAPSHOT_QUERY_OBSERVER_BEAN_NAME
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

/**
 * Query AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [QuerySchemaAutoConfiguration::class])
@Import(SnapshotQueryGatewayRegistrar::class, EventStreamQueryGatewayRegistrar::class)
@ConditionalOnWowEnabled
@EnableConfigurationProperties(QueryProperties::class)
class QueryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun queryEntryPolicy(queryProperties: QueryProperties): QueryEntryPolicy =
        QueryEntryPolicy(
            requireExplicitEntry = queryProperties.requireExplicitEntry,
            requireAuthenticatedScope = queryProperties.requireAuthenticatedScope,
            http = queryProperties.http.toBudget(),
        )

    /** The options an application's `AbacQueryPolicy` takes in its constructor. */
    @Bean
    @ConditionalOnMissingBean
    fun abacQueryOptions(queryProperties: QueryProperties): AbacQueryOptions = queryProperties.abac.toOptions()

    @Bean(SNAPSHOT_QUERY_OBSERVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [SNAPSHOT_QUERY_OBSERVER_BEAN_NAME])
    fun snapshotQueryObserver(meterRegistry: ObjectProvider<MeterRegistry>, environment: Environment): QueryObserver =
        queryObserver(meterRegistry, environment)

    @Bean(EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME])
    fun eventStreamQueryObserver(
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment
    ): QueryObserver =
        queryObserver(meterRegistry, environment)

    /** Logs failures and, with `wow.metrics.enabled` and a registry, publishes `wow.query` meters. */
    private fun queryObserver(meterRegistry: ObjectProvider<MeterRegistry>, environment: Environment): QueryObserver {
        val registry = meterRegistry.getIfAvailable()?.takeIf { environment.isMetricsEnabled() }
            ?: return QueryLogObserver()
        return CompositeQueryObserver(listOf(QueryLogObserver(), QueryMetricsObserver(registry)))
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotQueryBackendFactory::class)
    fun unavailableSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory =
        UnavailableSnapshotQueryBackendFactory

    @Bean
    @ConditionalOnMissingBean(EventStreamQueryBackendFactory::class)
    fun unavailableEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory =
        UnavailableEventStreamQueryBackendFactory
}
