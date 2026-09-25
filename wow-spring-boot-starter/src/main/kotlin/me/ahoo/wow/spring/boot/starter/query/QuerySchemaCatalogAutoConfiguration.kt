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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.metrics.isMetricsEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.lang.Nullable
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@AutoConfiguration(after = [QueryAutoConfiguration::class])
@ConditionalOnWowEnabled
@EnableConfigurationProperties(QueryProperties::class)
class QuerySchemaCatalogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun querySchemaCatalog(
        snapshotQueryBackendFactories: ObjectProvider<SnapshotQueryBackendFactory>,
        eventStreamQueryBackendFactories: ObjectProvider<EventStreamQueryBackendFactory>,
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
    ): QuerySchemaCatalog {
        val snapshotQueryBackendFactory = snapshotQueryBackendFactories.getIfAvailable {
            UnavailableSnapshotQueryBackendFactory
        }
        val eventStreamQueryBackendFactory =
            eventStreamQueryBackendFactories.getIfAvailable { UnavailableEventStreamQueryBackendFactory }
        return QuerySchemaCatalog(
            MetadataSearcher.namedAggregateType.keys.flatMap { namedAggregate ->
                listOf(
                    QuerySchemaCatalog.Entry(
                        namedAggregate,
                        QueryModel.SNAPSHOT,
                        snapshotQueryBackendFactory.create(namedAggregate).schemaProvider,
                    ),
                    QuerySchemaCatalog.Entry(
                        namedAggregate,
                        QueryModel.EVENT_STREAM,
                        eventStreamQueryBackendFactory.create(namedAggregate).schemaProvider,
                    ),
                )
            },
            meterRegistry.getIfAvailable()?.takeIf { environment.isMetricsEnabled() },
        )
    }

    @Bean
    fun querySchemaRevalidation(
        catalog: QuerySchemaCatalog,
        queryProperties: QueryProperties
    ): QuerySchemaRevalidation =
        QuerySchemaRevalidation(catalog, queryProperties.schema.revalidateInterval)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.actuate.endpoint.annotation.Endpoint"])
    class EndpointConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun querySchemaEndpoint(catalog: QuerySchemaCatalog): QuerySchemaEndpoint = QuerySchemaEndpoint(catalog)
    }
}

/** Revalidates the catalog every [interval] while the application runs; a zero interval disables it. */
class QuerySchemaRevalidation(
    private val catalog: QuerySchemaCatalog,
    private val interval: java.time.Duration,
) : SmartLifecycle {
    @Volatile
    private var running: Disposable? = null

    override fun start() {
        if (interval.isZero || interval.isNegative) return
        running = Flux.interval(interval, interval)
            .onBackpressureDrop()
            .concatMap({ catalog.revalidate().then() }, 0)
            .subscribe()
    }

    override fun stop() {
        running?.dispose()
        running = null
    }

    override fun isRunning(): Boolean = running?.isDisposed == false
}

/**
 * `wowQuerySchema`: per-instance query schema versions, and a manual revalidation. Replicas can differ while they
 * revalidate; compare their versions here.
 */
@Endpoint(id = "wowQuerySchema")
class QuerySchemaEndpoint(private val catalog: QuerySchemaCatalog) {
    @ReadOperation
    fun versions(): Mono<List<QuerySchemaCatalog.Status>> = catalog.versions().collectList()

    /** Reloads every schema, or only [aggregate]'s (`context.aggregate`, alias form). */
    @WriteOperation
    fun revalidate(@Nullable aggregate: String?): Mono<List<QuerySchemaCatalog.Status>> =
        catalog.revalidate(aggregate).collectList()
}
