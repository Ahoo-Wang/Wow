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
import me.ahoo.wow.openapi.route.aggregateRouteMetadata

class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    private val routes: MutableList<RouteSpec> = mutableListOf()
) : MutableList<RouteSpec> by routes {
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

    private fun mergeComponents(other: Components) {
        other.schemas?.forEach { (name, schema) ->
            openAPI.components.addSchemas(name, schema)
        }
        other.responses?.forEach { (name, response) ->
            openAPI.components.addResponses(name, response)
        }
        other.parameters?.forEach { (name, parameter) ->
            openAPI.components.addParameters(name, parameter)
        }
        other.examples?.forEach { (name, example) ->
            openAPI.components.addExamples(name, example)
        }
        other.requestBodies?.forEach { (name, requestBody) ->
            openAPI.components.addRequestBodies(name, requestBody)
        }
        other.headers?.forEach { (name, header) ->
            openAPI.components.addHeaders(name, header)
        }
        other.securitySchemes?.forEach { (name, securityScheme) ->
            openAPI.components.addSecuritySchemes(name, securityScheme)
        }
        other.links?.forEach { (name, link) ->
            openAPI.components.addLinks(name, link)
        }
        other.callbacks?.forEach { (name, callback) ->
            openAPI.components.addCallbacks(name, callback)
        }
        other.extensions?.forEach { (name, extension) ->
            openAPI.components.addExtension(name, extension)
        }
        other.pathItems?.forEach { (name, pathItem) ->
            openAPI.components.addPathItem(name, pathItem)
        }
    }

    private fun mergeRouteSpecFactoryComponents() {
        GlobalRouteSpecFactoryProvider.get().forEach {
            mergeComponents(it.components)
        }
        AggregateRouteSpecFactoryProvider.get().forEach {
            mergeComponents(it.components)
        }
    }

    private fun buildGlobalRouteSpec() {
        GlobalRouteSpecFactoryProvider.get().forEach {
            it.create(currentContext).forEach { routeSpec ->
                add(routeSpec)
            }
        }
    }

    private fun buildAggregateRouteSpec() {
        MetadataSearcher.namedAggregateType.forEach { aggregateEntry ->
            val aggregateType = aggregateEntry.value
            val aggregateRouteMetadata = aggregateType.aggregateRouteMetadata()
            AggregateRouteSpecFactoryProvider.get().forEach { aggregateRouteSpecFactory ->
                aggregateRouteSpecFactory.create(currentContext, aggregateRouteMetadata).forEach { routeSpec ->
                    add(routeSpec)
                }
            }
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
        mergeRouteSpecFactoryComponents()
        val groupedPathRoutes = routes.groupBy {
            it.path
        }
        for ((path, routeSpecs) in groupedPathRoutes) {
            openAPI.paths.addPathItem(path, routeSpecs.toPathItem())
        }
        return this
    }
}
