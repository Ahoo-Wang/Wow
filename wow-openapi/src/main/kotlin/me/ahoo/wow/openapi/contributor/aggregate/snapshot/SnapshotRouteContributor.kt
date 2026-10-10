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

package me.ahoo.wow.openapi.contributor.aggregate.snapshot

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.BatchComponents
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.component.QueryComponents
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRoute
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRouteScope
import me.ahoo.wow.openapi.contributor.aggregate.STREAMING_ACCEPT
import me.ahoo.wow.openapi.contributor.aggregationResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotCursorResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotListResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotPagedResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotSingleResponse
import me.ahoo.wow.openapi.contributor.querySchemaParameters
import me.ahoo.wow.openapi.contributor.querySchemaResponses
import me.ahoo.wow.openapi.contributor.stateCursorResponse
import me.ahoo.wow.openapi.contributor.stateListResponse
import me.ahoo.wow.openapi.contributor.statePagedResponse
import me.ahoo.wow.openapi.contributor.stateSingleResponse
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteSuffixes
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Snapshot as Keys

private const val SNAPSHOT = "snapshot"
private const val SNAPSHOT_STATE = "snapshot_state"
private const val SNAPSHOT_SCHEMA = "snapshot_schema"

/**
 * The snapshot routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
object SnapshotRouteContributor : RouteContributor {
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
            resourceName = SNAPSHOT_SCHEMA,
            operation = "get",
            summary = "Get Snapshot Schema",
            method = Https.Method.GET,
            pathSuffix = RouteSuffixes.SNAPSHOT_SCHEMA,
            parameters = querySchemaParameters,
            responses = querySchemaResponses
        ),
        /*
         * compat(wow<9.2): `POST …/snapshot/schema/refresh`, removed in 9.2 when revalidation moved to the
         * `wowQuerySchema` actuator endpoint. Operators' scripts still call it, so it revalidates this aggregate's
         * schemas and answers as `GET …/schema` does.
         */
        AggregateRoute(
            handlerKey = Keys.SCHEMA_REFRESH,
            resourceName = SNAPSHOT_SCHEMA,
            operation = "refresh",
            summary = "Refresh Snapshot Schema (deprecated: use the wowQuerySchema actuator endpoint)",
            pathSuffix = RouteSuffixes.SNAPSHOT_SCHEMA_REFRESH,
            responses = querySchemaResponses
        )
    )

    /** The routes of one aggregate instance or of a batch of them. */
    private fun instanceRoutes(scope: AggregateRouteScope): List<AggregateRoute> = listOf(
        AggregateRoute(
            handlerKey = Keys.LOAD,
            resourceName = SNAPSHOT,
            operation = "load",
            summary = "Get Snapshot",
            method = Https.Method.GET,
            appendTenantPath = scope.defaultAppendTenantPath,
            appendOwnerPath = scope.defaultAppendOwnerPath,
            appendIdPath = scope.defaultAppendIdPath,
            pathSuffix = RouteSuffixes.SNAPSHOT,
            responses = listOf(loadSnapshotResponse(scope.aggregateMetadata), CommonComponents.notFoundResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.REGENERATE,
            resourceName = SNAPSHOT,
            operation = "regenerate",
            summary = "Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            appendTenantPath = scope.defaultAppendTenantPath,
            appendIdPath = true,
            pathSuffix = RouteSuffixes.SNAPSHOT,
            responses = listOf(HttpResponse(Https.Code.OK), CommonComponents.notFoundResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.BATCH_REGENERATE,
            resourceName = SNAPSHOT,
            operation = "batch_regenerate",
            summary = "Batch Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            pathSuffix = RouteSuffixes.SNAPSHOT_BATCH,
            parameters = listOf(
                BatchComponents.batchAfterIdPathParameter,
                BatchComponents.batchLimitPathParameter
            ),
            responses = listOf(BatchComponents.batchResultResponse, CommonComponents.requestTimeoutResponse)
        )
    )

    /** The query routes, published once per tenant/owner variant. */
    @Suppress("LongMethod")
    private fun queryRoutes(aggregate: AggregateMetadata<*, *>): List<AggregateRoute> = listOf(
        AggregateRoute(
            handlerKey = Keys.COUNT,
            resourceName = SNAPSHOT,
            operation = "count",
            summary = "Count Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_COUNT,
            requestBody = QueryComponents.aggregatedCountQueryRequestBody(aggregate),
            responses = listOf(
                QueryComponents.countQueryResponse,
                CommonComponents.requestTimeoutResponse,
                CommonComponents.tooManyRequestsResponse
            )
        ),
        AggregateRoute(
            handlerKey = Keys.AGGREGATION,
            resourceName = SNAPSHOT,
            operation = "aggregation",
            summary = "Aggregate Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_AGGREGATION,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedAggregationQueryRequestBody(aggregate),
            responses = listOf(
                aggregationResponse,
                CommonComponents.requestTimeoutResponse,
                CommonComponents.tooManyRequestsResponse
            )
        ),
        AggregateRoute(
            handlerKey = Keys.LIST_QUERY,
            resourceName = SNAPSHOT,
            operation = "list_query",
            summary = "List Query Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_LIST,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedListQueryRequestBody(aggregate),
            responses = listOf(materializedSnapshotListResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.LIST_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "list_query",
            summary = "List Query Snapshot State",
            pathSuffix = RouteSuffixes.SNAPSHOT_LIST_STATE,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedListQueryRequestBody(aggregate),
            responses = listOf(stateListResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.PAGED_QUERY,
            resourceName = SNAPSHOT,
            operation = "paged_query",
            summary = "Paged Query Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_PAGED,
            requestBody = QueryComponents.aggregatedPagedQueryRequestBody(aggregate),
            responses = listOf(materializedSnapshotPagedResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.PAGED_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "paged_query",
            summary = "Paged Query Snapshot State",
            pathSuffix = RouteSuffixes.SNAPSHOT_PAGED_STATE,
            requestBody = QueryComponents.aggregatedPagedQueryRequestBody(aggregate),
            responses = listOf(statePagedResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.CURSOR_QUERY,
            resourceName = SNAPSHOT,
            operation = "cursor_query",
            summary = "Cursor Query Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_CURSOR,
            requestBody = QueryComponents.aggregatedCursorQueryRequestBody(aggregate),
            responses = listOf(materializedSnapshotCursorResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.CURSOR_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "cursor_query",
            summary = "Cursor Query Snapshot State",
            pathSuffix = RouteSuffixes.SNAPSHOT_CURSOR_STATE,
            requestBody = QueryComponents.aggregatedCursorQueryRequestBody(aggregate),
            responses = listOf(stateCursorResponse(aggregate))
        ),
        AggregateRoute(
            handlerKey = Keys.SINGLE,
            resourceName = SNAPSHOT,
            operation = "single",
            summary = "Single Snapshot",
            pathSuffix = RouteSuffixes.SNAPSHOT_SINGLE,
            requestBody = QueryComponents.aggregatedSingleQueryRequestBody(aggregate),
            responses = listOf(materializedSnapshotSingleResponse(aggregate), CommonComponents.notFoundResponse)
        ),
        AggregateRoute(
            handlerKey = Keys.SINGLE_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "single",
            summary = "Single Snapshot State",
            pathSuffix = RouteSuffixes.SNAPSHOT_SINGLE_STATE,
            requestBody = QueryComponents.aggregatedSingleQueryRequestBody(aggregate),
            responses = listOf(stateSingleResponse(aggregate), CommonComponents.notFoundResponse)
        )
    )

    private fun loadSnapshotResponse(aggregate: AggregateMetadata<*, *>): HttpResponse = HttpResponse(
        statusCode = Https.Code.OK,
        headers = listOf(CommonComponents.errorCodeHeader),
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.TypeRef(Snapshot::class.java, listOf(HttpSchema.TypeRef(aggregate.state.aggregateType)))
            )
        )
    )
}
