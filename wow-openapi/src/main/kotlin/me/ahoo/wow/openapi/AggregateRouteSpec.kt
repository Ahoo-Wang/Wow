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
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.naming.getContextAlias
import me.ahoo.wow.openapi.Tags.asTags
import me.ahoo.wow.serialization.MessageRecords

const val TENANT_PATH_PREFIX = "tenant/{${MessageRecords.TENANT_ID}}"

abstract class AggregateRouteSpec : AbstractRouteSpec() {
    abstract val currentContext: NamedBoundedContext
    abstract val aggregateMetadata: AggregateMetadata<*, *>
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.add(aggregateMetadata.asStringWithAlias())
            aggregateMetadata.command.aggregateType.asTags().let {
                tags.addAll(it)
            }
            return tags
        }
    open val ignoreTenant: Boolean
        get() = !aggregateMetadata.staticTenantId.isNullOrBlank()
    open val appendIdPath: Boolean
        get() = false
    open val appendPathSuffix: String
        get() = ""
    override val path: String
        get() {
            val pathBuilder = PathBuilder()
            if (!ignoreTenant) {
                pathBuilder.append(TENANT_PATH_PREFIX)
            }
            val namedAggregate = aggregateMetadata.namedAggregate
            if (!currentContext.isSameBoundedContext(namedAggregate)) {
                pathBuilder.append(currentContext.getContextAlias())
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

    override fun build(): RouteSpec {
        super.build()
        if (!ignoreTenant) {
            addParameter(MessageRecords.TENANT_ID, ParameterIn.PATH, StringSchema()) {
                it.required(true)
                it.example(TenantId.DEFAULT_TENANT_ID)
            }
        }
        if (appendIdPath) {
            addParameter(MessageRecords.ID, ParameterIn.PATH, StringSchema())
        }
        return this
    }
}
