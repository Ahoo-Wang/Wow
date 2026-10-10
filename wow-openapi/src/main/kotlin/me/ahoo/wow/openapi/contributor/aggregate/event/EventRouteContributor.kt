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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.BatchComponents
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.component.EventComponents
import me.ahoo.wow.openapi.component.QueryComponents
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRoute
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRouteScope
import me.ahoo.wow.openapi.contributor.aggregate.STREAMING_ACCEPT
import me.ahoo.wow.openapi.contributor.aggregationResponse
import me.ahoo.wow.openapi.contributor.eventStreamCursorResponse
import me.ahoo.wow.openapi.contributor.eventStreamListResponse
import me.ahoo.wow.openapi.contributor.eventStreamPagedResponse
import me.ahoo.wow.openapi.contributor.querySchemaParameters
import me.ahoo.wow.openapi.contributor.querySchemaResponses
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteSuffixes
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Event as Keys

private const val EVENT = "event"
private const val EVENT_STREAM = "event_stream"
private const val EVENT_SCHEMA = "event_schema"
private const val STATE_EVENT = "state_event"

/**
 * The event routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
@InternalWowApi
object EventRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        val scope = AggregateRouteScope(currentContext, aggregateRouteMetadata)
        val queryRoutes = queryRoutes(scope.aggregateMetadata)
        val routes = schemaRoutes +
            scope.tenantOwnerVariants.flatMap { variant -> queryRoutes.map { it.within(variant) } } +
            instanceRoutes(scope)
        return routes.map(scope::contract)
    }

    /** The query capability descriptor routes. */
    private val schemaRoutes: List<AggregateRoute> = listOf(
        AggregateRoute(
            handlerKey = Keys.SCHEMA,
            resourceName = EVENT_SCHEMA,
            operation = "get",
            summary = "Get Event Stream Schema",
            method = Https.Method.GET,
            pathSuffix = RouteSuffixes.EVENT_SCHEMA,
            parameters = querySchemaParameters,
            responses = querySchemaResponses
        ),
        /*
         * compat(wow<9.2): `POST …/event/schema/refresh`, removed in 9.2 when revalidation moved to the
         * `wowQuerySchema` actuator endpoint. Operators' scripts still call it, so it revalidates this aggregate's
         * schemas and answers as `GET …/schema` does.
         */
        AggregateRoute(
            handlerKey = Keys.SCHEMA_REFRESH,
            resourceName = EVENT_SCHEMA,
            operation = "refresh",
            summary = "Refresh Event Stream Schema (deprecated: use the wowQuerySchema actuator endpoint)",
            pathSuffix = RouteSuffixes.EVENT_SCHEMA_REFRESH,
            responses = querySchemaResponses
        )
    )

    /** The routes of one aggregate instance or of a batch of them. */
    private fun instanceRoutes(scope: AggregateRouteScope): List<AggregateRoute> = listOf(
        AggregateRoute(
            handlerKey = Keys.LOAD,
            resourceName = EVENT_STREAM,
            operation = "load",
            summary = "Load Event Stream",
            method = Https.Method.GET,
            appendTenantPath = scope.defaultAppendTenantPath,
            appendIdPath = true,
            pathSuffix = RouteSuffixes.EVENT_RANGE,
            accept = STREAMING_ACCEPT,
            parameters = listOf(
                BatchComponents.headVersionPathParameter,
                BatchComponents.tailVersionPathParameter
            ),
            responses = listOf(eventStreamListResponse(scope.aggregateMetadata))
        ),
        AggregateRoute(
            handlerKey = Keys.COMPENSATE,
            resourceName = "",
            operation = "compensate",
            summary = "Event Compensate",
            method = Https.Method.PUT,
            appendTenantPath = scope.defaultAppendTenantPath,
            appendIdPath = true,
            pathSuffix = RouteSuffixes.EVENT_COMPENSATE,
            parameters = listOf(CommonComponents.versionPathParameter),
            requestBody = EventComponents.compensationTargetRequestBody,
            responses = listOf(EventComponents.compensationTargetResponse, CommonComponents.badRequestResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.RESEND_STATE,
            resourceName = STATE_EVENT,
            operation = "resend",
            summary = "Resend State Event",
            pathSuffix = RouteSuffixes.STATE_BATCH,
            parameters = listOf(
                BatchComponents.batchAfterIdPathParameter,
                BatchComponents.batchLimitPathParameter
            ),
            responses = listOf(BatchComponents.batchResultResponse, CommonComponents.requestTimeoutResponse)
        )
    )

    /** The query routes, published once per tenant/owner variant. */
    private fun queryRoutes(aggregate: AggregateMetadata<*, *>): List<AggregateRoute> = listOf(
        AggregateRoute(
            handlerKey = Keys.AGGREGATION,
            resourceName = EVENT,
            operation = "aggregation",
            summary = "Aggregate Event Stream",
            pathSuffix = RouteSuffixes.EVENT_AGGREGATION,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregationQueryRequestBody,
            responses = listOf(aggregationResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.COUNT,
            resourceName = EVENT,
            operation = "count",
            summary = "Count Event Stream",
            pathSuffix = RouteSuffixes.EVENT_COUNT,
            requestBody = QueryComponents.countQueryRequestBody,
            responses = listOf(QueryComponents.countQueryResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.LIST_QUERY,
            resourceName = EVENT,
            operation = "list_query",
            summary = "List Query Event Stream",
            pathSuffix = RouteSuffixes.EVENT_LIST,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.listQueryRequestBody,
            responses = listOf(eventStreamListResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.PAGED_QUERY,
            resourceName = EVENT,
            operation = "paged_query",
            summary = "Paged Query Event Stream",
            pathSuffix = RouteSuffixes.EVENT_PAGED,
            requestBody = QueryComponents.pagedQueryRequestBody,
            responses = listOf(eventStreamPagedResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.CURSOR_QUERY,
            resourceName = EVENT,
            operation = "cursor_query",
            summary = "Cursor Query Event Stream",
            pathSuffix = RouteSuffixes.EVENT_CURSOR,
            requestBody = QueryComponents.cursorQueryRequestBody,
            responses = listOf(eventStreamCursorResponse(aggregate))
        )
    )
}
