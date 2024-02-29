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

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.Components
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
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.configuration.namedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.ComponentRef.Companion.COMPONENTS_REF
import me.ahoo.wow.openapi.HeaderRef.Companion.ERROR_CODE_HEADER
import me.ahoo.wow.openapi.SchemaRef.Companion.toArraySchema
import me.ahoo.wow.openapi.SchemaRef.Companion.toRefSchema
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaName
import me.ahoo.wow.openapi.command.CommandHeaders

interface ComponentRef<C> {
    val name: String
    val component: C
    val ref: C

    companion object {
        const val COMPONENTS_REF = "#/components/"
        fun createComponents() = Components().apply {
            schemas = mutableMapOf()
            responses = mutableMapOf()
            parameters = mutableMapOf()
            examples = mutableMapOf()
            requestBodies = mutableMapOf()
            headers = mutableMapOf()
            securitySchemes = mutableMapOf()
            links = mutableMapOf()
            callbacks = mutableMapOf()
            extensions = mutableMapOf()
            pathItems = mutableMapOf()
        }
    }
}

class SchemaRef(
    override val name: String,
    override val component: Schema<*>,
    val schemas: Map<String, Schema<*>> = mapOf(name to component)
) : ComponentRef<Schema<*>> {
    override val ref: Schema<*> = name.toRefSchema()

    companion object {
        const val COMPONENTS_SCHEMAS_REF: String = COMPONENTS_REF + "schemas/"
        val ERROR_INFO = ErrorInfo::class.java.toSchemaRef()

        fun Class<out Enum<*>>.toSchemaRef(default: String? = null): SchemaRef {
            val enumSchema = StringSchema()
            enumSchema._enum(this.enumConstants.map { it.toString() })
            val schemaName = requireNotNull(this.toSchemaName())
            if (default.isNullOrBlank().not()) {
                enumSchema.setDefault(default)
            }
            return SchemaRef(schemaName, enumSchema)
        }

        fun Class<*>.toSchemas(): Map<String, Schema<*>> {
            return ModelConverters.getInstance().readAll(this)
        }

        fun String.toRefSchema(): Schema<*> {
            return Schema<Any>().`$ref`(COMPONENTS_SCHEMAS_REF + this)
        }

        fun Class<*>.toRefSchema(): Schema<*> {
            return requireNotNull(this.toSchemaName()).toRefSchema()
        }

        fun Schema<*>.toArraySchema(): ArraySchema {
            return ArraySchema().items(this)
        }

        fun Class<*>.toSchemaName(): String? {
            this.scan<io.swagger.v3.oas.annotations.media.Schema>()?.let {
                if (it.name.isNotBlank()) {
                    return it.name
                }
            }
            namedBoundedContext()?.let {
                it.getContextAlias().let { alias ->
                    return "$alias.$simpleName"
                }
            }
            if (name.startsWith("me.ahoo.wow.")) {
                return Wow.WOW_PREFIX + simpleName
            }
            return null
        }

        fun Class<*>.toSchemaRef(): SchemaRef {
            val schemaName = requireNotNull(this.toSchemaName())
            val schemas = toSchemas()
            val component = requireNotNull(schemas[schemaName])
            return SchemaRef(schemaName, component, schemas)
        }

        fun Class<*>.toSchemaRef(propertyName: String, propertyType: Class<*>): SchemaRef {
            return toSchemaRef(propertyName, propertyType.toSchemaRef())
        }

        fun Class<*>.toSchemaRef(propertyName: String, propertySchemaRef: SchemaRef): SchemaRef {
            val genericSchemaName = requireNotNull(this.toSchemaName())
            val genericSchemas = toSchemas()
            val genericSchema = requireNotNull(genericSchemas[genericSchemaName])
            genericSchema.properties[propertyName] = propertySchemaRef.ref
            val propertySchemas = propertySchemaRef.schemas
            val propertySchemaName = propertySchemaRef.name
            val schemaName = propertySchemaName + simpleName
            genericSchema.name = schemaName
            val schemas = (genericSchemas + propertySchemas)
                .minus(genericSchemaName)
                .plus(schemaName to genericSchema)
            return SchemaRef(schemaName, genericSchema, schemas)
        }
    }
}

