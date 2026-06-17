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

class RouteCatalog(routes: List<HttpRouteContract>) : Iterable<HttpRouteContract> {
    val routes: List<HttpRouteContract> = routes
        .sortedWith(COMPARATOR)
        .also(::validate)

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
