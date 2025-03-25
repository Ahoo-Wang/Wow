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
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.errorCode
import me.ahoo.wow.openapi.CommonComponent.Schema.errorInfo
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.serialization.MessageRecords

object CommonComponent {

    object Header {
        const val WOW_ERROR_CODE = "Wow-Error-Code"

        fun OpenAPIComponentContext.errorCode(): io.swagger.v3.oas.models.headers.Header =
            header("${Wow.WOW_PREFIX}.${WOW_ERROR_CODE}") {
                schema = StringSchema().example(ErrorCodes.SUCCEEDED)
                description = "Error code"
            }
    }

    object Schema {
        fun OpenAPIComponentContext.errorInfo(): io.swagger.v3.oas.models.media.Schema<*> =
            schema(DefaultErrorInfo::class.java)
    }

    object Parameter {
        fun OpenAPIComponentContext.id(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.ID
                schema = StringSchema().description("aggregate id")
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.ownerId(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.OWNER_ID
                schema = StringSchema().description("aggregate owner id")
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.tenantId(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.TENANT_ID
                schema = StringSchema().description("aggregate tenant id")._default(TenantId.DEFAULT_TENANT_ID)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.version(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.VERSION
                schema = IntegerSchema().description("aggregate version")._default(Int.MAX_VALUE)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.createTime(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.CREATE_TIME
                schema = IntegerSchema()
                `in`(ParameterIn.PATH.toString())
            }
    }

    object Response {

        fun OpenAPIComponentContext.ok(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.SUCCEEDED}") {
                description(ErrorCodes.SUCCEEDED_MESSAGE)
                content(schema = errorInfo())
            }

        fun OpenAPIComponentContext.badRequest(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.BAD_REQUEST}") {
                description("Bad Request")
                header(Header.WOW_ERROR_CODE, errorCode())
                content(schema = errorInfo())
            }

        fun OpenAPIComponentContext.notFound(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.NOT_FOUND}") {
                description("Not Found")
                header(Header.WOW_ERROR_CODE, errorCode())
                content(schema = errorInfo())
            }

        fun OpenAPIComponentContext.requestTimeout(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.REQUEST_TIMEOUT}") {
                description("Request Timeout")
                header(Header.WOW_ERROR_CODE, errorCode())
                content(schema = errorInfo())
            }

        fun OpenAPIComponentContext.tooManyRequests(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.TOO_MANY_REQUESTS}") {
                description("Too Many Requests")
                header(Header.WOW_ERROR_CODE, errorCode())
                content(schema = errorInfo())
            }

        fun OpenAPIComponentContext.internalServerError(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.INTERNAL_SERVER_ERROR}") {
                description("Internal Server Error")
                header(Header.WOW_ERROR_CODE, errorCode())
                content(schema = errorInfo())
            }
    }
}