fun Schema<*>.toContent(
    name: String = "*/*",
    customize: (Content) -> Unit = {}
): Content {
    val content = Content()
        .addMediaType(name, MediaType().schema(this))
    customize(content)
    return content
}

fun Schema<*>.toJsonContent(
    customize: (Content) -> Unit = {}
): Content {
    return this.toContent(Https.MediaType.APPLICATION_JSON, customize = customize)
}

class HeaderRef(
    override val name: String,
    override val component: Header
) : ComponentRef<Header> {
    override val ref: Header = name.toRefHeader()

    companion object {
        const val COMPONENTS_HEADERS_REF: String = COMPONENTS_REF + "headers/"
        val ERROR_CODE_HEADER = HeaderRef(
            name = CommandHeaders.WOW_ERROR_CODE,
            component = Header()
                .content(StringSchema().toContent())
                .description("Error Code"),
        )

        fun String.toRefHeader(): Header {
            return Header().`$ref`(COMPONENTS_HEADERS_REF + this)
        }

        fun MutableMap<String, Header>.with(headerRef: HeaderRef): MutableMap<String, Header> {
            put(headerRef.name, headerRef.component)
            return this
        }
    }
}

class ParameterRef(override val name: String, override val component: Parameter) : ComponentRef<Parameter> {
    override val ref: Parameter = name.toRefParameter()

    companion object {
        const val COMPONENTS_PARAMETERS_REF: String = COMPONENTS_REF + "parameters/"
        fun String.toRefParameter(): Parameter {
            return Parameter().`$ref`(COMPONENTS_PARAMETERS_REF + this)
        }

        fun MutableMap<String, Parameter>.with(parameterRef: ParameterRef): MutableMap<String, Parameter> {
            put(parameterRef.name, parameterRef.component)
            return this
        }

        fun MutableList<Parameter>.withParameter(
            name: String,
            parameterIn: ParameterIn,
            schema: Schema<*>,
            customize: (Parameter) -> Unit = {}
        ): MutableList<Parameter> {
            any {
                it.name == name && it.`in` == parameterIn.toString()
            }.let {
                if (it) {
                    return this
                }
            }
            val parameter = Parameter()
                .name(name)
                .`in`(parameterIn.toString())
                .schema(schema)
            customize(parameter)
            add(parameter)
            return this
        }
    }
}

class RequestBodyRef(override val name: String, override val component: RequestBody) : ComponentRef<RequestBody> {
    override val ref: RequestBody = name.toRefRequestBody()

    companion object {
        const val COMPONENTS_REQUEST_BODIES_REF: String = COMPONENTS_REF + "requestBodies/"
        fun String.toRefRequestBody(): RequestBody {
            return RequestBody().`$ref`(COMPONENTS_REQUEST_BODIES_REF + this)
        }

        fun Schema<*>.toRequestBody(): RequestBody {
            return RequestBody()
                .required(true)
                .content(
                    toJsonContent()
                )
        }

        fun Class<*>.toRequestBody(): RequestBody {
            return toRefSchema().toRequestBody()
        }

        fun Class<*>.toRequestBodyRef(): RequestBodyRef {
            return RequestBodyRef(
                name = requireNotNull(toSchemaName()),
                component = toRequestBody()
            )
        }

        fun MutableMap<String, RequestBody>.with(requestBodyRef: RequestBodyRef): MutableMap<String, RequestBody> {
            put(requestBodyRef.name, requestBodyRef.component)
            return this
        }
    }
}

