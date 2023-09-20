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
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory.Companion.appendIdPathParameter
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory.Companion.appendTenantPathParameter
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.ParameterRef.Companion.withParameter
import me.ahoo.wow.openapi.Tags.asTags
import me.ahoo.wow.serialization.MessageRecords

const val TENANT_PATH_PREFIX = "tenant/{${MessageRecords.TENANT_ID}}"

interface AggregateRouteSpec : RouteSpec {
    val currentContext: NamedBoundedContext
    val aggregateMetadata: AggregateMetadata<*, *>
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.add(aggregateMetadata.asStringWithAlias())
            aggregateMetadata.command.aggregateType.asTags().let {
                tags.addAll(it)
            }
            return tags
        }
    val appendTenantPath: Boolean
        get() = aggregateMetadata.staticTenantId.isNullOrBlank()
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
            pathBuilder.append(namedAggregate.aggregateName)
            if (appendIdPath) {
                pathBuilder.append("{${MessageRecords.ID}}")
            }
            if (appendPathSuffix.isNotEmpty()) {
                pathBuilder.append(appendPathSuffix)
            }
            return pathBuilder.build()
        }
    override val parameters: List<Parameter>
        get() = mutableListOf<Parameter>()
            .appendTenantPathParameter(appendIdPath)
            .appendIdPathParameter(appendIdPath)
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

        fun MutableList<Parameter>.appendIdPathParameter(appendIdPath: Boolean): MutableList<Parameter> {
            if (appendIdPath.not()) {
                return this
            }
            withParameter(MessageRecords.ID, ParameterIn.PATH, StringSchema())
            return this
        }
    }
}
