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

import java.lang.reflect.Type

data class HttpRouteContract(
    val routeId: String,
    val method: String,
    val path: String,
    val handlerKey: String,
    val category: String = "",
    val accept: List<String> = listOf("application/json"),
    val produce: List<String> = emptyList(),
    val parameters: List<HttpParameter> = emptyList(),
    val requestBody: HttpRequestBody? = null,
    val responses: List<HttpResponse> = emptyList(),
    val tags: List<HttpTag> = emptyList(),
    val handlerMetadata: HttpRouteHandlerMetadata = HttpRouteHandlerMetadata.None,
    val resourceScope: String = ""
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
    val example: Any? = null
)

enum class HttpParameterLocation {
    PATH,
    QUERY,
    HEADER
}

data class HttpRequestBody(
    val required: Boolean = false,
    val description: String? = null,
    val content: List<HttpContent> = emptyList()
)

data class HttpResponse(
    val statusCode: String,
    val description: String? = null,
    val headers: List<HttpHeader> = emptyList(),
    val content: List<HttpContent> = emptyList()
)

data class HttpHeader(
    val name: String,
    val schema: HttpSchema = HttpSchema.String,
    val description: String? = null
)

data class HttpContent(
    val mediaType: String,
    val schema: HttpSchema
)

sealed interface HttpSchema {
    data object String : HttpSchema
    data object Integer : HttpSchema
    data object Boolean : HttpSchema
    data object Long : HttpSchema
    data object Object : HttpSchema
    data class TypeRef(val mainTargetType: Type, val typeParameters: List<Type> = emptyList()) : HttpSchema
    data class Array(val item: HttpSchema) : HttpSchema
    data class ComponentRef(val key: kotlin.String) : HttpSchema
}
