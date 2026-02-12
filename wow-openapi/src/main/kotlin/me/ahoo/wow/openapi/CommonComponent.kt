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
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.CommonComponent.Schema.errorInfoSchema
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.serialization.MessageRecords

object CommonComponent {

    object Header {
        const val ERROR_CODE = "Wow-Error-Code"
        const val SPACE_ID = "Wow-Space-Id"
        fun OpenAPIComponentContext.errorCodeHeader(): io.swagger.v3.oas.models.headers.Header =
            header("${Wow.WOW_PREFIX}${ERROR_CODE}") {
                schema = StringSchema().example(ErrorCodes.SUCCEEDED)
                description = "Error code"
            }
    }

    object Schema {
        fun OpenAPIComponentContext.errorInfoSchema(): io.swagger.v3.oas.models.media.Schema<*> =
            schema(DefaultErrorInfo::class.java)
    }

    object Parameter {
        fun OpenAPIComponentContext.spaceIdHeaderParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = Header.SPACE_ID
                schema = StringSchema().description("aggregate space id").example(SpaceIdCapable.DEFAULT_SPACE_ID)
                `in`(ParameterIn.HEADER.toString())
            }

        fun OpenAPIComponentContext.idPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.ID
                schema = StringSchema().description("aggregate id")
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.ownerIdPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.OWNER_ID
                schema = StringSchema().description("aggregate owner id")
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.tenantIdPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.TENANT_ID
                schema = StringSchema().description("aggregate tenant id").example(TenantId.DEFAULT_TENANT_ID)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.versionPathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.VERSION
                schema = IntegerSchema().description("aggregate version").example(EventStore.DEFAULT_TAIL_VERSION)
                `in`(ParameterIn.PATH.toString())
            }

        fun OpenAPIComponentContext.createTimePathParameter(): io.swagger.v3.oas.models.parameters.Parameter =
            parameter {
                name = MessageRecords.CREATE_TIME
                schema = IntegerSchema()
                `in`(ParameterIn.PATH.toString())
            }
    }

    object Response {

        fun ApiResponseBuilder.withErrorCodeHeader(componentContext: OpenAPIComponentContext): ApiResponseBuilder {
            return header(Header.ERROR_CODE, componentContext.errorCodeHeader())
        }

        fun OpenAPIComponentContext.badRequestResponse(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.BAD_REQUEST}") {
                withErrorCodeHeader(this@badRequestResponse)
                description("Bad Request")
                content(schema = errorInfoSchema())
            }

        fun OpenAPIComponentContext.notFoundResponse(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.NOT_FOUND}") {
                withErrorCodeHeader(this@notFoundResponse)
                description("Not Found")
                content(schema = errorInfoSchema())
            }

        fun OpenAPIComponentContext.requestTimeoutResponse(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.REQUEST_TIMEOUT}") {
                withErrorCodeHeader(this@requestTimeoutResponse)
                description("Request Timeout")
                content(schema = errorInfoSchema())
            }

        fun OpenAPIComponentContext.tooManyRequestsResponse(): io.swagger.v3.oas.models.responses.ApiResponse =
            response("${Wow.WOW_PREFIX}${ErrorCodes.TOO_MANY_REQUESTS}") {
                withErrorCodeHeader(this@tooManyRequestsResponse)
                description("Too Many Requests")
                content(schema = errorInfoSchema())
            }
    }
}
