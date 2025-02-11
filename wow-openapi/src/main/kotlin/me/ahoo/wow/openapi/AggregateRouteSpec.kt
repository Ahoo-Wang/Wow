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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory.Companion.appendIdPathParameter
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory.Companion.appendOwnerPathParameter
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory.Companion.appendTenantPathParameter
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.ParameterRef.Companion.withParameter
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.route.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

const val TENANT_PATH_VARIABLE = "{${MessageRecords.TENANT_ID}}"
const val TENANT_PATH_PREFIX = "tenant/$TENANT_PATH_VARIABLE"
const val OWNER_PATH_VARIABLE = "{${MessageRecords.OWNER_ID}}"
const val OWNER_PATH_PREFIX = "owner/$OWNER_PATH_VARIABLE"
const val ID_PATH_VARIABLE = "{${MessageRecords.ID}}"

interface AggregateRouteSpec : RouteSpec {
    val currentContext: NamedBoundedContext
    val aggregateRouteMetadata: AggregateRouteMetadata<*>
    val aggregateMetadata: AggregateMetadata<*, *>
        get() = aggregateRouteMetadata.aggregateMetadata
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.add(aggregateMetadata.toStringWithAlias())
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
            pathBuilder.append(namedAggregate.aggregateName)
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
            return mutableListOf<Parameter>()
                .appendTenantPathParameter(appendTenantPath)
                .appendOwnerPathParameter(appendOwnerPath)
                .appendIdPathParameter(appendIdPath)
        }
}

abstract class AbstractAggregateRouteSpecFactory : AggregateRouteSpecFactory {
    override val components: Components = createComponents()

    companion object {
        fun MutableList<Parameter>.appendTenantPathParameter(appendTenantPath: Boolean): MutableList<Parameter> {
            if (appendTenantPath.not()) {
                return this
            }
            withParameter(MessageRecords.TENANT_ID, ParameterIn.PATH, StringSchema()) {
                it.required(true)
                it.example(TenantId.DEFAULT_TENANT_ID)
            }
            return this
        }

        fun MutableList<Parameter>.appendOwnerPathParameter(appendOwnerPath: Boolean): MutableList<Parameter> {
            if (appendOwnerPath.not()) {
                return this
            }
            withParameter(MessageRecords.OWNER_ID, ParameterIn.PATH, StringSchema()) {
                it.required(true)
            }
            return this
        }

        fun MutableList<Parameter>.appendIdPathParameter(appendIdPath: Boolean): MutableList<Parameter> {
            if (appendIdPath.not()) {
                return this
            }
            withParameter(MessageRecords.ID, ParameterIn.PATH, StringSchema())
            return this
        }
    }
}

abstract class AbstractTenantOwnerAggregateRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    abstract fun createSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean
    ): AggregateRouteSpec

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<RouteSpec> {
        val defaultRouteSpec = createSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            appendTenantPath = false,
            appendOwnerPath = false
        )
        return buildList {
            add(defaultRouteSpec)
            val appendTenantPath = aggregateRouteMetadata.aggregateMetadata.staticTenantId.isNullOrBlank()
            if (appendTenantPath) {
                val tenantRouteSpec = createSpec(
                    currentContext = currentContext,
                    aggregateRouteMetadata = aggregateRouteMetadata,
                    appendTenantPath = true,
                    appendOwnerPath = false
                )
                add(tenantRouteSpec)
            }
            val appendOwnerPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.NEVER
            if (appendOwnerPath) {
                val ownerRouteSpec = createSpec(
                    currentContext = currentContext,
                    aggregateRouteMetadata = aggregateRouteMetadata,
                    appendTenantPath = false,
                    appendOwnerPath = true
                )
                add(ownerRouteSpec)
            }
        }
    }
}
