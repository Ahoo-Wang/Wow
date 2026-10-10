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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.contract.HttpComponent
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRoute
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRouteScope
import me.ahoo.wow.openapi.contributor.aggregate.ScopeNaming
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteSuffixes
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.State as Keys

private const val TRACING_PARAMETER_KEY_PREFIX = "wow.aggregate-tracing."

/**
 * The state routes of an aggregate. Their paths come from [me.ahoo.wow.rest.RouteSuffixes]; their resource and
 * operation names make the route ids that wow-generator reads (see [me.ahoo.wow.openapi.RouteIdSpec]).
 */
object StateRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        val scope = AggregateRouteScope(currentContext, aggregateRouteMetadata)
        val aggregate = scope.aggregateMetadata
        val routes = listOf(
            AggregateRoute(
                handlerKey = Keys.AGGREGATE_TRACING,
                resourceName = "aggregate_tracing",
                operation = "get",
                summary = "Get Aggregate Tracing",
                method = Https.Method.GET,
                appendTenantPath = scope.defaultAppendTenantPath,
                appendIdPath = true,
                pathSuffix = RouteSuffixes.STATE_TRACING,
                parameters = tracingQueryParameters,
                responses = listOf(tracingResponse(aggregate)),
                naming = ScopeNaming.TENANT_ID_ONLY
            ),
            loadRoute(
                scope = scope,
                handlerKey = Keys.LOAD_AGGREGATE,
                resourceName = "aggregate",
                summary = "Load State Aggregate",
                pathSuffix = RouteSuffixes.STATE
            ),
            loadRoute(
                scope = scope,
                handlerKey = Keys.LOAD_VERSIONED_AGGREGATE,
                resourceName = "versioned_aggregate",
                summary = "Load Versioned State Aggregate",
                pathSuffix = RouteSuffixes.STATE_VERSIONED,
                parameters = listOf(CommonComponents.versionPathParameter)
            ),
            loadRoute(
                scope = scope,
                handlerKey = Keys.LOAD_TIME_BASED_AGGREGATE,
                resourceName = "time_based_aggregate",
                summary = "Load Time Based State Aggregate",
                pathSuffix = RouteSuffixes.STATE_TIME_BASED,
                parameters = listOf(CommonComponents.createTimePathParameter)
            )
        )
        return routes.map(scope::contract)
    }

    /** A `GET` route that loads the aggregate's state, under its default tenant, owner and id path. */
    private fun loadRoute(
        scope: AggregateRouteScope,
        handlerKey: String,
        resourceName: String,
        summary: String,
        pathSuffix: String,
        parameters: List<HttpParameter> = emptyList()
    ): AggregateRoute = AggregateRoute(
        handlerKey = handlerKey,
        resourceName = resourceName,
        operation = "load",
        summary = summary,
        method = Https.Method.GET,
        appendTenantPath = scope.defaultAppendTenantPath,
        appendOwnerPath = scope.defaultAppendOwnerPath,
        appendIdPath = scope.defaultAppendIdPath,
        pathSuffix = pathSuffix,
        parameters = parameters,
        responses = loadAggregateResponses(summary, scope.aggregateMetadata),
        naming = ScopeNaming.TENANT_ID_ONLY
    )

    private val tracingQueryParameters: List<HttpParameter> = listOf(
        tracingQueryParameter(name = "headVersion", description = "The first aggregate version to emit."),
        tracingQueryParameter(name = "tailVersion", description = "The last aggregate version to replay and emit."),
        tracingQueryParameter(name = "limit", description = "The maximum number of tail versions to emit.")
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

    private fun tracingResponse(aggregate: AggregateMetadata<*, *>): HttpResponse = HttpResponse(
        statusCode = Https.Code.OK,
        description = "Get Aggregate Tracing",
        headers = listOf(CommonComponents.errorCodeHeader),
        content = listOf(
            HttpContent(
                Https.MediaType.APPLICATION_JSON,
                HttpSchema.Array(
                    HttpSchema.TypeRef(
                        StateEvent::class.java,
                        listOf(HttpSchema.TypeRef(aggregate.state.aggregateType))
                    )
                )
            )
        )
    )

    private fun loadAggregateResponses(summary: String, aggregate: AggregateMetadata<*, *>): List<HttpResponse> = listOf(
        HttpResponse(
            statusCode = Https.Code.OK,
            description = summary,
            headers = listOf(CommonComponents.errorCodeHeader),
            content = listOf(
                HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.TypeRef(aggregate.state.aggregateType))
            )
        ),
        CommonComponents.badRequestResponse,
        CommonComponents.notFoundResponse
    )
}
