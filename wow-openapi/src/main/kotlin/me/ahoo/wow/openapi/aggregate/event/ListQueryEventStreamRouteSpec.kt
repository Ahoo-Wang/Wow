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

package me.ahoo.wow.openapi.aggregate.event

import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.QueryComponent.RequestBody.listQueryRequestBody
import me.ahoo.wow.openapi.QueryComponent.Response.loadEventStreamResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.AbstractTenantSpaceOwnerAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.aggregate.TenantSpaceOwnerAggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class ListQueryEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val appendTenantPath: Boolean,
    override val appendSpacePath: Boolean,
    override val appendOwnerPath: Boolean,
    override val componentContext: OpenAPIComponentContext
) : TenantSpaceOwnerAggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .appendOwner(appendOwnerPath)
            .resourceName("event")
            .operation("list_query")
            .build()
    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "event/list"
    override val accept: List<String>
        get() = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
    override val operationSummary: String
        get() = "List Query Event Stream"
    override val requestBody: RequestBody = componentContext.listQueryRequestBody()

    override val responses: ApiResponses = ApiResponses().apply {
        addApiResponse(Https.Code.OK, componentContext.loadEventStreamResponse(aggregateMetadata))
    }
}

class ListQueryEventStreamRouteSpecFactory : AbstractTenantSpaceOwnerAggregateRouteSpecFactory() {
    override fun createSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        appendTenantPath: Boolean,
        appendSpacePath: Boolean,
        appendOwnerPath: Boolean
    ): AggregateRouteSpec {
        return ListQueryEventStreamRouteSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            appendTenantPath = appendTenantPath,
            appendSpacePath = appendSpacePath,
            appendOwnerPath = appendOwnerPath,
            componentContext = componentContext
        )
    }
}
