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

package me.ahoo.wow.openapi.catalog

import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata

/**
 * The validated set of HTTP routes a service exposes.
 *
 * [routes] is the presentation order (commands first, then by path), which OpenAPI documents follow.
 * [dispatchRoutes] is the order a first-match router must try them in: generated templates may overlap without being
 * equal — `POST /{resource}/snapshot/count` and a command at `POST /{resource}/{id}/count` both match
 * `/{resource}/snapshot/count` — and there the template with a literal at the first segment they differ must win,
 * whatever order the routes were declared in. Two templates of the same method that match exactly the same paths
 * (equal but for their variable names) cannot be told apart by any order and are rejected.
 */
class RouteCatalog(routes: List<HttpRouteContract>) : Iterable<HttpRouteContract> {
    val routes: List<HttpRouteContract> = routes
        .sortedWith(COMPARATOR)
        .also(::validate)

    /**
     * [routes] in dispatch precedence: of two routes of the same method whose templates overlap, the more specific
     * one — a literal where the other has a variable, at the first segment they differ — comes first.
     */
    val dispatchRoutes: List<HttpRouteContract> = this.routes
        .map { it to RouteTemplate(it.path) }
        .sortedWith(compareBy<Pair<HttpRouteContract, RouteTemplate>> { it.second }.thenBy { it.first.method })
        .map { it.first }

    override fun iterator(): Iterator<HttpRouteContract> {
        return routes.iterator()
    }

    private fun validate(routes: List<HttpRouteContract>) {
        routes.forEach { route ->
            require(route.routeId.isNotBlank()) {
                "routeId must not be blank for [${route.method} ${route.path}]."
            }
            require(route.handlerKey.isNotBlank()) {
                "handlerKey must not be blank for route [${route.routeId}]."
            }
            validatePathVariables(route)
        }

        routes.groupBy { it.routeKey }
            .filterValues { it.size > 1 }
            .forEach { (routeKey, duplicates) ->
                throw IllegalArgumentException(
                    "Duplicate route [$routeKey]: ${duplicates.joinToString { it.routeId }}."
                )
            }
        validateTemplateShapes(routes)
    }

    /**
     * Rejects routes of the same method whose templates differ only in their variable names: they match the same
     * paths, so a first-match router always takes the first and the other can never be reached.
     */
    private fun validateTemplateShapes(routes: List<HttpRouteContract>) {
        routes.groupBy { "${it.method} ${RouteTemplate(it.path).shape}" }
            .filterValues { it.size > 1 }
            .forEach { (shape, ambiguous) ->
                throw IllegalArgumentException(
                    "Ambiguous routes [$shape]: ${ambiguous.joinToString { it.describe() }} match the same paths, " +
                        "so only the first could ever be dispatched. Give one of them a distinct path."
                )
            }
    }

    private fun validatePathVariables(route: HttpRouteContract) {
        val templateVariables = Regex("\\{([^}]+)}")
            .findAll(route.path)
            .map { it.groupValues[1] }
            .toSet()
        val parameterVariables = route.parameters
            .filter { it.location == HttpParameterLocation.PATH }
            .map { it.name }
            .toSet()
        val missingParameters = templateVariables - parameterVariables
        require(missingParameters.isEmpty()) {
            "Route [${route.routeId}] path variables missing parameters: $missingParameters."
        }
        val missingTemplates = parameterVariables - templateVariables
        require(missingTemplates.isEmpty()) {
            "Route [${route.routeId}] path parameters missing path variables: $missingTemplates."
        }
    }

    private companion object {
        private fun HttpRouteContract.describe(): String {
            val source = when (val metadata = handlerMetadata) {
                is HttpRouteHandlerMetadata.Command ->
                    " (aggregate ${metadata.aggregateRouteMetadata.aggregateMetadata.aggregateName}, " +
                        "command ${metadata.commandRouteMetadata.commandMetadata.commandType.name})"

                is HttpRouteHandlerMetadata.Aggregate ->
                    " (aggregate ${metadata.aggregateRouteMetadata.aggregateMetadata.aggregateName})"

                HttpRouteHandlerMetadata.None -> ""
            }
            return "[$routeId $method $path$source]"
        }

        private val COMPARATOR: Comparator<HttpRouteContract> = compareBy<HttpRouteContract> { it.priority }
            .thenBy { it.path }
            .thenBy { it.method }
            .thenBy { it.routeId }

        private val HttpRouteContract.priority: Int
            get() = when (handlerMetadata) {
                is HttpRouteHandlerMetadata.Command -> 0
                else -> 1
            }
    }
}
