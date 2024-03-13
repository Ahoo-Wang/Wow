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
import io.swagger.v3.oas.models.info.Info
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Condition.Companion.EMPTY_VALUE
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaName

class RouterSpecs(
    private val currentContext: NamedBoundedContext,
    private val routes: MutableList<RouteSpec> = mutableListOf()
) : MutableList<RouteSpec> by routes {
    @Volatile
    private var built: Boolean = false
    private val openAPI = OpenAPI().apply {
        info = Info()
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
            val aggregateMetadata = aggregateType.aggregateMetadata<Any, Any>()
            AggregateRouteSpecFactoryProvider.get().forEach { aggregateRouteSpecFactory ->
                aggregateRouteSpecFactory.create(currentContext, aggregateMetadata).forEach { routeSpec ->
                    add(routeSpec)
                }
            }
        }
    }

    private fun customSchema() {
        val conditionSchemaName = Condition::class.java.toSchemaName()
        val conditionSchema = openAPI.components.schemas[conditionSchemaName]
        conditionSchema?.let {
            it.properties[Condition::field.name]?.setDefault(EMPTY_VALUE)
            it.properties[Condition::operator.name]?.setDefault(Operator.ALL.name)
            it.properties[Condition::value.name]?.setDefault(EMPTY_VALUE)
            it.properties[Condition::children.name]?.setDefault(emptyList<Condition>())
            it.properties["not"]?.setDefault(false)
        }

        val querySchemaName = Query::class.java.toSchemaName()
        val querySchema = openAPI.components.schemas[querySchemaName]
        querySchema?.let {
            it.properties[Query::sort.name]?.setDefault(emptyList<Sort>())
            it.properties[Query::limit.name]?.setDefault(Pagination.DEFAULT.size)
        }

        val paginationSchemaName = Pagination::class.java.toSchemaName()
        val paginationSchema = openAPI.components.schemas[paginationSchemaName]
        paginationSchema?.let {
            it.properties[Pagination::index.name]?.setDefault(Pagination.DEFAULT.index)
            it.properties[Pagination::size.name]?.setDefault(Pagination.DEFAULT.size)
        }

        val pagedQuerySchemaName = PagedQuery::class.java.toSchemaName()
        val pagedQuerySchema = openAPI.components.schemas[pagedQuerySchemaName]
        pagedQuerySchema?.let {
            it.properties[Query::sort.name]?.setDefault(emptyList<Sort>())
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
        customSchema()
        val groupedPathRoutes = routes.groupBy {
            it.path
        }
        for ((path, routeSpecs) in groupedPathRoutes) {
            openAPI.paths.addPathItem(path, routeSpecs.toPathItem())
        }
        return this
    }
}
