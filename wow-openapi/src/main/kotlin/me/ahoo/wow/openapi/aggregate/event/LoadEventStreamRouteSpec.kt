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

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.BatchComponent.Parameter.headVersionPathParameter
import me.ahoo.wow.openapi.BatchComponent.Parameter.tailVersionPathParameter
import me.ahoo.wow.openapi.BatchComponent.PathVariable.HEAD_VERSION
import me.ahoo.wow.openapi.BatchComponent.PathVariable.TAIL_VERSION
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.QueryComponent.Response.loadEventStreamResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class LoadEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val componentContext: OpenAPIComponentContext
) : AggregateRouteSpec {

    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .appendSpace(appendSpacePath)
            .resourceName("event_stream")
            .operation("load").build()
    override val method: String
        get() = Https.Method.GET
    override val accept: List<String>
        get() = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
    override val appendOwnerPath: Boolean
        get() = false
    override val appendIdPath: Boolean
        get() = true
    override val summary: String
        get() = "Load Event Stream"
    override val responses: ApiResponses = ApiResponses().apply {
        addApiResponse(Https.Code.OK, componentContext.loadEventStreamResponse(aggregateMetadata))
    }

    override val appendPathSuffix: String
        get() = "event/{${HEAD_VERSION}}/{${TAIL_VERSION}}"
    override val parameters: List<Parameter>
        get() = super.parameters + listOf(
            componentContext.headVersionPathParameter(),
            componentContext.tailVersionPathParameter()
        )
}

class LoadEventStreamRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<AggregateRouteSpec> {
        return listOf(
            LoadEventStreamRouteSpec(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext
            )
        )
    }
}
