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
import me.ahoo.wow.openapi.BatchComponent.Parameter.batchAfterIdPathParameter
import me.ahoo.wow.openapi.BatchComponent.Parameter.batchLimitPathParameter
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_AFTER_ID
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_LIMIT
import me.ahoo.wow.openapi.BatchComponent.Response.batchResultResponse
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse

internal fun OpenAPIComponentContext.batchAfterIdPathParameterRef(): HttpParameter {
    batchAfterIdPathParameter()
    return batchPathParameterRef(BATCH_AFTER_ID)
}

internal fun OpenAPIComponentContext.batchLimitPathParameterRef(): HttpParameter {
    batchLimitPathParameter()
    return batchPathParameterRef(BATCH_LIMIT)
}

internal fun OpenAPIComponentContext.batchResultResponseRef(): HttpResponse {
    batchResultResponse()
    return HttpResponse(
        statusCode = Https.Code.OK,
        componentRef = "${Wow.WOW_PREFIX}BatchResult"
    )
}

private fun batchPathParameterRef(name: String): HttpParameter {
    return HttpParameter(
        name = name,
        location = HttpParameterLocation.PATH,
        required = true,
        componentRef = "${Wow.WOW_PREFIX}$name"
    )
}
