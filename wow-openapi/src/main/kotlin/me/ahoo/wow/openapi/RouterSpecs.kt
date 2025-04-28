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
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpecFactoryProvider
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.GlobalRouteSpecFactoryProvider
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.schema.openapi.InlineSchemaCapable

class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    private val routes: MutableList<RouteSpec> = mutableListOf(),
    override val inline: Boolean = false,
    override val componentContext: OpenAPIComponentContext =
        OpenAPIComponentContext.default(inline, defaultSchemaNamePrefix = "${currentContext.getContextAlias()}.")
) : InlineSchemaCapable, OpenAPIComponentContextCapable, MutableList<RouteSpec> by routes {

    @Volatile
    private var built: Boolean = false
    private val openAPI = OpenAPI().apply {
        specVersion(SpecVersion.V31)
        info = Info()
            .title(currentContext.getContextAlias())
            .description(currentContext.contextName)
        paths = Paths()
        components = Components()
    }

    private fun buildGlobalRouteSpec() {
        GlobalRouteSpecFactoryProvider(componentContext).get().forEach {
            it.create(currentContext).forEach { routeSpec ->
                add(routeSpec)
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
            aggregateRouteSpecFactories.forEach { aggregateRouteSpecFactory ->
                aggregateRouteSpecFactory.create(currentContext, aggregateRouteMetadata).forEach { routeSpec ->
                    add(routeSpec)
                }
            }
        }
    }

    private fun mergeComponentContext() {
        componentContext.finish()
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

    fun openAPI(): OpenAPI {
        build()
        return openAPI
    }

    fun build(): RouterSpecs {
        if (built) {
            return this
        }
        built = true
        buildGlobalRouteSpec()
        buildAggregateRouteSpec()
        val groupedPathRoutes = routes.groupBy {
            it.path
        }
        for ((path, routeSpecs) in groupedPathRoutes) {
            openAPI.paths.addPathItem(path, routeSpecs.toPathItem())
        }
        mergeComponentContext()
        return this
    }
}
