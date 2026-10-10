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

package me.ahoo.wow.openapi.contributor.aggregate.event

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.BatchComponents
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.component.EventComponents
import me.ahoo.wow.openapi.component.QueryComponents
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contributor.aggregate.TenantOwnerVariant
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.aggregate.tenantOwnerSummary
import me.ahoo.wow.openapi.contributor.aggregate.tenantOwnerVariants
import me.ahoo.wow.openapi.contributor.aggregationResponse
import me.ahoo.wow.openapi.contributor.eventStreamCursorResponse
import me.ahoo.wow.openapi.contributor.eventStreamListResponse
import me.ahoo.wow.openapi.contributor.eventStreamPagedResponse
import me.ahoo.wow.openapi.contributor.querySchemaParameters
import me.ahoo.wow.openapi.contributor.querySchemaResponses
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteSuffixes

/**
 * The event routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
object EventRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        return buildList {
            add(eventSchemaRoute(currentContext, aggregateRouteMetadata))
            add(eventSchemaRefreshRoute(currentContext, aggregateRouteMetadata))
            aggregateRouteMetadata.tenantOwnerVariants().forEach { variant ->
                addAll(queryRoutes(currentContext, aggregateRouteMetadata, variant))
            }
            add(loadEventStreamRoute(currentContext, aggregateRouteMetadata))
            add(eventCompensateRoute(currentContext, aggregateRouteMetadata))
            add(resendStateEventRoute(currentContext, aggregateRouteMetadata))
        }
    }

    /*
     * compat(wow<9.2): `POST …/event/schema/refresh`, removed in 9.2 when revalidation moved to the `wowQuerySchema` actuator
     * endpoint. Operators' scripts still call it, so it revalidates this aggregate's schemas and answers as
     * `GET …/schema` does.
     */
    private fun eventSchemaRefreshRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract = eventRoute(
        currentContext = currentContext,
        aggregateRouteMetadata = aggregateRouteMetadata,
        handlerKey = BuiltInHttpRouteHandlerKeys.Event.SCHEMA_REFRESH,
        resourceName = "event_schema",
        operation = "refresh",
        operationSummary = "Refresh Event Stream Schema (deprecated: use the wowQuerySchema actuator endpoint)",
        method = Https.Method.POST,
        appendTenantPath = false,
        appendOwnerPath = false,
        appendPathSuffix = RouteSuffixes.EVENT_SCHEMA_REFRESH,
        responses = querySchemaResponses,
    )

    private fun eventSchemaRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract = eventRoute(
        currentContext = currentContext,
        aggregateRouteMetadata = aggregateRouteMetadata,
        handlerKey = BuiltInHttpRouteHandlerKeys.Event.SCHEMA,
        resourceName = "event_schema",
        operation = "get",
        operationSummary = "Get Event Stream Schema",
        method = Https.Method.GET,
        appendTenantPath = false,
        appendOwnerPath = false,
        appendPathSuffix = RouteSuffixes.EVENT_SCHEMA,
        extraParameters = querySchemaParameters,
        responses = querySchemaResponses,
    )

    @Suppress("LongMethod")
    private fun queryRoutes(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): List<HttpRouteContract> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        return listOf(
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.AGGREGATION,
                resourceName = EVENT,
                operation = "aggregation",
                operationSummary = "Aggregate Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.EVENT_AGGREGATION,
                accept = STREAMING_ACCEPT,
                requestBody = QueryComponents.aggregationQueryRequestBody,
                responses = listOf(aggregationResponse),
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.COUNT,
                resourceName = EVENT,
                operation = "count",
                operationSummary = "Count Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.EVENT_COUNT,
                requestBody = QueryComponents.countQueryRequestBody,
                responses = listOf(QueryComponents.countQueryResponse)
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY,
                resourceName = EVENT,
                operation = "list_query",
                operationSummary = "List Query Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.EVENT_LIST,
                accept = STREAMING_ACCEPT,
                requestBody = QueryComponents.listQueryRequestBody,
                responses = listOf(eventStreamListResponse(aggregateMetadata))
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.PAGED_QUERY,
                resourceName = EVENT,
                operation = "paged_query",
                operationSummary = "Paged Query Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.EVENT_PAGED,
                requestBody = QueryComponents.pagedQueryRequestBody,
                responses = listOf(eventStreamPagedResponse(aggregateMetadata))
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Event.CURSOR_QUERY,
                resourceName = EVENT,
                operation = "cursor_query",
                operationSummary = "Cursor Query Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.EVENT_CURSOR,
                requestBody = QueryComponents.cursorQueryRequestBody,
                responses = listOf(eventStreamCursorResponse(aggregateMetadata))
            )
        )
    }

    private fun loadEventStreamRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Event.LOAD,
            resourceName = EVENT_STREAM,
            operation = "load",
            operationSummary = "Load Event Stream",
            method = Https.Method.GET,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = RouteSuffixes.EVENT_RANGE,
            accept = STREAMING_ACCEPT,
            extraParameters = listOf(
                BatchComponents.headVersionPathParameter,
                BatchComponents.tailVersionPathParameter
            ),
            responses = listOf(eventStreamListResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun eventCompensateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Event.COMPENSATE,
            operation = "compensate",
            operationSummary = "Event Compensate",
            method = Https.Method.PUT,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = RouteSuffixes.EVENT_COMPENSATE,
            extraParameters = listOf(CommonComponents.versionPathParameter),
            requestBody = EventComponents.compensationTargetRequestBody,
            responses = listOf(
                EventComponents.compensationTargetResponse,
                CommonComponents.badRequestResponse
            )
        )
    }

    private fun resendStateEventRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Event.RESEND_STATE,
            resourceName = STATE_EVENT,
            operation = "resend",
            operationSummary = "Resend State Event",
            appendTenantPath = false,
            appendOwnerPath = false,
            appendPathSuffix = RouteSuffixes.STATE_BATCH,
            extraParameters = listOf(
                BatchComponents.batchAfterIdPathParameter,
                BatchComponents.batchLimitPathParameter
            ),
            responses = listOf(
                BatchComponents.batchResultResponse,
                CommonComponents.requestTimeoutResponse
            )
        )
    }

    private fun eventRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        handlerKey: String,
        operation: String,
        operationSummary: String,
        resourceName: String = "",
        method: String = Https.Method.POST,
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean,
        appendIdPath: Boolean = false,
        appendPathSuffix: String,
        accept: List<String> = listOf(Https.MediaType.APPLICATION_JSON),
        extraParameters: List<HttpParameter> = emptyList(),
        requestBody: HttpRequestBody? = null,
        responses: List<HttpResponse>
    ): HttpRouteContract {
        return HttpRouteContract(
            routeId = RouteIdSpec()
                .aggregate(aggregateRouteMetadata.aggregateMetadata)
                .appendTenant(appendTenantPath)
                .appendOwner(appendOwnerPath)
                .resourceName(resourceName)
                .operation(operation)
                .build(),
            method = method,
            path = aggregatePath(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                appendTenantPath = appendTenantPath,
                appendOwnerPath = appendOwnerPath,
                appendIdPath = appendIdPath,
                appendPathSuffix = appendPathSuffix
            ),
            handlerKey = handlerKey,
            summary = tenantOwnerSummary(operationSummary, appendTenantPath, appendOwnerPath),
            accept = accept,
            parameters = aggregateParameters(
                aggregateRouteMetadata = aggregateRouteMetadata,
                appendTenantPath = appendTenantPath,
                appendOwnerPath = appendOwnerPath,
                appendIdPath = appendIdPath
            ) + extraParameters,
            requestBody = requestBody,
            responses = responses,
            tags = aggregateTags(aggregateRouteMetadata.aggregateMetadata),
            handlerMetadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
        )
    }

    private const val EVENT = "event"
    private const val EVENT_STREAM = "event_stream"
    private const val STATE_EVENT = "state_event"
    private val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
}
