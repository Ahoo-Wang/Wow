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

import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

interface TenantSpaceOwnerAggregateRouteSpec : AggregateRouteSpec {
    val operationSummary: String
    override val summary: String
        get() = TenantSpaceOwnerRouteSummarySpec()
            .operationSummary(operationSummary)
            .appendTenant(appendTenantPath)
            .appendSpace(appendSpacePath)
            .appendOwner(appendOwnerPath)
            .build()
}

abstract class AbstractTenantSpaceOwnerAggregateRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    abstract fun createSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        appendTenantPath: Boolean,
        appendSpacePath: Boolean,
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
            appendSpacePath = false,
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
                    appendSpacePath = false,
                    appendOwnerPath = false
                )
                add(tenantRouteSpec)

                if (aggregateRouteMetadata.spaced) {
                    val tenantRouteSpec = createSpec(
                        currentContext = currentContext,
                        aggregateRouteMetadata = aggregateRouteMetadata,
                        appendTenantPath = true,
                        appendSpacePath = true,
                        appendOwnerPath = false
                    )
                    add(tenantRouteSpec)
                }
            }
            val appendOwnerPath = aggregateRouteMetadata.owner != AggregateRoute.Owner.NEVER
            if (appendOwnerPath) {
                val ownerRouteSpec = createSpec(
                    currentContext = currentContext,
                    aggregateRouteMetadata = aggregateRouteMetadata,
                    appendTenantPath = false,
                    appendSpacePath = false,
                    appendOwnerPath = true
                )
                add(ownerRouteSpec)
            }
        }
    }
}
