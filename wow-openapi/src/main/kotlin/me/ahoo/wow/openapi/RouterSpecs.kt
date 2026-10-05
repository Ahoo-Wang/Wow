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

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.info.Info
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.openapi.OpenAPIExtensions.withExtensions
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.catalog.RouteCatalogBuilder
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.catalog.RouteContributors
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.RoutingComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contributor.DefaultRouteContributors
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.render.OpenApiRenderer

class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    val componentContext: OpenAPIComponentContext =
        OpenAPIComponentContext.default(false, defaultSchemaNamePrefix = currentContext.getContextAliasPrefix()),
    val routeContributors: List<RouteContributor> = DefaultRouteContributors.all()
) {
    companion object {
        const val DEFAULT_OPENAPI_INFO_TITLE = "OpenAPI definition"
    }

    private val orderedRouteContributors: List<RouteContributor> = RouteContributors.sort(routeContributors)

    /** The routes built with [componentContext], which generates and registers their components. */
    private val documentedRouteCatalogLazy: Lazy<RouteCatalog> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        toRouteCatalog(collectContributedRoutes(componentContext))
    }
    private val documentedRouteCatalog: RouteCatalog by documentedRouteCatalogLazy

    /**
     * What the router dispatches by: the documented routes when they are already built (the routing facts are the
     * same), otherwise the routes built without generating any schema.
     */
    private val routeCatalog: RouteCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (documentedRouteCatalogLazy.isInitialized()) {
            documentedRouteCatalog
        } else {
            toRouteCatalog(collectContributedRoutes(RoutingComponentContext))
        }
    }

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

    private fun prepareOpenAPI(openAPI: OpenAPI) {
        openAPI.apply {
            specVersion(SpecVersion.V31)
            ensureInfo()
            if (paths == null) {
                paths = Paths()
            }
            if (components == null) {
                components = Components()
            }
        }
    }

    private fun mergeFinishedComponents(openAPI: OpenAPI) {
        componentContext.schemas.forEach { (name, schema) ->
            openAPI.components.addSchemas(name, schema)
        }
        componentContext.parameters.forEach { (name, parameter) ->
            openAPI.components.addParameters(name, parameter)
        }
        componentContext.headers.forEach { (name, header) ->
            openAPI.components.addHeaders(name, header)
        }
        componentContext.requestBodies.forEach { (name, requestBody) ->
            openAPI.components.addRequestBodies(name, requestBody)
        }
        componentContext.responses.forEach { (name, response) ->
            openAPI.components.addResponses(name, response)
        }
    }

    fun mergeOpenAPI(openAPI: OpenAPI) {
        mergeOpenAPIFromCatalog(openAPI)
    }

    fun mergeOpenAPIFromCatalog(openAPI: OpenAPI) {
        prepareOpenAPI(openAPI)
        val catalog = documentedRouteCatalog
        componentContext.finish()
        OpenApiRenderer(componentContext).render(catalog, openAPI)
        componentContext.finish()
        mergeFinishedComponents(openAPI)
    }

    /** Builds the route catalog. No schema is generated: that happens when the OpenAPI document is rendered. */
    fun build(): RouterSpecs {
        toRouteCatalog()
        return this
    }

    /**
     * Builds the documented contracts now, generating and registering their components, so that the first
     * [mergeOpenAPIFromCatalog] does not. Call it at startup when the OpenAPI document is served: schema generation
     * may block (it reads query schemas), so it must not first run on a request thread. Called before [build], the
     * router reuses these contracts instead of building the routes a second time.
     */
    fun buildDocumentation(): RouterSpecs {
        documentedRouteCatalog
        return this
    }

    /**
     * The route catalog the router dispatches by. Its contracts carry every routing fact; the schemas they embed are
     * placeholders, since schemas are generated only by [mergeOpenAPIFromCatalog].
     */
    fun toRouteCatalog(): RouteCatalog {
        return routeCatalog
    }

    private fun toRouteCatalog(contributedRoutes: Iterable<HttpRouteContract>): RouteCatalog {
        return RouteCatalogBuilder().addAll(contributedRoutes).build()
    }

    private fun collectContributedRoutes(componentContext: OpenAPIComponentContext): List<HttpRouteContract> {
        val builder = RouteCatalogBuilder()
        orderedRouteContributors.filter { it.category == RouteCategory.GLOBAL }.forEach { contributor ->
            builder.addAll(contributor.contributeGlobal(currentContext, componentContext))
        }
        val aggregateContributors = orderedRouteContributors.filter { it.category != RouteCategory.GLOBAL }
        if (aggregateContributors.isEmpty()) {
            return builder.build().routes
        }
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateType = aggregateEntry.value
            val aggregateRouteMetadata = aggregateType.aggregateRouteMetadata()
            if (aggregateRouteMetadata.enabled.not()) {
                return@forEach
            }
            aggregateContributors.forEach { contributor ->
                builder.addAll(
                    contributor.contributeAggregate(currentContext, aggregateRouteMetadata, componentContext)
                )
            }
        }
        return builder.build().routes
    }
}
