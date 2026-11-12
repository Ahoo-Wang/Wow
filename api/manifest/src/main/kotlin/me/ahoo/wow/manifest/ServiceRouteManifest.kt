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

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A service route of a context (`@ServiceRoute`): a route whose handler is the service's own code. Its
 * [name] is its identity within the context, for compatibility and for its generated client; [route] is where the
 * edge serves it, below the context; [parameters] are its path variables and query parameters; [request] and
 * [response] are the inline JSON Schemas of its body and its answer, null when it takes none or answers
 * `204 No Content`: a reference into `$defs` (`{"$ref": "#/$defs/<key>"}`), or an array of one
 * (`{"type": "array", "items": {"$ref": ...}}`, `"uniqueItems": true` for a set), a nullable one, the body or an
 * item, written with a `null` alternative ([Manifest.nullableSchema]). A null answer is `204 No Content`, no body, so
 * an answer's schema is never nullable itself: [responseOptional] says the route may answer `204 No Content` instead
 * of its [response] (its function returns a nullable value), where a route of no [response] always answers it (its
 * function returns `Unit`). [errors] are the codes it refuses with, entries of the context's error table.
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class ServiceRouteManifest @InternalWowApi constructor(
    val name: String,
    val route: RouteManifest,
    val parameters: List<RouteParameterManifest> = emptyList(),
    val request: Map<String, Any?>? = null,
    val response: Map<String, Any?>? = null,
    val responseOptional: Boolean = false,
    val errors: List<String> = emptyList(),
    val description: String? = null,
    val deprecated: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ServiceRouteManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ServiceRouteManifest(name=$name, route=$route, parameters=$parameters, request=$request, " +
            "response=$response, responseOptional=$responseOptional, errors=$errors, description=$description, " +
            "deprecated=$deprecated)"

    private fun valueFields(): List<Any?> =
        listOf(name, route, parameters, request, response, responseOptional, errors, description, deprecated)
}

/**
 * A parameter of a [ServiceRouteManifest]: a variable of its path or a query parameter ([location]), its [schema] an
 * inline JSON Schema of a scalar (`{"type":"string"}`, an `enum`), and whether a call must give it ([required]; a
 * path variable always is).
 *
 * Fields may be added: pass the constructor's arguments by name.
 */
class RouteParameterManifest @InternalWowApi constructor(
    val name: String,
    val location: Location,
    val schema: Map<String, Any?>,
    val required: Boolean = location == Location.PATH,
) {
    init {
        require(required || location != Location.PATH) { "The path variable $name is always required." }
    }

    /**
     * Where a parameter is read from.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     */
    enum class Location { PATH, QUERY }

    override fun equals(other: Any?): Boolean =
        this === other || other is RouteParameterManifest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "RouteParameterManifest(name=$name, location=$location, schema=$schema, required=$required)"

    private fun valueFields(): List<Any?> = listOf(name, location, schema, required)
}
