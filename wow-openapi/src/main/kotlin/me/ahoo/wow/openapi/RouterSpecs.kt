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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.openapi.OpenAPIExtensions.withExtensions
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.catalog.RouteCatalogBuilder
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.QueryFieldSources
import me.ahoo.wow.openapi.contributor.DefaultRouteContributors
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.render.DocumentTemplate
import me.ahoo.wow.openapi.render.OpenApiRenderer
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import reactor.core.scheduler.Schedulers
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The routes of a service and their OpenAPI document.
 *
 * The route catalog ([toRouteCatalog]) is built once, from the [routeContributors], and generates no schema. The
 * document is rendered from it once, generating the schemas and components with [componentContext]; [mergeOpenAPI]
 * merges copies of it and may be called concurrently.
 *
 * The queryable fields each aggregated query request body names (`x-wow-query-fields`) are those of the aggregate's
 * state, from Wow's type inference, unless the query schema sources are given: then they are the fields those sources
 * declare, as the query schema Catalog merges them.
 */
class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    private val componentContext: OpenAPIComponentContext =
        OpenAPIComponentContext.default(false, defaultSchemaNamePrefix = currentContext.getContextAliasPrefix()),
    val routeContributors: List<RouteContributor> = DefaultRouteContributors.all()
) {
    /**
     * Routes whose aggregated query request bodies list the fields [querySchemaSources] declare, merged under
     * [querySensitivity]: the sources and policy the application's query schema Catalog compiles, so the document
     * names the fields its queries accept. Storage facts are not read; what the storage supports for each field is the
     * query capability descriptor's.
     */
    constructor(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext,
        routeContributors: List<RouteContributor>,
        querySchemaSources: List<QuerySchemaSource>,
        querySensitivity: QuerySensitivityPolicy = QuerySensitivityPolicy.DEFAULT,
    ) : this(currentContext, componentContext, routeContributors) {
        queryFieldSources = QueryFieldSources(querySchemaSources.toList(), querySensitivity)
    }

    private var queryFieldSources: QueryFieldSources = QueryFieldSources.INFERRED

    companion object {
        const val DEFAULT_OPENAPI_INFO_TITLE = "OpenAPI definition"
    }

    private val routeCatalog: RouteCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        collectContributedRoutes()
    }

    /** Serializes the one render. */
    private val renderLock = ReentrantLock()

    /** The document rendered once, by the first [mergeOpenAPI] or [buildDocumentation]. */
    @Volatile
    private var template: DocumentTemplate? = null

    private fun serviceVersion(): String? {
        val firstLocalAggregateType = MetadataSearcher.namedAggregateType.filter {
            it.key.isSameBoundedContext(currentContext)
        }.map {
            it.value
        }.firstOrNull() ?: return null
        return firstLocalAggregateType.`package`.implementationVersion
    }

    private fun description(): String? {
        return MetadataSearcher.metadata.contexts[currentContext.contextName]?.description
    }

    private fun OpenAPI.ensureInfo() {
        val info = this.info ?: Info()
        info.withExtensions(currentContext)
        if (info.title.isNullOrBlank() || info.title == DEFAULT_OPENAPI_INFO_TITLE) {
            info.title = currentContext.contextName
        }
        serviceVersion()?.let {
            info.version = it
        }
        if (info.description.isNullOrBlank()) {
            info.description = description()
        }
        this.info = info
    }

    /**
     * Merges the routes into [openAPI]: its info, a fresh copy of the path item and operations of every route, the
     * tags, and the schemas and components the routes reference.
     *
     * The document is rendered once, by the first call (or by [buildDocumentation]); that render generates the schemas
     * and may block, so on a non-blocking thread (an event loop) the first call fails with [IllegalStateException]:
     * call [buildDocumentation] at startup. Every call merges a copy of the rendered document, so a caller may change
     * the path items, operations and components it gets without affecting other documents; only the
     * [io.swagger.v3.oas.models.media.Schema] instances are shared, so copy a schema before changing it. It may be
     * called concurrently.
     */
    fun mergeOpenAPI(openAPI: OpenAPI) {
        val template = template ?: run {
            check(!Schedulers.isInNonBlockingThread()) {
                "The OpenAPI document is not rendered yet, and rendering it may block, which " +
                    "${Thread.currentThread().name} does not allow: call RouterSpecs.buildDocumentation() at startup."
            }
            documentTemplate()
        }
        openAPI.ensureInfo()
        template.mergeInto(openAPI)
    }

    private fun documentTemplate(): DocumentTemplate {
        template?.let { return it }
        return renderLock.withLock {
            template ?: DocumentTemplate(
                OpenApiRenderer(componentContext, queryFieldSources).render(routeCatalog, OpenAPI())
            ).also { template = it }
        }
    }

    /** Builds the route catalog. No schema is generated: that happens when the OpenAPI document is rendered. */
    fun build(): RouterSpecs {
        toRouteCatalog()
        return this
    }

    /**
     * Renders the document now, unless it is rendered already. Call it at startup when the OpenAPI document is
     * served: rendering generates the schemas, which may block (it infers query fields), so it must not run on a
     * request thread. Later [mergeOpenAPI] calls only copy the rendered document and never block.
     */
    fun buildDocumentation(): RouterSpecs {
        documentTemplate()
        return this
    }

    /** The route catalog the router dispatches by. */
    fun toRouteCatalog(): RouteCatalog {
        return routeCatalog
    }

    private fun collectContributedRoutes(): RouteCatalog {
        val builder = RouteCatalogBuilder()
        routeContributors.forEach { contributor ->
            builder.addAll(contributor.contributeGlobal(currentContext))
        }
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateRouteMetadata = aggregateEntry.value.aggregateRouteMetadata()
            if (aggregateRouteMetadata.enabled.not()) {
                return@forEach
            }
            routeContributors.forEach { contributor ->
                builder.addAll(contributor.contributeAggregate(currentContext, aggregateRouteMetadata))
            }
        }
        return builder.build()
    }
}
