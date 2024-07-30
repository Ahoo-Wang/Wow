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

package me.ahoo.wow.openapi.event

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.withRequestTimeout
import me.ahoo.wow.openapi.RouteIdSpec

class ArchiveAggregateIdRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec {

    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("aggregate_id")
            .operation("archive")
            .build()

    override val method: String
        get() = Https.Method.PUT
    override val summary: String
        get() = "Archive AggregateId"
    override val appendPathSuffix: String
        get() = "event/aggregate_id"

    override val appendTenantPath: Boolean
        get() = false
    override val responses: ApiResponses
        get() = ApiResponses()
            .addApiResponse(Https.Code.OK, ApiResponse().description(ErrorInfo.SUCCEEDED))
            .withRequestTimeout()

    override val parameters: List<Parameter>
        get() = emptyList()
}

class ArchiveAggregateIdRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<AggregateRouteSpec> {
        return listOf(ArchiveAggregateIdRouteSpec(currentContext, aggregateMetadata))
    }
}
