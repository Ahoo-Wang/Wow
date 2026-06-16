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
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_AFTER_ID
import me.ahoo.wow.openapi.BatchComponent.PathVariable.BATCH_LIMIT
import me.ahoo.wow.openapi.BatchComponent.PathVariable.HEAD_VERSION
import me.ahoo.wow.openapi.BatchComponent.PathVariable.TAIL_VERSION
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.aggregate.event.CountEventStreamRouteSpec
import me.ahoo.wow.openapi.aggregate.event.EventCompensateRouteSpec
import me.ahoo.wow.openapi.aggregate.event.ListQueryEventStreamRouteSpec
import me.ahoo.wow.openapi.aggregate.event.LoadEventStreamRouteSpec
import me.ahoo.wow.openapi.aggregate.event.PagedQueryEventStreamRouteSpec
import me.ahoo.wow.openapi.aggregate.event.state.ResendStateEventRouteSpec
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.aggregate.versionPathParameterRef
import me.ahoo.wow.openapi.contributor.badRequestResponseRef
import me.ahoo.wow.openapi.contributor.batchAfterIdPathParameterRef
import me.ahoo.wow.openapi.contributor.batchLimitPathParameterRef
import me.ahoo.wow.openapi.contributor.batchResultResponseRef
import me.ahoo.wow.openapi.contributor.compensationTargetRequestBodyRef
import me.ahoo.wow.openapi.contributor.compensationTargetResponseRef
import me.ahoo.wow.openapi.contributor.countQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.countQueryResponseRef
import me.ahoo.wow.openapi.contributor.eventStreamListResponse
import me.ahoo.wow.openapi.contributor.eventStreamPagedResponse
import me.ahoo.wow.openapi.contributor.headVersionPathParameterRef
import me.ahoo.wow.openapi.contributor.listQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.pagedQueryRequestBodyRef
import me.ahoo.wow.openapi.contributor.requestTimeoutResponseRef
import me.ahoo.wow.openapi.contributor.tailVersionPathParameterRef
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

object EventRouteContributor : RouteContributor {
    override val id: String = "aggregate.event"
    override val category: RouteCategory = RouteCategory.EVENT
    override val order: Int = 400

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return buildList {
            tenantOwnerVariants(aggregateRouteMetadata).forEach { variant ->
                addAll(queryRoutes(currentContext, aggregateRouteMetadata, componentContext, variant))
            }
            add(loadEventStreamRoute(currentContext, aggregateRouteMetadata, componentContext))
            add(eventCompensateRoute(currentContext, aggregateRouteMetadata, componentContext))
            add(resendStateEventRoute(currentContext, aggregateRouteMetadata, componentContext))
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
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext,
                routeSpecType = CountEventStreamRouteSpec::class.java,
                resourceName = EVENT,
                operation = "count",
                operationSummary = "Count Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = "event/count",
                requestBody = componentContext.countQueryRequestBodyRef(),
                responses = listOf(componentContext.countQueryResponseRef())
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext,
                routeSpecType = ListQueryEventStreamRouteSpec::class.java,
                resourceName = EVENT,
                operation = "list_query",
                operationSummary = "List Query Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = "event/list",
                accept = STREAMING_ACCEPT,
                requestBody = componentContext.listQueryRequestBodyRef(),
                responses = listOf(componentContext.eventStreamListResponse(aggregateMetadata))
            ),
            eventRoute(
                currentContext = currentContext,
                aggregateRouteMetadata = aggregateRouteMetadata,
                componentContext = componentContext,
                routeSpecType = PagedQueryEventStreamRouteSpec::class.java,
                resourceName = EVENT,
                operation = "paged_query",
                operationSummary = "Paged Query Event Stream",
                appendTenantPath = variant.appendTenantPath,
                appendOwnerPath = variant.appendOwnerPath,
                appendPathSuffix = "event/paged",
                requestBody = componentContext.pagedQueryRequestBodyRef(),
                responses = listOf(componentContext.eventStreamPagedResponse(aggregateMetadata))
            )
        )
    }

    private fun loadEventStreamRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            routeSpecType = LoadEventStreamRouteSpec::class.java,
            resourceName = EVENT_STREAM,
            operation = "load",
            operationSummary = "Load Event Stream",
            method = Https.Method.GET,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = "event/{$HEAD_VERSION}/{$TAIL_VERSION}",
            accept = STREAMING_ACCEPT,
            extraParameters = listOf(
                componentContext.headVersionPathParameterRef(),
                componentContext.tailVersionPathParameterRef()
            ),
            responses = listOf(componentContext.eventStreamListResponse(aggregateRouteMetadata.aggregateMetadata))
        )
    }

    private fun eventCompensateRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            routeSpecType = EventCompensateRouteSpec::class.java,
            operation = "compensate",
            operationSummary = "Event Compensate",
            method = Https.Method.PUT,
            appendTenantPath = aggregateRouteMetadata.defaultAppendTenantPath(),
            appendOwnerPath = false,
            appendIdPath = true,
            appendPathSuffix = "{${MessageRecords.VERSION}}/compensate",
            extraParameters = listOf(componentContext.versionPathParameterRef()),
            requestBody = componentContext.compensationTargetRequestBodyRef(),
            responses = listOf(
                componentContext.compensationTargetResponseRef(),
                componentContext.badRequestResponseRef()
            )
        )
    }

    private fun resendStateEventRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract {
        return eventRoute(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            componentContext = componentContext,
            routeSpecType = ResendStateEventRouteSpec::class.java,
            resourceName = STATE_EVENT,
            operation = "resend",
            operationSummary = "Resend State Event",
            appendTenantPath = false,
            appendOwnerPath = false,
            appendPathSuffix = "state/{$BATCH_AFTER_ID}/{$BATCH_LIMIT}",
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

    private fun eventRoute(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
        routeSpecType: Class<*>,
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
            handlerKey = routeSpecType.name,
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

    private const val EVENT = "event"
    private const val EVENT_STREAM = "event_stream"
    private const val STATE_EVENT = "state_event"
    private val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)
}
