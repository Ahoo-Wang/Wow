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
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
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
import me.ahoo.wow.openapi.contributor.aggregate.createTimePathParameterRef
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.aggregate.versionPathParameterRef
import me.ahoo.wow.openapi.contributor.badRequestResponseRef
import me.ahoo.wow.openapi.contributor.errorCodeHeaderRef
import me.ahoo.wow.openapi.contributor.notFoundResponseRef
import me.ahoo.wow.openapi.contributor.schemaRef
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

object StateRouteContributor : RouteContributor {
    override val id: String = "aggregate.state"
    override val category: RouteCategory = RouteCategory.STATE
    override val order: Int = 200

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return listOf(
            aggregateTracingRoute(currentContext, aggregateRouteMetadata, componentContext),
            loadAggregateRoute(currentContext, aggregateRouteMetadata, componentContext),
            loadVersionedAggregateRoute(currentContext, aggregateRouteMetadata, componentContext),
            loadTimeBasedAggregateRoute(currentContext, aggregateRouteMetadata, componentContext)
        )
    }

    private fun aggregateTracingRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.AGGREGATE_TRACING,
            resourceName = "aggregate_tracing",
            operation = "get",
            summary = "Get Aggregate Tracing",
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = "state/tracing",
            extraParameters = tracingQueryParameters(componentContext),
            responses = tracingResponses(
                aggregateRouteMetadata,
                componentContext
            )
        )
    }

    private fun loadAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE,
            resourceName = "aggregate",
            operation = "load",
            summary = "Load State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.AGGREGATE_ID,
            appendPathSuffix = "state",
            responses = loadAggregateResponses(
                "Load State Aggregate",
                aggregateRouteMetadata,
                componentContext
            )
        )
    }

    private fun loadVersionedAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_VERSIONED_AGGREGATE,
            resourceName = "versioned_aggregate",
            operation = "load",
            summary = "Load Versioned State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.AGGREGATE_ID,
            appendPathSuffix = "state/{version}",
            extraParameters = listOf(componentContext.versionPathParameterRef()),
            responses = loadAggregateResponses(
                "Load Versioned State Aggregate",
                aggregateRouteMetadata,
                componentContext
            )
        )
    }

    private fun loadTimeBasedAggregateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return stateRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.State.LOAD_TIME_BASED_AGGREGATE,
            resourceName = "time_based_aggregate",
            operation = "load",
            summary = "Load Time Based State Aggregate",
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.AGGREGATE_ID,
            appendPathSuffix = "state/time/{createTime}",
            extraParameters = listOf(componentContext.createTimePathParameterRef()),
            responses = loadAggregateResponses(
                "Load Time Based State Aggregate",
                aggregateRouteMetadata,
                componentContext
            )
        )
    }

    private fun stateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
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
            produce = listOf(Https.MediaType.APPLICATION_JSON),
            parameters = componentContext.aggregateParameters(
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

    private fun tracingQueryParameters(componentContext: OpenAPIComponentContext): List<HttpParameter> {
        return listOf(
            componentContext.tracingQueryParameter(
                key = HEAD_VERSION_PARAMETER_KEY,
                name = HEAD_VERSION,
                description = "The first aggregate version to emit."
            ),
            componentContext.tracingQueryParameter(
                key = TAIL_VERSION_PARAMETER_KEY,
                name = TAIL_VERSION,
                description = "The last aggregate version to replay and emit."
            ),
            componentContext.tracingQueryParameter(
                key = LIMIT_PARAMETER_KEY,
                name = LIMIT,
                description = "The maximum number of tail versions to emit."
            )
        )
    }

    private fun OpenAPIComponentContext.tracingQueryParameter(
        key: String,
        name: String,
        description: String
    ): HttpParameter {
        parameter(key) {
            this.name = name
            schema = IntegerSchema().description(description)
            `in`(ParameterIn.QUERY.toString())
            required = false
        }
        return HttpParameter(
            name = name,
            location = HttpParameterLocation.QUERY,
            componentRef = key
        )
    }

    private fun tracingResponses(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                description = "Get Aggregate Tracing",
                headers = listOf(componentContext.errorCodeHeaderRef()),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.Array(
                            HttpSchema.TypeRef(
                                StateEvent::class.java,
                                listOf(aggregateRouteMetadata.aggregateMetadata.state.aggregateType)
                            )
                        )
                    )
                )
            )
        )
    }

    private fun loadAggregateResponses(
        summary: String,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                description = summary,
                headers = listOf(componentContext.errorCodeHeaderRef()),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        schemaRef(aggregateRouteMetadata.aggregateMetadata.state.aggregateType)
                    )
                )
            ),
            componentContext.badRequestResponseRef(),
            componentContext.notFoundResponseRef()
        )
    }

    private const val HEAD_VERSION = "headVersion"
    private const val TAIL_VERSION = "tailVersion"
    private const val LIMIT = "limit"
    private const val HEAD_VERSION_PARAMETER_KEY = "wow.aggregate-tracing.headVersion"
    private const val TAIL_VERSION_PARAMETER_KEY = "wow.aggregate-tracing.tailVersion"
    private const val LIMIT_PARAMETER_KEY = "wow.aggregate-tracing.limit"
}
