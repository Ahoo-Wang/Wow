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

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.CommonComponent.Header
import me.ahoo.wow.openapi.CommonComponent.Header.errorCodeHeader
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

class AggregateTracingRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val componentContext: OpenAPIComponentContext
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("aggregate_tracing")
            .operation("get")
            .build()

    override val method: String
        get() = Https.Method.GET
    override val appendOwnerPath: Boolean
        get() = false
    override val appendIdPath: Boolean
        get() = true
    override val appendPathSuffix: String
        get() = "state/tracing"
    override val summary: String
        get() = "Get Aggregate Tracing"
    override val parameters: List<Parameter>
        get() = super.parameters + listOf(
            componentContext.tracingQueryParameter(
                key = HEAD_VERSION_PARAMETER_KEY,
                name = HEAD_VERSION,
                description = "The first aggregate version to emit.",
            ),
            componentContext.tracingQueryParameter(
                key = TAIL_VERSION_PARAMETER_KEY,
                name = TAIL_VERSION,
                description = "The last aggregate version to replay and emit.",
            ),
            componentContext.tracingQueryParameter(
                key = LIMIT_PARAMETER_KEY,
                name = LIMIT,
                description = "The maximum number of tail versions to emit.",
            ),
        )
    override val requestBody: RequestBody? = null
    override val responses: ApiResponses = ApiResponses().apply {
        ApiResponseBuilder()
            .description(summary)
            .header(Header.ERROR_CODE, componentContext.errorCodeHeader())
            .content(
                schema = componentContext.arraySchema(
                    StateEvent::class.java,
                    aggregateMetadata.state.aggregateType
                )
            )
            .build()
            .let {
                addApiResponse(Https.Code.OK, it)
            }
    }

    private fun OpenAPIComponentContext.tracingQueryParameter(
        key: String,
        name: String,
        description: String
    ): Parameter {
        return parameter(key) {
            this.name = name
            schema = IntegerSchema().description(description)
            `in`(ParameterIn.QUERY.toString())
            required = false
        }
    }

    private companion object {
        const val HEAD_VERSION = "headVersion"
        const val TAIL_VERSION = "tailVersion"
        const val LIMIT = "limit"
        const val HEAD_VERSION_PARAMETER_KEY = "wow.aggregate-tracing.headVersion"
        const val TAIL_VERSION_PARAMETER_KEY = "wow.aggregate-tracing.tailVersion"
        const val LIMIT_PARAMETER_KEY = "wow.aggregate-tracing.limit"
    }
}

class AggregateTracingRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<AggregateRouteSpec> {
        val routeSpec = AggregateTracingRouteSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext
        )
        return listOf(routeSpec)
    }
}
