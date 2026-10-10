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

import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.BatchComponents
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.component.QueryComponents
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.aggregate.TenantOwnerVariant
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.aggregate.tenantOwnerSummary
import me.ahoo.wow.openapi.contributor.aggregate.tenantOwnerVariants
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

/**
 * The snapshot routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
object SnapshotRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        return buildList {
            add(snapshotSchemaRoute(currentContext, aggregateRouteMetadata))
            add(snapshotSchemaRefreshRoute(currentContext, aggregateRouteMetadata))
            aggregateRouteMetadata.tenantOwnerVariants().forEach { variant ->
                addAll(queryRoutes(currentContext, aggregateRouteMetadata, variant))
            }
            add(loadSnapshotRoute(currentContext, aggregateRouteMetadata))
            add(regenerateSnapshotRoute(currentContext, aggregateRouteMetadata))
            add(batchRegenerateSnapshotRoute(currentContext, aggregateRouteMetadata))
        }
    }

    /*
     * compat(wow<9.2): `POST …/snapshot/schema/refresh`, removed in 9.2 when revalidation moved to the `wowQuerySchema` actuator
     * endpoint. Operators' scripts still call it, so it revalidates this aggregate's schemas and answers as
     * `GET …/schema` does.
     */
    private fun snapshotSchemaRefreshRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract = snapshotRoute(
        currentContext = currentContext,
        aggregateRouteMetadata = aggregateRouteMetadata,
        handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA_REFRESH,
        resourceName = "snapshot_schema",
        operation = "refresh",
        operationSummary = "Refresh Snapshot Schema (deprecated: use the wowQuerySchema actuator endpoint)",
        method = Https.Method.POST,
        appendTenantPath = false,
        appendOwnerPath = false,
        appendPathSuffix = RouteSuffixes.SNAPSHOT_SCHEMA_REFRESH,
        responses = querySchemaResponses,
    )

    private fun snapshotSchemaRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract = snapshotRoute(
        currentContext = currentContext,
        aggregateRouteMetadata = aggregateRouteMetadata,
        handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA,
        resourceName = "snapshot_schema",
        operation = "get",
        operationSummary = "Get Snapshot Schema",
        method = Https.Method.GET,
        appendTenantPath = false,
        appendOwnerPath = false,
        appendPathSuffix = RouteSuffixes.SNAPSHOT_SCHEMA,
        extraParameters = querySchemaParameters,
        responses = querySchemaResponses,
    )

    private fun queryRoutes(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): List<HttpRouteContract> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        return listOf(
            snapshotRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                resourceName = SNAPSHOT,
                operation = "count",
                operationSummary = "Count Snapshot",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = RouteSuffixes.SNAPSHOT_COUNT,
                requestBody = QueryComponents.aggregatedCountQueryRequestBody(aggregateMetadata),
                responses = listOf(
                    QueryComponents.countQueryResponse,
                    CommonComponents.requestTimeoutResponse,
                    CommonComponents.tooManyRequestsResponse
                )
            ),
            aggregationSnapshotRoute(currentContext, aggregateRouteMetadata, variant),
            listQuerySnapshotRoute(currentContext, aggregateRouteMetadata, variant),
            listQuerySnapshotStateRoute(currentContext, aggregateRouteMetadata, variant),
            pagedQuerySnapshotRoute(currentContext, aggregateRouteMetadata, variant),
            pagedQuerySnapshotStateRoute(currentContext, aggregateRouteMetadata, variant),
            cursorQuerySnapshotRoute(currentContext, aggregateRouteMetadata, variant),
            cursorQuerySnapshotStateRoute(currentContext, aggregateRouteMetadata, variant),
            singleSnapshotRoute(currentContext, aggregateRouteMetadata, variant),
            singleSnapshotStateRoute(currentContext, aggregateRouteMetadata, variant)
        )
    }

    private fun aggregationSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION,
            resourceName = SNAPSHOT,
            operation = "aggregation",
            operationSummary = "Aggregate Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_AGGREGATION,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedAggregationQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                aggregationResponse,
                CommonComponents.requestTimeoutResponse,
                CommonComponents.tooManyRequestsResponse
            )
        )
    }

    private fun listQuerySnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            resourceName = SNAPSHOT,
            operation = "list_query",
            operationSummary = "List Query Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_LIST,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedListQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                materializedSnapshotListResponse(aggregateRouteMetadata.aggregateMetadata)
            )
        )
    }

    private fun listQuerySnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "list_query",
            operationSummary = "List Query Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_LIST_STATE,
            accept = STREAMING_ACCEPT,
            requestBody = QueryComponents.aggregatedListQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(stateListResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun pagedQuerySnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
            resourceName = SNAPSHOT,
            operation = "paged_query",
            operationSummary = "Paged Query Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_PAGED,
            requestBody = QueryComponents.aggregatedPagedQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                materializedSnapshotPagedResponse(aggregateRouteMetadata.aggregateMetadata)
            )
        )
    }

    private fun pagedQuerySnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "paged_query",
            operationSummary = "Paged Query Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_PAGED_STATE,
            requestBody = QueryComponents.aggregatedPagedQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(statePagedResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun cursorQuerySnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY,
            resourceName = SNAPSHOT,
            operation = "cursor_query",
            operationSummary = "Cursor Query Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_CURSOR,
            requestBody = QueryComponents.aggregatedCursorQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                materializedSnapshotCursorResponse(aggregateRouteMetadata.aggregateMetadata)
            )
        )
    }

    private fun cursorQuerySnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.CURSOR_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "cursor_query",
            operationSummary = "Cursor Query Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_CURSOR_STATE,
            requestBody = QueryComponents.aggregatedCursorQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(stateCursorResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun singleSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            resourceName = SNAPSHOT,
            operation = "single",
            operationSummary = "Single Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_SINGLE,
            requestBody = QueryComponents.aggregatedSingleQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                materializedSnapshotSingleResponse(aggregateRouteMetadata.aggregateMetadata),
                CommonComponents.notFoundResponse
            )
        )
    }

    private fun singleSnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "single",
            operationSummary = "Single Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_SINGLE_STATE,
            requestBody = QueryComponents.aggregatedSingleQueryRequestBody(
                aggregateRouteMetadata.aggregateMetadata,
            ),
            responses = listOf(
                stateSingleResponse(aggregateRouteMetadata.aggregateMetadata),
                CommonComponents.notFoundResponse
            )
        )
    }

    private fun loadSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
            resourceName = SNAPSHOT,
            operation = "load",
            operationSummary = "Get Snapshot",
            method = Https.Method.GET,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.AGGREGATE_ID,
            appendPathSuffix = RouteSuffixes.SNAPSHOT,
            responses = loadSnapshotResponses(aggregateRouteMetadata)
        )
    }

    private fun regenerateSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE,
            resourceName = SNAPSHOT,
            operation = "regenerate",
            operationSummary = "Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = RouteSuffixes.SNAPSHOT,
            responses = listOf(
                HttpResponse(Https.Code.OK),
                CommonComponents.notFoundResponse
            )
        )
    }

    private fun batchRegenerateSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE,
            resourceName = SNAPSHOT,
            operation = "batch_regenerate",
            operationSummary = "Batch Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            appendTenantPath = false,
            appendOwnerPath = false,
            appendPathSuffix = RouteSuffixes.SNAPSHOT_BATCH,
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

    private fun snapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        handlerKey: String,
        resourceName: String,
        operation: String,
        operationSummary: String,
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

    private fun loadSnapshotResponses(
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                headers = listOf(CommonComponents.errorCodeHeader),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.TypeRef(
                            Snapshot::class.java,
                            listOf(HttpSchema.TypeRef(aggregateRouteMetadata.aggregateMetadata.state.aggregateType))
                        )
                    )
                )
            ),
            CommonComponents.notFoundResponse
        )
    }

    private const val SNAPSHOT = "snapshot"
    private const val SNAPSHOT_STATE = "snapshot_state"
    private val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
}
