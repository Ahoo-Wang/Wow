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

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.CommonComponent.Header.SPACE_ID
import me.ahoo.wow.openapi.CommonComponent.Parameter.createTimePathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.idPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.ownerIdPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.spaceIdHeaderParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.tenantIdPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.versionPathParameter
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.aggregate.ID_PATH_VARIABLE
import me.ahoo.wow.openapi.aggregate.OWNER_PATH_PREFIX
import me.ahoo.wow.openapi.aggregate.TENANT_PATH_PREFIX
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

internal fun AggregateRouteMetadata<*>.defaultAppendTenantPath(): Boolean {
    return aggregateMetadata.staticTenantId.isNullOrBlank()
}

internal fun AggregateRouteMetadata<*>.defaultAppendOwnerPath(): Boolean {
    return owner != AggregateRoute.Owner.NEVER
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
