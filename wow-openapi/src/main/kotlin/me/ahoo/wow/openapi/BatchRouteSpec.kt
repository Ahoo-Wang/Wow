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
import me.ahoo.wow.openapi.BatchComponent.Parameter.batchAfterIdPathParameter
import me.ahoo.wow.openapi.BatchComponent.Parameter.batchLimitPathParameter
import me.ahoo.wow.openapi.BatchComponent.Response.batchResultResponse
import me.ahoo.wow.openapi.CommonComponent.Response.requestTimeoutResponse
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec

interface BatchRouteSpec : AggregateRouteSpec {
    override val appendSpacePath: Boolean
        get() = false
    override val appendTenantPath: Boolean
        get() = false

    override val responses: ApiResponses
        get() = ApiResponses().apply {
            addApiResponse(Https.Code.OK, componentContext.batchResultResponse())
            addApiResponse(Https.Code.REQUEST_TIMEOUT, componentContext.requestTimeoutResponse())
        }

    override val parameters: List<Parameter>
        get() = super.parameters + listOf(
            componentContext.batchAfterIdPathParameter(),
            componentContext.batchLimitPathParameter()
        )
}

abstract class BatchRouteSpecFactory : AbstractAggregateRouteSpecFactory()
