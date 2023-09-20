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

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.openapi.BatchRouteSpecFactory.Companion.BATCH_RESULT_RESPONSE
import me.ahoo.wow.openapi.ResponseRef.Companion.asOkResponse
import me.ahoo.wow.openapi.RoutePaths.BATCH_CURSOR_ID_PARAMETER
import me.ahoo.wow.openapi.RoutePaths.BATCH_LIMIT_PARAMETER

interface BatchRouteSpec : AggregateRouteSpec {
    override val appendTenantPath: Boolean
        get() = false

    override val responses: ApiResponses
        get() = ApiResponses().addApiResponse(Https.Code.OK, BATCH_RESULT_RESPONSE.ref)

    override val parameters: List<Parameter>
        get() = super.parameters + listOf(
            BATCH_CURSOR_ID_PARAMETER.ref,
            BATCH_LIMIT_PARAMETER.ref
        )
}

abstract class BatchRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    companion object {
        val BATCH_RESULT_RESPONSE = BatchResult::class.java.asOkResponse().let {
            ResponseRef("${Wow.WOW_PREFIX}BatchResult", it)
        }
    }
}
