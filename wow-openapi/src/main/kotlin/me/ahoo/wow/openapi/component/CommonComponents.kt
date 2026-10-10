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

package me.ahoo.wow.openapi.component

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.HttpComponentContext
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.rest.WowHeaders.ERROR_CODE
import me.ahoo.wow.rest.WowHeaders.SPACE_ID

/** The components every Wow service shares: the error code header, the error responses and the path parameters. */
internal object CommonComponents {
    const val UNSUPPORTED_MEDIA_TYPE_ERROR_CODE = "UnsupportedMediaType"

    val ERROR_CODE_HEADER = HttpComponent.header(Wow.WOW_PREFIX + ERROR_CODE) {
        schema = StringSchema().example(ErrorCodes.SUCCEEDED)
        description = "Error code"
    }

    /** The `Wow-Error-Code` response header. */
    val errorCodeHeader = HttpHeader(name = ERROR_CODE, component = ERROR_CODE_HEADER)

    fun ApiResponseBuilder.withErrorCodeHeader(context: HttpComponentContext): ApiResponseBuilder =
        header(ERROR_CODE, context.ref(ERROR_CODE_HEADER))

    val badRequestResponse = errorResponse(Https.Code.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "Bad Request")
    val notFoundResponse = errorResponse(Https.Code.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not Found")
    val requestTimeoutResponse =
        errorResponse(Https.Code.REQUEST_TIMEOUT, ErrorCodes.REQUEST_TIMEOUT, "Request Timeout")
    val unsupportedMediaTypeResponse = errorResponse(
        Https.Code.UNSUPPORTED_MEDIA_TYPE,
        UNSUPPORTED_MEDIA_TYPE_ERROR_CODE,
        "Unsupported Media Type"
    )
    val tooManyRequestsResponse =
        errorResponse(Https.Code.TOO_MANY_REQUESTS, ErrorCodes.TOO_MANY_REQUESTS, "Too Many Requests")

    private fun errorResponse(statusCode: String, errorCode: String, description: String): HttpResponse {
        val component: HttpComponent<ApiResponse> = HttpComponent.response(Wow.WOW_PREFIX + errorCode) { context ->
            withErrorCodeHeader(context)
            description(description)
            content(schema = context.schema(DefaultErrorInfo::class.java))
        }
        return HttpResponse(statusCode = statusCode, component = component)
    }

    val spaceIdHeaderParameter = HttpParameter(
        name = SPACE_ID,
        location = HttpParameterLocation.HEADER,
        component = HttpComponent.parameter(Wow.WOW_PREFIX + SPACE_ID) {
            name = SPACE_ID
            schema = StringSchema().description("aggregate space id").example(SpaceIdCapable.DEFAULT_SPACE_ID)
            `in`(ParameterIn.HEADER.toString())
        }
    )

    val idPathParameter = pathParameter(RouteVariables.ID) {
        schema = StringSchema().description("aggregate id")
    }

    val ownerIdPathParameter = pathParameter(RouteVariables.OWNER_ID) {
        schema = StringSchema().description("aggregate owner id")
    }

    val tenantIdPathParameter = pathParameter(RouteVariables.TENANT_ID) {
        schema = StringSchema().description("aggregate tenant id").example(TenantId.DEFAULT_TENANT_ID)
    }

    val versionPathParameter = pathParameter(RouteVariables.VERSION) {
        schema = IntegerSchema().description("aggregate version").example(EventStore.DEFAULT_TAIL_VERSION)
    }

    val createTimePathParameter = pathParameter(RouteVariables.CREATE_TIME) {
        schema = IntegerSchema()
    }
}

/** A required path parameter registered as the `wow.{name}` component. */
internal fun pathParameter(
    name: String,
    builder: io.swagger.v3.oas.models.parameters.Parameter.(HttpComponentContext) -> Unit
): HttpParameter = HttpParameter(
    name = name,
    location = HttpParameterLocation.PATH,
    required = true,
    component = HttpComponent.parameter(Wow.WOW_PREFIX + name) { context ->
        this.name = name
        builder(context)
        `in`(ParameterIn.PATH.toString())
    }
)
