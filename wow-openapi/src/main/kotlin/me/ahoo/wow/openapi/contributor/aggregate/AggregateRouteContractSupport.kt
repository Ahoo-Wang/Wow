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
import me.ahoo.wow.openapi.CommonComponent.Parameter.createTimePathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.idPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.ownerIdPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.spaceIdHeaderParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.tenantIdPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.versionPathParameter
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.rest.WowHeaders.SPACE_ID
import me.ahoo.wow.serialization.MessageRecords

private const val TENANT_PATH_VARIABLE = "{${MessageRecords.TENANT_ID}}"
private const val TENANT_PATH_PREFIX = "tenant/$TENANT_PATH_VARIABLE"
private const val OWNER_PATH_VARIABLE = "{${MessageRecords.OWNER_ID}}"
private const val OWNER_PATH_PREFIX = "owner/$OWNER_PATH_VARIABLE"
private const val ID_PATH_VARIABLE = "{${MessageRecords.ID}}"

internal fun AggregateRouteMetadata<*>.defaultAppendTenantPath(): Boolean {
    return aggregateMetadata.staticTenantId.isNullOrBlank()
}

internal fun AggregateRouteMetadata<*>.defaultAppendOwnerPath(): Boolean {
    return ownerPolicy != OwnerPolicy.NEVER
}

/**
 * The tenant/owner scope of one variant of an aggregate's query routes.
 */
internal data class TenantOwnerVariant(
    val appendTenantPath: Boolean,
    val appendOwnerPath: Boolean
)

/**
 * The scopes an aggregate's query routes are published under: always the unscoped route; a tenant variant when the
 * aggregate has no static tenant; an owner variant when it has an owner; and, when both apply, a tenant + owner
 * variant (`tenant/{tenantId}/owner/{ownerId}/…`, the order of the command routes), so a gateway that secures by
 * path can confine a caller to its own owner within its own tenant. The order is the order of publication: a new
 * variant goes last.
 */
internal fun AggregateRouteMetadata<*>.tenantOwnerVariants(): List<TenantOwnerVariant> {
    val appendTenantPath = defaultAppendTenantPath()
    val appendOwnerPath = defaultAppendOwnerPath()
    return buildList {
        add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = false))
        if (appendTenantPath) {
            add(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = false))
        }
        if (appendOwnerPath) {
            add(TenantOwnerVariant(appendTenantPath = false, appendOwnerPath = true))
        }
        if (appendTenantPath && appendOwnerPath) {
            add(TenantOwnerVariant(appendTenantPath = true, appendOwnerPath = true))
        }
    }
}

/**
 * `Count Snapshot`, `Count Snapshot Within Tenant`, `… Within Owner`, `… Within Tenant Owner`.
 */
internal fun tenantOwnerSummary(
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

internal fun aggregatePath(
    currentContext: NamedBoundedContext,
    aggregateRouteMetadata: AggregateRouteMetadata<*>,
    appendTenantPath: Boolean,
    appendOwnerPath: Boolean,
    appendIdPath: Boolean,
    appendPathSuffix: String = ""
): String {
    val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
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
    if (appendPathSuffix.isNotEmpty()) {
        pathBuilder.append(appendPathSuffix)
    }
    return pathBuilder.build()
}

internal fun aggregateTags(aggregateMetadata: AggregateMetadata<*, *>): List<HttpTag> {
    return buildList {
        add(HttpTag(aggregateMetadata.toStringWithAlias()))
        aggregateMetadata.command.aggregateType.toTags().forEach { tag ->
            add(HttpTag(tag.name, tag.description))
        }
    }
}

internal fun OpenAPIComponentContext.aggregateParameters(
    aggregateRouteMetadata: AggregateRouteMetadata<*>,
    appendTenantPath: Boolean,
    appendOwnerPath: Boolean,
    appendIdPath: Boolean
): List<HttpParameter> {
    return buildList {
        if (appendTenantPath) {
            tenantIdPathParameter()
            add(componentPathParameter(MessageRecords.TENANT_ID))
        }
        if (appendOwnerPath) {
            ownerIdPathParameter()
            add(componentPathParameter(MessageRecords.OWNER_ID))
        }
        if (appendIdPath) {
            idPathParameter()
            add(componentPathParameter(MessageRecords.ID))
        }
        if (aggregateRouteMetadata.spaced) {
            spaceIdHeaderParameter()
            add(
                HttpParameter(
                    name = SPACE_ID,
                    location = HttpParameterLocation.HEADER,
                    componentRef = "wow.$SPACE_ID"
                )
            )
        }
    }
}

internal fun OpenAPIComponentContext.versionPathParameterRef(): HttpParameter {
    versionPathParameter()
    return componentPathParameter(MessageRecords.VERSION)
}

internal fun OpenAPIComponentContext.createTimePathParameterRef(): HttpParameter {
    createTimePathParameter()
    return componentPathParameter(MessageRecords.CREATE_TIME)
}

private fun componentPathParameter(name: String): HttpParameter {
    return HttpParameter(
        name = name,
        location = HttpParameterLocation.PATH,
        required = true,
        componentRef = "wow.$name"
    )
}
