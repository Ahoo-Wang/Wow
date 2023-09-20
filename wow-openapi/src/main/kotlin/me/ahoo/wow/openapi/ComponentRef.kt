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
import me.ahoo.wow.configuration.asNamedBoundedContext
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.ComponentRef.Companion.COMPONENTS_REF
import me.ahoo.wow.openapi.HeaderRef.Companion.ERROR_CODE_HEADER
import me.ahoo.wow.openapi.SchemaRef.Companion.asArraySchema
import me.ahoo.wow.openapi.SchemaRef.Companion.asRefSchema
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemName
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
    override val ref: Schema<*> = name.asRefSchema()

    companion object {
        val COMPONENTS_SCHEMAS_REF: String = COMPONENTS_REF + "schemas/"
        val ERROR_INFO = ErrorInfo::class.java.asSchemaRef()

        fun Class<out Enum<*>>.asSchemaRef(default: String? = null): SchemaRef {
            val enumSchema = StringSchema()
            enumSchema._enum(this.enumConstants.map { it.toString() })
            val schemaName = requireNotNull(this.asSchemName())
            if (default.isNullOrBlank().not()) {
                enumSchema.setDefault(default)
            }
            return SchemaRef(schemaName, enumSchema)
        }

        fun Class<*>.asSchemas(): Map<String, Schema<*>> {
            return ModelConverters.getInstance().readAll(this)
        }

        fun String.asRefSchema(): Schema<*> {
            return Schema<Any>().`$ref`(COMPONENTS_SCHEMAS_REF + this)
        }

        fun Class<*>.asRefSchema(): Schema<*> {
            return requireNotNull(this.asSchemName()).asRefSchema()
        }

        fun Schema<*>.asArraySchema(): ArraySchema {
            return ArraySchema().items(this)
        }

        fun Class<*>.asSchemName(): String? {
            this.scan<io.swagger.v3.oas.annotations.media.Schema>()?.let {
                if (it.name.isNotBlank()) {
                    return it.name
                }
            }
            asNamedBoundedContext()?.let {
                it.getContextAlias().let { alias ->
                    return "$alias.$simpleName"
                }
            }
            if (name.startsWith("me.ahoo.wow.")) {
                return Wow.WOW_PREFIX + simpleName
            }
            return null
        }

        fun Class<*>.asSchemaRef(): SchemaRef {
            val schemaName = requireNotNull(this.asSchemName())
            val schemas = asSchemas()
            val component = requireNotNull(schemas[schemaName])
            return SchemaRef(schemaName, component, schemas)
        }

        fun Class<*>.asSchemaRef(propertyName: String, propertyType: Class<*>): SchemaRef {
            val genericSchemaName = requireNotNull(this.asSchemName())
            val genericSchemas = asSchemas()
            val genericSchema = requireNotNull(genericSchemas[genericSchemaName])
            genericSchema.properties[propertyName] = propertyType.asSchemaRef().ref
            val propertySchemas = propertyType.asSchemas()
            val propertySchemaName = propertyType.asSchemName()
            val schemaName = propertySchemaName + simpleName
            genericSchema.name = schemaName
            val schemas = (genericSchemas + propertySchemas)
                .minus(genericSchemaName)
                .plus(schemaName to genericSchema)
            return SchemaRef(schemaName, genericSchema, schemas)
        }
    }
}

fun Schema<*>.asContent(
    name: String = "*",
    customize: (Content) -> Unit = {}
): Content {
    val content = Content()
        .addMediaType(name, MediaType().schema(this))
    customize(content)
    return content
}

fun Schema<*>.asJsonContent(
    customize: (Content) -> Unit = {}
): Content {
    return this.asContent(Https.MediaType.APPLICATION_JSON, customize = customize)
}

