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

package me.ahoo.wow.openapi.aggregate

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.tags.Tag
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.AbstractRouteSpecFactory
import me.ahoo.wow.openapi.CommonComponent.Parameter.idPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.ownerIdPathParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.spaceIdHeaderParameter
import me.ahoo.wow.openapi.CommonComponent.Parameter.tenantIdPathParameter
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

const val TENANT_PATH_VARIABLE = "{${MessageRecords.TENANT_ID}}"
const val TENANT_PATH_PREFIX = "tenant/$TENANT_PATH_VARIABLE"
const val OWNER_PATH_VARIABLE = "{${MessageRecords.OWNER_ID}}"
const val OWNER_PATH_PREFIX = "owner/$OWNER_PATH_VARIABLE"
const val ID_PATH_VARIABLE = "{${MessageRecords.ID}}"

interface AggregateRouteSpec : RouteSpec, OpenAPIComponentContextCapable {
    val currentContext: NamedBoundedContext
    val aggregateRouteMetadata: AggregateRouteMetadata<*>
    val aggregateMetadata: AggregateMetadata<*, *>
        get() = aggregateRouteMetadata.aggregateMetadata
    override val tags: List<Tag>
        get() {
            val tags = mutableListOf<Tag>()
            tags.add(Tag().name(aggregateMetadata.toStringWithAlias()))
            aggregateMetadata.command.aggregateType.toTags().let {
                tags.addAll(it)
            }
            return tags
        }
    val appendTenantPath: Boolean
        get() = aggregateMetadata.staticTenantId.isNullOrBlank()
    val appendOwnerPath: Boolean
        get() = aggregateRouteMetadata.owner != AggregateRoute.Owner.NEVER
    val appendIdPath: Boolean
        get() = false
    val appendPathSuffix: String
        get() = ""
    override val path: String
        get() {
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
    override val parameters: List<Parameter>
        get() {
            return buildList {
                if (appendTenantPath) {
                    add(componentContext.tenantIdPathParameter())
                }
                if (appendOwnerPath) {
                    add(componentContext.ownerIdPathParameter())
                }
                if (appendIdPath) {
                    add(componentContext.idPathParameter())
                }

                if (aggregateRouteMetadata.spaced) {
                    add(componentContext.spaceIdHeaderParameter())
                }
            }
        }
}

abstract class AbstractAggregateRouteSpecFactory : AggregateRouteSpecFactory, AbstractRouteSpecFactory()
