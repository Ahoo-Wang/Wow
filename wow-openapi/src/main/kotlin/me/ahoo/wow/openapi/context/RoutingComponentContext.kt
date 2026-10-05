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
import com.fasterxml.classmate.TypeResolver
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.RequestBodyBuilder
import java.lang.reflect.Type

/**
 * The component context route contracts are built with for routing: it generates no JSON Schema and registers no
 * component. A contributor's contract keeps every routing fact (path, method, handler, parameters, request body and
 * response presence) because those never depend on a generated schema; a schema it embeds is an empty placeholder.
 *
 * The OpenAPI document is rendered from contracts built again with the real [OpenAPIComponentContext], so schema
 * generation runs only when the document is rendered.
 */
internal object RoutingComponentContext : OpenAPIComponentContext {
    private val typeResolver = TypeResolver()

    override val inline: Boolean = false
    override val schemas: Map<String, Schema<*>> = emptyMap()
    override val parameters: Map<String, Parameter> = emptyMap()
    override val headers: Map<String, Header> = emptyMap()
    override val requestBodies: Map<String, RequestBody> = emptyMap()
    override val responses: Map<String, ApiResponse> = emptyMap()

    override fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType =
        typeResolver.resolve(mainTargetType, *typeParameters)

    override fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> = Schema<Any>()

    override fun componentSchema(key: String, schema: Schema<*>): Schema<*> = Schema<Any>()

    override fun arraySchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*> =
        ArraySchema().items(Schema<Any>())

    override fun parameter(key: String, builder: Parameter.() -> Unit): Parameter = Parameter().also(builder)

    override fun header(key: String, builder: Header.() -> Unit): Header = Header().also(builder)

    override fun requestBody(key: String, builder: RequestBodyBuilder.() -> Unit): RequestBody =
        RequestBodyBuilder().also(builder).build()

    override fun response(key: String, builder: ApiResponseBuilder.() -> Unit): ApiResponse =
        ApiResponseBuilder().also(builder).build()

    override fun finish() = Unit
}
