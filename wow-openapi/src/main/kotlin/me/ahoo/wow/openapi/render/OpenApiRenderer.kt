/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.openapi.render

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteCatalog
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema

class OpenApiRenderer {

    fun render(catalog: RouteCatalog, openAPI: OpenAPI): OpenAPI {
        openAPI.specVersion(SpecVersion.V31)
        if (openAPI.paths == null) {
            openAPI.paths = Paths()
        }

        catalog.routes.groupBy { it.path }.forEach { (path, routes) ->
            val pathItem = openAPI.paths[path] ?: PathItem()
            routes.forEach { route ->
                pathItem.addOperation(route.method, route.toOperation())
            }
            openAPI.paths.addPathItem(path, pathItem)
        }

        catalog.routes
            .flatMap { it.tags }
            .distinctBy { it.name }
            .forEach { tag ->
                openAPI.addTagsItem(Tag().name(tag.name).description(tag.description))
            }
        return openAPI
    }

    private fun HttpRouteContract.toOperation() = io.swagger.v3.oas.models.Operation().also { operation ->
        operation.operationId = routeId
        operation.tags = tags.map { it.name }
        operation.parameters = parameters.map { it.toParameter() }
        operation.requestBody = requestBody?.toRequestBody()
        operation.responses = responses.toApiResponses()
    }

    private fun HttpParameter.toParameter(): Parameter {
        componentRef?.let { componentRef ->
            return Parameter().also {
                it.`$ref` = COMPONENTS_PARAMETERS_REF + componentRef
            }
        }
        return Parameter()
            .name(name)
            .`in`(location.toParameterIn())
            .required(required)
            .description(description)
            .example(example)
            .schema(schema.toSchema())
    }

    private fun HttpParameterLocation.toParameterIn(): String {
        return when (this) {
            HttpParameterLocation.PATH -> "path"
            HttpParameterLocation.QUERY -> "query"
            HttpParameterLocation.HEADER -> "header"
        }
    }

    private fun HttpRequestBody.toRequestBody(): RequestBody {
        componentRef?.let { componentRef ->
            return RequestBody().also {
                it.`$ref` = COMPONENTS_REQUEST_BODIES_REF + componentRef
            }
        }
        return RequestBody()
            .required(required)
            .description(description)
            .also {
                if (content.isNotEmpty()) {
                    it.content(content.toContent())
                }
            }
    }

    private fun List<HttpResponse>.toApiResponses(): ApiResponses {
        return ApiResponses().also { apiResponses ->
            forEach { response ->
                apiResponses.addApiResponse(response.statusCode, response.toApiResponse())
            }
        }
    }

    private fun HttpResponse.toApiResponse(): ApiResponse {
        componentRef?.let { componentRef ->
            return ApiResponse().also {
                it.`$ref` = COMPONENTS_RESPONSES_REF + componentRef
            }
        }
        return ApiResponse()
            .description(description)
            .also {
                if (headers.isNotEmpty()) {
                    it.headers(headers.toHeaders())
                }
                if (content.isNotEmpty()) {
                    it.content(content.toContent())
                }
            }
    }

    private fun List<HttpHeader>.toHeaders(): Map<String, Header> {
        return associate { header ->
            header.name to header.toHeader()
        }
    }

    private fun HttpHeader.toHeader(): Header {
        componentRef?.let { componentRef ->
            return Header().also {
                it.`$ref` = COMPONENTS_HEADERS_REF + componentRef
            }
        }
        return Header()
            .description(description)
            .schema(schema.toSchema())
    }

    private fun List<HttpContent>.toContent(): Content {
        return Content().also { content ->
            forEach { httpContent ->
                content.addMediaType(
                    httpContent.mediaType,
                    MediaType().schema(httpContent.schema.toSchema())
                )
            }
        }
    }

    private fun HttpSchema.toSchema(): Schema<*> {
        return when (this) {
            HttpSchema.String -> StringSchema()
            HttpSchema.Boolean -> BooleanSchema()
            HttpSchema.Integer -> IntegerSchema()
            HttpSchema.Long -> IntegerSchema().format("int64")
            HttpSchema.Object -> ObjectSchema()
            is HttpSchema.TypeRef -> ObjectSchema()
            is HttpSchema.Array -> ArraySchema().items(item.toSchema())
            is HttpSchema.ComponentRef -> Schema<Any>().also {
                it.`$ref` = COMPONENTS_SCHEMAS_REF + key
            }
        }
    }

    private fun PathItem.addOperation(method: String, operation: io.swagger.v3.oas.models.Operation) {
        when (method.uppercase()) {
            Https.Method.GET -> get(operation)
            Https.Method.POST -> post(operation)
            Https.Method.PUT -> put(operation)
            Https.Method.DELETE -> delete(operation)
            Https.Method.PATCH -> patch(operation)
            Https.Method.OPTIONS -> options(operation)
            Https.Method.HEAD -> head(operation)
            Https.Method.TRACE -> trace(operation)
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
    }

    companion object {
        private const val COMPONENTS_SCHEMAS_REF = "#/components/schemas/"
        private const val COMPONENTS_PARAMETERS_REF = "#/components/parameters/"
        private const val COMPONENTS_REQUEST_BODIES_REF = "#/components/requestBodies/"
        private const val COMPONENTS_RESPONSES_REF = "#/components/responses/"
        private const val COMPONENTS_HEADERS_REF = "#/components/headers/"
    }
}
