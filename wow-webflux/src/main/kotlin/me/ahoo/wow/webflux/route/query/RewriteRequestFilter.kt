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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.webflux.route.command.getOwnerId
import me.ahoo.wow.webflux.route.command.getSpaceId
import me.ahoo.wow.webflux.route.command.getTenantId
import org.springframework.web.reactive.function.server.ServerRequest

interface RewriteRequestFilter {
    fun rewrite(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        filter: FilterExpression,
    ): FilterExpression

    fun <Q : FilterCapable<Q>> rewrite(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        query: Q,
    ): Q = query.withFilter(
        rewrite(aggregateMetadata, request, query.filter),
    )
}

abstract class AbstractRewriteRequestFilter : RewriteRequestFilter {
    protected open fun ServerRequest.resolveTenantId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getTenantId(aggregateMetadata)
    }

    protected open fun ServerRequest.resolveOwnerId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getOwnerId()
    }

    protected open fun ServerRequest.resolveSpaceId(aggregateMetadata: AggregateMetadata<*, *>): String? {
        return getSpaceId()
    }

    override fun rewrite(
        aggregateMetadata: AggregateMetadata<*, *>,
        request: ServerRequest,
        filter: FilterExpression,
    ): FilterExpression {
        val tenantId = request.resolveTenantId(aggregateMetadata)
        val ownerId = request.resolveOwnerId(aggregateMetadata)
        val spaceId = request.resolveSpaceId(aggregateMetadata)
        if (tenantId.isNullOrBlank() && ownerId.isNullOrBlank() && spaceId.isNullOrBlank()) {
            return filter
        }
        val appendFilter = requestScopeFilter(tenantId, ownerId, spaceId)
        return filter.appendFilter(appendFilter)
    }

    private fun requestScopeFilter(tenantId: String?, ownerId: String?, spaceId: String?): FilterExpression = filter {
        if (!tenantId.isNullOrBlank()) {
            tenantId(tenantId)
        }
        if (!ownerId.isNullOrBlank()) {
            ownerId(ownerId)
        }
        if (!spaceId.isNullOrBlank()) {
            spaceId(spaceId)
        }
    }
}
