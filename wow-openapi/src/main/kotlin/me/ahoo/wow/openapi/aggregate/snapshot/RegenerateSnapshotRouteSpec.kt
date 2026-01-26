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

package me.ahoo.wow.openapi.aggregate.snapshot

import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.CommonComponent.Response.notFoundResponse
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class RegenerateSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val componentContext: OpenAPIComponentContext
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .appendSpace(appendSpacePath)
            .resourceName("snapshot")
            .operation("regenerate")
            .build()

    override val method: String
        get() = Https.Method.PUT
    override val summary: String
        get() = "Regenerate Aggregate Snapshot"
    override val appendOwnerPath: Boolean
        get() = false
    override val appendIdPath: Boolean
        get() = true
    override val appendPathSuffix: String
        get() = "snapshot"
    override val responses: ApiResponses = ApiResponses().apply {
        addApiResponse(Https.Code.OK, ApiResponse())
        addApiResponse(Https.Code.NOT_FOUND, componentContext.notFoundResponse())
    }
}

class RegenerateSnapshotRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<AggregateRouteSpec> {
        return listOf(
            RegenerateSnapshotRouteSpec(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext
            )
        )
    }
}
