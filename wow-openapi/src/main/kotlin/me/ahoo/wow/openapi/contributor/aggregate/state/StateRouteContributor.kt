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

package me.ahoo.wow.openapi.contributor.aggregate.state

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteSuffixes

/**
 * The state routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
object StateRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        return listOf(
            aggregateTracingRoute(currentContext, aggregateRouteMetadata),
            loadAggregateRoute(currentContext, aggregateRouteMetadata),
            loadVersionedAggregateRoute(currentContext, aggregateRouteMetadata),
            loadTimeBasedAggregateRoute(currentContext, aggregateRouteMetadata)
        )
    }

    private fun aggregateTracingRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING,
            resourceName = "aggregate_tracing",
            operation = "get",
            summary = "Get Aggregate Tracing",
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = RouteSuffixes.STATE_TRACING,
            extraParameters = tracingQueryParameters,
            responses = tracingResponses(
                aggregateRouteMetadata
            )
        )
    }

    private fun loadAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
            resourceName = "aggregate",
            operation = "load",
            summary = "Load State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.AGGREGATE_ID,
            appendPathSuffix = RouteSuffixes.STATE,
            responses = loadAggregateResponses(
                "Load State Aggregate",
                aggregateRouteMetadata
            )
        )
    }

    private fun loadVersionedAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE,
            resourceName = "versioned_aggregate",
            operation = "load",
            summary = "Load Versioned State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.AGGREGATE_ID,
            appendPathSuffix = RouteSuffixes.STATE_VERSIONED,
            extraParameters = listOf(CommonComponents.versionPathParameter),
            responses = loadAggregateResponses(
                "Load Versioned State Aggregate",
                aggregateRouteMetadata
            )
        )
    }

    private fun loadTimeBasedAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE,
            resourceName = "time_based_aggregate",
            operation = "load",
            summary = "Load Time Based State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.AGGREGATE_ID,
            appendPathSuffix = RouteSuffixes.STATE_TIME_BASED,
            extraParameters = listOf(CommonComponents.createTimePathParameter),
            responses = loadAggregateResponses(
                "Load Time Based State Aggregate",
                aggregateRouteMetadata
            )
        )
    }

    private fun stateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        handlerKey: String,
        resourceName: String,
        operation: String,
        summary: String,
        appendOwnerPath: Boolean,
        appendIdPath: Boolean,
        appendPathSuffix: String,
        extraParameters: List<HttpParameter> = emptyList(),
        responses: List<HttpResponse>
    ): HttpRouteContract {
        val appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath()
        return HttpRouteContract(
            routeId = RouteIdSpec()
                .aggregate(aggregateRouteMetadata.aggregateMetadata)
                .appendTenant(appendTenantPath)
                .resourceName(resourceName)
                .operation(operation)
                .build(),
            method = Https.Method.GET,
            path = aggregatePath(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                appendTenantPath = appendTenantPath,
                appendOwnerPath = appendOwnerPath,
                appendIdPath = appendIdPath,
                appendPathSuffix = appendPathSuffix
            ),
            handlerKey = handlerKey,
            summary = summary,
            parameters = aggregateParameters(
                aggregateRouteMetadata = aggregateRouteMetadata,
                appendTenantPath = appendTenantPath,
                appendOwnerPath = appendOwnerPath,
                appendIdPath = appendIdPath
            ) + extraParameters,
            responses = responses,
            tags = aggregateTags(aggregateRouteMetadata.aggregateMetadata),
            handlerMetadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
        )
    }

    private val tracingQueryParameters: List<HttpParameter> = listOf(
        tracingQueryParameter(
            name = HEAD_VERSION,
            description = "The first aggregate version to emit."
        ),
        tracingQueryParameter(
            name = TAIL_VERSION,
            description = "The last aggregate version to replay and emit."
        ),
        tracingQueryParameter(
            name = LIMIT,
            description = "The maximum number of tail versions to emit."
        )
    )

    /** An optional query parameter registered as the `wow.aggregate-tracing.{name}` component. */
    private fun tracingQueryParameter(name: String, description: String): HttpParameter {
        return HttpParameter(
            name = name,
            location = HttpParameterLocation.QUERY,
            component = HttpComponent.parameter("$TRACING_PARAMETER_KEY_PREFIX$name") {
                this.name = name
                schema = IntegerSchema().description(description)
                `in`(ParameterIn.QUERY.toString())
                required = false
            }
        )
    }

    private fun tracingResponses(
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                description = "Get Aggregate Tracing",
                headers = listOf(CommonComponents.errorCodeHeader),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.Array(
                            HttpSchema.TypeRef(
                                StateEvent::class.java,
                                listOf(HttpSchema.TypeRef(aggregateRouteMetadata.aggregateMetadata.state.aggregateType))
                            )
                        )
                    )
                )
            )
        )
    }

    private fun loadAggregateResponses(
        summary: String,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                description = summary,
                headers = listOf(CommonComponents.errorCodeHeader),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.TypeRef(aggregateRouteMetadata.aggregateMetadata.state.aggregateType)
                    )
                )
            ),
            CommonComponents.badRequestResponse,
            CommonComponents.notFoundResponse
        )
    }

    private const val HEAD_VERSION = "headVersion"
    private const val TAIL_VERSION = "tailVersion"
    private const val LIMIT = "limit"
    private const val TRACING_PARAMETER_KEY_PREFIX = "wow.aggregate-tracing."
}
