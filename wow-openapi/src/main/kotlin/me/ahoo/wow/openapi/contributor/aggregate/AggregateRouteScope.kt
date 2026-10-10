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

package me.ahoo.wow.openapi.contributor.aggregate

import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.component.CommonComponents
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.RouteVariables

private const val TENANT_PATH_PREFIX = "tenant/{${RouteVariables.TENANT_ID}}"
private const val OWNER_PATH_PREFIX = "owner/{${RouteVariables.OWNER_ID}}"
private const val ID_PATH_VARIABLE = "{${RouteVariables.ID}}"

internal val JSON_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON)
internal val STREAMING_ACCEPT = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)

/**
 * The tenant/owner scope of one variant of an aggregate's query routes.
 */
internal data class TenantOwnerVariant(
    val appendTenantPath: Boolean,
    val appendOwnerPath: Boolean
)

/**
 * How a route's tenant/owner scope shows in its route id and summary. Route ids are a contract (see [RouteIdSpec]),
 * so each route family keeps the naming it was published with.
 */
internal enum class ScopeNaming {
    /** `{aggregate}.tenant.owner.{resource}.{operation}`, summary `… Within Tenant Owner`: snapshot and event routes. */
    TENANT_OWNER,

    /**
     * `{aggregate}.tenant.{resource}.{operation}`, summary unchanged, even when the path has an owner segment: the
     * state routes, as published.
     */
    TENANT_ID_ONLY
}

/**
 * One row of an aggregate's route table: what differs between routes. [AggregateRouteScope.contract] adds what they
 * share: the path prefix, the route id prefix, the aggregate parameters, the tags and the handler metadata.
 */
internal data class AggregateRoute(
    val handlerKey: String,
    val resourceName: String,
    val operation: String,
    val summary: String,
    val pathSuffix: String,
    val responses: List<HttpResponse>,
    val method: String = Https.Method.POST,
    val appendTenantPath: Boolean = false,
    val appendOwnerPath: Boolean = false,
    val appendIdPath: Boolean = false,
    val accept: List<String> = JSON_ACCEPT,
    val parameters: List<HttpParameter> = emptyList(),
    val requestBody: HttpRequestBody? = null,
    val naming: ScopeNaming = ScopeNaming.TENANT_OWNER
) {
    /** This route published under [variant]. */
    fun within(variant: TenantOwnerVariant): AggregateRoute = copy(
        appendTenantPath = variant.appendTenantPath,
        appendOwnerPath = variant.appendOwnerPath
    )
}

/**
 * The routes of one aggregate in [currentContext]: produces their paths, route ids, aggregate parameters, tags and
 * tenant/owner summaries in one place, so a route contributor only lists what differs between its routes.
 */
internal class AggregateRouteScope(
    private val currentContext: NamedBoundedContext,
    val aggregateRouteMetadata: AggregateRouteMetadata<*>
) {
    val aggregateMetadata: AggregateMetadata<*, *> = aggregateRouteMetadata.aggregateMetadata

    /** `tenant/{tenantId}` unless the aggregate has a static tenant. */
    val defaultAppendTenantPath: Boolean = aggregateMetadata.staticTenantId.isNullOrBlank()

    /** `owner/{ownerId}` unless the aggregate has no owner. */
    val defaultAppendOwnerPath: Boolean = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.NEVER

    /** `{id}` unless the owner is the aggregate id, which the owner segment already states. */
    val defaultAppendIdPath: Boolean = aggregateRouteMetadata.ownerPolicy != OwnerPolicy.AGGREGATE_ID

    /** The aggregate tag followed by the tags declared on the aggregate type. */
    val tags: List<HttpTag> = buildList {
        add(HttpTag(aggregateMetadata.toStringWithAlias()))
        aggregateMetadata.command.aggregateType.toTags().forEach { tag ->
            add(HttpTag(tag.name, tag.description))
        }
    }

    /**
     * The scopes an aggregate's query routes are published under: always the unscoped route; a tenant variant when
     * the aggregate has no static tenant; an owner variant when it has an owner; and, when both apply, a tenant +
     * owner variant (`tenant/{tenantId}/owner/{ownerId}/…`, the order of the command routes), so a gateway that
     * secures by path can confine a caller to its own owner within its own tenant. The order is the order of
     * publication: a new variant goes last.
     */
    val tenantOwnerVariants: List<TenantOwnerVariant> = buildList {
        add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = false))
        if (defaultAppendTenantPath) {
            add(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = false))
        }
        if (defaultAppendOwnerPath) {
            add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = true))
        }
        if (defaultAppendTenantPath && defaultAppendOwnerPath) {
            add(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = true))
        }
    }

    /** `[{context alias}/][tenant/{tenantId}/][owner/{ownerId}/]{resource}[/{id}][/{suffix}]`. */
    fun path(
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean,
        appendIdPath: Boolean,
        pathSuffix: String = ""
    ): String {
        val pathBuilder = PathBuilder()
        val namedAggregate = aggregateMetadata.namedAggregate
        if (!currentContext.isSameBoundedContext(namedAggregate)) {
            pathBuilder.append(namedAggregate.getContextAlias())
        }
        if (appendTenantPath) {
            pathBuilder.append(TENANT_PATH_PREFIX)
        }
        if (appendOwnerPath) {
            pathBuilder.append(OWNER_PATH_PREFIX)
        }
        pathBuilder.append(aggregateRouteMetadata.resourceName)
        if (appendIdPath) {
            pathBuilder.append(ID_PATH_VARIABLE)
        }
        return pathBuilder.append(pathSuffix).build()
    }

    /** The parameters of the path segments [path] appends, then the space id header of a spaced aggregate. */
    fun parameters(
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean,
        appendIdPath: Boolean
    ): List<HttpParameter> = buildList {
        if (appendTenantPath) {
            add(CommonComponents.tenantIdPathParameter)
        }
        if (appendOwnerPath) {
            add(CommonComponents.ownerIdPathParameter)
        }
        if (appendIdPath) {
            add(CommonComponents.idPathParameter)
        }
        if (aggregateRouteMetadata.spaced) {
            add(CommonComponents.spaceIdHeaderParameter)
        }
    }

    /** The route [route] describes, in this aggregate. */
    fun contract(route: AggregateRoute): HttpRouteContract {
        val namesOwner = route.naming == ScopeNaming.TENANT_OWNER && route.appendOwnerPath
        return HttpRouteContract(
            routeId = RouteIdSpec()
                .aggregate(aggregateMetadata)
                .appendTenant(route.appendTenantPath)
                .appendOwner(namesOwner)
                .resourceName(route.resourceName)
                .operation(route.operation)
                .build(),
            method = route.method,
            path = path(route.appendTenantPath, route.appendOwnerPath, route.appendIdPath, route.pathSuffix),
            handlerKey = route.handlerKey,
            summary = when (route.naming) {
                ScopeNaming.TENANT_OWNER -> tenantOwnerSummary(
                    route.summary,
                    route.appendTenantPath,
                    route.appendOwnerPath
                )

                ScopeNaming.TENANT_ID_ONLY -> route.summary
            },
            accept = route.accept,
            parameters = parameters(route.appendTenantPath, route.appendOwnerPath, route.appendIdPath) +
                route.parameters,
            requestBody = route.requestBody,
            responses = route.responses,
            tags = tags,
            handlerMetadata = HttpRouteHandlerMetadata.Aggregate(aggregateRouteMetadata)
        )
    }
}

/**
 * `Count Snapshot`, `Count Snapshot Within Tenant`, `… Within Owner`, `… Within Tenant Owner`.
 */
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
