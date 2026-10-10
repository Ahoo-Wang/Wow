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

package me.ahoo.wow.openapi.contract

import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import java.lang.reflect.Type

/**
 * One HTTP route: what the router dispatches by and what the OpenAPI document describes. It is pure data: building it
 * generates no schema and registers no component. Schemas ([HttpSchema.TypeRef]) and components ([HttpComponent]) are
 * references the renderer resolves when the document is rendered.
 *
 * The path item of [path] takes the summary and description of the first route on it.
 */
data class HttpRouteContract(
    val routeId: String,
    val method: String,
    val path: String,
    val handlerKey: String,
    val summary: String = "",
    val description: String = "",
    val accept: List<String> = listOf("application/json"),
    val parameters: List<HttpParameter> = emptyList(),
    val requestBody: HttpRequestBody? = null,
    val responses: List<HttpResponse> = emptyList(),
    val tags: List<HttpTag> = emptyList(),
    val handlerMetadata: HttpRouteHandlerMetadata = HttpRouteHandlerMetadata.None
) {
    val routeKey: String
        get() = "$method $path"
}

data class HttpTag(
    val name: String,
    val description: String? = null
)

data class HttpParameter(
    val name: String,
    val location: HttpParameterLocation,
    val required: Boolean = false,
    val schema: HttpSchema = HttpSchema.String,
    val description: String? = null,
    val example: Any? = null,
    /** When set, the document references this component instead of describing the parameter inline. */
    val component: HttpComponent<Parameter>? = null
)

enum class HttpParameterLocation {
    PATH,
    QUERY,
    HEADER
}

data class HttpRequestBody(
    val required: Boolean = false,
    val description: String? = null,
    val content: List<HttpContent> = emptyList(),
    val contentDeclared: Boolean = content.isNotEmpty(),
    /** When set, the document references this component instead of describing the request body inline. */
    val component: HttpComponent<RequestBody>? = null
)

data class HttpResponse(
    val statusCode: String,
    val description: String? = null,
    val headers: List<HttpHeader> = emptyList(),
    val content: List<HttpContent> = emptyList(),
    val contentDeclared: Boolean = content.isNotEmpty(),
    /** When set, the document references this component instead of describing the response inline. */
    val component: HttpComponent<ApiResponse>? = null
)

data class HttpHeader(
    val name: String,
    val schema: HttpSchema = HttpSchema.String,
    val description: String? = null,
    /** When set, the document references this component instead of describing the header inline. */
    val component: HttpComponent<Header>? = null
)

data class HttpContent(
    val mediaType: String,
    val schema: HttpSchema
)

/**
 * The schema of a parameter, header or content. A contract never holds a generated schema: [TypeRef] names a type
 * whose schema the renderer generates, and [Raw] holds a static schema that needs no generation.
 */
sealed interface HttpSchema {
    data object String : HttpSchema
    data object Integer : HttpSchema
    data object Object : HttpSchema

    /**
     * The schema generated for [type], parameterized by [typeArguments], themselves type references, so nested
     * generics such as `PagedList<MaterializedSnapshot<S>>` need no type resolution while the contract is built.
     */
    data class TypeRef(val type: Type, val typeArguments: List<TypeRef> = emptyList()) : HttpSchema
    data class Array(val item: HttpSchema) : HttpSchema

    /** A static schema that does not depend on schema generation, such as an untyped aggregation row. */
    data class Raw(val schema: Schema<*>) : HttpSchema
}
