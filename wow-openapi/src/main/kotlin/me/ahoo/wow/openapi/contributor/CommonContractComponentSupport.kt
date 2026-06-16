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

package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.api.Wow
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.CommonComponent.Header.ERROR_CODE
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.CommonComponent.Response.badRequestResponse
import me.ahoo.wow.openapi.CommonComponent.Response.notFoundResponse
import me.ahoo.wow.openapi.CommonComponent.Response.requestTimeoutResponse
import me.ahoo.wow.openapi.CommonComponent.Response.tooManyRequestsResponse
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpHeader
import me.ahoo.wow.openapi.contract.HttpResponse

internal fun OpenAPIComponentContext.errorCodeHeaderRef(): HttpHeader {
    errorCodeHeader()
    return HttpHeader(name = ERROR_CODE, componentRef = "${Wow.WOW_PREFIX}$ERROR_CODE")
}

internal fun OpenAPIComponentContext.badRequestResponseRef(): HttpResponse {
    badRequestResponse()
    return HttpResponse(
        statusCode = Https.Code.BAD_REQUEST,
        componentRef = "${Wow.WOW_PREFIX}${ErrorCodes.BAD_REQUEST}"
    )
}

internal fun OpenAPIComponentContext.notFoundResponseRef(): HttpResponse {
    notFoundResponse()
    return HttpResponse(
        statusCode = Https.Code.NOT_FOUND,
        componentRef = "${Wow.WOW_PREFIX}${ErrorCodes.NOT_FOUND}"
    )
}

internal fun OpenAPIComponentContext.requestTimeoutResponseRef(): HttpResponse {
    requestTimeoutResponse()
    return HttpResponse(
        statusCode = Https.Code.REQUEST_TIMEOUT,
        componentRef = "${Wow.WOW_PREFIX}${ErrorCodes.REQUEST_TIMEOUT}"
    )
}

internal fun OpenAPIComponentContext.tooManyRequestsResponseRef(): HttpResponse {
    tooManyRequestsResponse()
    return HttpResponse(
        statusCode = Https.Code.TOO_MANY_REQUESTS,
        componentRef = "${Wow.WOW_PREFIX}${ErrorCodes.TOO_MANY_REQUESTS}"
    )
}
