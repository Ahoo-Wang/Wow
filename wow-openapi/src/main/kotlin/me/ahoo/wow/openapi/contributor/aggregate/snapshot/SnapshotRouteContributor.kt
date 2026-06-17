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

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_AFTER_ID
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_LIMIT
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.aggregatedCountQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.aggregatedListQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.aggregatedPagedQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.aggregatedSingleQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.batchAfterIdPathParameterRef
import me.ahoo.wow.openapi.contributor.batchLimitPathParameterRef
import me.ahoo.wow.openapi.contributor.batchResultResponseRef
import me.ahoo.wow.openapi.contributor.countQueryResponseRef
import me.ahoo.wow.openapi.contributor.errorCodeHeaderRef
import me.ahoo.wow.openapi.contributor.materializedSnapshotListResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotPagedResponse
import me.ahoo.wow.openapi.contributor.materializedSnapshotSingleResponse
import me.ahoo.wow.openapi.contributor.notFoundResponseRef
import me.ahoo.wow.openapi.contributor.requestTimeoutResponseRef
import me.ahoo.wow.openapi.contributor.stateListResponse
import me.ahoo.wow.openapi.contributor.statePagedResponse
import me.ahoo.wow.openapi.contributor.stateSingleResponse
import me.ahoo.wow.openapi.contributor.tooManyRequestsResponseRef
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

