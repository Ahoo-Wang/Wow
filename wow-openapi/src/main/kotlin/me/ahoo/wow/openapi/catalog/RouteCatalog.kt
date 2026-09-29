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
import java.util.PriorityQueue

/**
 * The validated set of HTTP routes a service exposes.
 *
 * [routes] is the catalog order (commands first, then by path), which OpenAPI documents follow.
 * [dispatchRoutes] is the order a first-match router tries them in. Generated templates may overlap without being
 * equal: a command at `POST /{resource}/{id}/count` matches every path the query `POST /{resource}/snapshot/count`
 * matches, so in catalog order the query could never be reached. Where one template's paths are a subset of
 * another's, the subset is tried first; every other pair keeps the catalog order, so no request that reached a route
 * before goes elsewhere. Two templates of the same method that match exactly the same paths (equal but for their
 * variable names) cannot be told apart by any order and are rejected.
 */
class RouteCatalog(routes: List<HttpRouteContract>) : Iterable<HttpRouteContract> {
    val routes: List<HttpRouteContract> = routes
        .sortedWith(COMPARATOR)
        .also(::validate)

    /**
     * [routes] in dispatch order: a route whose template matches a proper subset of another's paths (same method)
     * comes before it; otherwise the catalog order is kept.
     */
    val dispatchRoutes: List<HttpRouteContract> = dispatchOrder(this.routes)

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
        /**
         * A stable topological sort: the only constraints are "a strictly contained template precedes its container",
         * and among the routes free to go next the earliest in catalog order goes first.
         */
        private fun dispatchOrder(routes: List<HttpRouteContract>): List<HttpRouteContract> {
            val templates = routes.map { RouteTemplate(it.path) }
            val successors = List(routes.size) { mutableListOf<Int>() }
            val pending = IntArray(routes.size)
            routes.indices.groupBy { routes[it].method }.values.forEach { sameMethod ->
                for (inner in sameMethod) {
                    for (outer in sameMethod) {
                        if (inner != outer && templates[inner].canNest(templates[outer]) &&
                            templates[inner].isStrictlyWithin(templates[outer])
                        ) {
                            successors[inner].add(outer)
                            pending[outer]++
                        }
                    }
                }
            }
            val ready = PriorityQueue<Int>()
            routes.indices.filter { pending[it] == 0 }.forEach(ready::add)
            val ordered = ArrayList<HttpRouteContract>(routes.size)
            while (ready.isNotEmpty()) {
                val next = ready.poll()
                ordered.add(routes[next])
                successors[next].forEach { outer ->
                    if (--pending[outer] == 0) {
                        ready.add(outer)
                    }
                }
            }
            check(ordered.size == routes.size) { "Route containment must not be cyclic." }
            return ordered
        }

        private fun RouteTemplate.canNest(other: RouteTemplate): Boolean =
            segments.size == other.segments.size || hasCatchAll || other.hasCatchAll

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
