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
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.SchemaVersion
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.openapi.InlineSchemaCapable
import me.ahoo.wow.schema.openapi.OpenAPISchemaBuilder
import java.lang.reflect.Type

interface OpenAPIComponentContext : InlineSchemaCapable {
    companion object {
        const val COMPONENTS_PREFIX = "#/components/"
        const val COMPONENTS_HEADERS_REF = "${COMPONENTS_PREFIX}headers/"
        const val COMPONENTS_PARAMETERS_REF = "${COMPONENTS_PREFIX}parameters/"
        const val COMPONENTS_REQUEST_BODIES_REF = "${COMPONENTS_PREFIX}requestBodies/"
        const val COMPONENTS_RESPONSES_REF = "${COMPONENTS_PREFIX}responses/"

        fun default(
            inline: Boolean = false,
            schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12,
            defaultSchemaNamePrefix: String = ""
        ): OpenAPIComponentContext {
            val schemaGeneratorBuilder = SchemaGeneratorBuilder().schemaVersion(schemaVersion)
            if (inline) {
                schemaGeneratorBuilder.customizer {
                    it.with(Option.INLINE_ALL_SCHEMAS)
                }
            }
            return DefaultOpenAPIComponentContext(
                OpenAPISchemaBuilder(
                    defaultSchemaNamePrefix = defaultSchemaNamePrefix,
                    schemaGeneratorBuilder = schemaGeneratorBuilder
                )
            )
        }
    }

    val schemas: Map<String, Schema<*>>
    val parameters: Map<String, Parameter>
    val headers: Map<String, Header>
    val requestBodies: Map<String, RequestBody>
    val responses: Map<String, ApiResponse>

    fun resolveType(mainTargetType: Type, vararg typeParameters: Type): ResolvedType
    fun schema(mainTargetType: Type, vararg typeParameters: Type): Schema<*>
    fun arraySchema(mainTargetType: Type, vararg typeParameters: Type): Schema<*>
    fun parameter(key: String = "", builder: Parameter.() -> Unit): Parameter
    fun header(key: String = "", builder: Header.() -> Unit): Header
    fun requestBody(key: String = "", builder: RequestBodyBuilder.() -> Unit): RequestBody
    fun response(key: String = "", builder: ApiResponseBuilder.() -> Unit): ApiResponse

    fun finish()
}
