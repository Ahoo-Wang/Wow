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

package me.ahoo.wow.openapi.aggregate.state

import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.withBadRequest
import me.ahoo.wow.openapi.ResponseRef.Companion.withNotFound
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class LoadAggregateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("aggregate")
            .operation("load")
            .build()

    override val method: String
        get() = Https.Method.GET
    override val appendIdPath: Boolean
        get() = aggregateRouteMetadata.owner != AggregateRoute.Owner.AGGREGATE_ID
    override val appendPathSuffix: String
        get() = "state"

    override val summary: String
        get() = "Load state aggregate"

    override val requestBody: RequestBody? = null
    override val responses: ApiResponses
        get() = aggregateMetadata.state.aggregateType.toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }.withBadRequest().withNotFound()
}

class LoadAggregateRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<RouteSpec> {
        aggregateRouteMetadata.aggregateMetadata.state.aggregateType.toSchemas().mergeSchemas()
        return listOf(LoadAggregateRouteSpec(currentContext, aggregateRouteMetadata))
    }
}
