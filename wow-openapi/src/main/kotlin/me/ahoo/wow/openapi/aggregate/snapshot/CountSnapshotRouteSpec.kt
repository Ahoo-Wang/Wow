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

import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.QueryComponent.RequestBody.countQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.Response.countQueryResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.AbstractTenantOwnerAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class CountSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val appendTenantPath: Boolean,
    override val appendOwnerPath: Boolean,
    override val componentContext: OpenAPIComponentContext
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .appendOwner(appendOwnerPath)
            .resourceName("snapshot")
            .operation("count")
            .build()

    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "snapshot/count"

    override val summary: String
        get() = "Count snapshot"
    override val requestBody: RequestBody = componentContext.countQueryRequestBody()

    override val responses: ApiResponses
        get() = ApiResponses().apply {
            addApiResponse(Https.Code.OK, componentContext.countQueryResponse())
        }
}

class CountSnapshotRouteSpecFactory : AbstractTenantOwnerAggregateRouteSpecFactory() {
    override fun createSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean
    ): AggregateRouteSpec {
        return CountSnapshotRouteSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            appendTenantPath = appendTenantPath,
            appendOwnerPath = appendOwnerPath,
            componentContext = componentContext
        )
    }
}