object SnapshotRouteContributor : RouteContributor {
    override val id: String = "aggregate.snapshot"
    override val category: RouteCategory = RouteCategory.SNAPSHOT
    override val order: Int = 300

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return buildList {
            tenantOwnerVariants(aggregateRouteMetadata).forEach { variant ->
                addAll(queryRoutes(currentContext, aggregateRouteMetadata, componentContext, variant))
            }
            add(loadSnapshotRoute(currentContext, aggregateRouteMetadata, componentContext))
            add(regenerateSnapshotRoute(currentContext, aggregateRouteMetadata, componentContext))
            add(batchRegenerateSnapshotRoute(currentContext, aggregateRouteMetadata, componentContext))
        }
    }

    private fun queryRoutes(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): List<HttpRouteContract> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        return listOf(
            snapshotRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext,
                handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
                resourceName = SNAPSHOT,
                operation = "count",
                operationSummary = "Count Snapshot",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = "snapshot/count",
                requestBody = componentContext.aggregatedCountQueryRequestBodyRef(aggregateMetadata),
                responses = listOf(
                    componentContext.countQueryResponseRef(),
                    componentContext.requestTimeoutResponseRef(),
                    componentContext.tooManyRequestsResponseRef()
                )
            ),
            listQuerySnapshotRoute(currentContext, aggregateRouteMetadata, componentContext, variant),
            listQuerySnapshotStateRoute(currentContext, aggregateRouteMetadata, componentContext, variant),
            pagedQuerySnapshotRoute(currentContext, aggregateRouteMetadata, componentContext, variant),
            pagedQuerySnapshotStateRoute(currentContext, aggregateRouteMetadata, componentContext, variant),
            singleSnapshotRoute(currentContext, aggregateRouteMetadata, componentContext, variant),
            singleSnapshotStateRoute(currentContext, aggregateRouteMetadata, componentContext, variant)
        )
    }

    private fun listQuerySnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            resourceName = SNAPSHOT,
            operation = "list_query",
            operationSummary = "List Query Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/list",
            accept = STREAMING_ACCEPT,
            requestBody = componentContext.aggregatedListQueryRequestBodyRef(aggregateRouteMetadata.aggregateMetadata),
            responses = listOf(
                componentContext.materializedSnapshotListResponse(aggregateRouteMetadata.aggregateMetadata)
            )
        )
    }

    private fun listQuerySnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "list_query",
            operationSummary = "List Query Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/list/state",
            accept = STREAMING_ACCEPT,
            requestBody = componentContext.aggregatedListQueryRequestBodyRef(aggregateRouteMetadata.aggregateMetadata),
            responses = listOf(componentContext.stateListResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun pagedQuerySnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
            resourceName = SNAPSHOT,
            operation = "paged_query",
            operationSummary = "Paged Query Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/paged",
            requestBody = componentContext.aggregatedPagedQueryRequestBodyRef(aggregateRouteMetadata.aggregateMetadata),
            responses = listOf(
                componentContext.materializedSnapshotPagedResponse(aggregateRouteMetadata.aggregateMetadata)
            )
        )
    }

    private fun pagedQuerySnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "paged_query",
            operationSummary = "Paged Query Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/paged/state",
            requestBody = componentContext.aggregatedPagedQueryRequestBodyRef(aggregateRouteMetadata.aggregateMetadata),
            responses = listOf(componentContext.statePagedResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun singleSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            resourceName = SNAPSHOT,
            operation = "single",
            operationSummary = "Single Snapshot",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/single",
            requestBody = componentContext.aggregatedSingleQueryRequestBodyRef(
                aggregateRouteMetadata.aggregateMetadata
            ),
            responses = listOf(
                componentContext.materializedSnapshotSingleResponse(aggregateRouteMetadata.aggregateMetadata),
                componentContext.notFoundResponseRef()
            )
        )
    }

    private fun singleSnapshotStateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        variant: TenantOwnerVariant
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE,
            resourceName = SNAPSHOT_STATE,
            operation = "single",
            operationSummary = "Single Snapshot State",
            appendTenantPath = variant.appendTenantPath,
            appendOwnerPath = variant.appendOwnerPath,
            appendPathSuffix = "snapshot/single/state",
            requestBody = componentContext.aggregatedSingleQueryRequestBodyRef(
                aggregateRouteMetadata.aggregateMetadata
            ),
            responses = listOf(
                componentContext.stateSingleResponse(aggregateRouteMetadata.aggregateMetadata),
                componentContext.notFoundResponseRef()
            )
        )
    }

    private fun loadSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
            resourceName = SNAPSHOT,
            operation = "load",
            operationSummary = "Get Snapshot",
            method = Https.Method.GET,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = aggregateRouteMetadata.defaultAppendOwnerPath(),
            appendIdPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.AGGREGATE_ID,
            appendPathSuffix = "snapshot",
            responses = loadSnapshotResponses(aggregateRouteMetadata, componentContext)
        )
    }

    private fun regenerateSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.REGENERATE,
            resourceName = SNAPSHOT,
            operation = "regenerate",
            operationSummary = "Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = "snapshot",
            responses = listOf(
                HttpResponse(Https.Code.OK),
                componentContext.notFoundResponseRef()
            )
        )
    }

    private fun batchRegenerateSnapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return snapshotRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            handlerKey = BuiltInHttpRouteHandlerKeys.Snapshot.BATCH_REGENERATE,
            resourceName = SNAPSHOT,
            operation = "batch_regenerate",
            operationSummary = "Batch Regenerate Aggregate Snapshot",
            method = Https.Method.PUT,
            appendTenantPath = false,
            appendOwnerPath = false,
            appendPathSuffix = "snapshot/{$BATCH_AFTER_ID}/{$BATCH_LIMIT}",
            extraParameters = listOf(
                componentContext.batchAfterIdPathParameterRef(),
                componentContext.batchLimitPathParameterRef()
            ),
            responses = listOf(
                componentContext.batchResultResponseRef(),
                componentContext.requestTimeoutResponseRef()
            )
        )
    }

    private fun snapshotRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
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
            parameters = componentContext.aggregateParameters(
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
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpResponse> {
        return listOf(
            HttpResponse(
                statusCode = Https.Code.OK,
                headers = listOf(componentContext.errorCodeHeaderRef()),
                content = listOf(
                    HttpContent(
                        Https.MediaType.APPLICATION_JSON,
                        HttpSchema.TypeRef(
                            Snapshot::class.java,
                            listOf(aggregateRouteMetadata.aggregateMetadata.state.aggregateType)
                        )
                    )
                )
            ),
            componentContext.notFoundResponseRef()
        )
    }

    private fun tenantOwnerVariants(
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<TenantOwnerVariant> {
        return buildList {
            add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = false))
            if (aggregateRouteMetadata.defaultAppendTenantPath()) {
                add(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = false))
            }
            if (aggregateRouteMetadata.defaultAppendOwnerPath()) {
                add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = true))
            }
        }
    }

    private fun tenantOwnerSummary(
        operationSummary: String,
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean
    ): String {
        return buildString {
            append(operationSummary)
            if (appendTenantPath || appendOwnerPath) {
                append(" Within")
                if (appendTenantPath) {
                    append(" Tenant")
                }
                if (appendOwnerPath) {
                    append(" Owner")
                }
            }
        }
    }

    private data class TenantOwnerVariant(
        val appendTenantPath: Boolean,
        val appendOwnerPath: Boolean
    )

    private const val SNAPSHOT = "snapshot"
    private const val SNAPSHOT_STATE = "snapshot_state"
    private val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
}
