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

package me.ahoo.wow.route

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.getContextAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.serialization.MessageRecords

interface AggregateRoutePathSpec {
    val currentContext: NamedBoundedContext
    val namedAggregate: NamedAggregate
    val staticTenantId: String?
    val ignoreTenant: Boolean
        get() = !staticTenantId.isNullOrBlank()

    /**
     * [NamedAggregate.contextName]/tenant/{tenantId}[NamedAggregate.aggregateName]/
     */
    val aggregateNamePath: String
        get() {
            val aggregateNamePath = if (ignoreTenant) {
                namedAggregate.aggregateName
            } else {
                "$TENANT_PATH_PREFIX/${namedAggregate.aggregateName}"
            }
            if (currentContext.isSameBoundedContext(namedAggregate)) {
                return aggregateNamePath
            }
            val contextAlias = namedAggregate.getContextAlias()
            return "$contextAlias/$aggregateNamePath"
        }

    /**
     * [aggregateNamePath]/{[MessageRecords.ID]}
     */
    val routePath: String
        get() {
            return "$aggregateNamePath/{${MessageRecords.ID}}"
        }

    companion object {
        const val TENANT_PATH_PREFIX = "tenant/{${MessageRecords.TENANT_ID}}"

        fun AggregateMetadata<*, *>.asAggregateIdRoutePathSpec(currentContext: NamedBoundedContext): AggregateRoutePathSpec {
            return AggregateIdRoutePathSpec(
                currentContext = currentContext,
                namedAggregate = this.namedAggregate,
                staticTenantId = staticTenantId,
            )
        }

        fun CommandRouteMetadata<*>.asAggregateRoutePathSpec(
            currentContext: NamedBoundedContext,
            aggregateMetadata: AggregateMetadata<*, *>
        ): CommandAggregateRoutePathSpec {
            return CommandAggregateRoutePathSpec(
                currentContext = currentContext,
                namedAggregate = aggregateMetadata.namedAggregate,
                commandRouteMetadata = this,
                staticTenantId = aggregateMetadata.staticTenantId,
            )
        }
    }
}