class ResponseRef(override val name: String, override val component: ApiResponse, val code: String = Https.Code.OK) :
    ComponentRef<ApiResponse> {
    override val ref: ApiResponse = name.toRefResponse()

    companion object {
        val COMPONENTS_RESPONSES_REF: String = COMPONENTS_REF + "responses/"
        fun String.toRefResponse(): ApiResponse {
            return ApiResponse().`$ref`(COMPONENTS_RESPONSES_REF + this)
        }

        val ERROR_INFO_CONTENT = ErrorInfo::class.java.toRefSchema().toJsonContent()

        fun Content.toResponse(description: String = ErrorInfo.SUCCEEDED): ApiResponse {
            return ApiResponse()
                .addHeaderObject(CommandHeaders.WOW_ERROR_CODE, ERROR_CODE_HEADER.ref)
                .description(description)
                .content(this)
        }

        fun Schema<*>.toResponse(
            mediaType: String = Https.MediaType.APPLICATION_JSON,
            description: String = ErrorInfo.SUCCEEDED
        ): ApiResponse {
            return toContent(mediaType).toResponse(description)
        }

        fun Class<*>.toResponse(isArray: Boolean = false, description: String = ErrorInfo.SUCCEEDED): ApiResponse {
            val responseSchema = this.toRefSchema()
            return if (isArray) {
                responseSchema.toArraySchema()
            } else {
                responseSchema
            }.toResponse(description = description)
        }

        val BAD_REQUEST = ResponseRef(
            name = "${Wow.WOW_PREFIX}BadRequest",
            component = ERROR_INFO_CONTENT.toResponse("Bad Request"),
            code = Https.Code.BAD_REQUEST
        )
        val NOT_FOUND = ResponseRef(
            name = "${Wow.WOW_PREFIX}NotFound",
            component = ERROR_INFO_CONTENT.toResponse("Not Found"),
            code = Https.Code.NOT_FOUND
        )
        val REQUEST_TIMEOUT = ResponseRef(
            name = "${Wow.WOW_PREFIX}RequestTimeout",
            component = ERROR_INFO_CONTENT.toResponse("Request Timeout"),
            code = Https.Code.REQUEST_TIMEOUT
        )
        val TOO_MANY_REQUESTS = ResponseRef(
            name = "${Wow.WOW_PREFIX}TooManyRequests",
            component = ERROR_INFO_CONTENT.toResponse("Too Many Requests"),
            code = Https.Code.TOO_MANY_REQUESTS
        )

        fun MutableMap<String, ApiResponse>.with(responseRef: ResponseRef): MutableMap<String, ApiResponse> {
            put(responseRef.name, responseRef.component)
            return this
        }

        fun MutableMap<String, ApiResponse>.withBadRequest(): MutableMap<String, ApiResponse> {
            return with(BAD_REQUEST)
        }

        fun MutableMap<String, ApiResponse>.withNotFound(): MutableMap<String, ApiResponse> {
            return with(NOT_FOUND)
        }

        fun MutableMap<String, ApiResponse>.withRequestTimeout(): MutableMap<String, ApiResponse> {
            return with(REQUEST_TIMEOUT)
        }

        fun MutableMap<String, ApiResponse>.withTooManyRequests(): MutableMap<String, ApiResponse> {
            return with(TOO_MANY_REQUESTS)
        }

        fun ApiResponses.with(responseRef: ResponseRef): ApiResponses {
            return addApiResponse(responseRef.code, responseRef.component)
        }

        fun ApiResponses.withBadRequest(): ApiResponses {
            return with(BAD_REQUEST)
        }

        fun ApiResponses.withNotFound(): ApiResponses {
            return with(NOT_FOUND)
        }

        fun ApiResponses.withRequestTimeout(): ApiResponses {
            return with(REQUEST_TIMEOUT)
        }

        fun ApiResponses.withTooManyRequests(): ApiResponses {
            return with(TOO_MANY_REQUESTS)
        }
    }
}
