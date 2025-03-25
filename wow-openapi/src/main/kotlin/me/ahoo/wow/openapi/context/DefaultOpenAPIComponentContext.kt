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

package me.ahoo.wow.openapi.context

import com.fasterxml.classmate.ResolvedType
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_HEADERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_PARAMETERS_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_REQUEST_BODIES_REF
import me.ahoo.wow.openapi.context.OpenAPIComponentContext.Companion.COMPONENTS_RESPONSES_REF
import me.ahoo.wow.schema.openapi.InlineSchemaCapable
import me.ahoo.wow.schema.openapi.OpenAPISchemaBuilder
import java.lang.reflect.Type

class DefaultOpenAPIComponentContext(private val schemaBuilder: OpenAPISchemaBuilder) : OpenAPIComponentContext,
    InlineSchemaCapable by schemaBuilder {
    override val schemas: Map<String, Schema<*>>
        get() = schemaBuilder.build()
    override val parameters: MutableMap<String, Parameter> = mutableMapOf()
    override val headers: MutableMap<String, Header> = mutableMapOf()
    override val requestBodies: MutableMap<String, RequestBody> = mutableMapOf()
    override val responses: MutableMap<String, ApiResponse> = mutableMapOf()
    override fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType {
        return schemaBuilder.resolveType(mainTargetType, *typeParameters)
    }

    override fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> {
        return schemaBuilder.generateSchema(mainTargetType, *typeParameters)
    }

    private fun String.requiredKeyNotBlank() {
        require(isNotBlank()) {
            "key must not be blank"
        }
    }

    override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter {
        val parameter = Parameter().also(builder)
        if (inline) {
            return parameter
        }
        key.requiredKeyNotBlank()
        parameters[key] = parameter
        return Parameter().also {
            it.`$ref` = "$COMPONENTS_PARAMETERS_REF$key"
        }
    }

    override fun header(key: String, builder: (Header) -> Unit): Header {
        val header = Header().also(builder)
        if (inline) {
            return header
        }
        key.requiredKeyNotBlank()
        headers[key] = header
        return Header().also {
            it.`$ref` = "$COMPONENTS_HEADERS_REF$key"
        }
    }

    override fun requestBody(key: String, builder: (RequestBody) -> Unit): RequestBody {
        val requestBody = RequestBody().also(builder)
        if (inline) {
            return requestBody
        }
        key.requiredKeyNotBlank()
        requestBodies[key] = requestBody
        return RequestBody().also {
            it.`$ref` = "$COMPONENTS_REQUEST_BODIES_REF$key"
        }
    }

    override fun response(key: String, builder: (ApiResponse) -> Unit): ApiResponse {
        val apiResponse = ApiResponse().also(builder)
        if (inline) {
            return apiResponse
        }
        key.requiredKeyNotBlank()
        responses[key] = apiResponse
        return ApiResponse().also {
            it.`$ref` = "$COMPONENTS_RESPONSES_REF$key"
        }
    }
}