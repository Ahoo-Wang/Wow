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
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.getContextAliasPrefix
import me.ahoo.wow.openapi.OpenAPIExtensions.withExtensions
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpecFactoryProvider
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.GlobalRouteSpecFactoryProvider
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.migration.RouteSpecContractAdapter
import me.ahoo.wow.openapi.render.OpenApiRenderer
import me.ahoo.wow.schema.typed.AggregatedFields

class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    private val routes: MutableList<RouteSpec> = mutableListOf(),
    override val componentContext: OpenAPIComponentContext =
        OpenAPIComponentContext.default(false, defaultSchemaNamePrefix = currentContext.getContextAliasPrefix())
) : OpenAPIComponentContextCapable, Iterable<RouteSpec> by routes {
    companion object {
        const val DEFAULT_OPENAPI_INFO_TITLE = "OpenAPI definition"
    }

    @Volatile
    private var built: Boolean = false

    private fun buildGlobalRouteSpec() {
        GlobalRouteSpecFactoryProvider(componentContext).get().forEach {
            it.create(currentContext).forEach { routeSpec ->
                routes.add(routeSpec)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildAggregateRouteSpec() {
        val aggregateRouteSpecFactories = AggregateRouteSpecFactoryProvider(componentContext).get()
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateType = aggregateEntry.value
            val aggregateRouteMetadata = aggregateType.aggregateRouteMetadata()
            if (aggregateRouteMetadata.enabled.not()) {
                return@forEach
            }
            componentContext.schema(AggregatedFields::class.java, aggregateType)
            aggregateRouteSpecFactories.forEach { aggregateRouteSpecFactory ->
                aggregateRouteSpecFactory.create(currentContext, aggregateRouteMetadata).forEach { routeSpec ->
                    routes.add(routeSpec)
                }
            }
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

    private fun finishAndMergeComponents(openAPI: OpenAPI) {
        componentContext.finish()
        mergeFinishedComponents(openAPI)
    }

    private fun initializeRouteComponents(): List<RouteSpec> {
        return routes.map { route ->
            InitializedRouteSpec(
                id = route.id,
                path = route.path,
                method = route.method,
                summary = route.summary,
                description = route.description,
                tags = route.tags,
                accept = route.accept,
                parameters = route.parameters,
                requestBody = route.requestBody,
                responses = route.responses
            )
        }
    }

    fun mergeOpenAPI(openAPI: OpenAPI) {
        prepareOpenAPI(openAPI)
        val groupedPathRoutes = routes.groupBy {
            it.path
        }
        for ((path, routeSpecs) in groupedPathRoutes) {
            openAPI.paths.addPathItem(path, routeSpecs.toPathItem())
        }
        routes.flatMap { it.tags }.distinctBy { it.name }.forEach {
            openAPI.addTagsItem(it)
        }
        finishAndMergeComponents(openAPI)
    }

    fun mergeOpenAPIFromCatalog(openAPI: OpenAPI) {
        prepareOpenAPI(openAPI)
        val initializedRoutes = initializeRouteComponents()
        componentContext.finish()
        OpenApiRenderer().render(toRouteCatalog(initializedRoutes), openAPI)
        mergeFinishedComponents(openAPI)
    }

    fun build(): RouterSpecs {
        if (built) {
            return this
        }
        built = true
        buildGlobalRouteSpec()
        buildAggregateRouteSpec()
        return this
    }

    fun toRouteCatalog(): RouteCatalog {
        return toRouteCatalog(routes)
    }

    private fun toRouteCatalog(routeSpecs: Iterable<RouteSpec>): RouteCatalog {
        return RouteSpecContractAdapter(componentContext).toRouteCatalog(routeSpecs)
    }
}

private data class InitializedRouteSpec(
    override val id: String,
    override val path: String,
    override val method: String,
    override val summary: String,
    override val description: String,
    override val tags: List<Tag>,
    override val accept: List<String>,
    override val parameters: List<Parameter>,
    override val requestBody: RequestBody?,
    override val responses: ApiResponses
) : RouteSpec