class HeaderRef(
    override val name: String,
    override val component: Header
) : ComponentRef<Header> {
    override val ref: Header = name.asRefHeader()

    companion object {
        const val COMPONENTS_HEADERS_REF: String = COMPONENTS_REF + "headers/"
        val ERROR_CODE_HEADER = HeaderRef(
            name = CommandHeaders.WOW_ERROR_CODE,
            component = Header()
                .content(StringSchema().asContent())
                .description("Error Code"),
        )

        fun String.asRefHeader(): Header {
            return Header().`$ref`(COMPONENTS_HEADERS_REF + this)
        }

        fun MutableMap<String, Header>.with(headerRef: HeaderRef): MutableMap<String, Header> {
            put(headerRef.name, headerRef.component)
            return this
        }
    }
}

class ParameterRef(override val name: String, override val component: Parameter) : ComponentRef<Parameter> {
    override val ref: Parameter = name.asRefParameter()

    companion object {
        const val COMPONENTS_PARAMETERS_REF: String = COMPONENTS_REF + "parameters/"
        fun String.asRefParameter(): Parameter {
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
    override val ref: RequestBody = name.asRefRequestBody()

    companion object {
        const val COMPONENTS_REQUEST_BODIES_REF: String = COMPONENTS_REF + "requestBodies/"
        fun String.asRefRequestBody(): RequestBody {
            return RequestBody().`$ref`(COMPONENTS_REQUEST_BODIES_REF + this)
        }

        fun Schema<*>.asRequestBody(): RequestBody {
            return RequestBody()
                .required(true)
                .content(
                    asJsonContent()
                )
        }

        fun Class<*>.asRequestBody(): RequestBody {
            return asRefSchema().asRequestBody()
        }

        fun Class<*>.asRequestBodyRef(): RequestBodyRef {
            return RequestBodyRef(
                name = requireNotNull(asSchemName()),
                component = asRequestBody()
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
    override val ref: ApiResponse = name.asRefResponse()

    companion object {
        val COMPONENTS_RESPONSES_REF: String = COMPONENTS_REF + "responses/"
        fun String.asRefResponse(): ApiResponse {
            return ApiResponse().`$ref`(COMPONENTS_RESPONSES_REF + this)
        }

        val ERROR_INFO_CONTENT = ErrorInfo::class.java.asRefSchema().asJsonContent()

        fun Content.asResponse(description: String = ErrorInfo.SUCCEEDED): ApiResponse {
            return ApiResponse()
                .addHeaderObject(CommandHeaders.WOW_ERROR_CODE, ERROR_CODE_HEADER.ref)
                .description(description)
                .content(this)
        }

        fun Schema<*>.asResponse(description: String = ErrorInfo.SUCCEEDED): ApiResponse {
            return asJsonContent().asResponse(description)
        }

        fun Class<*>.asResponse(isArray: Boolean = false, description: String = ErrorInfo.SUCCEEDED): ApiResponse {
            val responseSchema = this.asRefSchema()
            return if (isArray) {
                responseSchema.asArraySchema()
            } else {
                responseSchema
            }.asResponse(description)
        }

        val BAD_REQUEST = ResponseRef(
            name = "${Wow.WOW_PREFIX}BadRequest",
            component = ERROR_INFO_CONTENT.asResponse("Bad Request"),
            code = Https.Code.BAD_REQUEST
        )
        val NOT_FOUND = ResponseRef(
            name = "${Wow.WOW_PREFIX}NotFound",
            component = ERROR_INFO_CONTENT.asResponse("Not Found"),
            code = Https.Code.NOT_FOUND
        )
        val REQUEST_TIMEOUT = ResponseRef(
            name = "${Wow.WOW_PREFIX}RequestTimeout",
            component = ERROR_INFO_CONTENT.asResponse("Request Timeout"),
            code = Https.Code.REQUEST_TIMEOUT
        )
        val TOO_MANY_REQUESTS = ResponseRef(
            name = "${Wow.WOW_PREFIX}TooManyRequests",
            component = ERROR_INFO_CONTENT.asResponse("Too Many Requests"),
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
