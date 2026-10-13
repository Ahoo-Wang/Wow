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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.http.query.EventQueryGateway
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryLogObserver
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.runtime.node.ServiceNode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.ClassUtils
import javax.sql.DataSource

/**
 * The query plane (decisions D126–D131, D150, D151), active when wow-query is on the classpath (wow-http brings it)
 * and `wow.query.enabled` is not off. Over the node's Manifest it builds:
 *
 * - a [SnapshotQueryBackend] chosen by the classpath as the stores are (decision D110): the application's own
 *   [QueryBackendFactory] bean when it has one, else PostgreSQL or MongoDB when the node runs on it; on the memory
 *   backend (or the application's own [me.ahoo.wow.runtime.node.BackendFactory]) without one, the snapshots are not
 *   queried and their routes are not served;
 * - a [QuerySchemaCatalog] compiling each aggregate's snapshot model from the Manifest
 *   ([ManifestStateSchemas], with the application's [MaskStrategyResolver] and [QuerySchemaSource] beans) under
 *   `wow.query.sensitivity`;
 * - a gateway per aggregate type the node hosts: [DefaultQueryGateway] for its snapshots, under the
 *   [ScopeQueryPolicy] (tenant by [HostQueryTenants], D116), the [AbacQueryPolicy] (`wow.query.abac`) and the
 *   application's [QueryPolicy] and [QueryFilter] beans, observed by [QueryLogObserver] and the [QueryObserver] beans;
 *   and [EventQueryGateway] for its events, over the context's log;
 * - [HttpQueries], which the public routes serve, and [QueryIndexesLifecycle], which manages the `x-wow-index`
 *   indexes at startup as `wow.query.index.ddl` says.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [QUERY_MODULE])
@ConditionalOnProperty(prefix = QueryProperties.PREFIX, name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(QueryProperties::class)
class QueryAutoConfiguration {
    /** The node's queries, once it has a [ServiceNode]. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ServiceNode::class)
    class NodeQueries {
        @Bean
        @ConditionalOnMissingBean
        fun wowQueryEntryPolicy(properties: QueryProperties): QueryEntryPolicy = properties.entryPolicy()

        @Bean
        @ConditionalOnMissingBean
        fun wowQueryTenantPolicy(node: ServiceNode): QueryTenantPolicy = HostQueryTenants(node.gateway.tenants)

        @Bean
        @ConditionalOnMissingBean
        fun wowSnapshotQueryBackend(
            wow: WowProperties,
            properties: QueryProperties,
            backends: HostBackends,
            beanFactory: ConfigurableListableBeanFactory,
            factories: ObjectProvider<QueryBackendFactory<*>>,
        ): SnapshotQueryBackend {
            factories.ifAvailable?.let { return SnapshotQueryBackend(it) }
            val ddl = properties.index.ddl
            return when (backends.kind) {
                BackendKind.POSTGRES ->
                    PostgresQueries.open(beanFactory.getBean(DataSource::class.java), backends, ddl)
                BackendKind.MONGO -> MongoQueries.open(
                    beanFactory.getBean(ClassUtils.forName(MONGO_CLIENT, beanFactory.beanClassLoader)),
                    wow.mongo.database,
                    ddl,
                )
                else -> {
                    logger.info {
                        "No snapshot query backend serves this node's backend (${backends.kind ?: "the application's"}): " +
                            "its snapshots are not queried; give it a QueryBackendFactory bean to query them."
                    }
                    SnapshotQueryBackend.NONE
                }
            }
        }

        @Bean
        @ConditionalOnMissingBean
        fun wowQuerySchemaCatalog(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            strategies: ObjectProvider<MaskStrategyResolver>,
            sources: ObjectProvider<QuerySchemaSource>,
        ): QuerySchemaCatalog {
            val manifestSource = ManifestStateSchemas(manifest, strategies.getIfAvailable { MaskStrategyResolver.NONE })
            return QuerySchemaCatalog(
                storages = QueryBackendStorages(backend.factory),
                compiler = QueryModelCompiler.of(
                    listOf(manifestSource) + sources.orderedStream().toList(),
                    properties.sensitivity.policy(),
                ),
                aggregates = snapshotTypes(manifest, node.contexts.keys),
            )
        }

        @Bean
        @ConditionalOnMissingBean
        @Suppress("LongParameterList") // One parameter per part of a gateway, each a bean the application may give.
        fun wowHttpQueries(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            catalog: QuerySchemaCatalog,
            entryPolicy: QueryEntryPolicy,
            tenants: QueryTenantPolicy,
            policies: ObjectProvider<QueryPolicy>,
            filters: ObjectProvider<QueryFilter>,
            observers: ObjectProvider<QueryObserver>,
        ): HttpQueries {
            val contexts = node.contexts.keys
            val observer = QueryObserver.of(listOf(QueryLogObserver()) + observers.orderedStream().toList())
            val scope = listOf(ScopeQueryPolicy(tenants), AbacQueryPolicy(properties.abac.options()))
            val factory = backend.factory
            val snapshots = if (factory == null) {
                emptyList()
            } else {
                snapshotTypes(manifest, contexts).map { type ->
                    DefaultQueryGateway(
                        aggregateType = type,
                        backend = factory.create(type).backend,
                        schemaProvider = catalog.provider(type, QueryModel.SNAPSHOT),
                        filters = filters.orderedStream().toList(),
                        policies = scope + policies.orderedStream().toList(),
                        observer = observer,
                        entryPolicy = entryPolicy,
                    )
                }
            }
            val events = queryTypes(manifest, contexts, QueryRoutes.EVENTS).map { type ->
                EventQueryGateway(type, node.contexts.getValue(type.context).backends.log, tenants, entryPolicy)
            }
            return HttpQueries(snapshots, events, properties.http.defaultListSize)
        }

        @Bean
        fun wowQueryIndexesLifecycle(
            manifest: Manifest,
            node: ServiceNode,
            properties: QueryProperties,
            backend: SnapshotQueryBackend,
            catalog: QuerySchemaCatalog,
        ): QueryIndexesLifecycle = QueryIndexesLifecycle(
            manifest,
            snapshotTypes(manifest, node.contexts.keys),
            backend,
            catalog,
            properties.index.ddl,
        )
    }

    private companion object {
        const val MONGO_CLIENT = "com.mongodb.client.MongoClient"
        val logger = KotlinLogging.logger {}
    }
}

/** A class of wow-query, present only when the application has the module. */
internal const val QUERY_MODULE = "me.ahoo.wow.query.QueryModule"
