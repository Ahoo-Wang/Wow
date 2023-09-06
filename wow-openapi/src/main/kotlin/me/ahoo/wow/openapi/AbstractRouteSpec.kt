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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.openapi.Schemas.asSchemaRef
import me.ahoo.wow.openapi.Schemas.asSchemas
import me.ahoo.wow.openapi.command.CommandHeaders.WOW_ERROR_CODE
import me.ahoo.wow.openapi.command.ErrorInfoSchema

abstract class AbstractRouteSpec : RouteSpec {
    open val requestBodyType: Class<*>?
        get() = null
    open val responseType: Class<*>?
        get() = null
    open val isArrayResponse: Boolean
        get() = false
    override val parameters: MutableList<Parameter> = mutableListOf()
    override val schemas: MutableMap<String, Schema<*>> = mutableMapOf()
    override val requestBody: RequestBody?
        get() {
            if (requestBodyType == null) {
                return null
            }
            val schema = requestBodyType!!.asSchemaRef()
            return RequestBody()
                .required(true)
                .content(
                    jsonContent(schema)
                )
        }

    override val responses: ApiResponses = ApiResponses()

    fun jsonContent(
        schema: Schema<*>,
        customize: (Content) -> Unit = {}
    ): Content {
        return context(Https.MediaType.APPLICATION_JSON, schema = schema, customize = customize)
    }

    fun context(
        name: String = "*",
        schema: Schema<*>,
        customize: (Content) -> Unit = {}
    ): Content {
        val content = Content()
            .addMediaType(name, MediaType().schema(schema))
        customize(content)
        return content
    }

    fun addParameter(
        name: String,
        parameterIn: ParameterIn,
        schema: Schema<*>,
        customize: (Parameter) -> Unit = {}
    ): Parameter? {
        parameters.any {
            it.name == name && it.`in` == parameterIn.toString()
        }.apply {
            if (this) {
                return null
            }
        }
        val parameter = Parameter()
            .name(name)
            .`in`(parameterIn.toString())
            .schema(schema)
        customize(parameter)
        parameters.add(parameter)
        return parameter
    }

    override fun build(): RouteSpec {
        initSchema()
        initResponse()
        return this
    }

    open fun customize(apiResponse: ApiResponse): ApiResponse {
        return apiResponse
    }

    private fun initResponse() {
        val errorCodeHeader = Header()
            .content(context(schema = StringSchema()))
            .description("Error Code")
        val succeededResponse = ApiResponse()
            .addHeaderObject(WOW_ERROR_CODE, errorCodeHeader)
            .description(ErrorInfo.SUCCEEDED)
        if (responseType != null && !responseType!!.isPrimitive) {
            val responseSchema = responseType!!.asSchemaRef()
            val schema = if (isArrayResponse) {
                ArraySchema().items(responseSchema)
            } else {
                responseSchema
            }
            succeededResponse.content(jsonContent(schema))
        }
        customize(succeededResponse)
        responses.addApiResponse(Https.Code.OK, succeededResponse)
        ApiResponse()
            .addHeaderObject(WOW_ERROR_CODE, errorCodeHeader)
            .description("Bad Request")
            .content(jsonContent(ErrorInfoSchema.schemaRef)).let {
                responses.addApiResponse(Https.Code.BAD_REQUEST, it)
            }
    }

    private fun initSchema() {
        listOf(requestBodyType, responseType).asSchemas().apply {
            schemas.putAll(this)
        }
    }

    override fun toString(): String {
        return "$id@($path && $method)"
    }
}
