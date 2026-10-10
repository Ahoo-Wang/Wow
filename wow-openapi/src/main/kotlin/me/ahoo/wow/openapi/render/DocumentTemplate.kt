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

package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag

/**
 * A rendered document that is merged into other documents, as many times as needed and from any thread, without
 * rendering again. Each merge gets its own copy of the path items, operations, parameters, request bodies, responses,
 * headers and media types, and of the parameter, header, request body and response components, so changing them
 * affects no other document. Only the [io.swagger.v3.oas.models.media.Schema] instances are shared, as they are
 * generated once: copy a schema before changing it.
 */
internal class DocumentTemplate(rendered: OpenAPI) {
    private val openapi: String? = rendered.openapi
    private val specVersion = rendered.specVersion
    private val paths: List<Pair<String, PathItem>> = rendered.paths.orEmpty().toList()
    private val tags: List<Pair<String, String?>> = rendered.tags.orEmpty().map { it.name to it.description }
    private val components: Components = rendered.components ?: Components()

    fun mergeInto(openAPI: OpenAPI) {
        openAPI.openapi(openapi).specVersion(specVersion)
        val targetPaths = openAPI.paths ?: Paths().also { openAPI.paths = it }
        paths.forEach { (path, template) ->
            val pathItem = template.copy()
            val existing = targetPaths[path]
            if (existing == null) {
                targetPaths.addPathItem(path, pathItem)
            } else {
                pathItem.summary?.let { existing.summary = it }
                pathItem.description?.let { existing.description = it }
                pathItem.readOperationsMap().forEach { (method, operation) ->
                    existing.operation(method, operation)
                }
            }
        }
        tags.forEach { (name, description) ->
            openAPI.addTagsItem(Tag().name(name).description(description))
        }
        mergeComponents(openAPI)
    }

    private fun mergeComponents(openAPI: OpenAPI) {
        val target = openAPI.components ?: Components().also { openAPI.components = it }
        components.schemas.orEmpty().forEach { (name, schema) -> target.addSchemas(name, schema) }
        components.parameters.orEmpty().forEach { (name, parameter) -> target.addParameters(name, parameter.copy()) }
        components.headers.orEmpty().forEach { (name, header) -> target.addHeaders(name, header.copy()) }
        components.requestBodies.orEmpty().forEach { (name, body) -> target.addRequestBodies(name, body.copy()) }
        components.responses.orEmpty().forEach { (name, response) -> target.addResponses(name, response.copy()) }
    }

    private companion object {
        fun PathItem.copy(): PathItem = PathItem().also { copy ->
            copy.summary = summary
            copy.description = description
            copy.`$ref` = `$ref`
            copy.servers = servers?.toMutableList()
            copy.parameters = parameters?.map { it.copy() }
            copy.extensions = extensions?.let(::LinkedHashMap)
            readOperationsMap().forEach { (method, operation) -> copy.operation(method, operation.copy()) }
        }

        fun Operation.copy(): Operation = Operation().also { copy ->
            copy.operationId = operationId
            copy.summary = summary
            copy.description = description
            copy.tags = tags?.toMutableList()
            copy.externalDocs = externalDocs
            copy.parameters = parameters?.map { it.copy() }
            copy.requestBody = requestBody?.copy()
            copy.responses = responses?.let { responses ->
                ApiResponses().also { copied ->
                    responses.forEach { (code, response) -> copied.addApiResponse(code, response.copy()) }
                    copied.extensions = responses.extensions?.let(::LinkedHashMap)
                }
            }
            copy.callbacks = callbacks?.let(::LinkedHashMap)
            copy.deprecated = deprecated
            copy.security = security?.toMutableList()
            copy.servers = servers?.toMutableList()
            copy.extensions = extensions?.let(::LinkedHashMap)
        }

        fun Parameter.copy(): Parameter = Parameter().also { copy ->
            copy.`$ref` = `$ref`
            copy.name = name
            copy.`in` = `in`
            copy.description = description
            copy.required = required
            copy.deprecated = deprecated
            copy.allowEmptyValue = allowEmptyValue
            copy.style = style
            copy.explode = explode
            copy.allowReserved = allowReserved
            copy.schema = schema
            copy.examples = examples?.let(::LinkedHashMap)
            example?.let { copy.example = it }
            copy.content = content?.copy()
            copy.extensions = extensions?.let(::LinkedHashMap)
        }

        fun RequestBody.copy(): RequestBody = RequestBody().also { copy ->
            copy.`$ref` = `$ref`
            copy.description = description
            copy.required = required
            copy.content = content?.copy()
            copy.extensions = extensions?.let(::LinkedHashMap)
        }

        fun ApiResponse.copy(): ApiResponse = ApiResponse().also { copy ->
            copy.`$ref` = `$ref`
            copy.description = description
            copy.headers = headers?.mapValuesTo(LinkedHashMap()) { (_, header) -> header.copy() }
            copy.content = content?.copy()
            copy.links = links?.let(::LinkedHashMap)
            copy.extensions = extensions?.let(::LinkedHashMap)
        }

        fun Header.copy(): Header = Header().also { copy ->
            copy.`$ref` = `$ref`
            copy.description = description
            copy.required = required
            copy.deprecated = deprecated
            copy.style = style
            copy.explode = explode
            copy.schema = schema
            copy.examples = examples?.let(::LinkedHashMap)
            example?.let { copy.example = it }
            copy.content = content?.copy()
            copy.extensions = extensions?.let(::LinkedHashMap)
        }

        fun Content.copy(): Content = Content().also { copy ->
            forEach { (name, mediaType) -> copy.addMediaType(name, mediaType.copy()) }
        }

        fun MediaType.copy(): MediaType = MediaType().also { copy ->
            copy.schema = schema
            copy.examples = examples?.let(::LinkedHashMap)
            if (exampleSetFlag) {
                copy.example = example
            }
            copy.encoding = encoding?.let(::LinkedHashMap)
            copy.extensions = extensions?.let(::LinkedHashMap)
        }
    }
}
