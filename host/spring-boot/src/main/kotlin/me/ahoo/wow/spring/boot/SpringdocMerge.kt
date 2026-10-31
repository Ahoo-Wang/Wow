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

package me.ahoo.wow.spring.boot

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion

/**
 * Merges the Wow document into springdoc's (①②): its paths, components, tags and global security join
 * the application's, and what both define differently fails the merge, so the startup ([SpringdocAutoConfiguration]).
 *
 * - **Paths**: a route both define, the same path (path variables compared by position, not by name) and method, is a
 *   conflict; a path both have with other methods gets the Wow operations beside the application's.
 * - **Components** (schemas, responses, parameters, headers, security schemes and the other kinds): a name both use
 *   for different definitions is a conflict; the same definition is kept once.
 * - **Tags** and **security requirements** the application's document lacks are added; a tag it has keeps its own
 *   description.
 * - **Info**: the Wow document's (the service's name and version) when the application's is springdoc's default.
 *
 * The Wow document is OpenAPI 3.1 (its schemas are JSON Schema 2020-12, read in OpenAPI's default dialect, which it
 * declares none of): a springdoc document of another version is a conflict too.
 */
internal object SpringdocMerge {
    /** springdoc's title of a document whose application gives none. */
    private const val SPRINGDOC_DEFAULT_TITLE = "OpenAPI definition"

    /** Merges [wow] into [target], or fails with every conflict found. */
    fun merge(target: OpenAPI, wow: OpenAPI) {
        val conflicts = ArrayList<String>()
        if (target.specVersion != SpecVersion.V31) {
            conflicts += "springdoc writes OpenAPI ${target.openapi}, Wow's document is OpenAPI 3.1: leave " +
                "springdoc.api-docs.version at openapi_3_1"
        }
        mergePaths(target, wow, conflicts)
        mergeComponents(target, wow.components, conflicts)
        check(conflicts.isEmpty()) {
            "The Wow routes conflict with the application's OpenAPI document: " +
                conflicts.joinToString("; ") + "."
        }
        wow.tags.orEmpty().filter { tag -> target.tags.orEmpty().none { it.name == tag.name } }.forEach(
            target::addTagsItem
        )
        wow.security.orEmpty().filter { it !in target.security.orEmpty() }.forEach(target::addSecurityItem)
        if (target.info == null || target.info.title == SPRINGDOC_DEFAULT_TITLE) {
            target.info = wow.info
        }
    }

    private fun mergePaths(target: OpenAPI, wow: OpenAPI, conflicts: MutableList<String>) {
        val paths = target.paths ?: Paths().also { target.paths = it }
        val routes = paths.flatMap { (path, item) -> item.readOperationsMap().keys.map { route(path, it) } }.toSet()
        wow.paths.orEmpty().forEach { (path, item) ->
            val clashes = item.readOperationsMap().keys.filter { route(path, it) in routes }
            if (clashes.isNotEmpty()) {
                conflicts += clashes.joinToString(", ") { "$it $path is a route of both" }
                return@forEach
            }
            val existing = paths[path]
            if (existing == null) {
                paths.addPathItem(path, item)
            } else {
                item.readOperationsMap().forEach { (method, operation) -> existing.operation(method, operation) }
            }
        }
    }

    /** A route: [method] and [path] with its variables unnamed, as a router matches it. */
    private fun route(path: String, method: PathItem.HttpMethod): String = "$method ${path.replace(VARIABLE, "{}")}"

    private fun mergeComponents(target: OpenAPI, wow: Components?, conflicts: MutableList<String>) {
        if (wow == null) {
            return
        }
        val components = target.components ?: Components().also { target.components = it }
        KINDS.forEach { kind -> kind.merge(components, wow, conflicts) }
    }

    /** A kind of component: its name in the document, how to read its map and how to add one entry. */
    private class Kind<T>(
        val name: String,
        val read: (Components) -> Map<String, T>?,
        val add: (Components, String, T) -> Unit,
    ) {
        fun merge(target: Components, wow: Components, conflicts: MutableList<String>) {
            val existing = read(target).orEmpty()
            read(wow).orEmpty().forEach { (key, definition) ->
                val defined = existing[key]
                when {
                    defined == null -> add(target, key, definition)
                    defined != definition -> conflicts += "the component $name/$key is defined differently"
                }
            }
        }
    }

    private val VARIABLE = Regex("\\{[^}]*}")

    private val KINDS: List<Kind<*>> = listOf(
        Kind("schemas", Components::getSchemas) { components, key, value -> components.addSchemas(key, value) },
        Kind("responses", Components::getResponses) { components, key, value -> components.addResponses(key, value) },
        Kind("parameters", Components::getParameters) { components, key, value ->
            components.addParameters(key, value)
        },
        Kind("examples", Components::getExamples) { components, key, value -> components.addExamples(key, value) },
        Kind("requestBodies", Components::getRequestBodies) { components, key, value ->
            components.addRequestBodies(key, value)
        },
        Kind("headers", Components::getHeaders) { components, key, value -> components.addHeaders(key, value) },
        Kind("securitySchemes", Components::getSecuritySchemes) { components, key, value ->
            components.addSecuritySchemes(key, value)
        },
        Kind("links", Components::getLinks) { components, key, value -> components.addLinks(key, value) },
        Kind("callbacks", Components::getCallbacks) { components, key, value -> components.addCallbacks(key, value) },
        Kind("pathItems", Components::getPathItems) { components, key, value -> components.addPathItem(key, value) },
    )
}
